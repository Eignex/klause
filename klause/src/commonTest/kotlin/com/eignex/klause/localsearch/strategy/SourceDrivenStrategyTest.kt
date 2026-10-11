package com.eignex.klause.localsearch.strategy

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.factor.table.Element
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.acceptance.AcceptanceRule
import com.eignex.klause.localsearch.movesource.ConfiguredSource
import com.eignex.klause.localsearch.movesource.ObjectiveSeed
import com.eignex.klause.localsearch.movesource.StallSwaps
import com.eignex.klause.localsearch.movesource.ViolatedRepairs
import com.eignex.klause.localsearch.schedule.ScheduleBundle
import com.eignex.klause.localsearch.schedule.StallSchedule
import com.eignex.klause.localsearch.schedule.WeightSchedule
import com.eignex.klause.localsearch.scoring.MoveScoring
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Acceptance test for the source-driven driver: a [SourceDrivenStrategy] is built *purely by
 * configuration* over the shared [com.eignex.klause.localsearch.movesource.MoveSource]
 * catalog, with no per-strategy generation code. The same sources [Cbls] draws from are reused here
 * by listing them, so adding a source in one place makes it available to every strategy.
 */
class SourceDrivenStrategyTest {

    @Test
    fun `nonimproving Element alternatives preserve primary greedy choices and random draws`() {
        for (bound in listOf(1L, 3L)) {
            val states = listOf(1L, 3L).map { maximum ->
                val problem = Problem(
                    0, 2, arrayOf(IntDomain(0, maximum), IntDomain(1, 1)),
                    arrayOf<Factor>(
                        Element(0, 1, longArrayOf(0, 1, 1, 1), false, 0),
                        Linear(longArrayOf(1), intArrayOf(0), LinearOp.LE, bound),
                    ),
                )
                LocalSearchState(LocalSearchModel.open(problem), Random(3)).also {
                    it.assignment.setInt(0, 0)
                    it.assignment.setInt(1, 1)
                    it.recompute()
                }
            }
            val strategies = states.map {
                SourceDrivenStrategy(
                    sources = listOf(ConfiguredSource(ViolatedRepairs.SINGLE)),
                    acceptance = AcceptanceRule.WalkSatNoise(0.0),
                    feasibleDescent = FeasibleDescent.RatchetAsConstraint,
                )
            }

            repeat(8) {
                val primary = strategies[0].pickMove(states[0])
                val expanded = strategies[1].pickMove(states[1])

                assertEquals(Move.IntSet(0, 1), primary)
                assertEquals(primary, expanded, "bound=$bound")
                assertEquals(states[0].rng.nextLong(), states[1].rng.nextLong(), "bound=$bound")
            }
        }
    }

    @Test
    fun `Element alternatives retain the complete WalkSAT noise neighborhood`() {
        val problem = Problem(
            0, 2, arrayOf(IntDomain(0, 3), IntDomain(1, 1)),
            arrayOf<Factor>(Element(0, 1, longArrayOf(0, 1, 1, 1), false, 0)),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 1)
        state.recompute()
        val acceptance = AcceptanceRule.WalkSatNoise(1.0)
        val strategy = SourceDrivenStrategy(
            sources = listOf(ConfiguredSource(ViolatedRepairs.SINGLE)),
            acceptance = acceptance,
            feasibleDescent = FeasibleDescent.RatchetAsConstraint,
        )
        val expected = Random(3)
        val repairs = listOf(Move.IntSet(0, 1), Move.IntSet(0, 2), Move.IntSet(0, 3))

        repeat(16) {
            expected.nextInt(1)
            val move = acceptance.choose(expected, repairs, emptyList(), 1.0) { -1.0 }

            assertEquals(move, strategy.pickMove(state))
            assertEquals(expected.nextLong(), state.rng.nextLong())
        }
    }

    @Test
    fun `a better Element alternative repairs the complete assignment`() {
        val problem = Problem(
            0, 2, arrayOf(IntDomain(0, 3).excludeValue(2), IntDomain(1, 1)),
            arrayOf<Factor>(
                Element(0, 1, longArrayOf(0, 1, 1, 1), false, 0),
                Linear(longArrayOf(1), intArrayOf(0), LinearOp.NE, 1),
            ),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 1)
        state.recompute()
        val strategy = SourceDrivenStrategy(
            sources = listOf(ConfiguredSource(ViolatedRepairs.SINGLE)),
            feasibleDescent = FeasibleDescent.RatchetAsConstraint,
        )

        val move = strategy.pickMove(state)
        state.apply(assertNotNull(move))

        assertEquals(Move.IntSet(0, 3), move)
        assertEquals(0L, state.cost)
        state.recompute()
        assertEquals(0L, state.cost)
    }

    @Test
    fun `refined overload reductions postpone weight bumps and plateau noise`() {
        for (scoring in MoveScoring.entries) {
            val problem = Problem(
                0, 4, arrayOf(IntDomain(0, 0), IntDomain(0, 0), IntDomain(82, 84), IntDomain(84, 84)),
                arrayOf<Factor>(Cumulative(
                    intArrayOf(0, 1), longArrayOf(84, 84), longArrayOf(1, 1), 1,
                    durationVars = intArrayOf(2, 3),
                )),
            )
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
            state.assignment.setInt(2, 84)
            state.assignment.setInt(3, 84)
            state.recompute()
            val stall = StallSchedule(1, 0.0, 1.0)
            val strategy = SourceDrivenStrategy(
                sources = emptyList(), scoring = scoring,
                schedule = ScheduleBundle(weights = WeightSchedule(bumpAfter = 1), noise = stall),
                feasibleDescent = FeasibleDescent.RatchetAsConstraint,
            )
            strategy.pickMove(state)
            val initialWeight = state.weights.factorWeights[0]

            for (duration in listOf(83L, 82L)) {
                state.apply(Move.IntSet(2, duration))
                strategy.pickMove(state)

                assertEquals(23L, state.cost, "$scoring duration=$duration")
                assertEquals(scoring == MoveScoring.Break, stall.stalled, "$scoring duration=$duration")
                assertEquals(if (scoring == MoveScoring.Break) 1.0 else 0.0, stall.level)
            }
            val expectedWeight = initialWeight + if (scoring == MoveScoring.Break) 2.0 else 0.0
            assertEquals(expectedWeight, state.weights.factorWeights[0], "$scoring")
            state.weights.factorWeights[0] = 9.0
            state.tabu.step++

            strategy.pickMove(state)

            assertEquals(10.0, state.weights.factorWeights[0], "$scoring")
            assertTrue(stall.stalled, "$scoring")
            assertEquals(1.0, stall.level)
        }
    }

    @Test
    fun `plateau feedback preserves integer drops despite fractional increases`() {
        val stall = StallSchedule(1, 0.0, 1.0)
        val cost = 9007199254740993L
        stall.update(0L, cost, 0.0)

        stall.update(1L, cost - 1, 0.9)

        assertEquals(false, stall.stalled)
    }

    @Test
    fun `plateau feedback re-anchors fractional progress on restart and reset`() {
        for (reset in listOf(false, true)) {
            val stall = StallSchedule(1, 0.0, 1.0)
            stall.update(2L, 23L, 0.1)
            if (reset) stall.reset()

            stall.update(0L, 23L, 0.9)
            stall.update(1L, 23L, 0.8)

            assertEquals(false, stall.stalled, "reset=$reset")
            stall.update(2L, 23L, 0.8)
            assertTrue(stall.stalled, "reset=$reset")
        }
    }

    /** Satisfiable `x0 + x1 = 2` over 0..3 — infeasible from the all-zero start, reachable by
     *  single-variable repair moves. */
    private fun satisfiableProblem(): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 2,
        intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
        factors = arrayOf<Factor>(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 2)),
    )

    @Test
    fun `feasible descent declines an objective-lowering move that breaks feasibility`() {
        // At the feasible optimum (1,1) of x0 + x1 = 2, minimizing x0 + x1: every objective-lowering move
        // ObjectiveSeed proposes (e.g. x0 -> 0) breaks the equality, and none preserving it improves the
        // objective. Under a strong shaping pull the old shaped score ranked the infeasible move best;
        // GreedyDescent must disqualify any violation-adding move and report the local optimum (null)
        // instead of a move the engine would only revert.
        val strategy = SourceDrivenStrategy(
            sources = listOf(ConfiguredSource(ObjectiveSeed())),
            scoring = MoveScoring.Weighted,
            acceptance = AcceptanceRule.WalkSatNoise(0.0),
            feasibleDescent = FeasibleDescent.SelfOwned,
            feasibleAcceptance = AcceptanceRule.GreedyDescent,
        )
        val state = LocalSearchState(satisfiableProblem().bake(), Random(7))
        state.shaping.objective = LinearObjective(intCoefficients = longArrayOf(1, 1))
        state.assignment.setInt(0, 1)
        state.assignment.setInt(1, 1)
        state.shaping.shapingLambda = 10.0 // a strong objective pull, as an aggressive linear shaping would supply
        state.recompute()
        assertEquals(0L, state.cost, "fixture must start feasible")
        assertNull(
            strategy.pickMove(state),
            "a feasibility-breaking obj-lowering move must never be picked at cost == 0",
        )
    }

    @Test
    fun `score-only sources are never taken by the noise draw`() {
        // Only a ScoreOnly source, hot noise: the noise branch has nothing to take, so the move
        // must come from the greedy score path — proving score-only moves bypass the dice.
        val strategy = SourceDrivenStrategy(
            sources = listOf(ConfiguredSource(StallSwaps(cap = 16))),
            acceptance = AcceptanceRule.WalkSatNoise(1.0),
            feasibleDescent = FeasibleDescent.RatchetAsConstraint,
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
        val state = LocalSearchState(problem.bake(), Random(7))
        state.recompute()
        assertTrue(state.cost > 0L, "fixture must be infeasible so StallSwaps is in phase")
        // Repeated picks must never throw / index an empty noise pool; a returned move (when any)
        // is a score-only swap, not a noise draw.
        repeat(50) { strategy.pickMove(state) }
    }
}
