package com.eignex.klause.solver.localsearch.movesource

import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink

/**
 * Repair moves drawn from sampled violated factors. Draws `min(sampleCount, violated.size)`
 * uniformly-random violated factors and asks each for its repair-move suggestions via
 * [com.eignex.klause.solver.Factor.proposeRepairMoves]. The null "no candidate" contract is policy
 * the caller enforces — this source only fills the sink.
 */
class ViolatedRepairs(
    /** Number of violated factors to sample per [generate] (capped at the violated count). */
    private val sampleCount: Int,
) : MoveSource {
    init {
        require(sampleCount >= 1) { "sampleCount >= 1, got $sampleCount" }
    }

    override val id: MoveSourceId = ID
    override val phase: Phase = Phase.Infeasible
    override val pool: Pool = Pool.NoiseEligible

    override fun generate(state: LocalSearchState, sink: MoveSink) {
        if (state.violated.isEmpty()) return
        repeat(minOf(sampleCount, state.violated.size)) {
            val fid = state.violated.random(state.rng)
            state.factors[fid].proposeRepairMoves(state, fid, sink)
        }
    }

    /** Catalog identity and the single-draw opener instance. */
    companion object {
        /** Catalog id for this source. */
        val ID: MoveSourceId = MoveSourceId("violated-repairs")

        /** Single-factor draw — the WalkSAT/probSAT opener. */
        val SINGLE: ViolatedRepairs = ViolatedRepairs(sampleCount = 1)
    }
}
