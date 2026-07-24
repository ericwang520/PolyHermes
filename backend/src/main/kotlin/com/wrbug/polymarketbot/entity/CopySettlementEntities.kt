package com.wrbug.polymarketbot.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.math.BigDecimal

@Entity
@Table(name = "copy_settlement_sync_cursor")
data class CopySettlementSyncCursor(
    @Id
    @Column(name = "leader_id")
    val leaderId: Long,
    @Column(name = "last_timestamp", nullable = false)
    var lastTimestamp: Long,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Long = System.currentTimeMillis()
)

@Entity
@Table(
    name = "copy_settlement_event",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_copy_settlement_stable_event", columnNames = ["stable_event_key"])
    ],
    indexes = [
        Index(name = "idx_copy_settlement_pending", columnList = "status,event_time"),
        Index(name = "idx_copy_settlement_leader_time", columnList = "leader_id,event_time")
    ]
)
data class CopySettlementEvent(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @Column(name = "leader_id", nullable = false)
    val leaderId: Long,
    @Column(name = "stable_event_key", nullable = false, length = 180)
    val stableEventKey: String,
    @Column(name = "leader_trade_id", nullable = false, length = 100)
    val leaderTradeId: String,
    @Column(name = "action", nullable = false, length = 10)
    val action: String,
    @Column(name = "transaction_hash", length = 100)
    val transactionHash: String? = null,
    @Column(name = "market_id", nullable = false, length = 100)
    val marketId: String,
    @Column(name = "outcome_index")
    val outcomeIndex: Int? = null,
    @Column(name = "amount", nullable = false, precision = 20, scale = 8)
    val amount: BigDecimal = BigDecimal.ZERO,
    @Column(name = "event_time", nullable = false)
    val eventTime: Long,
    @Column(name = "status", nullable = false, length = 20)
    var status: String = "NEW",
    @Column(name = "attempt_count", nullable = false)
    var attemptCount: Int = 0,
    @Column(name = "last_error", length = 1000)
    var lastError: String? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Long = System.currentTimeMillis()
)
