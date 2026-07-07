package com.eignex.klause.backtrack.selector

import com.eignex.klause.propagation.PropagationSession
import kotlin.random.Random

/** Largest value first (`indomain_max`). For bools: `true` then `false`. */
object IndomainMax : ValueSelector {
    override fun values(session: PropagationSession, varRef: VarRef, rng: Random): Sequence<Long> = when (varRef) {
        is VarRef.Bool -> sequenceOf(1L, 0L)
        is VarRef.IntVar -> domainValuesDescending(session.intDomain(varRef.varId))
    }
}
