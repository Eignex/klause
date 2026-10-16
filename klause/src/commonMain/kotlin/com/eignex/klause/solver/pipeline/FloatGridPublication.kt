package com.eignex.klause.solver.pipeline

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.validation.verifyProjectedSourceWitness
import com.eignex.klause.util.Cancellation

internal class FloatGridPublication(
    private val binding: SelectedFloatGridBinding,
    private val cancellation: Cancellation,
    private val toleranceCheck: ((Sample) -> Boolean)?,
    private val callbacks: FiniteSolveCallbacks,
) {
    private var accepted = 0L
    private var best: Sample? = null
    private var bestObjective: BigFraction? = null

    fun onSample(native: Sample) {
        accept(native)?.let(callbacks.onSample)
    }

    fun onImprovement(native: Sample, worker: String, elapsedMs: Long) {
        val sample = accept(native) ?: return
        val objective = requireNotNull(bestObjective)
        val normalized = if (binding.metadata.maximize) objective.negated() else objective
        callbacks.onImprovement(FiniteSolveImprovement(sample, worker, elapsedMs, normalized.toDouble()))
    }

    private fun accept(native: Sample): Sample? {
        if (cancellation()) return null
        val mapping = binding.projection.mapping
        mapping.requireOwner(binding.consumer)
        val sample = mapping.reconstruct(binding.projection.problem, native, cancellation) ?: return null
        val verdict = verifyProjectedSourceWitness(binding.sourceChecker, sample, cancellation, toleranceCheck)
        if (verdict !is Verification.Accepted || cancellation()) return null
        val objective = binding.objective?.let {
            cancellation.charge(it.native.boolWeightValues.size.toLong() + it.native.intCoefficientValues.size)
            if (cancellation()) return null
            it.sourceValue(it.native.evaluateLong(native), maximize = false)
        }
        val previous = bestObjective
        if (previous != null && objective != null &&
            (if (binding.metadata.maximize) objective <= previous else objective >= previous)
        ) return null
        if (cancellation()) return null
        best = sample
        bestObjective = objective
        accepted++
        return sample
    }

    fun result(
        preparation: FinitePipelinePreparation,
        elapsed: kotlin.time.Duration,
        native: FiniteSolveOutcome,
    ): FiniteSolveResult {
        if (native is FiniteSolveOutcome.Completed) native.bestSample?.let(::onSample)
        val qualified = if (native is FiniteSolveOutcome.Completed) {
            FiniteSolveOutcome.Completed(
                when {
                    accepted == 0L -> FiniteSolveVerdict.UNKNOWN
                    binding.objective != null -> FiniteSolveVerdict.BEST_FOUND
                    else -> FiniteSolveVerdict.SAT
                },
                native.stats, accepted, best, native.elapsedMs,
            )
        } else native
        return FiniteSolveResult(preparation, elapsed, qualified).also {
            it.bindNativeGridOutcome(NativeGridOutcome(binding.metadata, native))
        }
    }
}
