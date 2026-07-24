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
import com.wrbug.polymarketbot.service.common.PolymarketClobService
import com.wrbug.polymarketbot.service.copytrading.orders.CopyShareAccumulatorService
import com.wrbug.polymarketbot.service.copytrading.orders.ShareAccumulationResult
import com.wrbug.polymarketbot.service.copytrading.orders.minimumExecutableShares
import com.wrbug.polymarketbot.service.system.TelegramNotificationService
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

@Service
class CopySimulationService(
    private val sessionRepository: CopySimulationSessionRepository,
    private val positionRepository: CopySimulationPositionRepository,
    private val tradeRepository: CopySimulationTradeRepository,
    private val clobService: PolymarketClobService? = null,
    private val shareAccumulatorService: CopyShareAccumulatorService? = null,
    private val telegramNotificationService: TelegramNotificationService? = null
) {
    private val logger = LoggerFactory.getLogger(CopySimulationService::class.java)
    private val notificationScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val suppressedNotifications = ConcurrentHashMap.newKeySet<String>()

    @Transactional
    suspend fun process(
        copyTrading: CopyTrading,
        leaderTrade: TradeResponse,
        sendNotification: Boolean = true
    ): Result<Unit> {
        if (copyTrading.executionMode != "PAPER") return Result.success(Unit)
        val configId = copyTrading.id
            ?: return Result.failure(IllegalArgumentException("模拟配置尚未保存"))
        val action = leaderTrade.side.uppercase()
        val notificationKey = notificationKey(configId, leaderTrade.id, action)
        if (!sendNotification) suppressedNotifications.add(notificationKey)
        if (tradeRepository.existsByCopyTradingIdAndLeaderTradeIdAndAction(configId, leaderTrade.id, action)) {
            suppressedNotifications.remove(notificationKey)
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
        } finally {
            suppressedNotifications.remove(notificationKey)
        }
    }

    private suspend fun simulateBuy(config: CopyTrading, session: CopySimulationSession, trade: TradeResponse) {
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
        if (notional < config.minOrderSize) {
            return record(session, config, trade, "BUY", "FILTERED", "低于单笔最小金额", price, quantity, notional)
        }
        val tokenId = trade.tokenId
            ?: return record(session, config, trade, "BUY", "SKIPPED", "缺少 tokenId，无法读取市场最低 shares", price, quantity, notional)
        val rules = clobService?.getMarketOrderRules(tokenId)?.getOrNull()
            ?: return record(session, config, trade, "BUY", "SKIPPED", "无法读取市场 min_order_size", price, quantity, notional)
        val executionMinimumShares = minimumExecutableShares(
            useFakForSmallOrders = config.useFakForSmallOrders,
            executionPrice = price,
            marketMinimumShares = rules.minimumShares
        )
        val accumulator = shareAccumulatorService
            ?: return record(session, config, trade, "BUY", "SKIPPED", "零碎 shares 累积服务不可用", price, quantity, notional)
        val accumulation = accumulator.accumulateAndTake(
            copyTradingId = config.id!!,
            marketId = trade.market,
            outcomeIndex = outcomeIndex,
            tokenId = tokenId,
            side = "BUY",
            followerQuantity = quantity,
            leaderQuantity = trade.size.toSafeBigDecimal(),
            minimumShares = executionMinimumShares,
            maximumExecutableQuantity = config.maxOrderSize.divide(price, 8, RoundingMode.DOWN),
            eventTime = parseEventTime(trade.timestamp)
        )
        val accumulatedLeaderQuantity: BigDecimal
        when (accumulation) {
            is ShareAccumulationResult.Ignored -> {
                return record(
                    session, config, trade, "BUY", "SKIPPED", "零碎买单没有可执行容量",
                    price, quantity, notional
                )
            }
            is ShareAccumulationResult.Netted -> {
                return record(
                    session, config, trade, "BUY", "NETTED",
                    "零碎买卖互相抵消: ${accumulation.cancelledQuantity.stripTrailingZeros().toPlainString()} shares",
                    price, quantity, notional
                )
            }
            is ShareAccumulationResult.Pending -> {
                val reason = if (config.useFakForSmallOrders) {
                    val pendingNotional = accumulation.pendingQuantity.multiply(price)
                    "FAK 小额市价累积中: ${pendingNotional.stripTrailingZeros().toPlainString()} / 最低 1 USDC"
                } else {
                    "零碎单累积中: ${accumulation.pendingQuantity.stripTrailingZeros().toPlainString()} / " +
                        "${accumulation.minimumShares.stripTrailingZeros().toPlainString()} shares"
                }
                return record(
                    session, config, trade, "BUY", "PENDING", reason,
                    price, quantity, notional
                )
            }
            is ShareAccumulationResult.Ready -> {
                quantity = accumulation.quantity
                accumulatedLeaderQuantity = accumulation.leaderQuantity
                notional = quantity.multiply(price)
            }
        }
        val readyAccumulation = accumulation as ShareAccumulationResult.Ready
        if (notional > session.cashBalance) {
            accumulator.restore(
                config.id!!, trade.market, outcomeIndex, tokenId, "BUY",
                readyAccumulation.quantity, readyAccumulation.leaderQuantity,
                readyAccumulation.eventCount, parseEventTime(trade.timestamp)
            )
            return record(session, config, trade, "BUY", "REJECTED", "模拟现金不足", price, quantity, notional)
        }

        val position = positionRepository.findByCopyTradingIdAndMarketIdAndOutcomeIndex(
            config.id!!, trade.market, outcomeIndex
        )
        val existingValue = position?.quantity?.multiply(position.averageCost) ?: BigDecimal.ZERO
        if (config.maxPositionValue != null && existingValue.add(notional) > config.maxPositionValue) {
            accumulator.restore(
                config.id!!, trade.market, outcomeIndex, tokenId, "BUY",
                readyAccumulation.quantity, readyAccumulation.leaderQuantity,
                readyAccumulation.eventCount, parseEventTime(trade.timestamp)
            )
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
        updatedPosition.leaderQuantity = updatedPosition.leaderQuantity.add(accumulatedLeaderQuantity)
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

    private suspend fun simulateSell(config: CopyTrading, session: CopySimulationSession, trade: TradeResponse) {
        if (!config.supportSell) {
            return record(session, config, trade, "SELL", "SKIPPED", "配置未启用跟单卖出")
        }
        val outcomeIndex = trade.outcomeIndex
            ?: return record(session, config, trade, "SELL", "SKIPPED", "缺少 outcomeIndex")
        val price = trade.price.toSafeBigDecimal()
        val existingPosition = positionRepository.findByCopyTradingIdAndMarketIdAndOutcomeIndex(
            config.id!!, trade.market, outcomeIndex
        )

        val leaderSellQuantity = trade.size.toSafeBigDecimal()
        val effectiveRatio = if (existingPosition != null && existingPosition.leaderQuantity > BigDecimal.ZERO) {
            existingPosition.quantity.divide(existingPosition.leaderQuantity, 12, RoundingMode.DOWN)
        } else {
            config.copyRatio
        }
        val requested = leaderSellQuantity.multiply(effectiveRatio)
        val tokenId = trade.tokenId ?: existingPosition?.tokenId
            ?: return record(session, config, trade, "SELL", "SKIPPED", "缺少 tokenId，无法读取市场最低 shares", price = price)
        val rules = clobService?.getMarketOrderRules(tokenId)?.getOrNull()
            ?: return record(session, config, trade, "SELL", "SKIPPED", "无法读取市场 min_order_size", price = price)
        val executionMinimumShares = minimumExecutableShares(
            useFakForSmallOrders = config.useFakForSmallOrders,
            executionPrice = price,
            marketMinimumShares = rules.minimumShares
        )
        val accumulator = shareAccumulatorService
            ?: return record(session, config, trade, "SELL", "SKIPPED", "零碎 shares 累积服务不可用", price = price)
        val accumulation = accumulator.accumulateAndTake(
            copyTradingId = config.id!!,
            marketId = trade.market,
            outcomeIndex = outcomeIndex,
            tokenId = tokenId,
            side = "SELL",
            followerQuantity = requested,
            leaderQuantity = leaderSellQuantity,
            minimumShares = executionMinimumShares,
            maximumExecutableQuantity = existingPosition?.quantity ?: BigDecimal.ZERO,
            discardRemainderWhenNoCapacity = existingPosition == null,
            eventTime = parseEventTime(trade.timestamp)
        )
        val quantity: BigDecimal
        val accumulatedLeaderQuantity: BigDecimal
        when (accumulation) {
            is ShareAccumulationResult.Ignored -> {
                val reason = if (accumulation.cancelledQuantity > BigDecimal.ZERO) {
                    "已抵消 ${accumulation.cancelledQuantity.stripTrailingZeros().toPlainString()} shares；剩余卖出因无模拟持仓忽略"
                } else {
                    "没有可卖出的模拟持仓"
                }
                return record(
                    session, config, trade, "SELL", "SKIPPED", reason,
                    price, requested, requested.multiply(price)
                )
            }
            is ShareAccumulationResult.Netted -> {
                return record(
                    session, config, trade, "SELL", "NETTED",
                    "零碎买卖互相抵消: ${accumulation.cancelledQuantity.stripTrailingZeros().toPlainString()} shares",
                    price, requested, requested.multiply(price)
                )
            }
            is ShareAccumulationResult.Pending -> {
                val reason = if (config.useFakForSmallOrders) {
                    val pendingNotional = accumulation.pendingQuantity.multiply(price)
                    "FAK 小额市价累积中: ${pendingNotional.stripTrailingZeros().toPlainString()} / 最低 1 USDC"
                } else {
                    "零碎单累积中: ${accumulation.pendingQuantity.stripTrailingZeros().toPlainString()} / " +
                        "${accumulation.minimumShares.stripTrailingZeros().toPlainString()} shares"
                }
                return record(
                    session, config, trade, "SELL", "PENDING", reason,
                    price, requested, requested.multiply(price)
                )
            }
            is ShareAccumulationResult.Ready -> {
                quantity = accumulation.quantity
                accumulatedLeaderQuantity = accumulation.leaderQuantity
            }
        }
        val position = existingPosition
            ?: return record(session, config, trade, "SELL", "SKIPPED", "没有可卖出的模拟持仓", price = price)
        if (quantity <= BigDecimal.ZERO) {
            return record(session, config, trade, "SELL", "SKIPPED", "模拟持仓数量为 0", price = price)
        }
        val notional = quantity.multiply(price)
        val realized = price.subtract(position.averageCost).multiply(quantity)
        position.quantity = position.quantity.subtract(quantity)
        position.leaderQuantity = position.leaderQuantity
            .subtract(minOf(position.leaderQuantity, accumulatedLeaderQuantity))
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
        val positions = positionRepository.findByCopyTradingIdAndMarketIdOrderByOutcomeIndex(
            config.id!!, trade.market
        ).filter { it.quantity > BigDecimal.ZERO }
        if (positions.isEmpty()) {
            return record(session, config, trade, "REDEEM", "SKIPPED", "没有可赎回的模拟持仓")
        }

        // A redemption settles the whole condition: winning tokens pay $1 and
        // losing tokens are burned for $0. Data API uses outcomeIndex=999 and
        // amount=0 when the leader only redeemed losing inventory.
        val winningOutcome = trade.outcomeIndex?.takeIf { outcome ->
            outcome in 0..1 && trade.size.toSafeBigDecimal() > BigDecimal.ZERO
        }
        val payout = positions
            .filter { it.outcomeIndex == winningOutcome }
            .fold(BigDecimal.ZERO) { total, position -> total.add(position.quantity) }
        val cost = positions.fold(BigDecimal.ZERO) { total, position ->
            total.add(position.averageCost.multiply(position.quantity))
        }
        val realized = payout.subtract(cost)
        val totalQuantity = positions.fold(BigDecimal.ZERO) { total, position ->
            total.add(position.quantity)
        }

        positions.forEach { position ->
            val positionPayout = if (position.outcomeIndex == winningOutcome) position.quantity else BigDecimal.ZERO
            val positionRealized = positionPayout.subtract(position.averageCost.multiply(position.quantity))
            position.realizedPnl = position.realizedPnl.add(positionRealized)
            position.quantity = BigDecimal.ZERO
            position.leaderQuantity = BigDecimal.ZERO
            position.averageCost = BigDecimal.ZERO
            position.lastPrice = if (position.outcomeIndex == winningOutcome) BigDecimal.ONE else BigDecimal.ZERO
            position.valuationStatus = "SETTLED"
            position.updatedAt = System.currentTimeMillis()
            positionRepository.save(position)
        }
        settleSession(session, payout, realized)
        record(
            session, config, trade, "REDEEM", "FILLED", null,
            winningOutcome?.let { BigDecimal.ONE } ?: BigDecimal.ZERO,
            totalQuantity,
            payout,
            realized
        )
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
        val recordedTrade = tradeRepository.save(
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
        scheduleNotificationAfterCommit(config, session, recordedTrade)
    }

    private fun scheduleNotificationAfterCommit(
        config: CopyTrading,
        session: CopySimulationSession,
        trade: CopySimulationTrade
    ) {
        if (suppressedNotifications.contains(notificationKey(config.id!!, trade.leaderTradeId, trade.action))) return
        val service = telegramNotificationService ?: return
        if (!shouldSendNotification(config, trade.status)) return
        val message = buildNotificationMessage(config, session, trade)
        val send = {
            notificationScope.launch {
                runCatching { service.sendMessage(message) }
                    .onFailure {
                        logger.warn(
                            "发送模拟订单 Telegram 通知失败: copyTradingId={}, tradeId={}, error={}",
                            config.id,
                            trade.leaderTradeId,
                            it.message
                        )
                    }
            }
        }

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() {
                        send()
                    }
                }
            )
        } else {
            send()
        }
    }

    private fun notificationKey(copyTradingId: Long, leaderTradeId: String, action: String): String =
        "$copyTradingId:$leaderTradeId:${action.uppercase()}"

    internal fun shouldSendNotification(config: CopyTrading, status: String): Boolean =
        when (status) {
            "FILLED" -> true
            "FILTERED", "SKIPPED" -> config.pushFilteredOrders
            "REJECTED" -> config.pushFailedOrders
            else -> false
        }

    internal fun buildNotificationMessage(
        config: CopyTrading,
        session: CopySimulationSession,
        trade: CopySimulationTrade
    ): String {
        val statusLabel = when (trade.status) {
            "FILLED" -> "成交"
            "FILTERED" -> "已過濾"
            "REJECTED" -> "已拒絕"
            "SKIPPED" -> "已跳過"
            else -> trade.status
        }
        val outcome = trade.outcomeIndex?.let { if (it == 0) "YES / 0" else "NO / $it" } ?: "未知"
        val price = trade.price?.plain() ?: "-"
        val reason = trade.reason?.takeIf { it.isNotBlank() }?.let {
            "\n⚠️ <b>原因：</b>${escapeHtml(it)}"
        }.orEmpty()
        val realized = if (trade.realizedPnl.compareTo(BigDecimal.ZERO) == 0) {
            ""
        } else {
            "\n📈 <b>本次已實現：</b><code>${trade.realizedPnl.plain()} USDC</code>"
        }

        return """
            🧪 <b>模擬訂單$statusLabel</b>

            📋 <b>配置：</b>${escapeHtml(config.configName ?: "未命名配置")}
            🔄 <b>動作：</b><code>${escapeHtml(trade.action)}</code>
            🎯 <b>結果：</b><code>${escapeHtml(outcome)}</code>
            💵 <b>價格：</b><code>$price</code>
            📦 <b>數量：</b><code>${trade.quantity.plain()} shares</code>
            💰 <b>金額：</b><code>${trade.notional.plain()} USDC</code>$realized
            🏦 <b>模擬可用資金：</b><code>${session.cashBalance.plain()} USDC</code>
            🔎 <b>市場：</b><code>${escapeHtml(trade.marketId)}</code>$reason

            ⚠️ <b>這是模擬交易，沒有送出真實訂單。</b>
        """.trimIndent()
    }

    private fun BigDecimal.plain(): String =
        stripTrailingZeros().toPlainString()

    private fun escapeHtml(value: String): String =
        value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

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
        val originAt = current.originAt
        shareAccumulatorService?.clear(copyTradingId)
        tradeRepository.deleteByCopyTradingId(copyTradingId)
        positionRepository.deleteByCopyTradingId(copyTradingId)
        sessionRepository.deleteByCopyTradingId(copyTradingId)
        sessionRepository.flush()
        sessionRepository.save(
            CopySimulationSession(
                copyTradingId = copyTradingId,
                initialCash = initialCash,
                cashBalance = initialCash,
                originAt = originAt
            )
        )
        return summary(copyTradingId)
    }
}
