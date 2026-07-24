package com.wrbug.polymarketbot.service.copytrading.orders

import com.wrbug.polymarketbot.entity.CopyShareAccumulator
import com.wrbug.polymarketbot.repository.CopyShareAccumulatorRepository
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.`when`
import java.math.BigDecimal

class CopyShareAccumulatorServiceTest {

    @Test
    fun `persists dust then releases accumulated shares at market minimum`() = runTest {
        val repository = mock(CopyShareAccumulatorRepository::class.java)
        var stored: CopyShareAccumulator? = null
        `when`(repository.findByCopyTradingIdAndTokenIdAndSide(1, "token", "BUY"))
            .thenAnswer { stored }
        `when`(repository.save(any(CopyShareAccumulator::class.java))).thenAnswer {
            val row = it.getArgument<CopyShareAccumulator>(0)
            val persisted = if (row.id == null) row.copy(id = 9) else row
            stored = persisted
            persisted
        }

        val service = CopyShareAccumulatorService(repository)
        val first = service.accumulateAndTake(
            1, "market", 0, "token", "BUY",
            BigDecimal("2"), BigDecimal("20"), BigDecimal("5")
        )
        val second = service.accumulateAndTake(
            1, "market", 0, "token", "BUY",
            BigDecimal("2"), BigDecimal("20"), BigDecimal("5")
        )
        val third = service.accumulateAndTake(
            1, "market", 0, "token", "BUY",
            BigDecimal("2"), BigDecimal("20"), BigDecimal("5")
        )

        assertTrue(first is ShareAccumulationResult.Pending)
        assertEquals(0, (second as ShareAccumulationResult.Pending).pendingQuantity.compareTo(BigDecimal("4")))
        assertEquals(0, (third as ShareAccumulationResult.Ready).quantity.compareTo(BigDecimal("6")))
        assertEquals(0, third.leaderQuantity.compareTo(BigDecimal("60")))
    }

    @Test
    fun `keeps quantity above execution cap for the next order`() = runTest {
        val repository = mock(CopyShareAccumulatorRepository::class.java)
        val existing = CopyShareAccumulator(
            id = 10,
            copyTradingId = 2,
            marketId = "market",
            outcomeIndex = 0,
            tokenId = "token",
            side = "BUY",
            pendingQuantity = BigDecimal("4"),
            pendingLeaderQuantity = BigDecimal("40"),
            eventCount = 2
        )
        `when`(repository.findByCopyTradingIdAndTokenIdAndSide(2, "token", "BUY"))
            .thenReturn(existing)
        `when`(repository.save(any(CopyShareAccumulator::class.java))).thenAnswer {
            it.getArgument<CopyShareAccumulator>(0)
        }

        val result = CopyShareAccumulatorService(repository).accumulateAndTake(
            2, "market", 0, "token", "BUY",
            BigDecimal("2"), BigDecimal("20"), BigDecimal("5"),
            maximumExecutableQuantity = BigDecimal("5")
        ) as ShareAccumulationResult.Ready

        assertEquals(0, result.quantity.compareTo(BigDecimal("5")))
        assertEquals(0, result.remainingQuantity.compareTo(BigDecimal("1")))
        assertEquals(0, existing.pendingQuantity.compareTo(BigDecimal("1")))
    }

    @Test
    fun `opposite dust cancels instead of creating a future round trip`() = runTest {
        val repository = mock(CopyShareAccumulatorRepository::class.java)
        val stored = mutableMapOf<String, CopyShareAccumulator>()
        `when`(repository.findByCopyTradingIdAndTokenIdAndSide(3, "token", "BUY"))
            .thenAnswer { stored["BUY"] }
        `when`(repository.findByCopyTradingIdAndTokenIdAndSide(3, "token", "SELL"))
            .thenAnswer { stored["SELL"] }
        `when`(repository.save(any(CopyShareAccumulator::class.java))).thenAnswer {
            val row = it.getArgument<CopyShareAccumulator>(0)
            val persisted = if (row.id == null) row.copy(id = if (row.side == "BUY") 31 else 32) else row
            stored[persisted.side] = persisted
            persisted
        }
        doAnswer {
            val row = it.getArgument<CopyShareAccumulator>(0)
            stored.remove(row.side)
            null
        }.`when`(repository).delete(any(CopyShareAccumulator::class.java))

        val service = CopyShareAccumulatorService(repository)
        val buy = service.accumulateAndTake(
            3, "market", 0, "token", "BUY",
            BigDecimal("2"), BigDecimal("20"), BigDecimal("5")
        )
        val sell = service.accumulateAndTake(
            3, "market", 0, "token", "SELL",
            BigDecimal("2"), BigDecimal("20"), BigDecimal("5")
        )

        assertTrue(buy is ShareAccumulationResult.Pending)
        assertTrue(sell is ShareAccumulationResult.Netted)
        assertTrue(stored.isEmpty())
        assertEquals(
            0,
            (sell as ShareAccumulationResult.Netted).cancelledQuantity.compareTo(BigDecimal("2"))
        )
    }
}
