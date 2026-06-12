package com.eignex.klause.solver.backtrack.selector

import com.eignex.klause.solver.propagation.PropagationResult
import com.eignex.klause.solver.propagation.PropagationSession
import kotlin.random.Random

/**
 * Impact-based value selection (Refalo 2004). For each candidate value of `varRef`, probes
 * a real propagation pin via [PropagationSession.pinBool] / `pinInt`, measures the log of
 * the post-pin remaining-domain product, then reverts. Values are returned in **ascending
 * post-product order**: smaller residual search space = stronger pruning = try first.
 *
 *  - Values whose probe yields [PropagationResult.Unsat] are dropped entirely from the
 *    sequence — the engine never wastes a real pin on them. Free pre-pruning at every node.
 *  - For int domains larger than [maxProbes], a uniformly random subset is probed; the
 *    un-probed remainder is appended at the end in ascending order (so coverage is
 *    preserved if the engine backtracks past every probed value). Bool vars are always
 *    fully probed (only two values).
 *  - Composes with `LastConflict` and any variable heuristic; the cost is O(maxProbes ×
 *    propagation), amortised by the pruning power that lets the search skip whole subtrees.
 *
 * Caveat: the heuristic does work *inside* `values()` (pin + propagate + popLast). This is
 * cheap per call but isn't free — for large random/enumeration workloads where node count
 * dominates, the simpler `Indomain*` family will still win on wall-time even if each node
 * does more work. Use Impact when reasoning power per node matters, e.g. structured CSPs
 * with strong global propagators.
 */
internal class Impact(private val maxProbes: Int = 32) : ValueSelector {
    override fun values(session: PropagationSession, varRef: VarRef, rng: Random): Sequence<Int> =
        probeAndOrder(session, varRef, rng, maxProbes, ascending = true)
}
