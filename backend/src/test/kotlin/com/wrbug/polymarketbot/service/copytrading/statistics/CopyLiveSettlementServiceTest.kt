package com.wrbug.polymarketbot.service.copytrading.statistics

import com.wrbug.polymarketbot.api.TradeResponse
import com.wrbug.polymarketbot.dto.AccountPositionDto
import com.wrbug.polymarketbot.dto.PositionListResponse
import com.wrbug.polymarketbot.dto.PositionMergeRequest
import com.wrbug.polymarketbot.dto.PositionMergeResponse
import com.wrbug.polymarketbot.entity.CopyTrading
import com.wrbug.polymarketbot.repository.CopyExecutionEventRepository
import com.wrbug.polymarketbot.service.accounts.AccountService
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class CopyLiveSettlementServiceTest {
    private val accountService = mock(AccountService::class.java)
    private val eventService = mock(CopyExecutionEventService::class.java)
    private val eventRepository = mock(CopyExecutionEventRepository::class.java)
    private val service = CopyLiveSettlementService(accountService, eventService, eventRepository)

    @Test
    fun `disabled live settlement records skip without touching wallet`() = runTest {
        val config = CopyTrading(
            id = 1,
            accountId = 2,
            leaderId = 3,
            executionMode = "LIVE",
            followOnchainActions = false
        )

        val result = service.process(config, settlement("REDEEM"))

        assertTrue(result.isSuccess)
        verify(accountService, never()).getAllPositions()
    }

    @Test
    fun `merge uses follower matched inventory instead of leader amount`() = runTest {
        val config = CopyTrading(
            id = 4,
            accountId = 5,
            leaderId = 6,
            executionMode = "LIVE",
            followOnchainActions = true
        )
        `when`(accountService.getAllPositions()).thenReturn(
            Result.success(
                PositionListResponse(
                    currentPositions = listOf(
                        position(accountId = 5, outcomeIndex = 0, quantity = "7.5"),
                        position(accountId = 5, outcomeIndex = 1, quantity = "5.25")
                    ),
                    historyPositions = emptyList()
                )
            )
        )
        `when`(
            accountService.mergePositions(
                PositionMergeRequest(
                    accountId = 5,
                    marketId = "condition",
                    quantity = "5.25"
                )
            )
        ).thenReturn(
            Result.success(
                PositionMergeResponse(
                    accountId = 5,
                    marketId = "condition",
                    quantity = "5.25",
                    transactionHash = "0xtx"
                )
            )
        )

        val result = service.process(config, settlement("MERGE", size = "1000"))

        assertTrue(result.isSuccess)
        verify(accountService).mergePositions(
            PositionMergeRequest(
                accountId = 5,
                marketId = "condition",
                quantity = "5.25"
            )
        )
    }

    private fun settlement(action: String, size: String = "1") = TradeResponse(
        id = "settlement-1",
        market = "condition",
        side = action,
        price = "0",
        size = size,
        timestamp = "1760000000",
        user = "0xleader",
        outcomeIndex = 999
    )

    private fun position(accountId: Long, outcomeIndex: Int, quantity: String) = AccountPositionDto(
        accountId = accountId,
        accountName = "live",
        walletAddress = "0xwallet",
        proxyAddress = "0xproxy",
        marketId = "condition",
        marketTitle = "Market",
        marketSlug = "market",
        marketIcon = null,
        side = if (outcomeIndex == 0) "YES" else "NO",
        outcomeIndex = outcomeIndex,
        quantity = quantity,
        originalQuantity = quantity,
        avgPrice = "0.5",
        currentPrice = "0.5",
        currentValue = "1",
        initialValue = "1",
        pnl = "0",
        percentPnl = "0",
        realizedPnl = "0",
        percentRealizedPnl = "0",
        redeemable = false,
        mergeable = true,
        endDate = null
    )
}
