package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.CopySimulationPosition
import com.wrbug.polymarketbot.entity.CopySimulationSession
import com.wrbug.polymarketbot.entity.CopySimulationTrade
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.math.BigDecimal

interface CopySimulationSessionRepository : JpaRepository<CopySimulationSession, Long> {
    fun findByCopyTradingId(copyTradingId: Long): CopySimulationSession?
    fun deleteByCopyTradingId(copyTradingId: Long)
}

interface CopySimulationPositionRepository : JpaRepository<CopySimulationPosition, Long> {
    fun findByCopyTradingIdAndMarketIdAndOutcomeIndex(
        copyTradingId: Long,
        marketId: String,
        outcomeIndex: Int
    ): CopySimulationPosition?

    fun findByCopyTradingIdOrderByUpdatedAtDesc(copyTradingId: Long): List<CopySimulationPosition>
    fun findByCopyTradingIdAndMarketIdOrderByOutcomeIndex(
        copyTradingId: Long,
        marketId: String
    ): List<CopySimulationPosition>
    fun deleteByCopyTradingId(copyTradingId: Long)
}

interface CopySimulationTradeRepository : JpaRepository<CopySimulationTrade, Long> {
    fun existsByCopyTradingIdAndLeaderTradeIdAndAction(
        copyTradingId: Long,
        leaderTradeId: String,
        action: String
    ): Boolean

    fun findTop200ByCopyTradingIdOrderByEventTimeDesc(copyTradingId: Long): List<CopySimulationTrade>
    fun deleteByCopyTradingId(copyTradingId: Long)
    @Query(
        "SELECT COUNT(t) FROM CopySimulationTrade t " +
            "WHERE t.copyTradingId = :copyTradingId AND t.status = 'FILLED' AND t.createdAt >= :createdAt"
    )
    fun countFilledSince(
        @Param("copyTradingId") copyTradingId: Long,
        @Param("createdAt") createdAt: Long
    ): Long

    @Query(
        "SELECT COALESCE(SUM(t.realizedPnl), 0) FROM CopySimulationTrade t " +
            "WHERE t.copyTradingId = :copyTradingId AND t.status = 'FILLED' AND t.createdAt >= :createdAt"
    )
    fun sumRealizedPnlSince(
        @Param("copyTradingId") copyTradingId: Long,
        @Param("createdAt") createdAt: Long
    ): BigDecimal
}
