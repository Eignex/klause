package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.localsearch.LocalSearchState

/** Reification contract `auxBoolVar ↔ (body predicate)` for `ReifiedLinear`, `ReifiedCardinality`,
 *  `ReifiedPseudoBoolean` — an interface so a factor can also extend a body abstraction. */
interface ReifiedFactor : Factor {

    /** The reifying Boolean variable, true iff the body predicate holds. */
    val auxBoolVar: Int

    /** Whether the body predicate currently holds, read from the factor's payload at [factorId]. */
    fun holdsNow(state: LocalSearchState, factorId: Int): Boolean

    /** Violation magnitude of the body predicate at [factorId], capped at [softCap]; 0 when it holds. */
    fun residualNow(state: LocalSearchState, factorId: Int, softCap: Int): Int
}
