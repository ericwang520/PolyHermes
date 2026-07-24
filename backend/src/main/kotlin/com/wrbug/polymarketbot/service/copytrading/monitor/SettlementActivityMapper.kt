package com.wrbug.polymarketbot.service.copytrading.monitor

import com.wrbug.polymarketbot.api.TradeResponse
import com.wrbug.polymarketbot.api.UserActivityResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object SettlementActivityMapper {
    fun stableKey(action: String, transactionHash: String?, conditionId: String): String =
        "${action.uppercase()}:${transactionHash.orEmpty().lowercase()}:${conditionId.lowercase()}"

    fun leaderTradeId(action: String, transactionHash: String?, conditionId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(stableKey(action, transactionHash, conditionId).toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "settlement-${digest.take(48)}"
    }

    fun toTrade(activity: UserActivityResponse): TradeResponse {
        val action = activity.type.uppercase()
        return TradeResponse(
            id = leaderTradeId(action, activity.transactionHash, activity.conditionId),
            market = activity.conditionId,
            side = action,
            price = "0",
            size = (activity.usdcSize ?: activity.size ?: 0.0).toString(),
            timestamp = activity.timestamp.toString(),
            user = activity.proxyWallet,
            outcomeIndex = activity.outcomeIndex,
            outcome = activity.outcome,
            tokenId = activity.asset?.takeIf { it.isNotBlank() }
        )
    }
}
