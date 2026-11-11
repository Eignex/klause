package com.eignex.klause.solver.result

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.engine.FLOAT_EVIDENCE_TOLERANCE
import com.eignex.klause.lp.optimization.LinearNumericalClaim
import com.eignex.klause.simplex.exact.BigFraction
import kotlin.math.abs

/** Numerical source deductions, separate from exact source evidence and independent proof validation. */
class LinearNumericalResult internal constructor(
    internal val claim: LinearNumericalClaim,
    value: BigFraction?,
    complete: Boolean,
) {
    /** Original source revision, restrictions, objective identity and direction. */
    val scope = claim.scope
    /** Global objective bound in this source context's original objective units. */
    val bound: Double? = claim.bound
    /** Whether the complete source is numerically infeasible under the selected LP policy. */
    val infeasible: Boolean = complete && claim.infeasible
    /** Whether the accepted incumbent reaches the global bound within [objectiveTolerance]. */
    val optimal: Boolean = complete && !claim.infeasible && value?.let(claim::reaches) == true
    /** Relative objective comparison tolerance, with an absolute floor at unit objective magnitude. */
    val objectiveTolerance: Double = FLOAT_EVIDENCE_TOLERANCE
    /** Nonnegative relative numerical gap, scaled by max(1, abs(incumbent)); null without finite values. */
    val relativeGap: Double? = value?.toDouble()?.takeIf { it.isFinite() }?.let { standing ->
        bound?.let { bound ->
            val scale = maxOf(1.0, abs(standing), abs(bound))
            val difference = if (scope.objective?.sense == ObjectiveSense.MINIMIZE)
                standing / scale - bound / scale else bound / scale - standing / scale
            (difference / (maxOf(1.0, abs(standing)) / scale)).coerceAtLeast(0.0).takeIf { it.isFinite() }
        }
    }

    internal fun partial(value: BigFraction?): LinearNumericalResult? =
        if (bound == null) null else LinearNumericalResult(claim, value, false)
}
