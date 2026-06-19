package com.eignex.klause.solver.factor.bool

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.factor.compressViolation
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.util.IntIntMap

/** Body abstraction for the cardinality factors [Cardinality] and `ReifiedCardinality`: the
 *  unit-weighted true-count compared to the window `min..max`. */
abstract class CardinalitySumFactor(
    /** The literals being counted. */
    val literals: IntArray,
    /** Inclusive lower bound on the true count. */
    val min: Int,
    /** Inclusive upper bound on the true count. */
    val max: Int,
    excludedVar: Int,
) : WeightedSumFactor() {

    init {
        require(min in 0..max) { "Cardinality bounds invalid: $min..$max" }
        require(max <= literals.size) { "max ($max) exceeds literal count (${literals.size})" }
    }

    internal val signedByVar: IntIntMap = run {
        val signs = HashMap<Int, Int>()
        for (lit in literals) {
            val v = Lit.variable(lit)
            if (v == excludedVar) continue
            signs[v] = (signs[v] ?: 0) + if (Lit.isPositive(lit)) 1 else -1
        }
        IntIntMap.build(keys = signs.keys.toIntArray(), values = signs.values.toIntArray(), absent = 0)
    }

    final override val intVars: IntArray = EmptyIntArray

    protected fun countDistance(n: Long): Long = (if (n < min) min - n else 0L) + (if (n > max) n - max else 0L)

    final override fun holds(sum: Long): Boolean = sum >= min && sum <= max

    final override fun residual(sum: Long, softCap: Int): Int = compressViolation(countDistance(sum), softCap)

    final override fun initialize(state: LocalSearchState, factorId: Int) {
        var count = 0L
        for (lit in literals) {
            if (Lit.evaluate(lit, state.assignment.boolValue(Lit.variable(lit)))) count++
        }
        state.longPayload[factorId] = count
    }
}
