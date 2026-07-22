package com.wrbug.polymarketbot.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.web3j.crypto.Credentials
import org.web3j.crypto.Sign
import org.web3j.utils.Numeric

class Poly1271SignatureTest {

    @Test
    fun `matches official clob client v2 POLY_1271 reference vector`() {
        val exchange = "0xE111180000d2663C0091e4f400237545B87B996B"
        val depositWallet = "0x1111111111111111111111111111111111111111"
        val zeroBytes32 = "0x" + "0".repeat(64)

        val contentsHash = Eip712Encoder.encodeExchangeOrder(
            salt = 123456789L,
            maker = depositWallet,
            signer = depositWallet,
            tokenId = "123",
            makerAmount = "1000000",
            takerAmount = "8000000",
            side = "BUY",
            signatureType = 3,
            timestamp = "1760000000000",
            metadata = zeroBytes32,
            builder = zeroBytes32
        )
        val innerDigest = Eip712Encoder.encodeDepositWalletOrderDigest(
            chainId = 137L,
            exchangeContract = exchange,
            depositWallet = depositWallet,
            contentsHash = contentsHash
        )

        val credentials = Credentials.create(
            "ac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80"
        )
        val signature = Sign.signMessage(innerDigest, credentials.ecKeyPair, false)
        val wrapped = Eip712Encoder.wrapPoly1271OrderSignature(
            innerSignature = signature.r + signature.s + signature.v,
            appDomainSeparator = Eip712Encoder.encodeExchangeDomain(137L, exchange),
            contentsHash = contentsHash
        )

        // Generated with Polymarket/clob-client-v2@1.1.0 ExchangeOrderBuilderV2.
        val officialSignature =
            "0x8ba6bf4c6e19f45e0d3231fa7a5ac218fa16ddfb92d07612a9f16920583cf9e848272251a7a7251fe0975baa2b7efa23a9c054c409e59c6c20b0d8760391ed0f1b3264e159346253e26a64e00b69032db0e7d32f94628de3e6eecb50304d7af3d2a8c13a60619d59c6891d8e62867b87ab8210cd833a68d1156f2def2282119da24f726465722875696e743235362073616c742c61646472657373206d616b65722c61646472657373207369676e65722c75696e7432353620746f6b656e49642c75696e74323536206d616b6572416d6f756e742c75696e743235362074616b6572416d6f756e742c75696e743820736964652c75696e7438207369676e6174757265547970652c75696e743235362074696d657374616d702c62797465733332206d657461646174612c62797465733332206275696c6465722900ba"

        assertEquals(officialSignature, wrapped)
        assertEquals(317, Numeric.hexStringToByteArray(wrapped).size)
    }
}
