package com.wrbug.polymarketbot.service.accounts

import com.wrbug.polymarketbot.dto.SimulatedAccountCreateRequest
import com.wrbug.polymarketbot.entity.Account
import com.wrbug.polymarketbot.enums.WalletType
import com.wrbug.polymarketbot.repository.AccountRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito

class SimulatedAccountServiceTest {
    private val accountRepository = Mockito.mock(AccountRepository::class.java)
    private val service = SimulatedAccountService(accountRepository)

    @Test
    fun `creates credential-free simulated wallet`() {
        Mockito.`when`(accountRepository.existsByProxyAddress(Mockito.anyString())).thenReturn(false)
        Mockito.`when`(accountRepository.save(any(Account::class.java))).thenAnswer {
            (it.arguments[0] as Account).copy(id = 7L)
        }

        val result = service.create(
            SimulatedAccountCreateRequest(accountName = "bosona paper", initialBalance = "49.55")
        ).getOrThrow()

        assertEquals(7L, result.id)
        assertEquals(WalletType.SIMULATED.value, result.walletType)
        assertEquals("49.55", result.simulatedBalance)
        assertTrue(result.simulated)
        assertFalse(result.apiKeyConfigured)
        assertTrue(result.walletAddress.matches(Regex("^0x[0-9a-f]{40}$")))
    }

    @Test
    fun `rejects non-positive simulated balance`() {
        val result = service.create(SimulatedAccountCreateRequest(initialBalance = "0"))
        assertTrue(result.isFailure)
    }
}
