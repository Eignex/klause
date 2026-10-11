package com.eignex.klause.localsearch

import com.eignex.klause.ir.Problem
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
     *  (`Problem.impliedFactorMask`) start at [IMPLIED_FACTOR_INITIAL_WEIGHT] rather than 1.0, so the
     *  implied bulk can't dominate the initial descent before structural constraints are met;
     *  SAPS-style bumping still raises an implied invariant's weight if it persistently blocks
     *  progress.
     *
     *  When [normalizeWeightsByClass] is set, non-implied invariants are additionally damped by class
     *  population — see [preparation]. */
    val factorWeights: DoubleArray
        get() {
            _factorWeights?.let { return it }
            preparation().forEach { }
            return checkNotNull(_factorWeights)
        }

    /** The initial seeded per-factor weights ([preparation]), snapshotted once when
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

    internal fun preparation(): Sequence<Unit> = sequence {
        if (_factorWeights != null) return@sequence
        val n = problem.numFactors
        val implied = problem.impliedFactorMask
        val w = DoubleArray(n)
        yield(Unit)
        val counts = HashMap<KClass<*>, Int>()
        if (normalizeWeightsByClass) {
            for (i in 0 until n) {
                if (i % INITIALIZATION_BATCH == 0) yield(Unit)
                if (implied != null && implied[i]) continue
                val k = problem.factors[i]::class
                counts[k] = (counts[k] ?: 0) + 1
            }
        }
        var total = 0L
        var classes = 0
        for (count in counts.values) {
            total += count
            if (++classes % INITIALIZATION_BATCH == 0) yield(Unit)
        }
        val meanClassSize = if (counts.isEmpty()) 0.0 else total.toDouble() / counts.size
        for (i in 0 until n) {
            if (i % INITIALIZATION_BATCH == 0) yield(Unit)
            w[i] = when {
                implied != null && implied[i] -> IMPLIED_FACTOR_INITIAL_WEIGHT
                counts.isNotEmpty() && counts.getValue(problem.factors[i]::class) > meanClassSize ->
                    meanClassSize / counts.getValue(problem.factors[i]::class)
                else -> 1.0
            }
        }
        val base = DoubleArray(n)
        yield(Unit)
        for (from in 0 until n step INITIALIZATION_BATCH) {
            w.copyInto(base, from, from, minOf(from + INITIALIZATION_BATCH, n))
            yield(Unit)
        }
        _baseFactorWeights = base
        _factorWeights = w
    }
}
