package com.eignex.klause.backtrack.selector

import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.search.VarRef
import kotlin.random.Random

/**
 * Impact-based value selection (Refalo, "Impact-Based Search Strategies for Constraint Programming",
 * CP 2004, https://doi.org/10.1007/978-3-540-30201-8_41). For each candidate value of `varRef`, probes
 * a real propagation pin via `PropagationSession.probeBool` / `probeInt`, measures the log of
 * the post-pin remaining-domain product, then reverts. Values are returned in **ascending
 * post-product order**: smaller residual search space = stronger pruning = try first.
 *
 *  - Values whose probe yields [PropagationResult.Unsat] are dropped entirely from the
 *    sequence, so the engine never spends a real pin on them.
 *  - For int domains larger than [maxProbes], a uniformly random subset is probed; the
 *    un-probed remainder is appended at the end in ascending order (so coverage is
 *    preserved if the engine backtracks past every probed value). Bool vars are always
 *    fully probed (only two values).
 *  - Composes with `LastConflict` and any variable heuristic; the cost is O(maxProbes ×
 *    propagation), amortised by the pruning power that lets the search skip whole subtrees.
 *
 * The heuristic does propagation work inside `values()` (pin + propagate + popLast), so it pays off
 * only where per-node pruning matters more than node throughput.
 */
internal class Impact(private val maxProbes: Int = 32) : ValueSelector {

    override fun fresh() = Impact(maxProbes)
    override fun values(session: PropagationSession, varRef: VarRef, rng: Random): Sequence<Long> =
        probeAndOrder(session, varRef, rng, maxProbes, ascending = true)
}
