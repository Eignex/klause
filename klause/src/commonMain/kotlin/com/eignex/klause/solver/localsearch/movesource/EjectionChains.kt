package com.eignex.klause.solver.localsearch.movesource

import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.localsearch.proposeRepairChains

/**
 * Stall-gated ejection-chain proposals. Grows up to [cap] directed repair chains from random
 * violated seed factors, each chain entering the score-only race as one atomic compound.
 * Construction is delegated to [proposeRepairChains]: apply a violated factor's repair, find the
 * factor it newly regressed, append that factor's best eligible repair, and repeat to [maxDepth] —
 * emitting the walk's best two-or-more-part prefix.
 *
 * [Pool.ScoreOnly] / [Phase.Infeasible] for the same reason as [StallSwaps]: a coordinated
 * multi-variable move is the score-picked plateau escape, and a destructive perturbation if taken
 * by dice.
 */
class EjectionChains(
    /** Cap on chains produced per call. */
    private val cap: Int,
    /** Maximum repair steps per chain. */
    private val maxDepth: Int,
) : MoveSource {

    override val id: MoveSourceId = ID
    override val phase: Phase = Phase.Infeasible
    override val pool: Pool = Pool.ScoreOnly

    override fun generate(state: LocalSearchState, sink: MoveSink) {
        if (cap <= 0 || state.violated.isEmpty()) return
        var budget = cap
        repeat(minOf(cap, state.violated.size)) {
            if (budget <= 0) return
            val fid = state.violated.random(state.rng)
            budget -= state.proposeRepairChains(
                seedFactor = fid,
                maxDepth = maxDepth,
                firstMoveCap = CHAIN_FIRST_MOVES,
                sink = sink,
            )
        }
    }

    /** Catalog identity. */
    companion object {
        /** Catalog id for this source. */
        val ID: MoveSourceId = MoveSourceId("ejection-chains")

        /** First-move branch width per ejection-chain seed factor. */
        private const val CHAIN_FIRST_MOVES = 4
    }
}
