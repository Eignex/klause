package com.eignex.klause.backtrack.selector

import com.eignex.klause.ir.values
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.search.VarRef
import kotlin.random.Random

/**
 * Median value (middle of the domain by position) first, then alternating outward (`indomain_median`).
 * Differs from [IndomainMiddle] (mean of bounds) on skewed or holey domains.
 */
object IndomainMedian : ValueSelector {
    override fun fresh() = this

    override fun values(session: PropagationSession, varRef: VarRef, rng: Random): Sequence<Long> = when (varRef) {
        is VarRef.Bool -> sequenceOf(0L, 1L)

        is VarRef.IntVar -> {
            val d = session.intDomain(varRef.varId)
            // A non-enumerable domain has no positional index; the bounds midpoint is the exact median of
            // a hole-free interval.
            val median = if ((d.spanOrNull() == null)) boundsMidpoint(d) else d.values.valueAt(d.values.size / 2)
            centeredDomainValues(d, median)
        }
    }
}
