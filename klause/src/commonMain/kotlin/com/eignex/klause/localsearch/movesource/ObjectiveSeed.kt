package com.eignex.klause.localsearch.movesource

import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.solver.objective.FunctionalObjective
import com.eignex.klause.solver.objective.LinearObjective

/**
 * Objective-direction seed moves. Without it, a fully-satisfied state where no factor proposes a
 * structured move has zero candidates and the strategy returns null, spuriously restarting. This
 * seeds single-variable moves directly on the objective's decision variables:
 *  - [LinearObjective]: a step to the adjacent domain value of each nonzero-weight int var in the
 *    direction its coefficient says reduces the objective, plus a flip on each nonzero-weight bool.
 *  - [FunctionalObjective]: Boolean leaf flips and geometric integer steps on the searchable leaves
 *    (±1, ±2, ±4, … up to ±4096, plus the domain endpoints). Its gradient lives in `deltaIfApplied`,
 *    so a spread of jump sizes is seeded and the scoring keeps the best.
 *  - other objective shapes: nothing (no per-var direction without inspecting the shape).
 *
 * [Phase.Feasible]: the objective gradient only matters at `cost == 0`.
 */
class ObjectiveSeed : MoveSource {

    override val id: MoveSourceId = ID
    override val phase: Phase = Phase.Feasible
    override val pool: Pool = Pool.NoiseEligible

    override fun generate(state: LocalSearchState, sink: MoveSink) {
        when (val obj = state.shaping.proposalSeeds ?: state.shaping.objective ?: return) {
            is LinearObjective -> {
                for (v in obj.boolWeightValues.indices) {
                    if (obj.boolWeightValues[v] == 0L) continue
                    sink.addChannelingBoolFlip(state, v)
                }
                for (v in obj.intCoefficientValues.indices) {
                    if (obj.intCoefficientValues[v] == 0L) continue
                    val cur = state.assignment.intValue(v)
                    val d = state.rootDomains[v]
                    if (obj.intCoefficientValues[v] > 0 && cur > d.min) {
                        sink.addChannelingIntSet(state, v, d.lower(cur))
                    }
                    if (obj.intCoefficientValues[v] < 0 && cur < d.max) {
                        sink.addChannelingIntSet(state, v, d.higher(cur))
                    }
                }
            }

            is FunctionalObjective -> {
                if (state.shaping.boundedProposalLeaves) {
                    generateLeafBatch(state, sink, obj)
                    return
                }
                for (v in obj.boolLeafVars) sink.addBoolFlip(v)
                // Pure ±1 steps descend a wide-domain coordinate objective far too slowly.
                for (v in obj.leafVars) {
                    // The sink would drop a frozen, defined or foreign-owned target only after synthesis.
                    if (!sink.allowsInt(v)) continue
                    val cur = state.assignment.intValue(v)
                    val d = state.rootDomains[v]
                    if (state.shaping.adjacentProposalSteps) {
                        if (cur > d.min) sink.addChannelingIntSet(state, v, d.lower(cur))
                        if (cur < d.max) sink.addChannelingIntSet(state, v, d.higher(cur))
                        continue
                    }
                    var step = 1
                    while (step <= OBJ_SEED_MAX_STEP) {
                        val up = cur + step
                        val down = cur - step
                        if (up in d) sink.addChannelingIntSet(state, v, up)
                        if (down in d) sink.addChannelingIntSet(state, v, down)
                        if (up > d.max && down < d.min) break
                        step = step shl 1
                    }
                    if (cur != d.min) sink.addChannelingIntSet(state, v, d.min)
                    if (cur != d.max) sink.addChannelingIntSet(state, v, d.max)
                }
            }

            else -> { /* no per-var direction without inspecting the objective shape */ }
        }
    }

    private fun generateLeafBatch(state: LocalSearchState, sink: MoveSink, objective: FunctionalObjective) {
        val bools = objective.boolLeafVars
        val ints = objective.leafVars
        val count = bools.size + ints.size
        if (count == 0) return
        repeat(minOf(count, LEAVES_PER_PICK)) {
            state.checkScoringWork()
            val before = sink.size
            val index = state.shaping.proposalLeafCursor % count
            // Advance before synthesis so an interrupted expensive leaf cannot starve its successors.
            state.shaping.proposalLeafCursor = (index + 1) % count
            try {
                if (index < bools.size) {
                    sink.addBoolFlip(bools[index])
                } else {
                    generateIntLeaf(state, sink, ints[index - bools.size])
                }
            } finally {
                state.shaping.geometricProposalCandidates += sink.size - before
            }
        }
    }

    private fun generateIntLeaf(state: LocalSearchState, sink: MoveSink, v: Int) {
        if (!sink.allowsInt(v)) return
        val cur = state.assignment.intValue(v)
        val domain = state.rootDomains[v]
        var step = 1L
        while (step <= OBJ_SEED_MAX_STEP) {
            state.checkScoringWork()
            if (cur <= Long.MAX_VALUE - step && cur + step in domain) {
                sink.addChannelingIntSet(state, v, cur + step)
            }
            if (cur >= Long.MIN_VALUE + step && cur - step in domain) {
                sink.addChannelingIntSet(state, v, cur - step)
            }
            step = step shl 1
        }
        if (cur != domain.min) sink.addChannelingIntSet(state, v, domain.min)
        if (cur != domain.max) sink.addChannelingIntSet(state, v, domain.max)
    }

    /** Catalog identity. */
    companion object {
        /** Catalog id for this source. */
        val ID: MoveSourceId = MoveSourceId("objective-seed")

        /** Largest geometric step seeded per leaf var during functional-objective descent. */
        private const val OBJ_SEED_MAX_STEP = 4096
        private const val LEAVES_PER_PICK = 8
    }
}
