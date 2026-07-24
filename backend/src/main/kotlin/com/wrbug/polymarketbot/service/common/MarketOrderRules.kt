package com.wrbug.polymarketbot.service.common

import java.math.BigDecimal

data class MarketOrderRules(
    val minimumShares: BigDecimal,
    val tickSize: BigDecimal? = null,
    val negRisk: Boolean? = null
)
