package com.wrbug.polymarketbot.service.copytrading.statistics

import com.wrbug.polymarketbot.api.TradeResponse
import com.wrbug.polymarketbot.dto.AccountRedeemPositionItem
import com.wrbug.polymarketbot.dto.PositionMergeRequest
import com.wrbug.polymarketbot.dto.PositionRedeemRequest
import com.wrbug.polymarketbot.entity.CopyTrading
import com.wrbug.polymarketbot.repository.CopyExecutionEventRepository
import com.wrbug.polymarketbot.service.accounts.AccountService
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal

/**
 * Executes settlement against the follower's real, indexed positions.
 *
 * We intentionally do not copy the leader's raw settlement quantity:
 * - MERGE consumes only the follower's own matched YES/NO inventory.
 * - REDEEM consumes only positions that Data API marks redeemable.
 */
@Service
class CopyLiveSettlementService(
    private val accountService: AccountService,
    private val executionEventService: CopyExecutionEventService,
    private val executionEventRepository: CopyExecutionEventRepository
) {
    private val logger = LoggerFactory.getLogger(CopyLiveSettlementService::class.java)

    suspend fun process(config: CopyTrading, trade: TradeResponse): Result<Unit> {
        val action = trade.side.uppercase()
        val existing = executionEventRepository.findByCopyTradingIdAndLeaderTradeIdAndAction(
            requireNotNull(config.id),
            trade.id,
            action
        )
        if (existing?.status in setOf("FILLED", "SKIPPED")) {
            return Result.success(Unit)
        }
        executionEventService.detected(config, trade, action, "data-api-settlement")

        if (!config.followOnchainActions) {
            executionEventService.update(
                config, trade, action, "SKIPPED",
                "配置未启用跟随链上 MERGE / REDEEM",
                source = "data-api-settlement"
            )
            return Result.success(Unit)
        }

        return runCatching {
            val positions = accountService.getAllPositions().getOrThrow().currentPositions
                .filter {
                    it.accountId == config.accountId &&
                        it.marketId.equals(trade.market, ignoreCase = true) &&
                        it.originalQuantity.toSafeBigDecimal() > BigDecimal.ZERO
                }

            when (action) {
                "MERGE" -> merge(config, trade, positions)
                "REDEEM" -> redeem(config, trade, positions)
                else -> error("不支持的实盘结算类型: $action")
            }
        }.onFailure { error ->
            logger.error(
                "实盘结算失败: copyTradingId={}, action={}, market={}, error={}",
                config.id, action, trade.market, error.message, error
            )
            executionEventService.update(
                config, trade, action, "FAILED",
                error.message ?: "实盘结算失败",
                source = "data-api-settlement"
            )
        }
    }

    private suspend fun merge(
        config: CopyTrading,
        trade: TradeResponse,
        positions: List<com.wrbug.polymarketbot.dto.AccountPositionDto>
    ) {
        val mergeable = positions.filter { it.mergeable }
        val byOutcome = mergeable
            .filter { it.outcomeIndex != null }
            .associateBy { it.outcomeIndex!! }
        val first = byOutcome[0]
        val second = byOutcome[1]
        if (first == null || second == null) {
            executionEventService.update(
                config, trade, "MERGE", "SKIPPED",
                "跟单钱包没有可合并的成对持仓",
                source = "data-api-settlement"
            )
            return
        }

        val quantity = minOf(
            first.originalQuantity.toSafeBigDecimal(),
            second.originalQuantity.toSafeBigDecimal()
        )
        if (quantity <= BigDecimal.ZERO) {
            executionEventService.update(
                config, trade, "MERGE", "SKIPPED",
                "跟单钱包可合并数量为 0",
                source = "data-api-settlement"
            )
            return
        }

        val response = accountService.mergePositions(
            PositionMergeRequest(
                accountId = config.accountId,
                marketId = trade.market,
                quantity = quantity.toPlainString()
            )
        ).getOrThrow()
        executionEventService.update(
            config, trade, "MERGE", "FILLED",
            "已合并跟单钱包实际成对持仓",
            source = "data-api-settlement",
            executionPrice = BigDecimal.ONE,
            quantity = quantity,
            orderId = response.transactionHash
        )
    }

    private suspend fun redeem(
        config: CopyTrading,
        trade: TradeResponse,
        positions: List<com.wrbug.polymarketbot.dto.AccountPositionDto>
    ) {
        val redeemable = positions.filter { it.redeemable && it.outcomeIndex != null }
        if (redeemable.isEmpty()) {
            executionEventService.update(
                config, trade, "REDEEM", "SKIPPED",
                "跟单钱包没有可赎回持仓",
                source = "data-api-settlement"
            )
            return
        }

        val response = accountService.redeemPositions(
            PositionRedeemRequest(
                positions = redeemable.map {
                    AccountRedeemPositionItem(
                        accountId = config.accountId,
                        marketId = it.marketId,
                        outcomeIndex = requireNotNull(it.outcomeIndex),
                        side = it.side
                    )
                }
            )
        ).getOrThrow()
        val quantity = redeemable.fold(BigDecimal.ZERO) { total, position ->
            total.add(position.originalQuantity.toSafeBigDecimal())
        }
        executionEventService.update(
            config, trade, "REDEEM", "FILLED",
            "已赎回跟单钱包实际可赎回持仓",
            source = "data-api-settlement",
            executionPrice = BigDecimal.ONE,
            quantity = quantity,
            orderId = response.transactions.lastOrNull()?.transactionHash
        )
    }
}
