package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.localsearch.LocalSearchState

/** Reification contract `auxBoolVar ↔ (body predicate)` for `ReifiedLinear`, `ReifiedCardinality`,
 *  `ReifiedPseudoBoolean` — an interface so a factor can also extend a body abstraction. */
interface ReifiedFactor : Factor {

    val auxBoolVar: Int

    fun holdsNow(state: LocalSearchState, factorId: Int): Boolean

    fun residualNow(state: LocalSearchState, factorId: Int, softCap: Int): Int
}
