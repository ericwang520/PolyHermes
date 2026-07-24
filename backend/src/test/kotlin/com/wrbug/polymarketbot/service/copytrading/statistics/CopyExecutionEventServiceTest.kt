package com.wrbug.polymarketbot.service.copytrading.statistics

import com.wrbug.polymarketbot.api.TradeResponse
import com.wrbug.polymarketbot.entity.CopyExecutionEvent
import com.wrbug.polymarketbot.entity.CopyTrading
import com.wrbug.polymarketbot.repository.CopyExecutionEventRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.math.BigDecimal

class CopyExecutionEventServiceTest {
    private val repository = mock(CopyExecutionEventRepository::class.java)
    private val service = CopyExecutionEventService(repository)

    @Test
    fun `records detected signal then updates same ledger row to filtered`() {
        var stored: CopyExecutionEvent? = null
        `when`(repository.findByCopyTradingIdAndLeaderTradeIdAndAction(7, "trade-1", "BUY"))
            .thenAnswer { stored }
        `when`(repository.save(any(CopyExecutionEvent::class.java))).thenAnswer {
            it.getArgument<CopyExecutionEvent>(0).also { event -> stored = event }
        }

        val config = CopyTrading(
            id = 7,
            accountId = 6,
            leaderId = 1,
            copyMode = "RATIO",
            copyRatio = BigDecimal("0.01")
        )
        val trade = TradeResponse(
            id = "trade-1",
            market = "condition-1",
            side = "BUY",
            price = "0.25",
            size = "100",
            timestamp = "1760000000",
            user = "0xleader",
            outcomeIndex = 0,
            tokenId = "token-1"
        )

        service.detected(config, trade, "BUY", "activity-ws")
        service.update(
            config,
            trade,
            "BUY",
            "FILTERED",
            reason = "超过价格容忍度",
            executionPrice = BigDecimal("0.26"),
            quantity = BigDecimal.ONE
        )

        assertNotNull(stored)
        assertEquals("FILTERED", stored!!.status)
        assertEquals("超过价格容忍度", stored!!.reason)
        assertEquals(0, stored!!.quantity.compareTo(BigDecimal.ONE))
        assertEquals(0, stored!!.notional.compareTo(BigDecimal("0.26")))
        assertEquals(1760000000000L, stored!!.eventTime)
    }
}
