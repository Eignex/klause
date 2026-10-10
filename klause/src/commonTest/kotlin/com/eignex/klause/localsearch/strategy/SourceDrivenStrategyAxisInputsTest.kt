package com.eignex.klause.localsearch.strategy

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.localsearch.acceptance.AcceptanceRule
import com.eignex.klause.localsearch.movesource.ConfiguredSource
import com.eignex.klause.localsearch.movesource.MoveSource
import com.eignex.klause.localsearch.movesource.MoveSourceId
import com.eignex.klause.localsearch.movesource.Phase
import com.eignex.klause.localsearch.movesource.Pool
import com.eignex.klause.localsearch.movesource.ViolatedRepairs
import com.eignex.klause.localsearch.schedule.Geometric
import com.eignex.klause.localsearch.schedule.ScheduleBundle
import com.eignex.klause.localsearch.schedule.WeightSchedule
import com.eignex.klause.localsearch.scoring.MoveScoring
import com.eignex.klause.propagation.bake
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for the driver axis inputs: Break scoring, the schedule axis (weights, temperature), the
 * perturbation hook, and the configuration-checking filter.
 */
class SourceDrivenStrategyAxisInputsTest {

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
            feasibleDescent = FeasibleDescent.RatchetAsConstraint,
        )
        val state = LocalSearchState(infeasibleRing().bake(), Random(7))
        state.recompute()
        repeat(30) { strategy.pickMove(state)?.let { move -> state.apply(move) } }
        assertTrue(
            state.weights.factorWeights.max() > state.weights.baseFactorWeights.max(),
            "a permanently-stalled search must bump some weight above its seed",
        )
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
        val strategy = SourceDrivenStrategy(
            listOf(ConfiguredSource(twoMoves)),
            configurationChecking = true,
            feasibleDescent = FeasibleDescent.RatchetAsConstraint,
        )
        val state = LocalSearchState(problem.bake(), Random(7))
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
            feasibleDescent = FeasibleDescent.RatchetAsConstraint,
        )
        val state = LocalSearchState(infeasibleRing().bake(), Random(7))
        state.recompute()
        val t0 = temperature.temperature
        repeat(20) { strategy.pickMove(state)?.let { move -> state.apply(move) } }
        assertTrue(
            temperature.temperature < t0,
            "the driver must step the schedule temperature each Metropolis pick (was $t0)",
        )
    }
}
