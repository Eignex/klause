package com.eignex.klause.localsearch.strategy

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.factor.table.Element
import com.eignex.klause.ir.BoolFoldDefinition
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.CostShaping
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.localsearch.acceptance.AcceptanceRule
import com.eignex.klause.localsearch.movesource.ConfiguredSource
import com.eignex.klause.localsearch.movesource.MoveSource
import com.eignex.klause.localsearch.movesource.MoveSourceId
import com.eignex.klause.localsearch.movesource.ObjectiveSeed
import com.eignex.klause.localsearch.movesource.Phase
import com.eignex.klause.localsearch.movesource.Pool
import com.eignex.klause.localsearch.movesource.ViolatedRepairs
import com.eignex.klause.localsearch.schedule.Geometric
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

class SourceDrivenStrategyTest {
    @Test
    fun `explicit zero linear shaping should retain neutral objective scores`() {
        val problem = Problem(1, 0, emptyArray(), emptyArray())
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
        state.assignment.setBool(0, true)
        state.shaping.objective = LinearObjective(boolWeights = longArrayOf(1))
        state.shaping.feasibilityGated = CostShaping.Linear(0.0).feasibilityGated
        state.recompute()
        val strategy = SourceDrivenStrategy(
            sources = listOf(ConfiguredSource(ObjectiveSeed())),
            scoring = MoveScoring.Break,
            acceptance = AcceptanceRule.GreedyDescent,
            feasibleDescent = FeasibleDescent.SelfOwned,
        )

        val move = strategy.pickMove(state)

        assertNull(move)
    }

    @Test
    fun `self owned feasible walks should rank feasible maintained objective moves`() {
        val problem = Problem(
            3,
            0,
            emptyArray(),
            arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, false))),
                Clause(intArrayOf(Lit.make(1, true), Lit.make(2, true))),
                Clause(intArrayOf(Lit.make(1, false), Lit.make(2, false))),
            ),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
        state.assignment.setBool(2, true)
        state.invariants = DefinitionalSweep.infer(
            problem,
            boolFolds = listOf(
                BoolFoldDefinition(2, intArrayOf(Lit.make(1, false)), true),
            ),
        )?.network(0, 3)
        state.shaping.objective = LinearObjective(boolWeights = longArrayOf(-100, 0, 2))
        state.shaping.feasibilityGated = true
        state.recompute()
        val strategy = SourceDrivenStrategy(
            sources = listOf(ConfiguredSource(ObjectiveSeed())),
            scoring = MoveScoring.Break,
            acceptance = AcceptanceRule.Greedy,
            feasibleDescent = FeasibleDescent.SelfOwned,
        )

        val move = strategy.pickMove(state)

        assertEquals(Move.BoolFlip(1), move)
    }

    @Test
    fun `equal Element alternatives should preserve primary choices and random draws`() {
        for (noise in listOf(0.0, 1.0)) {
            val states = listOf(1L, 3L).map { maximum ->
                val problem = Problem(
                    0,
                    2,
                    arrayOf(IntDomain(0, maximum), IntDomain(1, 1)),
                    arrayOf<Factor>(Element(0, 1, longArrayOf(0, 1, 1, 1), false, 0)),
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
                    acceptance = AcceptanceRule.WalkSatNoise(noise),
                    feasibleDescent = FeasibleDescent.RatchetAsConstraint,
                ).also { it.prioritizePrimaryRepairs = true }
            }

            repeat(8) {
                val primary = strategies[0].pickMove(states[0])
                val expanded = strategies[1].pickMove(states[1])

                assertEquals(Move.IntSet(0, 1), primary)
                assertEquals(primary, expanded, "noise=$noise")
                assertEquals(states[0].rng.nextLong(), states[1].rng.nextLong(), "noise=$noise")
            }
        }
    }

    @Test
    fun `productive primary Element repairs should retain priority across strategy copies`() {
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 3).excludeValue(2), IntDomain(9, 9)),
            arrayOf<Factor>(
                Element(0, 1, longArrayOf(0, 9, 0, 9), false, 0),
                Linear(longArrayOf(1), intArrayOf(0), LinearOp.NE, 1),
            ),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 9)
        state.recompute()
        val before = state.cost
        val strategy = SourceDrivenStrategy(
            sources = listOf(ConfiguredSource(ViolatedRepairs.SINGLE)),
            feasibleDescent = FeasibleDescent.RatchetAsConstraint,
        ).also { it.prioritizePrimaryRepairs = true }

        val move = strategy.copy().pickMove(state)
        state.apply(assertNotNull(move))

        assertEquals(Move.IntSet(0, 1), move)
        assertTrue(state.cost < before)
    }

    @Test
    fun `a better Element alternative should repair the complete assignment`() {
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 3).excludeValue(2), IntDomain(1, 1)),
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
        ).also { it.prioritizePrimaryRepairs = true }

        val move = strategy.pickMove(state)
        state.apply(assertNotNull(move))

        assertEquals(Move.IntSet(0, 3), move)
        assertEquals(0L, state.cost)
        state.recompute()
        assertEquals(0L, state.cost)
    }

    @Test
    fun `refined overload reductions should postpone weight bumps and plateau noise`() {
        for (scoring in MoveScoring.entries) {
            val problem = Problem(
                0,
                4,
                arrayOf(IntDomain(0, 0), IntDomain(0, 0), IntDomain(82, 84), IntDomain(84, 84)),
                arrayOf<Factor>(
                    Cumulative(
                        intArrayOf(0, 1),
                        longArrayOf(84, 84),
                        longArrayOf(1, 1),
                        1,
                        durationVars = intArrayOf(2, 3),
                    ),
                ),
            )
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
            state.assignment.setInt(2, 84)
            state.assignment.setInt(3, 84)
            state.recompute()
            val stall = StallSchedule(1, 0.0, 1.0)
            val strategy = SourceDrivenStrategy(
                sources = emptyList(),
                scoring = scoring,
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
        }
    }

    @Test
    fun `plateau feedback should preserve integer drops despite fractional increases`() {
        val stall = StallSchedule(1, 0.0, 1.0)
        val cost = 9007199254740993L
        stall.update(0L, cost, 0.0)

        stall.update(1L, cost - 1, 0.9)

        assertEquals(false, stall.stalled)
    }

    @Test
    fun `plateau feedback should re-anchor fractional progress on restart and reset`() {
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

    private fun satisfiableProblem(): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 2,
        intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
        factors = arrayOf<Factor>(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 2)),
    )

    @Test
    fun `feasible descent should decline an objective-lowering move that breaks feasibility`() {
        // At (1,1), every objective-lowering move breaks x0 + x1 = 2; shaping must preserve feasibility.
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
    fun `weight schedule should bump violated factor weights on stall`() {
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
    fun `configuration checking should drop config-unchanged candidates`() {
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

        state.intConfChange[1] = false
        assertNotNull(strategy.pickMove(state))
    }

    @Test
    fun `driver should cool the schedule temperature for a metropolis acceptance`() {
        // A permanently infeasible problem keeps the noise pool non-empty on every pick.
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
