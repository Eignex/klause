package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.result.LinearOptimizationResult
import com.eignex.klause.util.Cancellation

/**
 * Optimize a complete exact affine source context through the shared Boolean and integer traversal.
 * Accepted callbacks carry owned witnesses and exact values in the context's original objective direction.
 * A limit's closure endpoint is never an incumbent. Callback failure closes the invocation and propagates.
 */
fun OpenTheoryPipeline.optimizeLinear(
    context: SourceProofContext,
    params: TheoryParams = TheoryParams(),
    onIncumbent: (Sample, BigFraction) -> Unit = { _, _ -> },
): LinearOptimizationResult {
    require(context.scope.objective != null) { "linear optimization requires a source objective" }
    return LinearAuthorityInvocation(context, params).use { invocation ->
        val result = requireNotNull(invocation.runSlice(Cancellation.Never, Long.MAX_VALUE, -1, onIncumbent))
        LinearOptimizationResult(context.scope, result.terminal, result.bound, result.incumbent,
            result.value, result.reason, result.stats)
    }
}

internal fun LinearOptimizationResult.openOptimum(): OpenTheoryOptimum {
    val sign = if (scope.objective?.sense == ObjectiveSense.MAXIMIZE) BigFraction.MINUS_ONE else BigFraction.ONE
    val sampled = incumbent?.let { OpenTheoryAssignment.Sampled(it) }
    val score = value?.let { it * sign }
    return when (val claim = terminal?.claim) {
        is SourceClaim.Optimal -> OpenTheoryOptimum.Optimal(OpenTheoryAssignment.Sampled(claim.sample),
            (claim.value as SourceValue.Rational).value * sign, stats, this)
        SourceClaim.Refutation -> OpenTheoryOptimum.Infeasible(stats, this)
        is SourceClaim.Ray -> OpenTheoryOptimum.Unbounded(OpenTheoryAssignment.Sampled(claim.sample),
            requireNotNull(score), stats, this)
        else -> OpenTheoryOptimum.Bounded(sampled, score, reason, stats, this)
    }
}
