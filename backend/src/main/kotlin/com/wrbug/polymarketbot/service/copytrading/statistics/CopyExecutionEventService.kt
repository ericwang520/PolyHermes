package com.wrbug.polymarketbot.service.copytrading.statistics

import com.wrbug.polymarketbot.api.TradeResponse
import com.wrbug.polymarketbot.entity.CopyExecutionEvent
import com.wrbug.polymarketbot.entity.CopyTrading
import com.wrbug.polymarketbot.repository.CopyExecutionEventRepository
import com.wrbug.polymarketbot.util.multi
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

@Service
class CopyExecutionEventService(
    private val repository: CopyExecutionEventRepository
) {
    private val logger = LoggerFactory.getLogger(CopyExecutionEventService::class.java)

    @Transactional
    fun detected(copyTrading: CopyTrading, trade: TradeResponse, action: String, source: String) {
        update(
            copyTrading = copyTrading,
            trade = trade,
            action = action,
            status = "DETECTED",
            source = source,
            quantity = runCatching {
                if (copyTrading.copyMode == "FIXED") {
                    copyTrading.fixedAmount?.divide(trade.price.toSafeBigDecimal(), 8, java.math.RoundingMode.DOWN)
                        ?: BigDecimal.ZERO
                } else {
                    trade.size.toSafeBigDecimal().multi(copyTrading.copyRatio)
                }
            }.getOrDefault(BigDecimal.ZERO)
        )
    }

    @Transactional
    fun update(
        copyTrading: CopyTrading,
        trade: TradeResponse,
        action: String,
        status: String,
        reason: String? = null,
        source: String? = null,
        marketId: String? = null,
        outcomeIndex: Int? = null,
        tokenId: String? = null,
        executionPrice: BigDecimal? = null,
        quantity: BigDecimal? = null,
        orderId: String? = null
    ) {
        try {
            val configId = requireNotNull(copyTrading.id)
            val event = repository.findByCopyTradingIdAndLeaderTradeIdAndAction(
                configId,
                trade.id,
                action
            ) ?: CopyExecutionEvent(
                copyTradingId = configId,
                accountId = copyTrading.accountId,
                leaderId = copyTrading.leaderId,
                leaderTradeId = trade.id,
                action = action,
                marketId = marketId ?: trade.market,
                outcomeIndex = outcomeIndex ?: trade.outcomeIndex,
                tokenId = tokenId ?: trade.tokenId,
                leaderPrice = runCatching { trade.price.toSafeBigDecimal() }.getOrNull(),
                source = source ?: "unknown",
                eventTime = normalizeEventTime(trade.timestamp)
            )

            event.status = status
            event.reason = reason?.take(1000)
            if (!marketId.isNullOrBlank()) event.marketId = marketId
            if (outcomeIndex != null) event.outcomeIndex = outcomeIndex
            if (!tokenId.isNullOrBlank()) event.tokenId = tokenId
            if (executionPrice != null) event.executionPrice = executionPrice
            if (quantity != null) event.quantity = quantity
            val priceForNotional = executionPrice ?: event.executionPrice ?: event.leaderPrice
            event.notional = priceForNotional?.let { event.quantity.multi(it) } ?: BigDecimal.ZERO
            if (!orderId.isNullOrBlank()) event.orderId = orderId
            if (!source.isNullOrBlank()) event.source = source
            event.updatedAt = System.currentTimeMillis()
            repository.save(event)
        } catch (e: Exception) {
            // 可观测性写入失败不能阻断真实下单。
            logger.error(
                "保存实盘执行事件失败: copyTradingId=${copyTrading.id}, tradeId=${trade.id}, action=$action, status=$status",
                e
            )
        }
    }

    private fun normalizeEventTime(timestamp: String): Long {
        val value = timestamp.toLongOrNull() ?: return System.currentTimeMillis()
        return if (value < 10_000_000_000L) value * 1000 else value
    }
}
