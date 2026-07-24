package com.wrbug.polymarketbot.entity

import jakarta.persistence.*
import java.math.BigDecimal

@Entity
@Table(name = "copy_simulation_session")
data class CopySimulationSession(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @Column(name = "copy_trading_id", nullable = false)
    val copyTradingId: Long,
    @Column(name = "initial_cash", nullable = false, precision = 20, scale = 8)
    val initialCash: BigDecimal,
    @Column(name = "cash_balance", nullable = false, precision = 20, scale = 8)
    var cashBalance: BigDecimal,
    @Column(name = "realized_pnl", nullable = false, precision = 20, scale = 8)
    var realizedPnl: BigDecimal = BigDecimal.ZERO,
    @Column(name = "total_fees", nullable = false, precision = 20, scale = 8)
    var totalFees: BigDecimal = BigDecimal.ZERO,
    @Column(name = "trade_count", nullable = false)
    var tradeCount: Int = 0,
    @Column(name = "status", nullable = false, length = 20)
    var status: String = "ACTIVE",
    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Long = System.currentTimeMillis()
)

@Entity
@Table(
    name = "copy_simulation_position",
    uniqueConstraints = [UniqueConstraint(
        name = "uk_copy_simulation_position",
        columnNames = ["copy_trading_id", "market_id", "outcome_index"]
    )]
)
data class CopySimulationPosition(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @Column(name = "session_id", nullable = false)
    val sessionId: Long,
    @Column(name = "copy_trading_id", nullable = false)
    val copyTradingId: Long,
    @Column(name = "market_id", nullable = false, length = 100)
    val marketId: String,
    @Column(name = "outcome_index", nullable = false)
    val outcomeIndex: Int,
    @Column(name = "token_id", length = 100)
    var tokenId: String? = null,
    @Column(name = "quantity", nullable = false, precision = 20, scale = 8)
    var quantity: BigDecimal = BigDecimal.ZERO,
    @Column(name = "leader_quantity", nullable = false, precision = 20, scale = 8)
    var leaderQuantity: BigDecimal = BigDecimal.ZERO,
    @Column(name = "average_cost", nullable = false, precision = 20, scale = 8)
    var averageCost: BigDecimal = BigDecimal.ZERO,
    @Column(name = "realized_pnl", nullable = false, precision = 20, scale = 8)
    var realizedPnl: BigDecimal = BigDecimal.ZERO,
    @Column(name = "last_price", precision = 20, scale = 8)
    var lastPrice: BigDecimal? = null,
    @Column(name = "valuation_status", nullable = false, length = 20)
    var valuationStatus: String = "LEADER_PRICE",
    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Long = System.currentTimeMillis()
)

@Entity
@Table(
    name = "copy_simulation_trade",
    uniqueConstraints = [UniqueConstraint(
        name = "uk_copy_simulation_trade_event",
        columnNames = ["copy_trading_id", "leader_trade_id", "action"]
    )]
)
data class CopySimulationTrade(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @Column(name = "session_id", nullable = false)
    val sessionId: Long,
    @Column(name = "copy_trading_id", nullable = false)
    val copyTradingId: Long,
    @Column(name = "leader_trade_id", nullable = false, length = 120)
    val leaderTradeId: String,
    @Column(name = "action", nullable = false, length = 20)
    val action: String,
    @Column(name = "market_id", nullable = false, length = 100)
    val marketId: String,
    @Column(name = "outcome_index")
    val outcomeIndex: Int? = null,
    @Column(name = "token_id", length = 100)
    val tokenId: String? = null,
    @Column(name = "price", precision = 20, scale = 8)
    val price: BigDecimal? = null,
    @Column(name = "quantity", nullable = false, precision = 20, scale = 8)
    val quantity: BigDecimal = BigDecimal.ZERO,
    @Column(name = "notional", nullable = false, precision = 20, scale = 8)
    val notional: BigDecimal = BigDecimal.ZERO,
    @Column(name = "fee", nullable = false, precision = 20, scale = 8)
    val fee: BigDecimal = BigDecimal.ZERO,
    @Column(name = "realized_pnl", nullable = false, precision = 20, scale = 8)
    val realizedPnl: BigDecimal = BigDecimal.ZERO,
    @Column(name = "status", nullable = false, length = 20)
    val status: String,
    @Column(name = "reason", length = 500)
    val reason: String? = null,
    @Column(name = "fill_assumption", nullable = false, length = 30)
    val fillAssumption: String = "LEADER_PRICE",
    @Column(name = "event_time", nullable = false)
    val eventTime: Long,
    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis()
)
