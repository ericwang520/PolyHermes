package com.wrbug.polymarketbot.service.system

import com.wrbug.polymarketbot.util.EthereumUtils
import com.wrbug.polymarketbot.util.RetrofitFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.math.BigInteger

class RelayClientPositionCallTest {

    private val service = RelayClientService(
        retrofitFactory = mock(RetrofitFactory::class.java),
        systemConfigService = mock(SystemConfigService::class.java),
        rpcNodeService = mock(RpcNodeService::class.java)
    )

    @Test
    fun `standard merge targets current pUSD collateral adapter`() {
        val tx = service.createMergeTx(
            conditionId = "0x" + "11".repeat(32),
            amountRaw = BigInteger("1000000"),
            isNegRisk = false
        )

        assertEquals("0xAdA100Db00Ca00073811820692005400218FcE1f", tx.to)
        assertTrue(
            tx.data.startsWith(
                EthereumUtils.getFunctionSelector(
                    "mergePositions(address,bytes32,bytes32,uint256[],uint256)"
                )
            )
        )
    }

    @Test
    fun `neg risk redeem and approval target neg risk adapter`() {
        val redeem = service.createRedeemTx(
            conditionId = "0x" + "22".repeat(32),
            indexSets = listOf(BigInteger.ONE, BigInteger.TWO),
            isNegRisk = true
        )
        val approval = service.createCtfAdapterApprovalTx(isNegRisk = true)

        assertEquals("0xadA2005600Dec949baf300f4C6120000bDB6eAab", redeem.to)
        assertEquals("0x4D97DCd97eC945f40cF65F87097ACe5EA0476045", approval.to)
        assertTrue(approval.data.contains("ada2005600dec949baf300f4c6120000bdb6eaab", ignoreCase = true))
    }
}
