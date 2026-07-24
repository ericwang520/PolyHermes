package com.wrbug.polymarketbot.entity

import jakarta.persistence.*
import java.math.BigDecimal

/**
 * 实盘跟单执行账本。
 *
 * 与 copy_order_tracking 不同，这张表会保存没有成功送到 CLOB 的 Leader 信号，
 * 例如价格过滤、零碎 shares 累积、没有流动性和签名失败。
 */
@Entity
@Table(
    name = "copy_execution_event",
    uniqueConstraints = [UniqueConstraint(
        name = "uk_copy_execution_event",
        columnNames = ["copy_trading_id", "leader_trade_id", "action"]
    )],
    indexes = [
        Index(name = "idx_copy_execution_event_config_time", columnList = "copy_trading_id,event_time"),
        Index(name = "idx_copy_execution_event_order_id", columnList = "order_id")
    ]
)
data class CopyExecutionEvent(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "copy_trading_id", nullable = false)
    val copyTradingId: Long,

    @Column(name = "account_id", nullable = false)
    val accountId: Long,

    @Column(name = "leader_id", nullable = false)
    val leaderId: Long,

    @Column(name = "leader_trade_id", nullable = false, length = 120)
    val leaderTradeId: String,

    @Column(name = "action", nullable = false, length = 20)
    val action: String,

    @Column(name = "status", nullable = false, length = 20)
    var status: String = "DETECTED",

    @Column(name = "market_id", nullable = false, length = 100)
    var marketId: String,

    @Column(name = "outcome_index")
    var outcomeIndex: Int? = null,

    @Column(name = "token_id", length = 100)
    var tokenId: String? = null,

    @Column(name = "leader_price", precision = 20, scale = 8)
    var leaderPrice: BigDecimal? = null,

    @Column(name = "execution_price", precision = 20, scale = 8)
    var executionPrice: BigDecimal? = null,

    @Column(name = "quantity", nullable = false, precision = 20, scale = 8)
    var quantity: BigDecimal = BigDecimal.ZERO,

    @Column(name = "notional", nullable = false, precision = 20, scale = 8)
    var notional: BigDecimal = BigDecimal.ZERO,

    @Column(name = "reason", length = 1000)
    var reason: String? = null,

    @Column(name = "order_id", length = 100)
    var orderId: String? = null,

    @Column(name = "source", nullable = false, length = 30)
    var source: String = "unknown",

    @Column(name = "event_time", nullable = false)
    val eventTime: Long,

    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Long = System.currentTimeMillis()
)
