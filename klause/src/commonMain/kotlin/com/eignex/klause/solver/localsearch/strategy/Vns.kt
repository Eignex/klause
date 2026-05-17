package com.eignex.klause.solver.localsearch.strategy

import com.eignex.klause.solver.Move
import com.eignex.klause.solver.localsearch.LocalSearchState

/**
 * Variable Neighborhood Search (Mladenović & Hansen 1997, ported to LS). Maintains a
 * neighborhood index `k` ∈ `[1, maxNeighborhood]`; at each step samples candidate moves
 * from a "size-k" neighborhood:
 *
 *  - k = 1: single-variable moves proposed by one randomly-picked violated factor
 *    (same as plain [WalkSat] / [ProbSat]).
 *  - k = 2..n: a [Move.Compound] of k primitive moves, each pulled from a different
 *    randomly-picked violated factor — coordinated multi-var transitions that single-flip
 *    strategies can't reach without crossing through worse intermediate states.
 *
 * The index *promotes* (k++) after [stagnationThreshold] consecutive non-improving picks
 * and *demotes back to 1* on any improvement (the cost dropped between consecutive
 * `pickMove` calls). When `k` saturates at [maxNeighborhood] and stagnation continues,
 * it cycles back to 1 — diversifies indefinitely without giving up.
 *
 * Candidate generation produces [candidatesPerCall] distinct k-tuples per call; the
 * strategy then picks the lowest shaped-break score (with optional [noise]-probability
 * random pick for exploration). Tabu filtering is delegated to [tabu].
 */
class Vns(
    val maxNeighborhood: Int = 3,
    val stagnationThreshold: Int = 30,
    val candidatesPerCall: Int = 4,
    val noise: Double = 0.1,
    val tabu: TabuFilter = TabuFilter(tenure = 10),
) : Strategy {

    init {
        require(maxNeighborhood >= 1) { "maxNeighborhood must be ≥ 1, got $maxNeighborhood" }
        require(stagnationThreshold > 0) { "stagnationThreshold must be > 0, got $stagnationThreshold" }
        require(candidatesPerCall > 0) { "candidatesPerCall must be > 0, got $candidatesPerCall" }
        require(noise in 0.0..1.0) { "noise must be in [0, 1], got $noise" }
    }

    /** Current neighborhood size. Exposed for tests / observability. */
    var currentNeighborhood: Int = 1
        private set

    private var stallCount: Int = 0
    private var lastCost: Int = Int.MAX_VALUE

    override fun pickMove(state: LocalSearchState): Move? {
        if (state.violated.isEmpty()) return null

        updateNeighborhood(state.cost)

        val candidates = generateCandidates(state, currentNeighborhood)
        if (candidates.isEmpty()) return null
        val filtered = tabu.filter(state, candidates)
        if (filtered.isEmpty()) return null

        if (state.rng.nextDouble() < noise) {
            return filtered[state.rng.nextInt(filtered.size)]
        }
        // Greedy on shaped break.
        var bestScore = Double.POSITIVE_INFINITY
        var pick: Move? = null
        for (m in filtered) {
            val s = state.shapedBreakScore(m)
            if (s < bestScore) { bestScore = s; pick = m }
        }
        return pick
    }

    private fun updateNeighborhood(currentCost: Int) {
        val hasPrior = lastCost != Int.MAX_VALUE
        if (hasPrior && currentCost < lastCost) {
            // Strict improvement since last call: demote to N1 and reset stall counter.
            currentNeighborhood = 1
            stallCount = 0
        } else {
            // No improvement yet (or first call with no prior observation): count as stall.
            stallCount++
            if (stallCount >= stagnationThreshold) {
                currentNeighborhood = if (currentNeighborhood >= maxNeighborhood) 1
                                      else currentNeighborhood + 1
                stallCount = 0
            }
        }
        lastCost = currentCost
    }

    private fun generateCandidates(state: LocalSearchState, k: Int): List<Move> {
        if (k == 1) return sampleSingleMoves(state, candidatesPerCall)
        val out = ArrayList<Move>(candidatesPerCall)
        repeat(candidatesPerCall) {
            val compound = sampleCompound(state, k)
            if (compound != null) out.add(compound)
        }
        return out
    }

    /** Sample up to [n] single-variable repair moves from a randomly-picked violated factor. */
    private fun sampleSingleMoves(state: LocalSearchState, n: Int): List<Move> {
        val factorId = state.violated.random(state.rng)
        state.moveSink.clear()
        state.factors[factorId].proposeRepairMoves(state, factorId, state.moveSink)
        val raw = state.moveSink.list
        if (raw.size <= n) return raw.toList()
        // Subsample down to n via reservoir-pick.
        val out = ArrayList<Move>(n)
        for (i in 0 until n) out.add(raw[state.rng.nextInt(raw.size)])
        return out
    }

    /** Build a Compound of [k] primitive moves, each pulled from a different randomly-picked
     *  violated factor. Returns null if fewer than 2 primitives could be gathered (degenerate
     *  case — e.g. only one violated factor with one candidate). */
    private fun sampleCompound(state: LocalSearchState, k: Int): Move? {
        val parts = ArrayList<Move>(k)
        repeat(k) {
            val fid = state.violated.random(state.rng)
            state.moveSink.clear()
            state.factors[fid].proposeRepairMoves(state, fid, state.moveSink)
            val raw = state.moveSink.list
            if (raw.isEmpty()) return@repeat
            val pick = raw[state.rng.nextInt(raw.size)]
            // Compound forbids nesting; flatten if the factor itself proposed a Compound.
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
