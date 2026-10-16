package com.eignex.klause.backtrack.selector

import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.search.VarRef
import kotlin.random.Random

/**
 * Counting-based value selection — the maxSD (maximum solution density) heuristic of Pesant, Quimper
 * and Zanarini, "Counting-Based Search: Branching Heuristics for Constraint Satisfaction Problems", JAIR 43,
 * 2012 (https://doi.org/10.1613/jair.3463), with solution density approximated cheaply: for each candidate
 * value, probe a real propagation pin, then score by the log-product of remaining domain sizes.
 * **Larger residual product = more solutions still supported = try first** (the dual ordering of [Impact]).
 *
 * The intuition is that values which leave the constraint network *richer* are more
 * likely to be on a path to a solution; values which immediately collapse domains are more
 * likely to lead to a dead-end. Same probing machinery and infeasible-value drop as
 * [Impact], with sorting reversed. Both compose with `LastConflict` and any variable
 * heuristic.
 *
 * The log-product is a proxy for the true solution density; per-factor solution counters (regular DFA,
 * AllDifferent permanent) would be more accurate but the factor API does not expose them.
 */
internal class MaxSd(private val maxProbes: Int = 32) : ValueSelector {

    override fun fresh() = MaxSd(maxProbes)
    override fun values(session: PropagationSession, varRef: VarRef, rng: Random): Sequence<Long> =
        probeAndOrder(session, varRef, rng, maxProbes, ascending = false)
}
