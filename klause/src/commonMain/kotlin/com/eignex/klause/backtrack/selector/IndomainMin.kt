package com.eignex.klause.backtrack.selector

import com.eignex.klause.propagation.PropagationSession
import kotlin.random.Random

/** Smallest value first (a.k.a. `indomain_min`). For bools: `false` then `true`. */
object IndomainMin : ValueSelector {
    override fun values(session: PropagationSession, varRef: VarRef, rng: Random): Sequence<Long> = when (varRef) {
        is VarRef.Bool -> sequenceOf(0L, 1L)
        is VarRef.IntVar -> domainValuesAscending(session.intDomain(varRef.varId))
    }
}
