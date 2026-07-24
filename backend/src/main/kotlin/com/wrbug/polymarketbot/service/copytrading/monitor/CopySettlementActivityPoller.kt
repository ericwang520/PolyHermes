package com.wrbug.polymarketbot.service.copytrading.monitor

import com.wrbug.polymarketbot.entity.CopySettlementEvent
import com.wrbug.polymarketbot.entity.CopySettlementSyncCursor
import com.wrbug.polymarketbot.repository.CopySettlementEventRepository
import com.wrbug.polymarketbot.repository.CopySettlementSyncCursorRepository
import com.wrbug.polymarketbot.repository.CopyTradingRepository
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.service.copytrading.statistics.CopyOrderTrackingService
import com.wrbug.polymarketbot.util.RetrofitFactory
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

/**
 * MERGE/REDEEM are not published by Polymarket's trades-only RTDS stream.
 * This durable Data API poller overlaps every request, persists a cursor and
 * stores each settlement before dispatching it to PAPER or LIVE execution.
 */
@Service
class CopySettlementActivityPoller(
    private val copyTradingRepository: CopyTradingRepository,
    private val leaderRepository: LeaderRepository,
    private val cursorRepository: CopySettlementSyncCursorRepository,
    private val eventRepository: CopySettlementEventRepository,
    private val retrofitFactory: RetrofitFactory,
    private val copyOrderTrackingService: CopyOrderTrackingService
) {
    private val logger = LoggerFactory.getLogger(CopySettlementActivityPoller::class.java)

    @Scheduled(
        fixedDelayString = "\${copy.settlement.poll-interval-ms:2000}",
        initialDelayString = "\${copy.settlement.initial-delay-ms:5000}"
    )
    fun poll() = runBlocking {
        val leaderIds = copyTradingRepository.findByEnabledTrue().map { it.leaderId }.distinct()
        leaderIds.forEach { leaderId ->
            runCatching { fetchLeader(leaderId) }
                .onFailure { logger.warn("同步 Leader 结算事件失败: leaderId={}, error={}", leaderId, it.message) }
        }
        processPending()
    }

    private suspend fun fetchLeader(leaderId: Long) {
        val leader = leaderRepository.findById(leaderId).orElse(null) ?: return
        val now = System.currentTimeMillis() / 1000
        val cursor = cursorRepository.findById(leaderId).orElse(
            CopySettlementSyncCursor(
                leaderId = leaderId,
                lastTimestamp = now - INITIAL_LOOKBACK_SECONDS
            )
        )
        val start = (cursor.lastTimestamp - OVERLAP_SECONDS).coerceAtLeast(0)
        val api = retrofitFactory.createDataApi()
        var offset = 0
        var maxTimestamp = cursor.lastTimestamp

        do {
            val response = api.getUserActivity(
                user = leader.leaderAddress,
                limit = PAGE_SIZE,
                offset = offset,
                // Data API expects one comma-separated value. Repeated `type`
                // query parameters silently collapse to only one action.
                type = listOf("MERGE,REDEEM"),
                start = start,
                end = now,
                sortBy = "TIMESTAMP",
                sortDirection = "ASC"
            )
            if (!response.isSuccessful) {
                throw IllegalStateException("Data API ${response.code()} ${response.message()}")
            }
            val page = response.body().orEmpty()
            val activities = page
                .filter { it.type.uppercase() in SETTLEMENT_ACTIONS }
                .sortedBy { it.timestamp }
            activities.forEach { activity ->
                persist(
                    CopySettlementEvent(
                        leaderId = leaderId,
                        stableEventKey = SettlementActivityMapper.stableKey(
                            activity.type,
                            activity.transactionHash,
                            activity.conditionId
                        ),
                        leaderTradeId = SettlementActivityMapper.leaderTradeId(
                            activity.type,
                            activity.transactionHash,
                            activity.conditionId
                        ),
                        action = activity.type.uppercase(),
                        transactionHash = activity.transactionHash,
                        marketId = activity.conditionId,
                        outcomeIndex = activity.outcomeIndex,
                        amount = (activity.usdcSize ?: activity.size ?: 0.0).toSafeBigDecimal(),
                        eventTime = activity.timestamp
                    )
                )
                maxTimestamp = maxOf(maxTimestamp, activity.timestamp)
            }
            offset += page.size
        } while (page.size == PAGE_SIZE)

        cursor.lastTimestamp = maxOf(maxTimestamp, now)
        cursor.updatedAt = System.currentTimeMillis()
        cursorRepository.save(cursor)
    }

    private fun persist(event: CopySettlementEvent) {
        if (eventRepository.findByStableEventKey(event.stableEventKey) != null) return
        try {
            eventRepository.saveAndFlush(event)
        } catch (_: DataIntegrityViolationException) {
            // Another scheduler/process persisted the same overlapped event.
        }
    }

    private suspend fun processPending() {
        eventRepository.findTop200ByStatusInOrderByEventTimeAsc(
            listOf("NEW", "FAILED", "PROCESSING")
        ).forEach { event ->
            event.status = "PROCESSING"
            event.attemptCount += 1
            event.updatedAt = System.currentTimeMillis()
            eventRepository.save(event)

            val trade = com.wrbug.polymarketbot.api.TradeResponse(
                id = event.leaderTradeId,
                market = event.marketId,
                side = event.action,
                price = "0",
                size = event.amount.toPlainString(),
                timestamp = event.eventTime.toString(),
                user = null,
                outcomeIndex = event.outcomeIndex
            )
            val result = copyOrderTrackingService.processTrade(
                event.leaderId,
                trade,
                "data-api-settlement"
            )
            event.status = if (result.isSuccess) "PROCESSED" else "FAILED"
            event.lastError = result.exceptionOrNull()?.message?.take(1000)
            event.updatedAt = System.currentTimeMillis()
            eventRepository.save(event)
        }
    }

    companion object {
        private const val PAGE_SIZE = 500
        private const val INITIAL_LOOKBACK_SECONDS = 120L
        private const val OVERLAP_SECONDS = 15L
        private val SETTLEMENT_ACTIONS = setOf("MERGE", "REDEEM")
    }
}
