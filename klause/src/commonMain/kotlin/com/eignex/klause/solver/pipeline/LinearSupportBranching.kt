package com.eignex.klause.solver.pipeline

import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.util.Cancellation
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

internal class LinearSupportBranching(private val funding: SourceLpFunding) {
    var consultations: Long = 0L
        private set
    var selections: Long = 0L
        private set
    var fallbacks: Long = 0L
        private set

    fun select(
        feedback: SourceNumericalFeedbackResult,
        fractional: List<Pair<SourceCoordinate, BigFraction>>,
        cancellation: Cancellation,
    ): Pair<SourceCoordinate, BigFraction>? {
        consultations++
        fun fallback(): Pair<SourceCoordinate, BigFraction>? {
            fallbacks++
            return fractional.firstOrNull()
        }
        val query = (feedback as? SourceNumericalFeedbackResult.Available)?.query ?: return fallback()
        val point = query.point ?: return fallback()
        if (cancellation() || funding.paused) return fallback()
        funding.charge(fractional.size.toLong(), fractional.size * 48L)
        val candidates = fractional.toMap()
        var selected: Pair<SourceCoordinate, BigFraction>? = null
        var supportSize = Int.MAX_VALUE
        var selectedFraction = -1.0
        for (cut in query.cuts) {
            if (cancellation() || funding.paused) return fallback()
            funding.charge(1L + cut.inequality.supportSize)
            if (!cut.installed || !cut.checked || !cut.ownerActive) continue
            val violation = cut.violation?.toDouble() ?: continue
            val rhs = cut.inequality.rhs.toDouble()
            if (!violation.isFinite() || !rhs.isFinite() || abs(violation) > 1e-8 * (1.0 + abs(rhs))) continue
            val size = cut.inequality.supportSize
            if (size > supportSize) continue
            for (coordinate in cut.inequality.coefficients.keys) {
                val value = candidates[coordinate] ?: continue
                if (point[coordinate] != value) continue
                val projected = value.toDouble()
                if (!projected.isFinite()) continue
                val fraction = projected - floor(projected)
                val distance = min(fraction, 1.0 - fraction)
                if (distance <= 0.0) continue
                if (size < supportSize || distance > selectedFraction ||
                    (distance == selectedFraction && coordinate.id < checkNotNull(selected).first.id)
                ) {
                    selected = coordinate to value
                    supportSize = size
                    selectedFraction = distance
                }
            }
        }
        if (selected == null || cancellation() || funding.paused) return fallback()
        if (selected != fractional.firstOrNull()) selections++
        return selected
    }
}
