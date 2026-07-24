package com.wrbug.polymarketbot.service.copytrading.simulation

import com.wrbug.polymarketbot.api.TradeResponse
import com.wrbug.polymarketbot.api.UserActivityResponse
import com.wrbug.polymarketbot.dto.CopySimulationSummaryDto
import com.wrbug.polymarketbot.repository.CopySimulationSessionRepository
import com.wrbug.polymarketbot.repository.CopyTradingRepository
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.service.copytrading.monitor.SettlementActivityMapper
import com.wrbug.polymarketbot.util.RetrofitFactory
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@Service
class CopySimulationReplayService(
    private val copyTradingRepository: CopyTradingRepository,
    private val leaderRepository: LeaderRepository,
    private val sessionRepository: CopySimulationSessionRepository,
    private val retrofitFactory: RetrofitFactory,
    private val simulationService: CopySimulationService
) {
    private val logger = LoggerFactory.getLogger(CopySimulationReplayService::class.java)

    suspend fun rebuild(copyTradingId: Long): Result<CopySimulationSummaryDto?> = runCatching {
        val config = copyTradingRepository.findById(copyTradingId).orElseThrow {
            IllegalArgumentException("跟单配置不存在: $copyTradingId")
        }
        require(config.executionMode == "PAPER") { "只有模拟配置可以重建账本" }
        val leader = leaderRepository.findById(config.leaderId).orElseThrow {
            IllegalArgumentException("Leader 不存在: ${config.leaderId}")
        }
        val replayStart = sessionRepository.findByCopyTradingId(copyTradingId)?.originAt
            ?: config.createdAt
        val replayEndSeconds = System.currentTimeMillis() / 1000
        val activities = fetchActivities(
            wallet = leader.leaderAddress,
            startSeconds = replayStart / 1000,
            endSeconds = replayEndSeconds
        )
        require(activities.isNotEmpty()) {
            "Data API 没有返回可重播活动，保留原模拟账本"
        }

        logger.info(
            "开始重建模拟账本: copyTradingId={}, start={}, activityCount={}",
            copyTradingId, replayStart, activities.size
        )
        // Keep the original flag separately. save/merge may update the managed
        // entity instance, so reading config.enabled again in finally can leave
        // a previously enabled PAPER configuration paused after rebuilding.
        val wasEnabled = config.enabled
        if (wasEnabled) {
            copyTradingRepository.save(config.copy(enabled = false, updatedAt = System.currentTimeMillis()))
            // Allow an already-running websocket handler to leave its transaction.
            delay(1000)
        }
        try {
            simulationService.reset(copyTradingId)
            replay(config, activities)

            // Capture events that arrived while the configuration was paused.
            val catchUp = fetchActivities(
                wallet = leader.leaderAddress,
                startSeconds = (replayEndSeconds - 1).coerceAtLeast(0),
                endSeconds = System.currentTimeMillis() / 1000
            )
            replay(config, catchUp)
        } finally {
            if (wasEnabled) {
                copyTradingRepository.save(config.copy(enabled = true, updatedAt = System.currentTimeMillis()))
            }
        }
        simulationService.summary(copyTradingId)
    }

    private suspend fun replay(config: com.wrbug.polymarketbot.entity.CopyTrading, activities: List<UserActivityResponse>) {
        activities.sortedWith(compareBy<UserActivityResponse> { it.timestamp }.thenBy { actionOrder(it.type) })
            .forEach { activity ->
                val trade = toTrade(activity) ?: return@forEach
                simulationService.process(config, trade, sendNotification = false).getOrThrow()
            }
    }

    private suspend fun fetchActivities(
        wallet: String,
        startSeconds: Long,
        endSeconds: Long
    ): List<UserActivityResponse> {
        val api = retrofitFactory.createDataApi()
        val all = mutableListOf<UserActivityResponse>()
        var offset = 0
        do {
            val response = api.getUserActivity(
                user = wallet,
                limit = PAGE_SIZE,
                offset = offset,
                // Data API expects one comma-separated value. Repeated `type`
                // parameters return an incomplete subset without an error.
                type = listOf("TRADE,MERGE,REDEEM"),
                start = startSeconds,
                end = endSeconds,
                sortBy = "TIMESTAMP",
                sortDirection = "ASC"
            )
            if (!response.isSuccessful) {
                error("Data API ${response.code()} ${response.message()}")
            }
            val page = response.body().orEmpty()
            all += page.filter { it.type.uppercase() in REPLAY_ACTIONS }
            offset += page.size
        } while (page.size == PAGE_SIZE && offset < MAX_ACTIVITIES)
        if (offset >= MAX_ACTIVITIES) {
            error("活动超过 $MAX_ACTIVITIES 笔，拒绝截断重建")
        }
        return all
    }

    internal fun toTrade(activity: UserActivityResponse): TradeResponse? {
        val action = activity.type.uppercase()
        if (action in SETTLEMENT_ACTIONS) return SettlementActivityMapper.toTrade(activity)
        if (action != "TRADE") return null
        val side = activity.side?.uppercase()?.takeIf { it == "BUY" || it == "SELL" } ?: return null
        val price = activity.price?.takeIf { it > 0.0 && it < 1.0 } ?: return null
        val size = activity.size?.takeIf { it > 0.0 } ?: return null
        val asset = activity.asset?.takeIf { it.isNotBlank() } ?: return null
        val stable = listOf(
            activity.transactionHash.orEmpty(),
            activity.timestamp,
            activity.conditionId,
            side,
            activity.outcomeIndex,
            asset,
            price,
            size
        ).joinToString(":")
        return TradeResponse(
            id = "activity-${sha256(stable).take(48)}",
            market = activity.conditionId,
            side = side,
            price = price.toString(),
            size = size.toString(),
            timestamp = activity.timestamp.toString(),
            user = activity.proxyWallet,
            outcomeIndex = activity.outcomeIndex,
            outcome = activity.outcome,
            tokenId = asset
        )
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun actionOrder(type: String): Int = when (type.uppercase()) {
        "TRADE" -> 0
        "MERGE" -> 1
        "REDEEM" -> 2
        else -> 3
    }

    companion object {
        private const val PAGE_SIZE = 500
        private const val MAX_ACTIVITIES = 20_000
        private val REPLAY_ACTIONS = setOf("TRADE", "MERGE", "REDEEM")
        private val SETTLEMENT_ACTIONS = setOf("MERGE", "REDEEM")
    }
}
