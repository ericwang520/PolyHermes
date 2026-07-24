package com.wrbug.polymarketbot.service.copytrading.orders

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class CopyOrderExecutionThresholdTest {
    @Test
    fun `uses market minimum shares when FAK small order mode is disabled`() {
        assertEquals(
            BigDecimal("5"),
            minimumExecutableShares(
                useFakForSmallOrders = false,
                executionPrice = BigDecimal("0.7875"),
                marketMinimumShares = BigDecimal("5")
            )
        )
    }

    @Test
    fun `uses one dollar notional when FAK small order mode is enabled`() {
        val threshold = minimumExecutableShares(
            useFakForSmallOrders = true,
            executionPrice = BigDecimal("0.7875"),
            marketMinimumShares = BigDecimal("5")
        )

        assertEquals(BigDecimal("1.26984127"), threshold)
        check(threshold.multiply(BigDecimal("0.7875")) >= BigDecimal.ONE)
        check(threshold < BigDecimal("5"))
    }

    @Test
    fun `one dollar threshold can exceed market share minimum at low prices`() {
        assertEquals(
            BigDecimal("20.00000000"),
            minimumExecutableShares(
                useFakForSmallOrders = true,
                executionPrice = BigDecimal("0.05"),
                marketMinimumShares = BigDecimal("5")
            )
        )
    }
}
