package com.eignex.klause.count

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.conditionedRoot
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.Cancellation

internal fun BakedProblem.countingScope(assumptions: Assumptions): BakedProblem {
    if (assumptions.isEmpty && assumptions.deductions.isEmpty) return this
    val conditioned = conditionedRoot(assumptions, Cancellation.Never)
    // Hashing rebuilds source rows, so Boolean restrictions must survive outside the root cache.
    val deductions = conditioned.rootDeductions as? PropagationResult.Implied ?: return conditioned
    val pins = Array(deductions.boolKeys.size) {
        Clause(intArrayOf(Lit.make(deductions.boolKeys[it], deductions.boolValues[it])))
    }
    return conditioned.withFactors(conditioned.factors + pins).bake()
}

internal fun BakedProblem.scopedApproximateCount(config: ApproxCountConfig): Count =
    if (rootDeductions is PropagationResult.Unsat) zeroCount else ApproxMC.run(this, config)

internal fun BakedProblem.scopedExactCount(config: ExactCountConfig): Sequence<Count> =
    if (rootDeductions is PropagationResult.Unsat) sequenceOf(zeroCount) else AnytimeCounter.run(this, config)

internal fun BakedProblem.accurateSamples(
    config: SamplingConfig,
    fallback: () -> Sequence<Sample>,
): Sequence<Sample> =
    if (rootDeductions is PropagationResult.Unsat) emptySequence() else UniGen.samples(this, config, fallback)

private val zeroCount = Count(0L, 0L, 0L, exact = true, confidence = 1.0)

internal fun combineCounts(proven: Count, approximate: () -> Count): Count {
    if (proven.exact) return proven
    val approx = approximate()
    val lower = maxOf(proven.lower, approx.lower)
    val upper = maxOf(lower, minOf(proven.upper, approx.upper))
    return Count(
        estimate = approx.estimate.coerceIn(lower, upper),
        lower = lower,
        upper = upper,
        exact = lower == upper,
        confidence = approx.confidence,
    )
}
