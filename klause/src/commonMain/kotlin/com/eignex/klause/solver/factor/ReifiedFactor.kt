package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.localsearch.LocalSearchState

/** Reification contract `auxBoolVar ↔ (body predicate)` for `ReifiedLinear`, `ReifiedCardinality`,
 *  `ReifiedPseudoBoolean` — an interface so a factor can also extend a body abstraction. */
interface ReifiedFactor : Factor {

    /** Reification literal: true iff the body predicate holds. */
    val auxBoolVar: Int

    /** Whether the body predicate currently holds. */
    fun holdsNow(state: LocalSearchState, factorId: Int): Boolean

    /** Compressed body residual at the current payload — graded distance to satisfying the body. */
    fun residualNow(state: LocalSearchState, factorId: Int, softCap: Int): Int

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean =
        state.assignment.boolValue(auxBoolVar) != holdsNow(state, factorId)

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int {
        val aux = state.assignment.boolValue(auxBoolVar)
        return when {
            aux == holdsNow(state, factorId) -> 0
            aux -> residualNow(state, factorId, state.violationSoftCap)
            else -> 1
        }
    }
}
