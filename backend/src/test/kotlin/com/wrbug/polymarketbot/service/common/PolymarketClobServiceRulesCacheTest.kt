package com.wrbug.polymarketbot.service.common

import com.wrbug.polymarketbot.api.OrderbookResponse
import com.wrbug.polymarketbot.api.PolymarketClobApi
import com.wrbug.polymarketbot.util.RetrofitFactory
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.math.BigDecimal

class PolymarketClobServiceRulesCacheTest {

    @Test
    fun `reuses cached market rules without another orderbook request`() = runTest {
        val service = PolymarketClobService(
            mock(PolymarketClobApi::class.java),
            mock(RetrofitFactory::class.java)
        )
        val orderbook = OrderbookResponse(
            bids = emptyList(),
            asks = emptyList(),
            minOrderSize = "5",
            tickSize = "0.01",
            negRisk = false
        )

        val first = service.getMarketOrderRules("token-cache", orderbook).getOrThrow()
        val cached = service.getMarketOrderRules("token-cache").getOrThrow()

        assertEquals(0, first.minimumShares.compareTo(BigDecimal("5")))
        assertEquals(0, cached.minimumShares.compareTo(BigDecimal("5")))
        assertEquals(0, cached.tickSize!!.compareTo(BigDecimal("0.01")))
    }
}
