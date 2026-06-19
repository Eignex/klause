package com.eignex.klause.solver.localsearch.strategy

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Move
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.localsearch.movesource.ConfiguredSource
import com.eignex.klause.solver.localsearch.movesource.MoveSource
import com.eignex.klause.solver.localsearch.movesource.MoveSourceId
import com.eignex.klause.solver.localsearch.movesource.Phase
import com.eignex.klause.solver.localsearch.movesource.Pool
import com.eignex.klause.solver.localsearch.movesource.ViolatedRepairs
import com.eignex.klause.solver.localsearch.schedule.Geometric
import com.eignex.klause.solver.localsearch.schedule.ScheduleBundle
import com.eignex.klause.solver.localsearch.schedule.WeightSchedule
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for the driver axis inputs that let the bespoke strategies be re-expressed as recipes: Break
 * scoring, the schedule axis (weights, temperature), the perturbation hook, and the
 * configuration-checking filter — each additive and defaulting to the prior behaviour.
 */
class SourceDrivenStrategyAxisInputsTest {

    /** Satisfiable `x0 + x1 = 2` over 0..3 (infeasible from all-zero, reachable by single-var repair). */
    private fun satisfiable(): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 2,
        intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
        factors = arrayOf<Factor>(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 2)),
    )

    /** Infeasible-by-construction ring so the search stalls forever (weights keep bumping). */
    private fun infeasibleRing(): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 2,
        intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
        factors = arrayOf<Factor>(
            Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.GE, 3),
            Linear(intArrayOf(-1, 1), intArrayOf(0, 1), LinearOp.GE, 3),
        ),
    )

    @Test
    fun `break scoring reaches feasibility`() {
        val strategy = SourceDrivenStrategy(
            sources = listOf(ConfiguredSource(ViolatedRepairs(sampleCount = 4))),
            scoring = MoveScoring.Break,
        )
        val state = LocalSearchState(satisfiable(), Random(7))
        state.recompute()
        var steps = 0
        while (steps < 200 && state.cost > 0L) {
            val m = strategy.pickMove(state) ?: break
            state.apply(m)
            steps++
        }
        assertEquals(0L, state.cost, "Break-scoring greedy must reach feasibility on x0 + x1 = 2")
    }

    @Test
    fun `weight schedule bumps violated factor weights on stall`() {
        val strategy = SourceDrivenStrategy(
            sources = listOf(ConfiguredSource(ViolatedRepairs(sampleCount = 4))),
            scoring = MoveScoring.Weighted,
            schedule = ScheduleBundle(
                weights = WeightSchedule.feasibilityJump(
                    weightBumpAfter = 1,
                    weightIncrement = 1.0,
                    weightDecay = 1.0,
                ),
            ),
        )
        val state = LocalSearchState(infeasibleRing(), Random(7))
        state.recompute()
        repeat(30) { strategy.pickMove(state)?.let { move -> state.apply(move) } }
        assertTrue(
            state.factorWeights.max() > state.baseFactorWeights.max(),
            "a permanently-stalled search must bump some weight above its seed",
        )
    }

    @Test
    fun `perturbation pre-empts the normal pick`() {
        val kick = Move.IntSet(0, 2)
        val strategy = SourceDrivenStrategy(
            sources = listOf(ConfiguredSource(ViolatedRepairs(sampleCount = 4))),
            perturbation = { kick },
        )
        val state = LocalSearchState(satisfiable(), Random(7))
        state.recompute()
        assertEquals(kick, strategy.pickMove(state), "a firing perturbation must be returned directly")
    }

    @Test
    fun `configuration checking drops config-unchanged candidates`() {
        // A source emitting one move on var 0 and one on var 1; CC blocks var 0, so the pick is var 1.
        val twoMoves = object : MoveSource {
            override val id = MoveSourceId("test:two-int-moves")
            override val phase = Phase.Any
            override val pool = Pool.NoiseEligible
            override fun generate(state: LocalSearchState, sink: MoveSink) {
                sink.addIntSet(0, 1)
                sink.addIntSet(1, 1)
            }
        }
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
            factors = arrayOf<Factor>(),
        )
        val strategy = SourceDrivenStrategy(listOf(ConfiguredSource(twoMoves)), configurationChecking = true)
        val state = LocalSearchState(problem, Random(7))
        state.recompute()
        state.intConfChange[0] = false // var 0 CC-blocked
        state.intConfChange[1] = true
        val m = strategy.pickMove(state)
        assertTrue(m is Move.IntSet && m.varId == 1, "CC must drop the var-0 (unchanged) move, leaving var 1; got $m")

        // When every candidate is CC-blocked, fall back to the full pool rather than null.
        state.intConfChange[1] = false
        assertNotNull(strategy.pickMove(state), "all-blocked CC must fall back, not starve the pick")
    }

    @Test
    fun `driver cools the schedule temperature for a metropolis acceptance`() {
        // Temperature lives in the schedule axis, not the acceptance rule: the driver must advance it
        // once per pick that sampled the noise pool. A permanently-infeasible problem keeps the noise
        // pool non-empty, so a Geometric schedule's temperature must fall over a run of picks.
        val temperature = Geometric(initialTemperature = 1.0, coolingRate = 0.9)
        val strategy = SourceDrivenStrategy(
            sources = listOf(ConfiguredSource(ViolatedRepairs(sampleCount = 4))),
            scoring = MoveScoring.Break,
            acceptance = AcceptanceRule.Metropolis,
            schedule = ScheduleBundle(temperature = temperature),
        )
        val state = LocalSearchState(infeasibleRing(), Random(7))
        state.recompute()
        val t0 = temperature.temperature
        repeat(20) { strategy.pickMove(state)?.let { move -> state.apply(move) } }
        assertTrue(
            temperature.temperature < t0,
            "the driver must step the schedule temperature each Metropolis pick (was $t0)",
        )
    }
}
