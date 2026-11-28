package com.eignex.klause.backtrack.selector

import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.search.VarRef
import kotlin.random.Random

/**
 * Value closest to the mean of the current bounds first, then alternating outward (`indomain_middle`).
 * Distinct from [IndomainMedian] (middle by position) on skewed or holey domains.
 */
object IndomainMiddle : ValueSelector {
    override fun fresh() = this

    override fun values(session: PropagationSession, varRef: VarRef, rng: Random): Sequence<Long> = when (varRef) {
        is VarRef.Bool -> sequenceOf(0L, 1L)

        is VarRef.IntVar -> {
            val d = session.intDomain(varRef.varId)
            centeredDomainValues(d, boundsMidpoint(d))
        }
    }
}
