package com.eignex.klause.solver.backtrack.selector

import com.eignex.klause.solver.propagation.PropagationSession
import kotlin.random.Random

/** Smallest value first (a.k.a. `indomain_min`). For bools: `false` then `true`. */
object IndomainMin : ValueSelector {
    override fun values(session: PropagationSession, varRef: VarRef, rng: Random): Sequence<Int> = when (varRef) {
        is VarRef.Bool -> sequenceOf(0, 1)
        is VarRef.IntVar -> domainValuesAscending(session.intDomain(varRef.varId))
    }
}
