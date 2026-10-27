package com.eignex.klause.localsearch.movesource

import com.eignex.klause.factor.global.AllDifferentInvariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.MoveSink

// Opt-in owner moves that let a satisfied implicitly seeded AllDifferent change its occupied value set: unused-value
// entries and alternating paths ending at an unused value. Owners are visited in a step-rotated order, so a model
// whose owners admit no such move draws no random value and keeps its trajectory.
internal class UnusedValueEntries(private val ownerCap: Int) : MoveSource {

    override val id: MoveSourceId = ID
    override val phase: Phase = Phase.Any
    override val pool: Pool = Pool.NoiseEligible

    override fun generate(state: LocalSearchState, sink: MoveSink) {
        if (ownerCap <= 0 || state.seeding.ownerInt == null) return
        val elected = state.seeding.electedImplicit
        if (elected.isEmpty()) return
        val start = (state.step % elected.size).toInt()
        var consulted = 0
        for (offset in elected.indices) {
            if (consulted >= ownerCap) return
            val fid = elected[(start + offset) % elected.size]
            val invariant = state.factors[fid] as? AllDifferentInvariant ?: continue
            if (state.violated.contains(fid)) continue
            consulted++
            invariant.proposeUnusedValueMoves(state, fid, sink)
        }
    }

    companion object {
        val ID: MoveSourceId = MoveSourceId("unused-values")
    }
}
