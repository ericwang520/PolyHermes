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

    @Test
    fun `paper ratio buy can fill below one USDC when minimum is one cent`() {
        var savedTrade: CopySimulationTrade? = null

        `when`(tradeRepository.existsByCopyTradingIdAndLeaderTradeIdAndAction(12, "trade-micro", "BUY"))
            .thenReturn(false)
        `when`(sessionRepository.findByCopyTradingId(12)).thenReturn(
            CopySimulationSession(
                id = 2,
                copyTradingId = 12,
                initialCash = BigDecimal("10"),
                cashBalance = BigDecimal("10")
            )
        )
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
            positionRepository.findByCopyTradingIdAndMarketIdAndOutcomeIndex(12, "condition-micro", 0)
        ).thenReturn(null)
        `when`(positionRepository.save(any(CopySimulationPosition::class.java))).thenAnswer {
            it.getArgument<CopySimulationPosition>(0)
        }
        `when`(sessionRepository.save(any(CopySimulationSession::class.java))).thenAnswer {
            it.getArgument<CopySimulationSession>(0)
        }
        `when`(tradeRepository.save(any(CopySimulationTrade::class.java))).thenAnswer {
            it.getArgument<CopySimulationTrade>(0).also { trade -> savedTrade = trade }
        }

        val result = service.process(
            copyTrading = CopyTrading(
                id = 12,
                accountId = 1,
                leaderId = 2,
                executionMode = "PAPER",
                copyMode = "RATIO",
                copyRatio = BigDecimal("0.01"),
                minOrderSize = BigDecimal("0.01"),
                maxOrderSize = BigDecimal("5")
            ),
            leaderTrade = TradeResponse(
                id = "trade-micro",
                market = "condition-micro",
                side = "BUY",
                price = "0.5",
                size = "62",
                timestamp = "1760000000",
                user = "0xleader",
                outcomeIndex = 0,
                tokenId = "456"
            )
        )

        assertTrue(result.isSuccess)
        assertEquals("FILLED", savedTrade!!.status)
        assertEquals(0, savedTrade!!.notional.compareTo(BigDecimal("0.31")))
    }

    @Test
    fun `paper notification preferences always include fills and gate other statuses`() {
        val defaultConfig = CopyTrading(id = 13, accountId = 1, leaderId = 2, executionMode = "PAPER")
        val verboseConfig = defaultConfig.copy(
            pushFailedOrders = true,
            pushFilteredOrders = true
        )

        assertTrue(service.shouldSendNotification(defaultConfig, "FILLED"))
        assertEquals(false, service.shouldSendNotification(defaultConfig, "FILTERED"))
        assertEquals(false, service.shouldSendNotification(defaultConfig, "REJECTED"))
        assertTrue(service.shouldSendNotification(verboseConfig, "FILTERED"))
        assertTrue(service.shouldSendNotification(verboseConfig, "SKIPPED"))
        assertTrue(service.shouldSendNotification(verboseConfig, "REJECTED"))
    }

    @Test
    fun `paper notification is unmistakably marked as simulated`() {
        val message = service.buildNotificationMessage(
            config = CopyTrading(
                id = 14,
                accountId = 1,
                leaderId = 2,
                executionMode = "PAPER",
                configName = "Bosona paper"
            ),
            session = CopySimulationSession(
                id = 3,
                copyTradingId = 14,
                initialCash = BigDecimal("300"),
                cashBalance = BigDecimal("299.69")
            ),
            trade = CopySimulationTrade(
                sessionId = 3,
                copyTradingId = 14,
                leaderTradeId = "trade-notify",
                action = "BUY",
                marketId = "condition-notify",
                outcomeIndex = 0,
                price = BigDecimal("0.5"),
                quantity = BigDecimal("0.62"),
                notional = BigDecimal("0.31"),
                status = "FILLED",
                eventTime = 1760000000000
            )
        )

        assertTrue(message.contains("模擬訂單成交"))
        assertTrue(message.contains("0.31 USDC"))
        assertTrue(message.contains("沒有送出真實訂單"))
    }
}
