package com.wrbug.polymarketbot.util

import com.wrbug.polymarketbot.api.BuilderRelayerApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.web3j.utils.Numeric
import java.math.BigInteger

class DepositWalletBatchEncodingTest {

    @Test
    fun `matches ethers v6 Deposit Wallet batch reference vector`() {
        val domainHash = Eip712Encoder.encodeDepositWalletDomain(
            chainId = 137L,
            verifyingContract = "0x1111111111111111111111111111111111111111"
        )
        val batchHash = Eip712Encoder.encodeDepositWalletBatch(
            wallet = "0x2222222222222222222222222222222222222222",
            nonce = BigInteger.valueOf(7),
            deadline = BigInteger.valueOf(1_760_000_000),
            calls = listOf(
                BuilderRelayerApi.DepositWalletCall(
                    target = "0x3333333333333333333333333333333333333333",
                    value = "0",
                    data = "0x1234"
                ),
                BuilderRelayerApi.DepositWalletCall(
                    target = "0x4444444444444444444444444444444444444444",
                    value = "5",
                    data = "0xabcd00"
                )
            )
        )
        val digest = Eip712Encoder.hashStructuredData(domainHash, batchHash)

        assertEquals(
            "0x5bd6712424895233714f4848fd97f0347e3bb74d8364b39f701009b05c5df522",
            Numeric.toHexString(domainHash)
        )
        assertEquals(
            "0x92a6ac4ddf1ec7eed7a526ef8547d1ef788c954eee9f92d7fd5e0fae2bb9423d",
            Numeric.toHexString(batchHash)
        )
        assertEquals(
            "0x43325504b8ff3853268b4610b39f99b38e01b358f7be0bd3b481d63a9043d5b6",
            Numeric.toHexString(digest)
        )
    }
}
