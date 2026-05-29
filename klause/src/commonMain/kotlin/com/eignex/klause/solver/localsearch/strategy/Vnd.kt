package com.eignex.klause.solver.localsearch.strategy

import com.eignex.klause.solver.Move
import com.eignex.klause.solver.localsearch.LocalSearchState

/**
 * Variable Neighbourhood Descent — deterministic descent across a k-level neighbourhood
 * ladder, accepting only strictly-improving moves (`netDelta < 0`). Algorithm:
 *
 *  1. Start at level `k = 1` (single-variable repair candidates from one violated factor).
 *  2. Sample [candidatesPerLevel] candidate moves at level `k`. Filter through [tabu].
 *  3. If any candidate strictly improves cost, return the best such (lowest shaped break).
 *     Reset `k = 1` next call so we re-descend from the cheapest neighbourhood.
 *  4. Otherwise advance to `k+1` and repeat, up to [maxNeighborhood].
 *  5. If no improving move at any level, return the best (or random under [noise])
 *     plateau-move from level 1 so search keeps exploring.
 *
 * This is the *descent* half of the classical "shake + VND" framework: VND escalates the
 * neighbourhood only when no improvement is available at the current level, and never commits
 * to a worsening move. The complementary *shake* (committed diversification on stagnation) is
 * supplied here by the restart-policy layer — [com.eignex.klause.solver.localsearch.IteratedLocalSearchRestart]
 * (BasinHopping perturbation) and [com.eignex.klause.solver.localsearch.AdaptivePerturbationRestart] —
 * which the portfolio composes with this strategy (the `vnd/ils-linkage` worker).
 */
/** Per-level neighbourhood operator. Returns a candidate move list for level [k]
 *  (1-indexed). Used by [Vnd] to override the default size-k Compound generation with
 *  a problem-specific operator (e.g. swap-pair at level 2, hot-spot at level 3). */
typealias VndLevelOperator = (state: LocalSearchState, k: Int, candidatesPerLevel: Int) -> List<Move>

class Vnd(
    val maxNeighborhood: Int = 3,
    val candidatesPerLevel: Int = 4,
    val noise: Double = 0.05,
    val tabu: TabuFilter = TabuFilter(tenure = 10),
    /** Skewed-VNS acceptance parameter (Hansen et al. 2010). When non-zero, the
     *  acceptance test becomes `netDelta + skewAlpha * distance < 0` where `distance`
     *  is the size of the move (1 for primitives, parts.size for Compound). Lets the
     *  descent accept slightly-worsening moves whose locality penalty is small —
     *  classical mechanism for escaping plateau lakes. Set to 0 for strict descent. */
    val skewAlpha: Double = 0.0,
    /** Optional per-level operator overrides. Index `i` overrides the candidate
     *  generator for level `i + 1`. Entries past the list size fall back to the
     *  default size-k Compound generator. */
    val levelOperators: List<VndLevelOperator> = emptyList(),
) : Strategy {

    init {
        require(maxNeighborhood >= 1) { "maxNeighborhood must be ≥ 1" }
        require(candidatesPerLevel > 0) { "candidatesPerLevel must be > 0" }
        require(noise in 0.0..1.0) { "noise must be in [0, 1]" }
        require(skewAlpha >= 0.0) { "skewAlpha must be ≥ 0" }
    }

    override fun pickMove(state: LocalSearchState): Move? {
        if (state.violated.isEmpty()) return null
        for (k in 1..maxNeighborhood) {
            val candidates = generateCandidates(state, k)
            if (candidates.isEmpty()) continue
            val filtered = tabu.filter(state, candidates)
            if (filtered.isEmpty()) continue
            var bestImp: Move? = null
            var bestImpScore = Double.POSITIVE_INFINITY
            for (m in filtered) {
                if (skewedImproves(state, m)) {
                    val s = state.shapedBreakScore(m)
                    if (s < bestImpScore) { bestImpScore = s; bestImp = m }
                }
            }
            if (bestImp != null) return bestImp
        }
        val plateau = tabu.filter(state, generateCandidates(state, 1))
        if (plateau.isEmpty()) return null
        if (state.rng.nextDouble() < noise) return plateau[state.rng.nextInt(plateau.size)]
        return state.greedyPickByShapedBreak(plateau)
    }

    /** Strict descent when [skewAlpha] is 0; otherwise skewed acceptance treats moves
     *  with small spatial reach as effectively improving. */
    private fun skewedImproves(state: LocalSearchState, m: Move): Boolean {
        val delta = state.netDelta(m)
        if (skewAlpha == 0.0) return delta < 0
        val size = when (m) {
            is Move.BoolFlip, is Move.IntSet -> 1
            is Move.Compound -> m.parts.size
        }
        return delta + skewAlpha * size < 0
    }

    private fun generateCandidates(state: LocalSearchState, k: Int): List<Move> {
        // Per-level operator override.
        val opIdx = k - 1
        if (opIdx < levelOperators.size) {
            return levelOperators[opIdx](state, k, candidatesPerLevel)
        }
        if (k == 1) {
            val factorId = state.violated.random(state.rng)
            state.moveSink.clear()
            state.factors[factorId].proposeRepairMoves(state, factorId, state.moveSink)
            val raw = state.moveSink.list
            if (raw.isEmpty()) return emptyList()
            if (raw.size <= candidatesPerLevel) return raw.toList()
            val out = ArrayList<Move>(candidatesPerLevel)
            for (i in 0 until candidatesPerLevel) out.add(raw[state.rng.nextInt(raw.size)])
            return out
        }
        val out = ArrayList<Move>(candidatesPerLevel)
        repeat(candidatesPerLevel) {
            val compound = sampleCompound(state, k) ?: return@repeat
            out.add(compound)
        }
        return out
    }

    private fun sampleCompound(state: LocalSearchState, k: Int): Move? {
        val parts = ArrayList<Move>(k)
        repeat(k) {
            val fid = state.violated.random(state.rng)
            state.moveSink.clear()
            state.factors[fid].proposeRepairMoves(state, fid, state.moveSink)
            val raw = state.moveSink.list
            if (raw.isEmpty()) return@repeat
            val pick = raw[state.rng.nextInt(raw.size)]
            when (pick) {
                is Move.BoolFlip, is Move.IntSet -> parts.add(pick)
                is Move.Compound -> for (p in pick.parts) parts.add(p)
            }
        }
        return when {
            parts.size >= 2 -> Move.Compound(parts)
            parts.size == 1 -> parts[0]
            else -> null
        }
    }
}
