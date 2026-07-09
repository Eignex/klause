package com.eignex.klause.localsearch

import com.eignex.klause.solver.Problem
import kotlin.reflect.KClass

/**
 * Per-invariant dynamic weights for weighted-violation strategies (DDFW, SAPS). Not read by the LS
 * engine itself; strategies that bias toward repairing persistently-violated invariants read and
 * mutate [factorWeights] between picks.
 *
 * Lazily allocated on first [factorWeights] access. Weight-blind strategies (WalkSat / ProbSat / SA)
 * never touch it and pay no allocation; only CBLS triggers the `DoubleArray(numFactors)`.
 * [WarmState.captureFrom] probes [allocated] first to avoid forcing the allocation to capture
 * all-1.0 defaults.
 */
class FactorWeightBook(private val problem: Problem) {

    /** Seed [factorWeights] by per-class population so no constraint kind dominates by count. Set by
     *  the engine from [LocalSearchParams.normalizeWeightsByClass] before the first weight access. */
    var normalizeWeightsByClass: Boolean = false
        internal set

    private var _factorWeights: DoubleArray? = null
    private var _baseFactorWeights: DoubleArray? = null

    /** Per-invariant dynamic weights. Invariants the model declared implied
     *  ([Problem.impliedFactorMask]) start at [IMPLIED_FACTOR_INITIAL_WEIGHT] rather than 1.0, so the
     *  implied bulk can't dominate the initial descent before structural constraints are met;
     *  SAPS-style bumping still raises an implied invariant's weight if it persistently blocks
     *  progress.
     *
     *  When [normalizeWeightsByClass] is set, non-implied invariants are additionally damped by class
     *  population — see [initialFactorWeights]. */
    val factorWeights: DoubleArray
        get() {
            var w = _factorWeights
            if (w == null) {
                w = initialFactorWeights()
                _factorWeights = w
                _baseFactorWeights = w.copyOf()
            }
            return w
        }

    /** The initial seeded per-factor weights ([initialFactorWeights]), snapshotted once when
     *  [factorWeights] is first allocated and never mutated. SAPS-style smoothing pulls the live
     *  weights back toward this baseline rather than a flat constant, so the per-class / implied
     *  seeding survives the reactive bumping. */
    val baseFactorWeights: DoubleArray
        get() {
            _baseFactorWeights?.let { return it }
            factorWeights // forces allocation, which also assigns _baseFactorWeights
            return _baseFactorWeights ?: error("baseFactorWeights is assigned when factorWeights is allocated")
        }

    /** True iff [factorWeights] has been touched (allocated). Reading is free; allows callers to
     *  probe without forcing the lazy allocation. */
    val allocated: Boolean get() = _factorWeights != null

    /** Build the initial per-factor weight vector. Non-implied factors start at 1.0, optionally
     *  class-normalised ([normalizeWeightsByClass]): an over-represented factor class (population
     *  above the mean over non-implied classes) is scaled so its aggregate weight is capped at that
     *  mean, never amplifying a smaller class above 1.0. Implied factors are pinned to
     *  [IMPLIED_FACTOR_INITIAL_WEIGHT] and excluded from the class tally. */
    private fun initialFactorWeights(): DoubleArray {
        val n = problem.numFactors
        val implied = problem.impliedFactorMask
        val w = DoubleArray(n) { 1.0 }
        if (normalizeWeightsByClass) {
            val counts = HashMap<KClass<*>, Int>()
            for (i in 0 until n) {
                if (implied != null && implied[i]) continue
                val k = problem.factors[i]::class
                counts[k] = (counts[k] ?: 0) + 1
            }
            if (counts.isNotEmpty()) {
                val meanClassSize = counts.values.sum().toDouble() / counts.size
                for (i in 0 until n) {
                    if (implied != null && implied[i]) continue
                    val c = counts.getValue(problem.factors[i]::class)
                    if (c > meanClassSize) w[i] = meanClassSize / c
                }
            }
        }
        if (implied != null) for (i in 0 until n) if (implied[i]) w[i] = IMPLIED_FACTOR_INITIAL_WEIGHT
        return w
    }
}
