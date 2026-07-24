package com.wrbug.polymarketbot.entity

import jakarta.persistence.*
import java.math.BigDecimal

@Entity
@Table(
    name = "copy_share_accumulator",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_copy_share_accumulator",
            columnNames = ["copy_trading_id", "token_id", "side"]
        )
    ]
)
data class CopyShareAccumulator(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @Column(name = "copy_trading_id", nullable = false)
    val copyTradingId: Long,
    @Column(name = "market_id", nullable = false, length = 100)
    val marketId: String,
    @Column(name = "outcome_index")
    val outcomeIndex: Int? = null,
    @Column(name = "token_id", nullable = false, length = 100)
    val tokenId: String,
    @Column(name = "side", nullable = false, length = 10)
    val side: String,
    @Column(name = "pending_quantity", nullable = false, precision = 20, scale = 8)
    var pendingQuantity: BigDecimal = BigDecimal.ZERO,
    @Column(name = "pending_leader_quantity", nullable = false, precision = 20, scale = 8)
    var pendingLeaderQuantity: BigDecimal = BigDecimal.ZERO,
    @Column(name = "event_count", nullable = false)
    var eventCount: Int = 0,
    @Column(name = "first_event_at", nullable = false)
    val firstEventAt: Long = System.currentTimeMillis(),
    @Column(name = "last_event_at", nullable = false)
    var lastEventAt: Long = System.currentTimeMillis(),
    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Long = System.currentTimeMillis()
)
