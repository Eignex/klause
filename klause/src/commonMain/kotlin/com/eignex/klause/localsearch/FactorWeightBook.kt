package com.eignex.klause.localsearch

import com.eignex.klause.ir.Problem
import kotlin.reflect.KClass

/**
 * Per-invariant dynamic weights that weighted-violation strategies read and bump between picks; the engine also
 * bumps factors an exact completion refutes. Allocated on first [factorWeights] access, so weight-blind strategies
 * pay nothing.
 */
class FactorWeightBook(private val problem: Problem) {

    /** Damps over-represented factor classes by population so no kind dominates by count; set before first access. */
    var normalizeWeightsByClass: Boolean = false
        internal set

    private var _factorWeights: DoubleArray? = null
    private var _baseFactorWeights: DoubleArray? = null

    /** Live per-invariant weights. Implied invariants start at [IMPLIED_FACTOR_INITIAL_WEIGHT] so the implied bulk
     *  cannot dominate the first descent; bumping still raises one that persistently blocks progress. */
    val factorWeights: DoubleArray
        get() {
            _factorWeights?.let { return it }
            preparation().forEach { }
            return checkNotNull(_factorWeights)
        }

    /** The seeded weights, never mutated; smoothing pulls the live weights back toward them, so the seeding survives
     *  reactive bumping. */
    val baseFactorWeights: DoubleArray
        get() {
            _baseFactorWeights?.let { return it }
            factorWeights // forces allocation, which also assigns _baseFactorWeights
            return _baseFactorWeights ?: error("baseFactorWeights is assigned when factorWeights is allocated")
        }

    /** True iff [factorWeights] has been allocated; probing it does not force the allocation. */
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
