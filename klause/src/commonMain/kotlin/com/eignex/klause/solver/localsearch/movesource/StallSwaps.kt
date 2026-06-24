package com.eignex.klause.solver.localsearch.movesource

import com.eignex.klause.solver.Move
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink

/**
 * Stall-gated int-pair swap proposals. Randomized rejection sampling: pick a violated factor, take
 * one of its int vars u, and pair it with either another var of the same factor or a var of a
 * frontier (variable-sharing) factor. A legal swap needs differing values and same-shaped domains —
 * swaps target permutation/assignment structure (course→period style vars over one value range)
 * where a single int-set breaks an equal-coefficient sum but a value exchange preserves it.
 *
 * [Pool.ScoreOnly]: a coordinated 2-variable move taken by the noise draw is a destructive
 * perturbation; score-picked it is the escape the single-set repair pool can't produce.
 * [Phase.Infeasible]: it is the feasibility-fight plateau buster.
 */
class StallSwaps(
    /** Cap on swap candidates produced per call. */
    private val cap: Int,
) : MoveSource {

    override val id: MoveSourceId = ID
    override val phase: Phase = Phase.Infeasible
    override val pool: Pool = Pool.ScoreOnly

    override fun generate(state: LocalSearchState, sink: MoveSink) {
        if (cap <= 0 || state.violated.isEmpty()) return
        val rng = state.rng
        val problem = state.problem
        var budget = cap
        // Randomized rejection sampling; most draws on bool-only or single-var factors miss,
        // so allow a few attempts per requested swap before giving up.
        var attempts = cap * ATTEMPTS_PER_SWAP
        while (budget > 0 && attempts-- > 0) {
            val fid = state.violated.random(rng)
            val vars = state.problem.factors[fid].intVars
            if (vars.isEmpty()) continue
            val u = vars[rng.nextInt(vars.size)]
            val w = if (vars.size >= 2 && rng.nextBoolean()) {
                vars[rng.nextInt(vars.size)]
            } else {
                val occ = problem.lsIntOccurrences[u]
                if (occ.isEmpty()) continue
                val nvars = state.problem.factors[occ[rng.nextInt(occ.size)]].intVars
                if (nvars.isEmpty()) continue
                nvars[rng.nextInt(nvars.size)]
            }
            if (w == u) continue
            // Check frozen vars explicitly: the compound bypasses the state sink's assumption filter.
            if (state.assumptions.isFrozenInt(u) || state.assumptions.isFrozenInt(w)) continue
            val du = problem.intDomains[u]
            val dw = problem.intDomains[w]
            // Same-shaped domains only: swaps target permutation/assignment structure sharing one
            // value range. Cross-domain swaps (decision var vs derived load/count var) are
            // semantically meaningless.
            if (du.min != dw.min || du.max != dw.max) continue
            val vu = state.assignment.intValue(u)
            val vw = state.assignment.intValue(w)
            if (vu == vw) continue
            if (vw !in du || vu !in dw) continue
            sink.addCompound(listOf(Move.IntSet(u, vw), Move.IntSet(w, vu)))
            budget--
        }
    }

    /** Catalog identity. */
    companion object {
        /** Catalog id for this source. */
        val ID: MoveSourceId = MoveSourceId("stall-swaps")

        /** Rejection-sampling attempts allowed per requested swap. */
        private const val ATTEMPTS_PER_SWAP = 4
    }
}
