package com.eignex.klause.meta.coreguided

import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.BigInt
import com.eignex.util.numbers.compareTo
import com.eignex.util.numbers.plus
import com.eignex.util.numbers.saturatedAdd

internal class CoreGuidedContinuation(initialMillis: Long) {
    var maximumMillis: Long = initialMillis
        private set
    var grants: Long = 0L
        private set
    private var fundedCores = 0L

    fun fund(cores: Long, lower: BigInt, rise: BigInt, incumbent: Double,
        spentMillis: Long, remainingMillis: Long?): Boolean {
        if (cores <= fundedCores || !incumbent.isFinite() || remainingMillis == null || remainingMillis < 8_000L) {
            return false
        }
        val upper = BigFraction.ofDouble(incumbent) ?: return false
        val floor = BigFraction.of(lower, BIG_ONE)
        if (upper <= floor || upper > BigFraction.of(lower + rise, BIG_ONE)) return false
        val limit = saturatedAdd(spentMillis, remainingMillis - remainingMillis / 4L)
        if (limit <= maximumMillis) return false
        maximumMillis = limit
        fundedCores = cores
        grants = saturatedAdd(grants, 1L)
        return true
    }
}
