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
    override fun fresh() = this

    override fun values(session: PropagationSession, varRef: VarRef, rng: Random): Sequence<Long> = when (varRef) {
        is VarRef.Bool -> sequenceOf(0L, 1L)

        is VarRef.IntVar -> {
            val d = session.intDomain(varRef.varId)
            // A non-enumerable domain has no meaningful positional index; the bounds midpoint is the
            // exact positional median for the shape that dominates there (a hole-free interval).
            val median = if (!d.enumerable) boundsMidpoint(d) else d.valueAt(d.size / 2)
            centeredDomainValues(d, median)
        }
    }
}
