package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.CopyShareAccumulator
import org.springframework.data.jpa.repository.JpaRepository

interface CopyShareAccumulatorRepository : JpaRepository<CopyShareAccumulator, Long> {
    fun findByCopyTradingIdAndTokenIdAndSide(
        copyTradingId: Long,
        tokenId: String,
        side: String
    ): CopyShareAccumulator?

    fun deleteByCopyTradingId(copyTradingId: Long)
}
