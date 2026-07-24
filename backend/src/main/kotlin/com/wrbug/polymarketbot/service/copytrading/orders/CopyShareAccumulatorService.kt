package com.wrbug.polymarketbot.service.copytrading.orders

import com.wrbug.polymarketbot.entity.CopyShareAccumulator
import com.wrbug.polymarketbot.repository.CopyShareAccumulatorRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap

@Service
class CopyShareAccumulatorService(
    private val repository: CopyShareAccumulatorRepository
) {
    private val mutexes = ConcurrentHashMap<String, Mutex>()

    fun clear(copyTradingId: Long) {
        repository.deleteByCopyTradingId(copyTradingId)
        val prefix = "$copyTradingId:"
        mutexes.keys.removeIf { it.startsWith(prefix) }
    }

    /**
     * 累积比例跟单产生的零碎 shares。达到市场最低 shares 后取出可执行部分，
     * 未执行余量继续保存在 MySQL；因此重启不会遗失。
     */
    suspend fun accumulateAndTake(
        copyTradingId: Long,
        marketId: String,
        outcomeIndex: Int?,
        tokenId: String,
        side: String,
        followerQuantity: BigDecimal,
        leaderQuantity: BigDecimal,
        minimumShares: BigDecimal,
        maximumExecutableQuantity: BigDecimal? = null,
        discardRemainderWhenNoCapacity: Boolean = false,
        eventTime: Long = System.currentTimeMillis()
    ): ShareAccumulationResult {
        require(followerQuantity >= BigDecimal.ZERO)
        require(leaderQuantity >= BigDecimal.ZERO)
        require(minimumShares > BigDecimal.ZERO)
        val normalizedSide = side.uppercase()
        require(normalizedSide == "BUY" || normalizedSide == "SELL")
        val key = "$copyTradingId:$tokenId"

        return mutexes.computeIfAbsent(key) { Mutex() }.withLock {
            var remainingFollowerQuantity = followerQuantity
            var remainingLeaderQuantity = leaderQuantity
            var cancelledIncomingQuantity = BigDecimal.ZERO
            val oppositeSide = if (normalizedSide == "BUY") "SELL" else "BUY"
            val opposite = repository.findByCopyTradingIdAndTokenIdAndSide(
                copyTradingId, tokenId, oppositeSide
            )
            if (opposite != null &&
                opposite.pendingQuantity > BigDecimal.ZERO &&
                remainingFollowerQuantity > BigDecimal.ZERO
            ) {
                val incomingQuantityBeforeNetting = remainingFollowerQuantity
                val incomingLeaderBeforeNetting = remainingLeaderQuantity
                val oppositeQuantityBeforeNetting = opposite.pendingQuantity
                val oppositeLeaderBeforeNetting = opposite.pendingLeaderQuantity
                val useLeaderRatio = incomingLeaderBeforeNetting > BigDecimal.ZERO &&
                    oppositeLeaderBeforeNetting > BigDecimal.ZERO
                val cancelledLeaderQuantity = if (useLeaderRatio) {
                    minOf(incomingLeaderBeforeNetting, oppositeLeaderBeforeNetting)
                } else {
                    BigDecimal.ZERO
                }
                val cancelledIncoming = if (useLeaderRatio) {
                    proportionalQuantity(
                        incomingQuantityBeforeNetting,
                        cancelledLeaderQuantity,
                        incomingLeaderBeforeNetting
                    )
                } else {
                    minOf(incomingQuantityBeforeNetting, oppositeQuantityBeforeNetting)
                }
                val cancelledOpposite = if (useLeaderRatio) {
                    proportionalQuantity(
                        oppositeQuantityBeforeNetting,
                        cancelledLeaderQuantity,
                        oppositeLeaderBeforeNetting
                    )
                } else {
                    cancelledIncoming
                }
                val incomingLeaderCancelled = if (useLeaderRatio) {
                    cancelledLeaderQuantity
                } else {
                    proportionalQuantity(
                        incomingLeaderBeforeNetting,
                        cancelledIncoming,
                        incomingQuantityBeforeNetting
                    )
                }
                val oppositeLeaderCancelled = if (useLeaderRatio) {
                    cancelledLeaderQuantity
                } else {
                    proportionalQuantity(
                        oppositeLeaderBeforeNetting,
                        cancelledOpposite,
                        oppositeQuantityBeforeNetting
                    )
                }

                cancelledIncomingQuantity = cancelledIncomingQuantity.add(cancelledIncoming)
                remainingFollowerQuantity = remainingFollowerQuantity.subtract(cancelledIncoming)
                remainingLeaderQuantity = remainingLeaderQuantity
                    .subtract(incomingLeaderCancelled)
                    .max(BigDecimal.ZERO)
                opposite.pendingQuantity = opposite.pendingQuantity.subtract(cancelledOpposite)
                    .max(BigDecimal.ZERO)
                opposite.pendingLeaderQuantity = opposite.pendingLeaderQuantity
                    .subtract(oppositeLeaderCancelled)
                    .max(BigDecimal.ZERO)
                opposite.updatedAt = System.currentTimeMillis()
                if (opposite.pendingQuantity.compareTo(BigDecimal.ZERO) == 0) {
                    repository.delete(opposite)
                } else {
                    repository.save(opposite)
                }

                if (remainingFollowerQuantity.compareTo(BigDecimal.ZERO) == 0) {
                    return@withLock ShareAccumulationResult.Netted(
                        cancelledQuantity = cancelledIncomingQuantity,
                        oppositeSide = oppositeSide
                    )
                }
            }

            val executableCap = maximumExecutableQuantity ?: remainingFollowerQuantity
            if (discardRemainderWhenNoCapacity && executableCap < minimumShares) {
                return@withLock ShareAccumulationResult.Ignored(
                    cancelledQuantity = cancelledIncomingQuantity,
                    discardedQuantity = remainingFollowerQuantity
                )
            }

            val row = repository.findByCopyTradingIdAndTokenIdAndSide(
                copyTradingId, tokenId, normalizedSide
            ) ?: CopyShareAccumulator(
                copyTradingId = copyTradingId,
                marketId = marketId,
                outcomeIndex = outcomeIndex,
                tokenId = tokenId,
                side = normalizedSide,
                firstEventAt = eventTime,
                lastEventAt = eventTime
            )

            row.pendingQuantity = row.pendingQuantity.add(remainingFollowerQuantity)
            row.pendingLeaderQuantity = row.pendingLeaderQuantity.add(remainingLeaderQuantity)
            row.eventCount += 1
            row.lastEventAt = eventTime
            row.updatedAt = System.currentTimeMillis()

            val totalExecutableCap = maximumExecutableQuantity ?: row.pendingQuantity
            if (row.pendingQuantity < minimumShares || totalExecutableCap < minimumShares) {
                repository.save(row)
                return@withLock ShareAccumulationResult.Pending(
                    pendingQuantity = row.pendingQuantity,
                    minimumShares = minimumShares,
                    eventCount = row.eventCount
                )
            }

            val executableQuantity = minOf(row.pendingQuantity, totalExecutableCap)
                .setScale(8, RoundingMode.DOWN)
            val leaderRatio = if (row.pendingQuantity > BigDecimal.ZERO) {
                executableQuantity.divide(row.pendingQuantity, 12, RoundingMode.DOWN)
            } else {
                BigDecimal.ZERO
            }
            val executableLeaderQuantity = row.pendingLeaderQuantity.multiply(leaderRatio)
                .setScale(8, RoundingMode.DOWN)
            val takenEventCount = row.eventCount

            row.pendingQuantity = row.pendingQuantity.subtract(executableQuantity)
            row.pendingLeaderQuantity = row.pendingLeaderQuantity
                .subtract(executableLeaderQuantity)
                .max(BigDecimal.ZERO)
            row.eventCount = if (row.pendingQuantity.compareTo(BigDecimal.ZERO) == 0) 0 else row.eventCount
            row.updatedAt = System.currentTimeMillis()
            if (row.pendingQuantity.compareTo(BigDecimal.ZERO) == 0) {
                row.id?.let(repository::deleteById)
            } else {
                repository.save(row)
            }

            ShareAccumulationResult.Ready(
                quantity = executableQuantity,
                leaderQuantity = executableLeaderQuantity,
                minimumShares = minimumShares,
                eventCount = takenEventCount,
                remainingQuantity = row.pendingQuantity
            )
        }
    }

    suspend fun restore(
        copyTradingId: Long,
        marketId: String,
        outcomeIndex: Int?,
        tokenId: String,
        side: String,
        followerQuantity: BigDecimal,
        leaderQuantity: BigDecimal,
        eventCount: Int = 1,
        eventTime: Long = System.currentTimeMillis()
    ) {
        if (followerQuantity <= BigDecimal.ZERO) return
        val normalizedSide = side.uppercase()
        val key = "$copyTradingId:$tokenId"
        mutexes.computeIfAbsent(key) { Mutex() }.withLock {
            val row = repository.findByCopyTradingIdAndTokenIdAndSide(
                copyTradingId, tokenId, normalizedSide
            ) ?: CopyShareAccumulator(
                copyTradingId = copyTradingId,
                marketId = marketId,
                outcomeIndex = outcomeIndex,
                tokenId = tokenId,
                side = normalizedSide,
                firstEventAt = eventTime,
                lastEventAt = eventTime
            )
            row.pendingQuantity = row.pendingQuantity.add(followerQuantity)
            row.pendingLeaderQuantity = row.pendingLeaderQuantity.add(leaderQuantity)
            row.eventCount += eventCount
            row.lastEventAt = eventTime
            row.updatedAt = System.currentTimeMillis()
            repository.save(row)
        }
    }

    private fun proportionalQuantity(
        total: BigDecimal,
        part: BigDecimal,
        whole: BigDecimal
    ): BigDecimal {
        if (total <= BigDecimal.ZERO || part <= BigDecimal.ZERO || whole <= BigDecimal.ZERO) {
            return BigDecimal.ZERO
        }
        return total.multiply(part)
            .divide(whole, 12, RoundingMode.DOWN)
            .setScale(8, RoundingMode.DOWN)
    }
}

sealed interface ShareAccumulationResult {
    data class Ignored(
        val cancelledQuantity: BigDecimal,
        val discardedQuantity: BigDecimal
    ) : ShareAccumulationResult

    data class Netted(
        val cancelledQuantity: BigDecimal,
        val oppositeSide: String
    ) : ShareAccumulationResult

    data class Pending(
        val pendingQuantity: BigDecimal,
        val minimumShares: BigDecimal,
        val eventCount: Int
    ) : ShareAccumulationResult

    data class Ready(
        val quantity: BigDecimal,
        val leaderQuantity: BigDecimal,
        val minimumShares: BigDecimal,
        val eventCount: Int,
        val remainingQuantity: BigDecimal
    ) : ShareAccumulationResult
}
