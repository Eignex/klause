package com.eignex.klause.solver.localsearch.movesource

import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink

/**
 * Repair moves drawn from sampled violated factors. Draws `min(sampleCount, violated.size)`
 * uniformly-random violated factors and asks each for its repair-move suggestions via
 * [com.eignex.klause.solver.Invariant.proposeRepairMoves]. The null "no candidate" contract is policy
 * the caller enforces — this source only fills the sink.
 */
class ViolatedRepairs(
    /** Number of violated factors to sample per [generate] (capped at the violated count). */
    private val sampleCount: Int,
    /** When true, draws each factor's opt-in [com.eignex.klause.solver.Invariant.proposeExtendedRepairMoves]
     *  (a richer repair, e.g. a Regular DP-optimal accepting run) instead of the default repair. */
    private val extended: Boolean = false,
) : MoveSource {
    init {
        require(sampleCount >= 1) { "sampleCount >= 1, got $sampleCount" }
    }

    override val id: MoveSourceId = if (extended) EXTENDED_ID else ID
    override val phase: Phase = Phase.Infeasible
    override val pool: Pool = Pool.NoiseEligible

    override fun generate(state: LocalSearchState, sink: MoveSink) {
        if (state.violated.isEmpty()) return
        repeat(minOf(sampleCount, state.violated.size)) {
            val fid = state.violated.random(state.rng)
            val f = state.factors[fid]
            if (extended) f.proposeExtendedRepairMoves(state, fid, sink) else f.proposeRepairMoves(state, fid, sink)
        }
    }

    /** Catalog identity and the single-draw opener instance. */
    companion object {
        /** Catalog id for this source. */
        val ID: MoveSourceId = MoveSourceId("violated-repairs")

        /** Catalog id for the extended variant (opt-in richer repairs). */
        val EXTENDED_ID: MoveSourceId = MoveSourceId("extended-repairs")

        /** Single-factor draw — the WalkSAT/probSAT opener. */
        val SINGLE: ViolatedRepairs = ViolatedRepairs(sampleCount = 1)

        /** Extended (opt-in) variant drawing each factor's extended repair moves. */
        fun extended(sampleCount: Int): ViolatedRepairs = ViolatedRepairs(sampleCount, extended = true)
    }
}
