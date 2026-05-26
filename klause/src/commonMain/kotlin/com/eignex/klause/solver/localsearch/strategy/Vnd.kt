package com.eignex.klause.solver.localsearch.strategy

import com.eignex.klause.solver.Move
import com.eignex.klause.solver.localsearch.LocalSearchState

/**
 * Variable Neighbourhood Descent — deterministic descent across the same k-level
 * neighbourhood ladder used by [Vns], but accepting only strictly-improving moves
 * (`netDelta < 0`). Algorithm:
 *
 *  1. Start at level `k = 1` (single-variable repair candidates from one violated factor).
 *  2. Sample [candidatesPerLevel] candidate moves at level `k`. Filter through [tabu].
 *  3. If any candidate strictly improves cost, return the best such (lowest shaped break).
 *     Reset `k = 1` next call so we re-descend from the cheapest neighbourhood.
 *  4. Otherwise advance to `k+1` and repeat, up to [maxNeighborhood].
 *  5. If no improving move at any level, return the best (or random under [noise])
 *     plateau-move from level 1 so search keeps exploring.
 *
 * Difference from [Vns]: VNS *shakes* upward on stagnation regardless of improvement;
 * VND *descends* deterministically and only escalates when no improvement is available
 * at the current level. The two have orthogonal stagnation-avoidance strategies and are
 * typically composed in classical VNS frameworks (shake + VND).
 */
class Vnd(
    val maxNeighborhood: Int = 3,
    val candidatesPerLevel: Int = 4,
    val noise: Double = 0.05,
    val tabu: TabuFilter = TabuFilter(tenure = 10),
) : Strategy {

    init {
        require(maxNeighborhood >= 1) { "maxNeighborhood must be ≥ 1" }
        require(candidatesPerLevel > 0) { "candidatesPerLevel must be > 0" }
        require(noise in 0.0..1.0) { "noise must be in [0, 1]" }
    }

    override fun pickMove(state: LocalSearchState): Move? {
        if (state.violated.isEmpty()) return null
        // Try levels 1..maxNeighborhood in order; return the first improving move found.
        for (k in 1..maxNeighborhood) {
            val candidates = generateCandidates(state, k)
            if (candidates.isEmpty()) continue
            val filtered = tabu.filter(state, candidates)
            if (filtered.isEmpty()) continue
            var bestImp: Move? = null
            var bestImpScore = Double.POSITIVE_INFINITY
            for (m in filtered) {
                if (state.netDelta(m) < 0) {
                    val s = state.shapedBreakScore(m)
                    if (s < bestImpScore) { bestImpScore = s; bestImp = m }
                }
            }
            if (bestImp != null) return bestImp
        }
        // No improving move at any level. Plateau-escape: pick at level 1.
        val plateau = tabu.filter(state, generateCandidates(state, 1))
        if (plateau.isEmpty()) return null
        if (state.rng.nextDouble() < noise) return plateau[state.rng.nextInt(plateau.size)]
        return state.greedyPickByShapedBreak(plateau)
    }

    private fun generateCandidates(state: LocalSearchState, k: Int): List<Move> {
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
