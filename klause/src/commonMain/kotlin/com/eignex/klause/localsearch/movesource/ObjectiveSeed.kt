package com.eignex.klause.localsearch.movesource

import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.solver.objective.FunctionalObjective
import com.eignex.klause.solver.objective.LinearObjective

/**
 * Objective-direction seed moves. Without it, a fully-satisfied state where no factor proposes a
 * structured move has zero candidates and the strategy returns null, spuriously restarting. This
 * seeds single-variable moves directly on the objective's decision variables:
 *  - [LinearObjective]: a ±1 step on each nonzero-weight var in the direction its coefficient says
 *    reduces the objective, plus a flip on each nonzero-weight bool.
 *  - [FunctionalObjective]: geometric steps (±1, ±2, ±4, …, plus the domain endpoints) on each leaf
 *    var — its gradient lives in `deltaIfApplied`, not per-var coefficients, so we seed a spread of
 *    jump sizes and let the scoring keep the best.
 *  - other objective shapes: nothing (no per-var direction without inspecting the shape).
 *
 * [Phase.Feasible]: the objective gradient only matters at `cost == 0`.
 */
class ObjectiveSeed : MoveSource {

    override val id: MoveSourceId = ID
    override val phase: Phase = Phase.Feasible
    override val pool: Pool = Pool.NoiseEligible

    override fun generate(state: LocalSearchState, sink: MoveSink) {
        when (val obj = state.shaping.objective ?: return) {
            is LinearObjective -> {
                for (v in obj.boolWeights.indices) {
                    if (obj.boolWeights[v] == 0L) continue
                    sink.addBoolFlip(v)
                }
                for (v in obj.intCoefficients.indices) {
                    if (obj.intCoefficients[v] == 0L) continue
                    val cur = state.assignment.intValue(v)
                    val d = state.rootDomains[v]
                    // Step toward smaller objective; channeling-aware so int-move + indicator
                    // updates stay atomic.
                    if (obj.intCoefficients[v] > 0 && cur > d.min) sink.addChannelingIntSet(state, v, d.lower(cur))
                    if (obj.intCoefficients[v] < 0 && cur < d.max) sink.addChannelingIntSet(state, v, d.higher(cur))
                }
            }

            is FunctionalObjective -> {
                // No per-var direction a priori (gradient lives in deltaIfApplied), so seed
                // geometric steps on each leaf var and let scoring keep the best. Pure ±1 descends
                // a wide-domain coordinate objective far too slowly.
                for (v in obj.leafVars) {
                    val cur = state.assignment.intValue(v)
                    val d = state.rootDomains[v]
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

    /** Catalog identity. */
    companion object {
        /** Catalog id for this source. */
        val ID: MoveSourceId = MoveSourceId("objective-seed")

        /** Largest geometric step seeded per leaf var during functional-objective descent. */
        private const val OBJ_SEED_MAX_STEP = 4096
    }
}
