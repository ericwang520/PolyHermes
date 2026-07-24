package com.wrbug.polymarketbot.service.copytrading.monitor

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigInteger

class OnChainWsUtilsTest {

    @Test
    fun `uses canonical CTF event topics for exact settlement classification`() {
        val mergeLogs = JsonParser.parseString(
            """[{"address":"${OnChainWsUtils.ERC1155_CONTRACT}","topics":["${OnChainWsUtils.POSITIONS_MERGE_TOPIC}"],"data":"0x"}]"""
        ).asJsonArray
        val redeemLogs = JsonParser.parseString(
            """[{"address":"${OnChainWsUtils.ERC1155_CONTRACT}","topics":["${OnChainWsUtils.PAYOUT_REDEMPTION_TOPIC}"],"data":"0x"}]"""
        ).asJsonArray

        assertEquals("MERGE", OnChainWsUtils.detectSettlementAction(mergeLogs))
        assertEquals("REDEEM", OnChainWsUtils.detectSettlementAction(redeemLogs))
    }

    @Test
    fun `classifies one burned outcome as redeem`() {
        assertEquals(
            "REDEEM",
            OnChainWsUtils.classifySettlement(
                mapOf(BigInteger.ONE to BigInteger("7500000")),
                BigInteger("7500000")
            )
        )
    }

    @Test
    fun `classifies equal paired burns with matching collateral as merge`() {
        assertEquals(
            "MERGE",
            OnChainWsUtils.classifySettlement(
                mapOf(
                    BigInteger.ONE to BigInteger("5000000"),
                    BigInteger.TWO to BigInteger("5000000")
                ),
                BigInteger("5000000")
            )
        )
    }

    @Test
    fun `fails closed for ambiguous settlement`() {
        assertEquals(
            "SETTLEMENT_UNKNOWN",
            OnChainWsUtils.classifySettlement(
                mapOf(
                    BigInteger.ONE to BigInteger("5000000"),
                    BigInteger.TWO to BigInteger("4000000")
                ),
                BigInteger("5000000")
            )
        )
        assertNull(
            OnChainWsUtils.classifySettlement(emptyMap(), BigInteger("5000000"))
        )
    }
}
