package com.eignex.klause.backtrack.selector

import com.eignex.klause.propagation.PropagationSession
import kotlin.random.Random

/**
 * Counting-based value selection — the Pesant 2005 "Maxsd" (maximum solution-density)
 * heuristic, instantiated with the cheap aFC (approximate Frequency Count, Zanarini-Pesant
 * 2009) proxy: for each candidate value, probe a real propagation pin, then score by the
 * log-product of remaining domain sizes. **Larger residual product = more solutions still
 * supported = try first** (the dual ordering of [Impact]).
 *
 * The Pesant intuition is that values which leave the constraint network *richer* are more
 * likely to be on a path to a solution; values which immediately collapse domains are more
 * likely to lead to a dead-end. Same probing machinery and infeasible-value drop as
 * [Impact], with sorting reversed. Both compose with `LastConflict` and any variable
 * heuristic.
 *
 * Notes:
 *  - The log-product is a geometric-mean proxy for the true solution-density. Exact factor
 *    counters (regular DFA, AllDifferent permanent) would be more accurate but require
 *    per-factor support that isn't in klause's current factor API.
 *  - Empirically: counting wins on structured combinatorial problems (rostering, scheduling)
 *    where the solution manifold is "fat" near correct subtrees; Impact wins on
 *    pruning-heavy first-fail problems.
 */
internal class MaxSd(private val maxProbes: Int = 32) : ValueSelector {
    override fun values(session: PropagationSession, varRef: VarRef, rng: Random): Sequence<Long> =
        probeAndOrder(session, varRef, rng, maxProbes, ascending = false)
}
