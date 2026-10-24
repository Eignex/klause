package com.eignex.klause.solver.pipeline

import com.eignex.klause.lp.optimization.floorSplit
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.util.Cancellation

internal class LinearBranchHistory {
    private class Direction {
        var count = 0L
        var mean = 0.0

        fun add(value: Double) {
            if (!value.isFinite() || value < 0.0 || count == Long.MAX_VALUE) return
            count++
            mean += (value - mean) / count
        }
    }

    private val history = HashMap<Pair<SourceCoordinate, Boolean>, Direction>()

    fun observe(candidate: Pair<SourceCoordinate, BigFraction>, upper: Boolean, gain: BigFraction) {
        if (gain < BigFraction.ZERO) return
        val floor = candidate.floorSplit().second
        val distance = if (upper) candidate.second - floor else floor + BigFraction.ONE - candidate.second
        if (distance <= BigFraction.ZERO || distance >= BigFraction.ONE) return
        val normalized = (gain * distance.reciprocal()).toDouble()
        if (normalized.isFinite()) history.getOrPut(candidate.first to upper, ::Direction).add(normalized)
    }

    fun select(
        candidates: List<Pair<SourceCoordinate, BigFraction>>, cancellation: Cancellation,
    ): Pair<SourceCoordinate, BigFraction>? {
        var selected = candidates.firstOrNull()
        var best = 0.0
        for (candidate in candidates) {
            if (cancellation()) return candidates.firstOrNull()
            val down = history[candidate.first to true] ?: continue
            val up = history[candidate.first to false] ?: continue
            if (down.count < 2 || up.count < 2) continue
            val fraction = (candidate.second - candidate.floorSplit().second).toDouble()
            if (!fraction.isFinite() || fraction <= 0.0 || fraction >= 1.0) continue
            val below = down.mean * fraction
            val above = up.mean * (1.0 - fraction)
            val score = minOf(below, above) + maxOf(below, above) / 6.0
            if (score.isFinite() && score > best) {
                selected = candidate
                best = score
            }
        }
        return selected
    }
}
