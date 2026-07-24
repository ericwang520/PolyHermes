package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.CopySettlementEvent
import com.wrbug.polymarketbot.entity.CopySettlementSyncCursor
import org.springframework.data.jpa.repository.JpaRepository

interface CopySettlementSyncCursorRepository : JpaRepository<CopySettlementSyncCursor, Long>

interface CopySettlementEventRepository : JpaRepository<CopySettlementEvent, Long> {
    fun findByStableEventKey(stableEventKey: String): CopySettlementEvent?
    fun findTop200ByStatusInOrderByEventTimeAsc(statuses: Collection<String>): List<CopySettlementEvent>
}
