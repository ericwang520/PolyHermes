package com.wrbug.polymarketbot.service.copytrading.simulation

import com.wrbug.polymarketbot.api.TradeResponse
import com.wrbug.polymarketbot.entity.CopySimulationPosition
import com.wrbug.polymarketbot.entity.CopySimulationSession
import com.wrbug.polymarketbot.entity.CopySimulationTrade
import com.wrbug.polymarketbot.entity.CopyTrading
import com.wrbug.polymarketbot.repository.CopySimulationPositionRepository
import com.wrbug.polymarketbot.repository.CopySimulationSessionRepository
import com.wrbug.polymarketbot.repository.CopySimulationTradeRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.math.BigDecimal

class CopySimulationServiceTest {

    private val sessionRepository = mock(CopySimulationSessionRepository::class.java)
    private val positionRepository = mock(CopySimulationPositionRepository::class.java)
    private val tradeRepository = mock(CopySimulationTradeRepository::class.java)
    private val service = CopySimulationService(sessionRepository, positionRepository, tradeRepository)

    @Test
    fun `paper fixed buy updates only simulated cash and position`() {
        var savedSession: CopySimulationSession? = null
        var savedPosition: CopySimulationPosition? = null
        var savedTrade: CopySimulationTrade? = null

        `when`(tradeRepository.existsByCopyTradingIdAndLeaderTradeIdAndAction(10, "trade-1", "BUY"))
            .thenReturn(false)
        `when`(sessionRepository.findByCopyTradingId(10)).thenReturn(null)
        `when`(sessionRepository.save(any(CopySimulationSession::class.java))).thenAnswer {
            val session = it.getArgument<CopySimulationSession>(0)
            val persisted = if (session.id == null) session.copy(id = 1L) else session
            savedSession = persisted
            persisted
        }
        `when`(
            tradeRepository.countFilledSince(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong()
            )
        ).thenReturn(0)
        `when`(
            tradeRepository.sumRealizedPnlSince(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong()
            )
        ).thenReturn(BigDecimal.ZERO)
        `when`(
            positionRepository.findByCopyTradingIdAndMarketIdAndOutcomeIndex(10, "condition-1", 0)
        ).thenReturn(null)
        `when`(positionRepository.save(any(CopySimulationPosition::class.java))).thenAnswer {
            it.getArgument<CopySimulationPosition>(0).also { position -> savedPosition = position }
        }
        `when`(tradeRepository.save(any(CopySimulationTrade::class.java))).thenAnswer {
            it.getArgument<CopySimulationTrade>(0).also { trade -> savedTrade = trade }
        }

        val result = service.process(
            copyTrading = CopyTrading(
                id = 10,
                accountId = 1,
                leaderId = 2,
                executionMode = "PAPER",
                paperInitialBalance = BigDecimal("10"),
                copyMode = "FIXED",
                fixedAmount = BigDecimal("2"),
                minOrderSize = BigDecimal("1"),
                maxOrderSize = BigDecimal("5")
            ),
            leaderTrade = TradeResponse(
                id = "trade-1",
                market = "condition-1",
                side = "BUY",
                price = "0.5",
                size = "100",
                timestamp = "1760000000",
                user = "0xleader",
                outcomeIndex = 0,
                tokenId = "123"
            )
        )

        assertTrue(result.isSuccess)
        assertEquals(0, savedSession!!.cashBalance.compareTo(BigDecimal("8")))
        assertEquals(0, savedPosition!!.quantity.compareTo(BigDecimal("4")))
        assertEquals(0, savedPosition!!.leaderQuantity.compareTo(BigDecimal("100")))
        assertEquals(0, savedPosition!!.averageCost.compareTo(BigDecimal("0.5")))
        assertEquals("FILLED", savedTrade!!.status)
        assertEquals("LEADER_PRICE", savedTrade!!.fillAssumption)
    }

    @Test
    fun `live mode never touches simulation ledger`() {
        val result = service.process(
            CopyTrading(id = 11, accountId = 1, leaderId = 2, executionMode = "LIVE"),
            TradeResponse(
                id = "trade-live",
                market = "condition-1",
                side = "BUY",
                price = "0.5",
                size = "1",
                timestamp = "1760000000",
                user = "0xleader",
                outcomeIndex = 0
            )
        )

        assertTrue(result.isSuccess)
        verify(sessionRepository, never()).findByCopyTradingId(11)
        verify(tradeRepository, never()).save(any(CopySimulationTrade::class.java))
    }
}
