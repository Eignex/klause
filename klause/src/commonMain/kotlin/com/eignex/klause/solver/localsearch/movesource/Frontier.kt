package com.eignex.klause.solver.localsearch.movesource

import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink

/**
 * Plateau-escape **frontier moves** — the single implementation behind `Cbls.sampleFrontier`
 * (with its `addNeighbourMoves` helper) (epic #710). When the violated-only repair pool traps the
 * search, every repair of a violated factor breaks a *satisfied neighbour*, and the moves that
 * would first re-arrange those neighbours are never generated. This injects bounded ±1 int-steps
 * and bool flips on the variables of factors that *neighbour* a violated factor (share a variable),
 * giving the search — together with the raised stall noise the strategy applies — moves to step
 * through the basin wall.
 *
 * Capped at [moveCap] candidates per call, expanding the neighbours of up to [violatedSampleCount]
 * sampled violated factors.
 */
class Frontier(
    /** Number of violated factors whose neighbours are expanded per call. */
    private val violatedSampleCount: Int,
    /** Cap on frontier (neighbour-variable) moves injected per call. */
    private val moveCap: Int,
) : MoveSource {
    init {
        require(violatedSampleCount >= 1) { "violatedSampleCount >= 1, got $violatedSampleCount" }
        require(moveCap >= 1) { "moveCap >= 1, got $moveCap" }
    }

    override val id: MoveSourceId = ID
    override val phase: Phase = Phase.Infeasible
    override val pool: Pool = Pool.NoiseEligible

    override fun generate(state: LocalSearchState, sink: MoveSink) {
        if (state.violated.isEmpty()) return
        val problem = state.problem
        var budget = moveCap
        repeat(minOf(violatedSampleCount, state.violated.size)) {
            if (budget <= 0) return
            val fid = state.violated.random(state.rng)
            val f = state.factors[fid]
            for (v in f.intVars) {
                for (nf in problem.intOccurrences[v]) {
                    if (nf == fid) continue
                    budget = addNeighbourMoves(state, sink, nf, budget)
                    if (budget <= 0) return
                }
            }
            for (v in f.boolVars) {
                for (nf in problem.boolOccurrences[v]) {
                    if (nf == fid) continue
                    budget = addNeighbourMoves(state, sink, nf, budget)
                    if (budget <= 0) return
                }
            }
        }
    }

    /** Emit ±1 int-steps and bool flips for every variable of factor [nf], spending from and
     *  returning the remaining [budget]. */
    private fun addNeighbourMoves(state: LocalSearchState, sink: MoveSink, nf: Int, budget: Int): Int {
        var b = budget
        val nfac = state.factors[nf]
        for (u in nfac.intVars) {
            if (b <= 0) return b
            val cur = state.assignment.intValue(u)
            val d = state.problem.intDomains[u]
            if (cur < d.max) {
                sink.addChannelingIntSet(state, u, cur + 1)
                b--
            }
            if (b <= 0) return b
            if (cur > d.min) {
                sink.addChannelingIntSet(state, u, cur - 1)
                b--
            }
        }
        for (u in nfac.boolVars) {
            if (b <= 0) return b
            sink.addBoolFlip(u)
            b--
        }
        return b
    }

    /** Identity. */
    companion object {
        /** Catalog id for this source. */
        val ID: MoveSourceId = MoveSourceId("frontier")
    }
}
