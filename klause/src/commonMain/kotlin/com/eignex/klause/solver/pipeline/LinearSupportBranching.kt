package com.eignex.klause.solver.pipeline

import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.lp.optimization.LinearAuthorityCutViolation
import com.eignex.klause.lp.optimization.LinearAuthorityFeedbackRequest
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.util.Cancellation
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

internal class LinearSupportBranching(
    private val funding: SourceLpFunding,
    private val feedback: SourceNumericalFeedback,
) {
    var consultations: Long = 0L
        private set
    var selections: Long = 0L
        private set
    var fallbacks: Long = 0L
        private set

    fun select(
        result: SourceNumericalFeedbackResult,
        fractional: List<Pair<SourceCoordinate, BigFraction>>,
        cancellation: Cancellation,
    ): Pair<SourceCoordinate, BigFraction>? {
        consultations++
        fun fallback(): Pair<SourceCoordinate, BigFraction>? {
            fallbacks++
            return fractional.firstOrNull()
        }
        val available = result as? SourceNumericalFeedbackResult.Available ?: return fallback()
        val query = available.query
        val point = query.point ?: return fallback()
        if (cancellation() || funding.paused) return fallback()
        funding.charge(fractional.size.toLong(), fractional.size * 128L)
        val candidates = fractional.withIndex().associate { (index, candidate) ->
            candidate.first to (index to candidate.second)
        }
        val distances = HashMap<SourceCoordinate, Double>()
        for ((coordinate, indexed) in candidates) {
            val value = indexed.second
            if (point[coordinate] != value) continue
            val projected = value.toDouble()
            if (!projected.isFinite()) continue
            val fraction = projected - floor(projected)
            val distance = min(fraction, 1.0 - fraction)
            if (distance > 0.0) distances[coordinate] = distance
        }
        var selected: Pair<SourceCoordinate, BigFraction>? = null
        var supportSize = Int.MAX_VALUE
        var selectedFraction = -1.0
        var selectedIndex = Int.MAX_VALUE
        for (cut in query.cuts) {
            if (cancellation() || funding.paused) return fallback()
            funding.charge(1L + cut.inequality.supportSize)
            if (!cut.installed || !cut.checked || !cut.ownerActive) continue
            val size = cut.inequality.supportSize
            if (size > supportSize) continue
            // A cut without a fractional candidate cannot change the selection, so its violation is never needed.
            if (cut.inequality.coefficients.keys.none { it in distances }) continue
            val violation = when (val evaluated = feedback.violation(available, cut)) {
                is LinearAuthorityCutViolation.Available -> evaluated.value.toDouble()
                is LinearAuthorityCutViolation.Unavailable -> when (evaluated.reason) {
                    LinearAuthorityCutViolation.Reason.NoPoint, LinearAuthorityCutViolation.Reason.Uncovered -> continue
                    else -> return fallback()
                }
            }
            val rhs = cut.inequality.rhs.toDouble()
            if (!violation.isFinite() || !rhs.isFinite() || abs(violation) > 1e-8 * (1.0 + abs(rhs))) continue
            for (coordinate in cut.inequality.coefficients.keys) {
                val distance = distances[coordinate] ?: continue
                val indexed = candidates.getValue(coordinate)
                if (size < supportSize || distance > selectedFraction ||
                    (distance == selectedFraction && indexed.first < selectedIndex)
                ) {
                    selected = coordinate to indexed.second
                    supportSize = size
                    selectedFraction = distance
                    selectedIndex = indexed.first
                }
            }
        }
        if (selected == null || cancellation() || funding.paused) return fallback()
        if (selected != fractional.firstOrNull()) selections++
        return selected
    }

    companion object {
        val REQUEST = LinearAuthorityFeedbackRequest(point = true, cuts = LinearAuthorityFeedbackRequest.Cuts.Installed)
    }
}
