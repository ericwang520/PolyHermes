package com.wrbug.polymarketbot.service.copytrading.statistics

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CopyOrderTrackingSignerValidationTest {

    private val ownerEoa = "0x56C8eb7E2658E8C196C0526D188d9E69957375CE"
    private val depositWallet = "0x499d6c7b4ed4acc5bdbd520438f733a3843b3e3d"

    @Test
    fun `deposit orders require the deposit wallet as order signer`() {
        assertTrue(
            CopyOrderTrackingService.isExpectedOrderSigner(
                signatureType = 3,
                orderSigner = depositWallet.uppercase(),
                makerAddress = depositWallet,
                walletAddress = ownerEoa
            )
        )
        assertFalse(
            CopyOrderTrackingService.isExpectedOrderSigner(
                signatureType = 3,
                orderSigner = ownerEoa,
                makerAddress = depositWallet,
                walletAddress = ownerEoa
            )
        )
    }

    @Test
    fun `legacy orders still require the owner EOA as order signer`() {
        assertTrue(
            CopyOrderTrackingService.isExpectedOrderSigner(
                signatureType = 2,
                orderSigner = ownerEoa,
                makerAddress = depositWallet,
                walletAddress = ownerEoa
            )
        )
        assertFalse(
            CopyOrderTrackingService.isExpectedOrderSigner(
                signatureType = 2,
                orderSigner = depositWallet,
                makerAddress = depositWallet,
                walletAddress = ownerEoa
            )
        )
    }
}
