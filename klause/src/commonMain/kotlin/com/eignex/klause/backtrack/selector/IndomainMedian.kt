package com.eignex.klause.backtrack.selector

import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.IntDomain
import kotlin.random.Random

/**
 * Median value (middle of the domain *by position*) first, then alternating outward
 * (`indomain_median`). [IntDomain.valueAt] is sparse-aware, so the median always lands on a
 * present value; differs from [IndomainMiddle] (mean of bounds) when the domain is skewed or holey.
 */
object IndomainMedian : ValueSelector {
    override fun values(session: PropagationSession, varRef: VarRef, rng: Random): Sequence<Int> = when (varRef) {
        is VarRef.Bool -> sequenceOf(0, 1)

        is VarRef.IntVar -> {
            val d = session.intDomain(varRef.varId)
            centeredDomainValues(d, d.valueAt(d.size / 2))
        }
    }
}
