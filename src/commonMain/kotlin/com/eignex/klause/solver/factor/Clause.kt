package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.SolverState

/**
 * Disjunction of Boolean literals. Payload at `intPayload[factorId]` is the count of true
 * literals; the clause is violated iff that count is zero. Assumes each variable appears at
 * most once in [literals].
 */
class Clause(
    val literals: IntArray,
    override val isHard: Boolean = true,
    override val weight: Double = 1.0,
) : Factor {

    override val boolVars: IntArray = IntArray(literals.size) { Lit.variable(literals[it]) }
    override val intVars: IntArray = EMPTY

    override fun initialize(state: SolverState, factorId: Int) {
        var count = 0
        for (lit in literals) {
            if (Lit.evaluate(lit, state.assignment.boolValue(Lit.variable(lit)))) count++
        }
        state.intPayload[factorId] = count
    }

    override fun isViolated(state: SolverState, factorId: Int): Boolean =
        state.intPayload[factorId] == 0

    override fun deltaIfBoolFlipped(state: SolverState, factorId: Int, boolVar: Int): Int {
        val pre = state.assignment.boolValue(boolVar)
        var change = 0
        for (lit in literals) {
            if (Lit.variable(lit) != boolVar) continue
            change += if (Lit.evaluate(lit, pre)) -1 else 1
        }
        val numSat = state.intPayload[factorId]
        val newSat = numSat + change
        return (if (newSat == 0) 1 else 0) - (if (numSat == 0) 1 else 0)
    }

    override fun applyBoolFlip(state: SolverState, factorId: Int, boolVar: Int): Int {
        val post = state.assignment.boolValue(boolVar)
        var change = 0
        for (lit in literals) {
            if (Lit.variable(lit) != boolVar) continue
            change += if (Lit.evaluate(lit, post)) 1 else -1
        }
        val oldSat = state.intPayload[factorId]
        val newSat = oldSat + change
        state.intPayload[factorId] = newSat
        return (if (newSat == 0) 1 else 0) - (if (oldSat == 0) 1 else 0)
    }

    private companion object {
        val EMPTY: IntArray = IntArray(0)
    }
}
