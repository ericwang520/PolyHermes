package com.wrbug.polymarketbot.service.copytrading.orders

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OrderSigningServiceTest {

    private val service = OrderSigningService()

    @Test
    fun `maps deposit wallet to signature type 3`() {
        assertEquals(1, service.getSignatureTypeForWalletType("magic"))
        assertEquals(2, service.getSignatureTypeForWalletType("safe"))
        assertEquals(3, service.getSignatureTypeForWalletType("deposit"))
    }

    @Test
    fun `uses deposit wallet as both maker and signer`() {
        val depositWallet = "0x1111111111111111111111111111111111111111"
        val order = service.createAndSignOrder(
            privateKey = "ac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80",
            makerAddress = depositWallet,
            tokenId = "123",
            side = "BUY",
            price = "0.50",
            size = "2",
            signatureType = 3
        )

        assertEquals(depositWallet, order.maker)
        assertEquals(depositWallet, order.signer)
        assertEquals(3, order.signatureType)
        assertEquals(636, order.signature.length)
    }
}
