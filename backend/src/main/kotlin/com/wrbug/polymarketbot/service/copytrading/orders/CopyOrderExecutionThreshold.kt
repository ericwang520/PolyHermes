package com.wrbug.polymarketbot.service.copytrading.orders

import java.math.BigDecimal
import java.math.RoundingMode

private val FAK_MINIMUM_NOTIONAL = BigDecimal.ONE

/**
 * FAK 小额市价模式按 1 USDC 名义金额释放累积订单。
 *
 * 关闭时仍遵循 CLOB orderbook 返回的 min_order_size（通常为 5 shares）。
 * 使用 CEILING 确保 quantity * price 不会因为截断而低于 1 USDC。
 */
fun minimumExecutableShares(
    useFakForSmallOrders: Boolean,
    executionPrice: BigDecimal,
    marketMinimumShares: BigDecimal
): BigDecimal {
    require(executionPrice > BigDecimal.ZERO)
    require(marketMinimumShares > BigDecimal.ZERO)
    if (!useFakForSmallOrders) return marketMinimumShares
    return FAK_MINIMUM_NOTIONAL.divide(executionPrice, 8, RoundingMode.CEILING)
}
