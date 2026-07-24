package com.wrbug.polymarketbot.service.accounts

import com.wrbug.polymarketbot.dto.AccountDto
import com.wrbug.polymarketbot.dto.SimulatedAccountCreateRequest
import com.wrbug.polymarketbot.entity.Account
import com.wrbug.polymarketbot.enums.WalletType
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.security.SecureRandom

@Service
class SimulatedAccountService(
    private val accountRepository: AccountRepository
) {
    private val random = SecureRandom()

    @Transactional
    fun create(request: SimulatedAccountCreateRequest): Result<AccountDto> = runCatching {
        val balance = request.initialBalance.toSafeBigDecimal()
        require(balance > BigDecimal.ZERO) { "模擬初始資金必須大於 0" }
        require(balance <= BigDecimal("1000000000")) { "模擬初始資金過大" }

        val syntheticAddress = generateUniqueAddress()
        val account = accountRepository.save(
            Account(
                privateKey = "SIMULATED_NO_PRIVATE_KEY",
                walletAddress = syntheticAddress,
                proxyAddress = syntheticAddress,
                accountName = request.accountName?.trim()?.takeIf { it.isNotBlank() }
                    ?: "模擬錢包",
                isEnabled = true,
                walletType = WalletType.SIMULATED.value,
                simulatedBalance = balance
            )
        )
        account.toSimulatedDto()
    }

    private fun generateUniqueAddress(): String {
        repeat(10) {
            val bytes = ByteArray(20)
            random.nextBytes(bytes)
            val address = "0x" + bytes.joinToString("") {
                (it.toInt() and 0xff).toString(16).padStart(2, '0')
            }
            if (!accountRepository.existsByProxyAddress(address)) return address
        }
        error("無法建立唯一的模擬錢包地址")
    }

    private fun Account.toSimulatedDto() = AccountDto(
        id = requireNotNull(id),
        walletAddress = walletAddress,
        proxyAddress = proxyAddress,
        accountName = accountName,
        isEnabled = isEnabled,
        walletType = walletType,
        simulated = true,
        simulatedBalance = simulatedBalance?.toPlainString(),
        balance = simulatedBalance?.toPlainString(),
        apiKeyConfigured = false,
        apiSecretConfigured = false,
        apiPassphraseConfigured = false
    )
}
