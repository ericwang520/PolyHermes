package com.wrbug.polymarketbot.service.copytrading.simulation

import com.wrbug.polymarketbot.api.TradeResponse
import com.wrbug.polymarketbot.dto.CopySimulationPositionDto
import com.wrbug.polymarketbot.dto.CopySimulationSummaryDto
import com.wrbug.polymarketbot.dto.CopySimulationTradeDto
import com.wrbug.polymarketbot.entity.CopySimulationPosition
import com.wrbug.polymarketbot.entity.CopySimulationSession
import com.wrbug.polymarketbot.entity.CopySimulationTrade
import com.wrbug.polymarketbot.entity.CopyTrading
import com.wrbug.polymarketbot.repository.CopySimulationPositionRepository
import com.wrbug.polymarketbot.repository.CopySimulationSessionRepository
import com.wrbug.polymarketbot.repository.CopySimulationTradeRepository
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Service
class CopySimulationService(
    private val sessionRepository: CopySimulationSessionRepository,
    private val positionRepository: CopySimulationPositionRepository,
    private val tradeRepository: CopySimulationTradeRepository
) {
    private val logger = LoggerFactory.getLogger(CopySimulationService::class.java)

    @Transactional
    fun process(copyTrading: CopyTrading, leaderTrade: TradeResponse): Result<Unit> {
        if (copyTrading.executionMode != "PAPER") return Result.success(Unit)
        val configId = copyTrading.id
            ?: return Result.failure(IllegalArgumentException("模拟配置尚未保存"))
        val action = leaderTrade.side.uppercase()
        if (tradeRepository.existsByCopyTradingIdAndLeaderTradeIdAndAction(configId, leaderTrade.id, action)) {
            return Result.success(Unit)
        }

        return try {
            val session = sessionRepository.findByCopyTradingId(configId)
                ?: sessionRepository.save(
                    CopySimulationSession(
                        copyTradingId = configId,
                        initialCash = copyTrading.paperInitialBalance,
                        cashBalance = copyTrading.paperInitialBalance
                    )
                )
            if (leaderTrade.market.isBlank()) {
                record(
                    session, copyTrading, leaderTrade, action, "SKIPPED",
                    "无法确定 conditionId，拒绝写入不可靠的模拟持仓"
                )
                return Result.success(Unit)
            }
            when (action) {
                "BUY" -> simulateBuy(copyTrading, session, leaderTrade)
                "SELL" -> simulateSell(copyTrading, session, leaderTrade)
                "MERGE" -> simulateMerge(copyTrading, session, leaderTrade)
                "REDEEM" -> simulateRedeem(copyTrading, session, leaderTrade)
                else -> record(
                    session, copyTrading, leaderTrade, action, "SKIPPED",
                    "不支持的模拟事件类型: $action"
                )
            }
            Result.success(Unit)
        } catch (e: DataIntegrityViolationException) {
            logger.debug("模拟事件已由并发处理: configId={}, tradeId={}", configId, leaderTrade.id)
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("模拟跟单失败: configId={}, tradeId={}", configId, leaderTrade.id, e)
            Result.failure(e)
        }
    }

    private fun simulateBuy(config: CopyTrading, session: CopySimulationSession, trade: TradeResponse) {
        val outcomeIndex = trade.outcomeIndex
            ?: return record(session, config, trade, "BUY", "SKIPPED", "缺少 outcomeIndex")
        val price = trade.price.toSafeBigDecimal()
        if (price <= BigDecimal.ZERO || price >= BigDecimal.ONE) {
            return record(session, config, trade, "BUY", "SKIPPED", "价格必须介于 0 与 1")
        }
        if (config.minPrice != null && price < config.minPrice ||
            config.maxPrice != null && price > config.maxPrice
        ) {
            return record(session, config, trade, "BUY", "FILTERED", "超出价格区间", price = price)
        }
        if (todayTradeCount(config.id!!) >= config.maxDailyOrders) {
            return record(session, config, trade, "BUY", "FILTERED", "达到每日最大模拟订单数", price = price)
        }
        if (todayRealizedPnl(config.id) <= config.maxDailyLoss.negate()) {
            return record(session, config, trade, "BUY", "FILTERED", "达到每日最大模拟亏损", price = price)
        }

        var quantity = when (config.copyMode) {
            "FIXED" -> (config.fixedAmount ?: BigDecimal.ZERO).divide(price, 8, RoundingMode.DOWN)
            "RATIO" -> trade.size.toSafeBigDecimal().multiply(config.copyRatio)
            else -> BigDecimal.ZERO
        }
        var notional = quantity.multiply(price)
        if (notional > config.maxOrderSize) {
            notional = config.maxOrderSize
            quantity = notional.divide(price, 8, RoundingMode.DOWN)
        }
        if (notional < config.minOrderSize) {
            return record(session, config, trade, "BUY", "FILTERED", "低于单笔最小金额", price, quantity, notional)
        }
        if (notional > session.cashBalance) {
            return record(session, config, trade, "BUY", "REJECTED", "模拟现金不足", price, quantity, notional)
        }

        val position = positionRepository.findByCopyTradingIdAndMarketIdAndOutcomeIndex(
            config.id!!, trade.market, outcomeIndex
        )
        val existingValue = position?.quantity?.multiply(position.averageCost) ?: BigDecimal.ZERO
        if (config.maxPositionValue != null && existingValue.add(notional) > config.maxPositionValue) {
            return record(session, config, trade, "BUY", "FILTERED", "达到最大仓位金额", price, quantity, notional)
        }
        val updatedPosition = position ?: CopySimulationPosition(
            sessionId = session.id!!,
            copyTradingId = config.id,
            marketId = trade.market,
            outcomeIndex = outcomeIndex,
            tokenId = trade.tokenId
        )
        val newQuantity = updatedPosition.quantity.add(quantity)
        updatedPosition.averageCost = existingValue.add(notional).divide(newQuantity, 8, RoundingMode.HALF_UP)
        updatedPosition.quantity = newQuantity
        updatedPosition.leaderQuantity = updatedPosition.leaderQuantity.add(trade.size.toSafeBigDecimal())
        updatedPosition.lastPrice = price
        updatedPosition.tokenId = trade.tokenId ?: updatedPosition.tokenId
        updatedPosition.valuationStatus = "LEADER_PRICE"
        updatedPosition.updatedAt = System.currentTimeMillis()
        positionRepository.save(updatedPosition)

        session.cashBalance = session.cashBalance.subtract(notional)
        session.tradeCount += 1
        session.updatedAt = System.currentTimeMillis()
        sessionRepository.save(session)
        record(session, config, trade, "BUY", "FILLED", null, price, quantity, notional)
    }

    private fun simulateSell(config: CopyTrading, session: CopySimulationSession, trade: TradeResponse) {
        if (!config.supportSell) {
            return record(session, config, trade, "SELL", "SKIPPED", "配置未启用跟单卖出")
        }
        val outcomeIndex = trade.outcomeIndex
            ?: return record(session, config, trade, "SELL", "SKIPPED", "缺少 outcomeIndex")
        val price = trade.price.toSafeBigDecimal()
        val position = positionRepository.findByCopyTradingIdAndMarketIdAndOutcomeIndex(
            config.id!!, trade.market, outcomeIndex
        ) ?: return record(session, config, trade, "SELL", "SKIPPED", "没有可卖出的模拟持仓", price = price)

        val leaderSellQuantity = trade.size.toSafeBigDecimal()
        val effectiveRatio = if (position.leaderQuantity > BigDecimal.ZERO) {
            position.quantity.divide(position.leaderQuantity, 12, RoundingMode.DOWN)
        } else {
            config.copyRatio
        }
        val requested = leaderSellQuantity.multiply(effectiveRatio)
        val quantity = minOf(position.quantity, requested)
        if (quantity <= BigDecimal.ZERO) {
            return record(session, config, trade, "SELL", "SKIPPED", "模拟持仓数量为 0", price = price)
        }
        val notional = quantity.multiply(price)
        val realized = price.subtract(position.averageCost).multiply(quantity)
        position.quantity = position.quantity.subtract(quantity)
        position.leaderQuantity = position.leaderQuantity
            .subtract(minOf(position.leaderQuantity, leaderSellQuantity))
            .max(BigDecimal.ZERO)
        position.realizedPnl = position.realizedPnl.add(realized)
        position.lastPrice = price
        position.updatedAt = System.currentTimeMillis()
        if (position.quantity.compareTo(BigDecimal.ZERO) == 0) position.averageCost = BigDecimal.ZERO
        positionRepository.save(position)

        session.cashBalance = session.cashBalance.add(notional)
        session.realizedPnl = session.realizedPnl.add(realized)
        session.tradeCount += 1
        session.updatedAt = System.currentTimeMillis()
        sessionRepository.save(session)
        record(session, config, trade, "SELL", "FILLED", null, price, quantity, notional, realized)
    }

    private fun simulateMerge(config: CopyTrading, session: CopySimulationSession, trade: TradeResponse) {
        val positions = positionRepository.findByCopyTradingIdAndMarketIdOrderByOutcomeIndex(
            config.id!!, trade.market
        ).filter { it.quantity > BigDecimal.ZERO }
        if (positions.size < 2) {
            return record(session, config, trade, "MERGE", "SKIPPED", "没有成对的模拟持仓")
        }
        val eventQuantity = trade.size.toSafeBigDecimal()
        val scaledQuantities = positions.take(2).map { position ->
            if (position.leaderQuantity > BigDecimal.ZERO && eventQuantity > BigDecimal.ZERO) {
                eventQuantity.multiply(
                    position.quantity.divide(position.leaderQuantity, 12, RoundingMode.DOWN)
                )
            } else {
                position.quantity
            }
        }
        val quantity = minOf(
            positions.minOf { it.quantity },
            scaledQuantities.minOrNull() ?: BigDecimal.ZERO
        )
        if (quantity <= BigDecimal.ZERO) {
            return record(session, config, trade, "MERGE", "SKIPPED", "可合并数量为 0")
        }
        val cost = positions.take(2).fold(BigDecimal.ZERO) { acc, p ->
            acc.add(p.averageCost.multiply(quantity))
        }
        val payout = quantity
        val realized = payout.subtract(cost)
        positions.take(2).forEach { position ->
            position.quantity = position.quantity.subtract(quantity)
            position.leaderQuantity = position.leaderQuantity
                .subtract(minOf(position.leaderQuantity, eventQuantity))
                .max(BigDecimal.ZERO)
            position.realizedPnl = position.realizedPnl.add(
                BigDecimal("0.5").subtract(position.averageCost).multiply(quantity)
            )
            if (position.quantity.compareTo(BigDecimal.ZERO) == 0) position.averageCost = BigDecimal.ZERO
            position.updatedAt = System.currentTimeMillis()
            positionRepository.save(position)
        }
        settleSession(session, payout, realized)
        record(session, config, trade, "MERGE", "FILLED", null, quantity = quantity, notional = payout, realizedPnl = realized)
    }

    private fun simulateRedeem(config: CopyTrading, session: CopySimulationSession, trade: TradeResponse) {
        val outcomeIndex = trade.outcomeIndex
            ?: return record(session, config, trade, "REDEEM", "SKIPPED", "缺少中奖 outcomeIndex")
        val position = positionRepository.findByCopyTradingIdAndMarketIdAndOutcomeIndex(
            config.id!!, trade.market, outcomeIndex
        ) ?: return record(session, config, trade, "REDEEM", "SKIPPED", "没有可赎回的模拟持仓")
        val eventQuantity = trade.size.toSafeBigDecimal()
        val effectiveRatio = if (position.leaderQuantity > BigDecimal.ZERO) {
            position.quantity.divide(position.leaderQuantity, 12, RoundingMode.DOWN)
        } else {
            BigDecimal.ONE
        }
        val quantity = minOf(
            position.quantity,
            if (eventQuantity > BigDecimal.ZERO) eventQuantity.multiply(effectiveRatio) else position.quantity
        )
        if (quantity <= BigDecimal.ZERO) {
            return record(session, config, trade, "REDEEM", "SKIPPED", "可赎回数量为 0")
        }
        val payout = quantity
        val realized = BigDecimal.ONE.subtract(position.averageCost).multiply(quantity)
        position.quantity = position.quantity.subtract(quantity)
        position.leaderQuantity = position.leaderQuantity
            .subtract(minOf(position.leaderQuantity, eventQuantity))
            .max(BigDecimal.ZERO)
        position.realizedPnl = position.realizedPnl.add(realized)
        position.lastPrice = BigDecimal.ONE
        position.valuationStatus = "SETTLED"
        if (position.quantity.compareTo(BigDecimal.ZERO) == 0) position.averageCost = BigDecimal.ZERO
        position.updatedAt = System.currentTimeMillis()
        positionRepository.save(position)
        settleSession(session, payout, realized)
        record(session, config, trade, "REDEEM", "FILLED", null, BigDecimal.ONE, quantity, payout, realized)
    }

    private fun settleSession(session: CopySimulationSession, payout: BigDecimal, realized: BigDecimal) {
        session.cashBalance = session.cashBalance.add(payout)
        session.realizedPnl = session.realizedPnl.add(realized)
        session.tradeCount += 1
        session.updatedAt = System.currentTimeMillis()
        sessionRepository.save(session)
    }

    private fun todayTradeCount(copyTradingId: Long): Long {
        val start = utcDayStart()
        return tradeRepository.countFilledSince(copyTradingId, start)
    }

    private fun todayRealizedPnl(copyTradingId: Long): BigDecimal =
        tradeRepository.sumRealizedPnlSince(copyTradingId, utcDayStart())

    private fun utcDayStart(): Long =
        LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun record(
        session: CopySimulationSession,
        config: CopyTrading,
        trade: TradeResponse,
        action: String,
        status: String,
        reason: String?,
        price: BigDecimal? = null,
        quantity: BigDecimal = BigDecimal.ZERO,
        notional: BigDecimal = BigDecimal.ZERO,
        realizedPnl: BigDecimal = BigDecimal.ZERO
    ) {
        tradeRepository.save(
            CopySimulationTrade(
                sessionId = session.id!!,
                copyTradingId = config.id!!,
                leaderTradeId = trade.id,
                action = action,
                marketId = trade.market,
                outcomeIndex = trade.outcomeIndex,
                tokenId = trade.tokenId,
                price = price,
                quantity = quantity,
                notional = notional,
                realizedPnl = realizedPnl,
                status = status,
                reason = reason,
                eventTime = parseEventTime(trade.timestamp)
            )
        )
    }

    private fun parseEventTime(raw: String): Long =
        raw.toLongOrNull()?.let { if (it < 10_000_000_000L) it * 1000 else it }
            ?: runCatching { Instant.parse(raw).toEpochMilli() }.getOrDefault(System.currentTimeMillis())

    @Transactional(readOnly = true)
    fun summary(copyTradingId: Long): CopySimulationSummaryDto? {
        val session = sessionRepository.findByCopyTradingId(copyTradingId) ?: return null
        val positions = positionRepository.findByCopyTradingIdOrderByUpdatedAtDesc(copyTradingId)
        val hasUnknown = positions.any { it.quantity > BigDecimal.ZERO && it.lastPrice == null }
        val positionValue = if (hasUnknown) null else positions.fold(BigDecimal.ZERO) { acc, p ->
            acc.add(p.quantity.multiply(p.lastPrice ?: BigDecimal.ZERO))
        }
        val unrealized = if (hasUnknown) null else positions.fold(BigDecimal.ZERO) { acc, p ->
            acc.add((p.lastPrice ?: BigDecimal.ZERO).subtract(p.averageCost).multiply(p.quantity))
        }
        val equity = positionValue?.add(session.cashBalance)
        val totalPnl = unrealized?.add(session.realizedPnl)
        return CopySimulationSummaryDto(
            copyTradingId = copyTradingId,
            initialCash = session.initialCash.toPlainString(),
            cashBalance = session.cashBalance.toPlainString(),
            positionValue = positionValue?.toPlainString(),
            equity = equity?.toPlainString(),
            realizedPnl = session.realizedPnl.toPlainString(),
            unrealizedPnl = unrealized?.toPlainString(),
            totalPnl = totalPnl?.toPlainString(),
            totalFees = session.totalFees.toPlainString(),
            tradeCount = session.tradeCount,
            status = session.status,
            positions = positions.map { p ->
                val marketValue = p.lastPrice?.multiply(p.quantity)
                CopySimulationPositionDto(
                    marketId = p.marketId,
                    outcomeIndex = p.outcomeIndex,
                    tokenId = p.tokenId,
                    quantity = p.quantity.toPlainString(),
                    averageCost = p.averageCost.toPlainString(),
                    lastPrice = p.lastPrice?.toPlainString(),
                    marketValue = marketValue?.toPlainString(),
                    unrealizedPnl = p.lastPrice?.subtract(p.averageCost)?.multiply(p.quantity)?.toPlainString(),
                    realizedPnl = p.realizedPnl.toPlainString(),
                    valuationStatus = p.valuationStatus
                )
            },
            trades = tradeRepository.findTop200ByCopyTradingIdOrderByEventTimeDesc(copyTradingId).map { t ->
                CopySimulationTradeDto(
                    id = t.id!!,
                    leaderTradeId = t.leaderTradeId,
                    action = t.action,
                    marketId = t.marketId,
                    outcomeIndex = t.outcomeIndex,
                    price = t.price?.toPlainString(),
                    quantity = t.quantity.toPlainString(),
                    notional = t.notional.toPlainString(),
                    realizedPnl = t.realizedPnl.toPlainString(),
                    status = t.status,
                    reason = t.reason,
                    fillAssumption = t.fillAssumption,
                    eventTime = t.eventTime
                )
            },
            updatedAt = session.updatedAt
        )
    }

    @Transactional
    fun reset(copyTradingId: Long): CopySimulationSummaryDto? {
        val current = sessionRepository.findByCopyTradingId(copyTradingId) ?: return null
        val initialCash = current.initialCash
        tradeRepository.deleteByCopyTradingId(copyTradingId)
        positionRepository.deleteByCopyTradingId(copyTradingId)
        sessionRepository.deleteByCopyTradingId(copyTradingId)
        sessionRepository.flush()
        sessionRepository.save(
            CopySimulationSession(
                copyTradingId = copyTradingId,
                initialCash = initialCash,
                cashBalance = initialCash
            )
        )
        return summary(copyTradingId)
    }
}
