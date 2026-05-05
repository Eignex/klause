package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.SolverState

/**
 * Disjunction of literals. Payload at `intPayload[factorId]` is the count of true literals;
 * the clause is violated iff that count is zero. Assumes each variable appears at most once.
 */
class Clause(
    val literals: IntArray,
    override val isHard: Boolean = true,
    override val weight: Double = 1.0,
) : Factor {

    override val variables: IntArray = IntArray(literals.size) { Lit.variable(literals[it]) }

    override fun initialize(state: SolverState, factorId: Int) {
        var count = 0
        for (lit in literals) {
            if (Lit.evaluate(lit, state.assignment[Lit.variable(lit)])) count++
        }
        state.intPayload[factorId] = count
    }

    override fun isViolated(state: SolverState, factorId: Int): Boolean =
        state.intPayload[factorId] == 0

    override fun deltaIfFlipped(state: SolverState, factorId: Int, variable: Int): Int {
        val pre = state.assignment[variable]
        var change = 0
        for (lit in literals) {
            if (Lit.variable(lit) != variable) continue
            change += if (Lit.evaluate(lit, pre)) -1 else 1
        }
        val numSat = state.intPayload[factorId]
        val newSat = numSat + change
        return (if (newSat == 0) 1 else 0) - (if (numSat == 0) 1 else 0)
    }

    override fun applyFlip(state: SolverState, factorId: Int, variable: Int): Int {
        val post = state.assignment[variable]
        var change = 0
        for (lit in literals) {
            if (Lit.variable(lit) != variable) continue
            change += if (Lit.evaluate(lit, post)) 1 else -1
        }
        val oldSat = state.intPayload[factorId]
        val newSat = oldSat + change
        state.intPayload[factorId] = newSat
        return (if (newSat == 0) 1 else 0) - (if (oldSat == 0) 1 else 0)
    }
}
