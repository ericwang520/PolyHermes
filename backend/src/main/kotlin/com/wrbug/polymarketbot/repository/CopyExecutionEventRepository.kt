package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.CopyExecutionEvent
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface CopyExecutionEventRepository : JpaRepository<CopyExecutionEvent, Long> {
    fun findByCopyTradingIdAndLeaderTradeIdAndAction(
        copyTradingId: Long,
        leaderTradeId: String,
        action: String
    ): CopyExecutionEvent?

    fun findTop200ByCopyTradingIdOrderByEventTimeDesc(copyTradingId: Long): List<CopyExecutionEvent>

    fun countByCopyTradingId(copyTradingId: Long): Long

    fun countByCopyTradingIdAndEventTimeGreaterThanEqual(copyTradingId: Long, eventTime: Long): Long

    @Query("SELECT e.status, COUNT(e) FROM CopyExecutionEvent e WHERE e.copyTradingId = :copyTradingId GROUP BY e.status")
    fun countStatuses(copyTradingId: Long): List<Array<Any>>
}
