package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.SolverState

/**
 * `min ≤ (#true literals) ≤ max`. Payload at `intPayload[factorId]` is the count of true literals.
 * AtMostOne, AtLeastOne, ExactlyOne are all special cases.
 */
class Cardinality(
    val literals: IntArray,
    val min: Int,
    val max: Int,
    override val isHard: Boolean = true,
    override val weight: Double = 1.0,
) : Factor {

    init {
        require(min in 0..max) { "Cardinality bounds invalid: $min..$max" }
        require(max <= literals.size) { "max ($max) exceeds literal count (${literals.size})" }
    }

    override val variables: IntArray = IntArray(literals.size) { Lit.variable(literals[it]) }

    override fun initialize(state: SolverState, factorId: Int) {
        var count = 0
        for (lit in literals) {
            if (Lit.evaluate(lit, state.assignment[Lit.variable(lit)])) count++
        }
        state.intPayload[factorId] = count
    }

    override fun isViolated(state: SolverState, factorId: Int): Boolean {
        val n = state.intPayload[factorId]
        return n < min || n > max
    }

    override fun deltaIfFlipped(state: SolverState, factorId: Int, variable: Int): Int {
        val pre = state.assignment[variable]
        var change = 0
        for (lit in literals) {
            if (Lit.variable(lit) != variable) continue
            change += if (Lit.evaluate(lit, pre)) -1 else 1
        }
        val n = state.intPayload[factorId]
        val newN = n + change
        val wasViolated = n < min || n > max
        val willViolate = newN < min || newN > max
        return (if (willViolate) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun applyFlip(state: SolverState, factorId: Int, variable: Int): Int {
        val post = state.assignment[variable]
        var change = 0
        for (lit in literals) {
            if (Lit.variable(lit) != variable) continue
            change += if (Lit.evaluate(lit, post)) 1 else -1
        }
        val oldN = state.intPayload[factorId]
        val newN = oldN + change
        state.intPayload[factorId] = newN
        val wasViolated = oldN < min || oldN > max
        val willViolate = newN < min || newN > max
        return (if (willViolate) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    companion object {
        fun atMostOne(literals: IntArray, isHard: Boolean = true, weight: Double = 1.0): Cardinality =
            Cardinality(literals, min = 0, max = 1, isHard = isHard, weight = weight)

        fun atLeastOne(literals: IntArray, isHard: Boolean = true, weight: Double = 1.0): Cardinality =
            Cardinality(literals, min = 1, max = literals.size, isHard = isHard, weight = weight)

        fun exactlyOne(literals: IntArray, isHard: Boolean = true, weight: Double = 1.0): Cardinality =
            Cardinality(literals, min = 1, max = 1, isHard = isHard, weight = weight)
    }
}
