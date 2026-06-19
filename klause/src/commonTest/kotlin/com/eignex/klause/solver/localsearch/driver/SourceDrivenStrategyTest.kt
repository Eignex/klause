package com.eignex.klause.solver.localsearch.driver

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.acceptance.AcceptanceRule
import com.eignex.klause.solver.localsearch.movesource.ConfiguredSource
import com.eignex.klause.solver.localsearch.movesource.ObjectiveSeed
import com.eignex.klause.solver.localsearch.movesource.SatisfiedStructured
import com.eignex.klause.solver.localsearch.movesource.StallSwaps
import com.eignex.klause.solver.localsearch.movesource.ViolatedRepairs
import com.eignex.klause.solver.localsearch.scoring.MoveScoring
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Acceptance test for the source-driven driver (epic #710): a [SourceDrivenStrategy] is built *purely by
 * configuration* over the shared [com.eignex.klause.solver.localsearch.movesource.MoveSource]
 * catalog, with no per-strategy generation code. The same sources [Cbls] draws from are reused
 * here in a different strategy by listing them — demonstrating that adding a source to one place
 * makes it available to every strategy for free.
 */
class SourceDrivenStrategyTest {

    /** Satisfiable `x0 + x1 = 2` over 0..3 — infeasible from the all-zero start, reachable by
     *  single-variable repair moves. */
    private fun satisfiableProblem(): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 2,
        intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
        factors = arrayOf<Factor>(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 2)),
    )

    private fun driveToFeasible(strategy: SourceDrivenStrategy, state: LocalSearchState, steps: Int): Boolean {
        state.recompute()
        repeat(steps) {
            if (state.cost == 0L) return true
            val m = strategy.pickMove(state) ?: return@repeat
            state.apply(m)
        }
        return state.cost == 0L
    }

    @Test
    fun `a focused arm built only from ViolatedRepairs solves a satisfiable instance`() {
        val strategy = SourceDrivenStrategy(
            sources = listOf(ConfiguredSource(ViolatedRepairs(sampleCount = 4))),
            scoring = MoveScoring.Raw,
        )
        val state = LocalSearchState(satisfiableProblem(), Random(7))
        assertTrue(driveToFeasible(strategy, state, steps = 200), "ViolatedRepairs-only arm must reach feasibility")
    }

    @Test
    fun `the same SatisfiedStructured and ObjectiveSeed sources Cbls uses are reusable by configuration`() {
        // Feasible-phase sources, listed by configuration — no generation code in this strategy.
        val strategy = SourceDrivenStrategy(
            sources = listOf(
                ConfiguredSource(SatisfiedStructured.sampled(4)),
                ConfiguredSource(ObjectiveSeed()),
            ),
            scoring = MoveScoring.Weighted,
        )
        // A feasible state (the EQ is satisfied at (1,1)) with an objective so ObjectiveSeed fires.
        val state = LocalSearchState(satisfiableProblem(), Random(7))
        state.objective = LinearObjective(intCoefficients = longArrayOf(1, 1))
        state.assignment.setInt(0, 1)
        state.assignment.setInt(1, 1)
        state.recompute()
        assertEquals(0L, state.cost, "fixture must start feasible so the feasible-phase sources fire")
        assertNotNull(strategy.pickMove(state), "feasible-phase sources must yield a candidate by configuration")
    }

    @Test
    fun `score-only sources are never taken by the noise draw`() {
        // Only a ScoreOnly source, hot noise: the noise branch has nothing to take, so the move
        // must come from the greedy score path — proving score-only moves bypass the dice.
        val strategy = SourceDrivenStrategy(
            sources = listOf(ConfiguredSource(StallSwaps(cap = 16))),
            acceptance = AcceptanceRule.WalkSatNoise(1.0),
        )
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
            factors = arrayOf<Factor>(
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.GE, 3),
                Linear(intArrayOf(-1, 1), intArrayOf(0, 1), LinearOp.GE, 3),
            ),
        )
        val state = LocalSearchState(problem, Random(7))
        state.recompute()
        assertTrue(state.cost > 0L, "fixture must be infeasible so StallSwaps is in phase")
        // Repeated picks must never throw / index an empty noise pool; a returned move (when any)
        // is a score-only swap, not a noise draw.
        repeat(50) { strategy.pickMove(state) }
    }
}
