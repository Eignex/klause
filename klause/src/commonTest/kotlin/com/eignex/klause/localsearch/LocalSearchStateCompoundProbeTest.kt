package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.table.Element
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.values
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.*
import com.eignex.klause.solver.objective.FunctionalObjective
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.Objective
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.bindStopControl
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LocalSearchStateCompoundProbeTest {

    @Test
    fun `probe inspections should be counted without a stop predicate`() {
        val problem = Problem(
            1,
            0,
            emptyArray(),
            arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, true)))),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(7))
        val move = Move.Compound(listOf(Move.BoolFlip(0), Move.BoolFlip(0)))
        var visits = 0L
        state.inspectionWork = WorkMeter { visits += it }

        state.scoringPick(ExecutionControl.Never) {
            state.weightedNetDelta(move)
            move
        }

        assertEquals(2L, visits)
    }

    @Test
    fun `worker cancellation within a compound probe should restore the committed state`() {
        val problem = Problem(
            1,
            0,
            emptyArray(),
            arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, true)))),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(7))
        state.assignment.setBool(0, false)
        state.recompute()
        val before = state.assignment.snapshot()
        val cost = state.cost
        val step = state.step
        val move = Move.Compound(List(2048) { Move.BoolFlip(0) })
        var polls = 0

        val interrupted = state.scoringPick(ExecutionControl { ++polls > 1 }.bindStopControl()) {
            state.weightedNetDelta(move)
            move
        }

        assertNull(interrupted)
        assertEquals(before, state.assignment.snapshot())
        assertEquals(cost, state.cost)
        assertEquals(step, state.step)
        state.apply(Move.BoolFlip(0))
        assertEquals(0L, state.cost)
        assertEquals(step + 1, state.step)
    }

    @Test
    fun `cancelled probes should preserve committed observations at every rollback boundary`() {
        val problem = Problem(
            1,
            3,
            arrayOf(IntDomain(0, 2), IntDomain(2, 2), IntDomain(0, 4)),
            arrayOf<Factor>(
                Product(0, 1, 2),
                Linear(intArrayOf(1), intArrayOf(2), LinearOp.EQ, 4),
                Clause(intArrayOf(Lit.make(0, false))),
            ),
        )
        val move = Move.Compound(listOf(Move.BoolFlip(0), Move.IntSet(0, 1), Move.IntSet(0, 2)))
        for (boundary in 1..4) {
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(7))
            state.invariants = assertNotNull(DefinitionalSweep.infer(problem)).network(3, 1)
            state.assignment.setInt(1, 2)
            state.recompute()
            state.apply(Move.Compound(listOf(Move.BoolFlip(0), Move.BoolFlip(0))))
            state.weights.factorWeights.fill(2.5)
            val before = state.assignment.snapshot()
            val degrees = state.factorDegree.copyOf()
            val cost = state.cost
            val best = state.bestCostSeen
            val step = state.step
            val touched = state.tabu.lastTouched.copyOf()
            val counts = state.tabu.touchCount.copyOf()
            val boolConf = state.boolConfChange.copyOf()
            val intConf = state.intConfChange.copyOf()
            val breaks = state.boolBreakCount.copyOf()
            val makes = state.boolMakeCount.copyOf()
            var polls = 0
            var probing = false

            val interrupted = state.scoringPick(ExecutionControl {
                if (!probing) return@ExecutionControl false
                assertEquals(best, state.bestCostSeen)
                assertEquals(step, state.step)
                assertContentEquals(touched, state.tabu.lastTouched)
                assertContentEquals(counts, state.tabu.touchCount)
                assertContentEquals(boolConf, state.boolConfChange)
                assertContentEquals(intConf, state.intConfChange)
                ++polls == boundary
            }) {
                probing = true
                state.weightedNetDelta(move)
                probing = false
                move
            }

            assertNull(interrupted)
            assertEquals(boundary, polls)
            assertEquals(before, state.assignment.snapshot())
            assertContentEquals(degrees, state.factorDegree)
            assertContentEquals(breaks, state.boolBreakCount)
            assertContentEquals(makes, state.boolMakeCount)
            assertEquals(cost, state.cost)
            val predicted = state.weightedNetDelta(move)
            state.apply(move)
            assertEquals((state.cost - cost) * 2.5, predicted)
            assertEquals(step + 1, state.step)
            assertEquals(state.cost, state.bestCostSeen)
        }
    }

    @Test
    fun `failed grading should restore state before the next probe`() {
        val problem = Problem(
            0,
            3,
            arrayOf(IntDomain(0, 1), IntDomain(3, 3), IntDomain(0, 3)),
            arrayOf<Factor>(Product(0, 1, 2), Linear(intArrayOf(1), intArrayOf(2), LinearOp.EQ, 0)),
        )
        val failures = listOf(IllegalArgumentException("grading failed"), CancellationException("grading cancelled"))
        for (failure in failures) {
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(7))
            state.invariants = assertNotNull(DefinitionalSweep.infer(problem)).network(3, 0)
            state.assignment.setInt(1, 3)
            state.recompute()
            state.weights.factorWeights.fill(2.5)
            val before = state.assignment.snapshot()
            val degrees = state.factorDegree.copyOf()
            val cost = state.cost
            val move = Move.IntSet(0, 1)
            val expected = state.netDelta(move)
            val objective = object : Objective {
                override fun evaluate(sample: Sample): Double {
                    if (sample.intValue(0) == 1L) throw failure
                    return 0.0
                }
            }

            assertFailsWith<RuntimeException> { state.objectiveDelta(objective, move) }

            assertEquals(before, state.assignment.snapshot())
            assertContentEquals(degrees, state.factorDegree)
            assertEquals(cost, state.cost)
            assertEquals(expected * 2.5, state.weightedNetDelta(move))
        }
    }

    @Test
    fun `nested move operations should be rejected throughout grading`() {
        val problem = Problem(1, 2, Array(2) { IntDomain(0, 1) }, emptyArray())
        for (applied in listOf(false, true)) {
            for (operation in 0..3) {
                val state = LocalSearchState(LocalSearchModel.open(problem), Random(7))
                val network = InvariantNetwork(listOf(DefinitionalSweep.SweepNode.Bool2Int(0, 0)), 2, 1)
                state.invariants = network
                state.recompute()
                val move = Move.BoolFlip(0)
                val duplicate = Move.Compound(listOf(move, move))
                val before = state.assignment.snapshot()
                val objective = object : Objective {
                    override fun evaluate(sample: Sample): Double {
                        if (sample.boolValue(0) == applied) {
                            when (operation) {
                                0 -> state.netDelta(duplicate)

                                1 -> state.objectiveDelta(
                                    LinearObjective(boolWeights = longArrayOf(1)),
                                    duplicate,
                                )

                                2 -> state.apply(move)

                                3 -> state.invariants = network
                            }
                        }
                        return 0.0
                    }
                }

                assertFailsWith<IllegalStateException> { state.objectiveDelta(objective, move) }

                assertEquals(before, state.assignment.snapshot())
                assertEquals(0L, state.netDelta(move))
                state.apply(move)
                assertEquals(1L, state.assignment.intValue(0))
            }
        }
    }

    @Test
    fun `native Element probes should retain destination changes through a derived index`() {
        val problem = Problem(
            0,
            8,
            arrayOf(
                IntDomain(0, 3),
                IntDomain(2, 5),
                IntDomain(0, 4),
                IntDomain(0, 4),
                IntDomain(0, 4),
                IntDomain(0, 4),
                IntDomain(2, 2),
                IntDomain(0, 8),
            ),
            arrayOf<Factor>(
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, -2),
                Element(1, 5, longArrayOf(2, 3, 4), true, 2),
                Product(5, 6, 7),
                Linear(intArrayOf(1), intArrayOf(7), LinearOp.EQ, 2),
            ),
        )
        val network = assertNotNull(DefinitionalSweep.infer(problem, intArrayOf(1, 5))).network(8, 0)
        val objective = LinearObjective(intCoefficients = longArrayOf(0, 0, 0, 0, 0, 0, 0, 1))
        val moves = listOf(
            Move.IntSet(4, 4) to 1L,
            Move.IntSet(2, 4) to 4L,
            Move.Compound(listOf(Move.IntSet(0, 2), Move.IntSet(4, 4))) to 4L,
            Move.Compound(listOf(Move.IntSet(4, 4), Move.IntSet(0, 2))) to 4L,
            Move.Compound(listOf(Move.IntSet(0, 2), Move.IntSet(0, 0), Move.IntSet(4, 4))) to 1L,
            Move.Compound(listOf(Move.IntSet(0, 3), Move.IntSet(4, 4))) to 1L,
        )
        for ((move, result) in moves) {
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
            state.invariants = network
            for ((variable, value) in longArrayOf(0, 2, 1, 2, 3, 1, 2, 2).withIndex()) {
                state.assignment.setInt(variable, value)
            }
            state.recompute()
            val cost = state.cost
            val predicted = state.netDelta(move)
            val objectiveDelta = state.objectiveDelta(objective, move)
            state.apply(move)

            assertEquals(result * 2, state.assignment.intValue(7), "$move")
            assertEquals((result * 2 - 2).toDouble(), objectiveDelta, "$move")
            assertEquals(state.cost - cost, predicted, "$move")
        }
    }

    private val cases = localSearchStateCases()

    @Test
    fun `definition input scores should include the propagated assignment`() {
        val problem = Problem(
            numBoolVars = 2,
            numIntVars = 2,
            intDomains = Array(2) { IntDomain(0, 1) },
            factors = arrayOf(Clause(intArrayOf(Lit.make(1, false)))),
        )
        val network = InvariantNetwork(
            listOf(
                DefinitionalSweep.SweepNode.Bool2Int(0, 0),
                DefinitionalSweep.SweepNode.CmpReif(1, longArrayOf(1, 1), intArrayOf(0, 1), 1L, LinearOp.EQ),
            ),
            2,
            2,
        )
        val objective = LinearObjective(boolWeights = longArrayOf(0, 3), intCoefficients = longArrayOf(2, 0))
        for (move in listOf(Move.BoolFlip(0), Move.IntSet(1, 1))) {
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(7))
            state.invariants = network
            state.recompute()
            state.weights.factorWeights[0] = 2.5
            state.shaping.objective = objective
            state.shaping.shapingLambda = 0.5
            val cost = state.cost
            val value = objective.evaluate(state.assignment)
            val predicted = state.netDelta(move)
            val weighted = state.weightedNetDelta(move)
            val breakScore = state.breakScore(move)
            val objectiveDelta = state.objectiveDelta(objective, move)
            val shapedDelta = state.shapedObjectiveDelta(move)

            state.apply(move)

            assertEquals(state.cost - cost, predicted, "$move")
            assertEquals(predicted * 2.5, weighted, "$move")
            assertEquals(1, breakScore, "$move")
            assertEquals(objective.evaluate(state.assignment) - value, objectiveDelta, "$move")
            assertEquals(checkNotNull(objectiveDelta) * 0.5, shapedDelta, "$move")
            assertEquals(true, state.assignment.boolValue(1), "$move")
        }
    }

    @Test
    fun `compound objective deltas should reflect final coordinate values`() {
        val problem = Problem(
            numBoolVars = 2,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 2)),
            factors = emptyArray(),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(7))
        val objective = LinearObjective(boolWeights = longArrayOf(2, 3), intCoefficients = longArrayOf(5))
        val cases = listOf(
            Move.Compound(listOf(Move.BoolFlip(0), Move.BoolFlip(0))) to 0.0,
            Move.Compound(listOf(Move.IntSet(0, 1), Move.IntSet(0, 2))) to 10.0,
            Move.Compound(listOf(Move.BoolFlip(0), Move.IntSet(0, 1))) to 7.0,
        )
        val before = state.assignment.snapshot()
        state.shaping.objective = objective
        state.shaping.shapingLambda = 0.5
        state.recompute()

        for ((move, expected) in cases) {
            assertEquals(expected, state.objectiveDelta(objective, move))
            assertEquals(expected * 0.5, state.shapedObjectiveDelta(move))
            assertEquals(before, state.assignment.snapshot())
        }
    }

    @Test
    fun `defined wide objective deltas should reflect final coordinate values and restore state`() {
        val half = 1L shl 62
        val problem = Problem(
            0,
            2,
            Array(2) { IntDomain(-half, half) },
            arrayOf(Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0)),
        )
        val definition = FunctionalObjective.Lin(
            1,
            1,
            longArrayOf(-1),
            arrayOf(FunctionalObjective.Operand.v(0)),
            0,
        )
        val state = LocalSearchState(problem.bake(), Random(0))
        state.invariants = InvariantNetwork(
            listOf(DefinitionalSweep.SweepNode.IntDef(definition, intArrayOf(0))),
            2,
            0,
        )
        state.assignment.setInt(0, -half)
        state.assignment.setInt(1, -half)
        state.recompute()
        val before = state.assignment.snapshot()
        val objective = LinearObjective(intCoefficients = longArrayOf(0, 1))
        val moves = listOf(
            Move.IntSet(0, half),
            Move.Compound(listOf(Move.IntSet(0, -half + 1), Move.IntSet(0, half))),
        )

        for (move in moves) {
            val delta = state.objectiveDelta(objective, move)

            assertEquals(half.toDouble() * 2.0, delta)
            assertEquals(before, state.assignment.snapshot())
            assertEquals(0L, state.cost)
        }
    }

    @Test
    fun `maintained functional objective deltas should follow the propagated term values`() {
        val problem = Problem(
            0,
            3,
            arrayOf(IntDomain(0, 2), IntDomain(3, 3), IntDomain(0, 5)),
            arrayOf<Factor>(Product(0, 1, 2)),
        )
        val sweep = assertNotNull(DefinitionalSweep.infer(problem))
        val objective = assertNotNull(sweep.functionalObjective(intArrayOf(2), longArrayOf(1), 0, true))
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        state.invariants = sweep.network(3, 0)
        state.assignment.setInt(1, 3)
        sweep.sweep(state.assignment, state.rootDomains)
        state.recompute()
        val before = state.assignment.intValue(2)
        val move = Move.IntSet(0, 2)

        val delta = state.objectiveDelta(objective, move)
        state.apply(move)

        assertEquals((state.assignment.intValue(2) - before).toDouble(), delta)
    }

    @Test
    fun `unmaintained functional objective probes should value moves from the committed assignment`() {
        val problem = Problem(
            0,
            3,
            arrayOf(IntDomain(0, 2), IntDomain(3, 3), IntDomain(0, 6)),
            arrayOf<Factor>(Product(0, 1, 2)),
        )
        val objective = assertNotNull(
            assertNotNull(DefinitionalSweep.infer(problem)).functionalObjective(intArrayOf(2), longArrayOf(1), 0, true),
        )
        val sweep = assertNotNull(DefinitionalSweep.infer(problem))
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        state.invariants = sweep.network(3, 0)
        state.assignment.setInt(1, 3)
        sweep.sweep(state.assignment, state.rootDomains)
        state.recompute()
        val probe = Move.IntSet(0, 1)
        val first = state.objectiveDelta(objective, probe)

        state.apply(Move.IntSet(0, 2))

        assertEquals(3.0, first)
        assertEquals(-3.0, state.objectiveDelta(objective, probe))
    }

    @Test
    fun `defined functional objective probes should retain unit improvements beyond double precision`() {
        val half = 1L shl 62
        val problem = Problem(
            0,
            2,
            Array(2) { IntDomain(-half, half) },
            arrayOf(Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0)),
        )
        val state = LocalSearchState(problem.bake(), Random(0))
        val definition = FunctionalObjective.Lin(
            1,
            1,
            longArrayOf(-1),
            arrayOf(FunctionalObjective.Operand.v(0)),
            0,
        )
        state.invariants = InvariantNetwork(
            listOf(DefinitionalSweep.SweepNode.IntDef(definition, intArrayOf(0))),
            2,
            0,
        )
        state.assignment.setInt(0, half)
        state.assignment.setInt(1, half)
        state.recompute()
        val before = state.assignment.snapshot()
        val objective = FunctionalObjective(1, true, listOf(definition), intArrayOf(0))

        val delta = state.objectiveDelta(objective, Move.IntSet(0, half - 1))

        assertEquals(-1.0, delta)
        assertEquals(before, state.assignment.snapshot())
        assertEquals(0L, state.cost)
    }

    @Test
    fun `compound probes should restore derived values when the inverse cannot write`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = Array(3) { IntDomain(0, 2) },
            factors = arrayOf(Linear(intArrayOf(1), intArrayOf(1), LinearOp.EQ, 0)),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(7))
        state.invariants = InvariantNetwork(
            listOf(DefinitionalSweep.SweepNode.ElementDef(1, 0, null, longArrayOf(2), 0)),
            3,
            0,
        )
        state.assignment.setInt(0, 1)
        state.recompute()
        val sample = state.assignment.snapshot()
        val cost = state.cost
        val step = state.step
        val touched = state.tabu.lastTouched.copyOf()
        val counts = state.tabu.touchCount.copyOf()
        val move = Move.Compound(listOf(Move.IntSet(0, 0), Move.IntSet(2, 1)))

        state.netDelta(move)
        state.breakScore(move)
        state.weightedNetDelta(move)

        assertEquals(sample, state.assignment.snapshot())
        assertEquals(cost, state.cost)
        assertEquals(step, state.step)
        assertContentEquals(touched, state.tabu.lastTouched)
        assertContentEquals(counts, state.tabu.touchCount)
        state.recompute()
        assertEquals(cost, state.cost)
    }

    @Test
    fun `compound scoring should propagate definitions after all inputs change`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = Array(3) { IntDomain(0, 2) },
            factors = arrayOf(Linear(intArrayOf(1), intArrayOf(2), LinearOp.EQ, 0)),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(7))
        state.invariants = InvariantNetwork(
            listOf(DefinitionalSweep.SweepNode.ElementDef(2, 0, intArrayOf(1), null, 0)),
            3,
            0,
        )
        state.assignment.setInt(1, 2)
        state.recompute()
        val move = Move.Compound(listOf(Move.IntSet(1, 1), Move.IntSet(0, 1)))
        val before = state.cost

        val predicted = state.netDelta(move)
        state.apply(move)

        assertEquals(state.cost - before, predicted)
        assertEquals(0L, predicted)
    }

    @Test
    fun `weighted compound predictions should match the committed degree change`() {
        for (case in cases) {
            val baked = case.problem.bake()
            for (seed in 0 until 3) {
                val state = LocalSearchState(baked, Random(seed))
                state.restart()
                val weights = state.weights.factorWeights
                for (i in weights.indices) weights[i] = (i + 1) * 0.25
                val compound = randomCompound(case.problem, state, Random(seed + 100)) ?: continue
                val sibling = LocalSearchState(baked, Random(seed))
                copyLocalSearchAssignment(state, sibling)
                sibling.recompute()
                val before = sibling.factorDegree.copyOf()

                val predicted = state.weightedNetDelta(compound)
                sibling.apply(compound)

                var expected = 0.0
                for (i in before.indices) expected += weights[i] * (sibling.factorDegree[i] - before[i])
                assertEquals(expected, predicted, "${case.name} seed=$seed")
            }
        }
    }

    @Test
    fun `compound probes should leave touch counts and break-make untouched`() {
        for (case in cases) {
            val baked = case.problem.bake()
            val state = LocalSearchState(baked, Random(0))
            state.restart()
            val expectedTouch = IntArray(case.problem.numBoolVars + case.problem.numIntVars)
            val rng = Random(0x5151L)

            repeat(10) {
                val move = randomPrimitiveLocalSearchMove(case.problem, state, rng)
                if (move != null) {
                    state.apply(move)
                    expectedTouch[slotOf(case.problem, move)]++
                }
                val compound = randomCompound(case.problem, state, rng) ?: return@repeat
                state.netDelta(compound)
                state.breakScore(compound)
            }
            val sibling = LocalSearchState(baked, Random(0))
            copyLocalSearchAssignment(state, sibling)
            sibling.recompute()

            assertContentEquals(expectedTouch, state.tabu.touchCount.copyOf(expectedTouch.size), case.name)
            assertEquals(sibling.cost, state.cost, case.name)
            for (b in 0 until case.problem.numBoolVars) {
                assertEquals(sibling.breakScore(Move.BoolFlip(b)), state.breakScore(Move.BoolFlip(b)), case.name)
                assertEquals(sibling.makeScore(Move.BoolFlip(b)), state.makeScore(Move.BoolFlip(b)), case.name)
            }
        }
    }

    private fun slotOf(problem: Problem, move: Move): Int = when (move) {
        is Move.BoolFlip -> move.varId
        is Move.IntSet -> problem.numBoolVars + move.varId
        is Move.RealSet, is Move.Compound -> error("primitive discrete move expected")
    }

    private fun randomCompound(problem: Problem, state: LocalSearchState, rng: Random): Move.Compound? {
        val a = randomPrimitiveLocalSearchMove(problem, state, rng) ?: return null
        repeat(8) {
            val b = randomPrimitiveLocalSearchMove(problem, state, rng) ?: return@repeat
            if (slotOf(problem, b) != slotOf(problem, a)) {
                return Move.Compound(listOf(a, b))
            }
        }
        return null
    }
}
