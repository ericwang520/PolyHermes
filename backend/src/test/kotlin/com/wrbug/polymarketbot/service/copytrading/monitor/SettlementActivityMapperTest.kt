package com.wrbug.polymarketbot.service.copytrading.monitor

import com.wrbug.polymarketbot.api.UserActivityResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SettlementActivityMapperTest {
    @Test
    fun `same settlement has stable bounded trade id across sources`() {
        val first = SettlementActivityMapper.leaderTradeId("REDEEM", "0xabc", "0xmarket")
        val second = SettlementActivityMapper.leaderTradeId("redeem", "0xABC", "0xMARKET")
        val otherMarket = SettlementActivityMapper.leaderTradeId("REDEEM", "0xabc", "0xother")

        assertEquals(first, second)
        assertNotEquals(first, otherMarket)
        assertTrue(first.length <= 100)
    }

    @Test
    fun `maps redeem payout and sentinel outcome`() {
        val trade = SettlementActivityMapper.toTrade(
            UserActivityResponse(
                proxyWallet = "0xleader",
                timestamp = 1760000000,
                conditionId = "0xcondition",
                type = "REDEEM",
                size = 0.0,
                usdcSize = 0.0,
                transactionHash = "0xtx",
                outcomeIndex = 999
            )
        )

        assertEquals("REDEEM", trade.side)
        assertEquals("0.0", trade.size)
        assertEquals(999, trade.outcomeIndex)
    }
}
