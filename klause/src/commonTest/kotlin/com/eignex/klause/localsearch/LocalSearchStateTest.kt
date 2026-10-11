package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.factor.table.Element
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorReduction
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.FunctionalObjective
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [LocalSearchState.synthesizeChannelingMove] reads the linear structure (coeffs/op/bound) from
 * the original factors, not the parallel invariants which no longer carry it after the
 * propagator/invariant split. These fixtures drive the EQ channeling paths the
 * [strategy.CblsStallSwapTest] fixture (GE/LE only) deliberately avoids.
 */
class LocalSearchStateTest {
    @Test
    fun `scaled maintained literal channels backsolve exact binary targets`() {
        for ((bound, outputCoefficient) in listOf(0 to 1L, 1 to 1L, 0 to -1L, 1 to -1L)) {
            val domains = arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(3, 3), IntDomain(0, 3))
            val rewrite = Product(1, 2, 3).structuralReduce(domains)
            assertTrue(rewrite is FactorReduction.Rewrite)
            val problem = Problem(1, 4, domains, arrayOf<Factor>(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 0),
                ReifiedLinear(0, intArrayOf(1), intArrayOf(1), LinearOp.EQ, bound),
                rewrite.replacement.single(),
            ))
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
            val network = assertNotNull(DefinitionalSweep.infer(problem, intArrayOf(1))).network(4, 1)
            state.invariants = InvariantNetwork(List(network.size) { network.node(it) } + listOf(
                DefinitionalSweep.SweepNode.IntDef(FunctionalObjective.Lin(
                    3, outputCoefficient, longArrayOf(-3 * outputCoefficient),
                    arrayOf(FunctionalObjective.Operand.v(1)), 0,
                ), intArrayOf(1)),
            ), 4, 1)
            state.assignment.setInt(0, if (bound == 1) 0 else 1)
            state.assignment.setInt(1, 1)
            state.assignment.setInt(2, 3)
            state.assignment.setInt(3, 3)
            state.assignment.setBool(0, bound == 1)
            state.recompute()

            state.moveSink.addChannelingIntSet(state, 3, 1)
            assertTrue(state.moveSink.list.isEmpty())

            for (target in listOf(0L, 3L, 0L)) {
                state.moveSink.clear()
                val snapshot = state.assignment.snapshot()
                state.moveSink.addChannelingIntSet(state, 3, target)
                val move = assertNotNull(state.moveSink.list.firstOrNull())
                val predicted = state.netDelta(move)
                assertEquals(snapshot, state.assignment.snapshot())
                state.apply(move)

                assertEquals(target, state.assignment.intValue(3))
                assertEquals(target / 3, state.assignment.intValue(1))
                assertEquals(state.assignment.intValue(0) == 0L, state.assignment.boolValue(0))
                assertEquals(0L, predicted)
                assertEquals(0L, state.cost)
                state.recompute()
                assertEquals(0L, state.cost)
            }
        }
    }

    @Test
    fun `scaled literal inverses preserve protected coordinates and target domains`() {
        for (protection in listOf(
            "choice pin", "choice owner", "channel pin", "channel owner", "indicator pin",
            "output pin", "output owner", "output domain", "channel domain",
        )) {
            val domains = arrayOf(
                IntDomain(0, 1), IntDomain(if (protection == "channel domain") 1 else 0, 1),
                IntDomain(3, 3), IntDomain(if (protection == "output domain") 1 else 0, 3),
            )
            val problem = Problem(1, 4, domains, arrayOf<Factor>(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 0),
                ReifiedLinear(0, intArrayOf(1), intArrayOf(1), LinearOp.EQ, 1),
                Linear(intArrayOf(1, -3), intArrayOf(3, 1), LinearOp.EQ, 0),
            ))
            val assumptions = when (protection) {
                "choice pin" -> Assumptions.None.withInt(0, 0)
                "channel pin" -> Assumptions.None.withInt(1, 1)
                "indicator pin" -> Assumptions.None.withBool(0, true)
                "output pin" -> Assumptions.None.withInt(3, 3)
                else -> Assumptions.None
            }
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3), assumptions)
            val network = assertNotNull(DefinitionalSweep.infer(problem, intArrayOf(1))).network(4, 1)
            state.invariants = InvariantNetwork(List(network.size) { network.node(it) } + listOf(
                DefinitionalSweep.SweepNode.IntDef(FunctionalObjective.Lin(
                    3, 1, longArrayOf(-3), arrayOf(FunctionalObjective.Operand.v(1)), 0,
                ), intArrayOf(1)),
            ), 4, 1)
            val owned = when (protection) {
                "choice owner" -> 0
                "channel owner" -> 1
                "output owner" -> 3
                else -> -1
            }
            if (owned >= 0) state.moveSink.setOwners(IntArray(4) { if (it == owned) 4 else -1 })
            for ((id, value) in longArrayOf(0, 1, 3, 3).withIndex()) state.assignment.setInt(id, value)
            state.assignment.setBool(0, true)
            state.recompute()
            val snapshot = state.assignment.snapshot()

            state.moveSink.addChannelingIntSet(state, 3, 0)

            assertTrue(state.moveSink.list.isEmpty(), protection)
            assertEquals(snapshot, state.assignment.snapshot(), protection)
            assertEquals(0L, state.cost, protection)
        }
    }

    @Test
    fun `suspended initial assignment preserves mixed seeded draws and pins`() {
        val problem = Problem(513, 513, Array(513) { IntDomain(-3, 7) }, emptyArray())
        val pins = Assumptions(bools = mapOf(0 to true, 512 to false), ints = mapOf(0 to 6L, 512 to -2L))
        for (anchored in listOf(false, true)) {
            val model = if (anchored) LocalSearchModel.open(problem) else LocalSearchModel.of(problem.bake())
            val direct = LocalSearchState(model, Random(7), pins)
            val suspended = LocalSearchState(model, Random(7), pins)

            direct.randomizeAssignment()
            val steps = suspended.initialAssignmentSteps(null).iterator()
            while (steps.hasNext()) steps.next()

            assertEquals(direct.assignment.snapshot(), suspended.assignment.snapshot())
            assertEquals(direct.rng.nextLong(), suspended.rng.nextLong())
            assertTrue(suspended.assignment.boolValue(0))
            assertEquals(-2L, suspended.assignment.intValue(512))
        }
    }

    @Test
    fun `root repairs preserve multiple selected Element requirements`() {
        for (definedResults in listOf(false, true)) {
            val problem = Problem(
                0, 9, arrayOf(
                    IntDomain(0, 1), IntDomain(2, 3), IntDomain(1, 1), IntDomain(1, 1),
                    IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 0),
                ), arrayOf<Factor>(
                    Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, -2),
                    Element(0, 2, longArrayOf(4, 5), true, 0),
                    Element(1, 3, longArrayOf(6, 7), true, 2),
                    Cumulative(intArrayOf(0, 8), longArrayOf(1, 1), longArrayOf(1, 1), 1),
                ),
            )
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
            val hints = if (definedResults) intArrayOf(1, 2, 3) else intArrayOf(1)
            state.invariants = assertNotNull(DefinitionalSweep.infer(problem, hints)).network(9, 0)
            for ((variable, value) in longArrayOf(0, 2, 1, 1, 1, 0, 1, 0, 0).withIndex()) {
                state.assignment.setInt(variable, value)
            }
            state.recompute()
            val before = state.assignment.snapshot()
            val degrees = state.factorDegree.copyOf()
            val payloads = state.intPayload.copyOf()
            val step = state.step

            state.moveSink.addChannelingIntSet(state, 0, 1)
            val coordinated = state.moveSink.list.filterIsInstance<Move.Compound>().single()
            val predicted = state.netDelta(coordinated)

            assertEquals(before, state.assignment.snapshot())
            assertEquals(1L, state.cost)
            assertEquals(step, state.step)
            assertContentEquals(degrees, state.factorDegree)
            assertContentEquals(payloads, state.intPayload)
            assertEquals(1L, state.netDelta(Move.IntSet(0, 1)))
            assertEquals(-1L, predicted)
            state.apply(coordinated)
            assertEquals(1L, state.assignment.intValue(0))
            assertEquals(1L, state.assignment.intValue(5))
            assertEquals(1L, state.assignment.intValue(7))
            assertEquals(0L, state.cost)
            state.recompute()
            assertEquals(0L, state.cost)
        }
    }

    @Test
    fun `joint root repairs select cells from the complete propagated move`() {
        val problem = Problem(
            0, 11, arrayOf(
                IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 2), IntDomain(1, 1), IntDomain(1, 1),
                IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 0),
            ), arrayOf<Factor>(
                Linear(intArrayOf(1, 1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 0),
                Element(0, 3, longArrayOf(5, 6), true, 0),
                Element(2, 4, longArrayOf(7, 8, 9), true, 0),
                Cumulative(intArrayOf(0, 10), longArrayOf(1, 1), longArrayOf(1, 1), 1),
            ),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        state.invariants = assertNotNull(DefinitionalSweep.infer(problem, intArrayOf(2, 3, 4))).network(11, 0)
        for ((variable, value) in longArrayOf(0, 0, 0, 1, 1, 1, 0, 1, 0, 0, 0).withIndex()) {
            state.assignment.setInt(variable, value)
        }
        state.recompute()
        val before = state.assignment.snapshot()

        state.moveSink.addChannelingCompound(state, listOf(Move.IntSet(0, 1), Move.IntSet(1, 1)))
        val coordinated = state.moveSink.list.filterIsInstance<Move.Compound>().maxBy { it.parts.size }

        assertEquals(before, state.assignment.snapshot())
        assertEquals(-1L, state.netDelta(coordinated))
        state.apply(coordinated)
        assertEquals(2L, state.assignment.intValue(2))
        assertEquals(1L, state.assignment.intValue(6))
        assertEquals(0L, state.assignment.intValue(8))
        assertEquals(1L, state.assignment.intValue(9))
        assertEquals(0L, state.cost)
        state.recompute()
        assertEquals(0L, state.cost)
    }

    @Test
    fun `Element-preserving proposals respect protected roots and cells`() {
        for (protection in listOf("cell-pin", "cell-owner", "cell-domain", "cell-definition", "root-pin")) {
            val factors = arrayOf<Factor>(
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, -2),
                Element(0, 2, longArrayOf(4, 5), true, 0),
                Element(1, 3, longArrayOf(6, 7), true, 2),
                Cumulative(intArrayOf(0, 8), longArrayOf(1, 1), longArrayOf(1, 1), 1),
            ) + if (protection == "cell-definition") arrayOf<Factor>(Product(7, 7, 5)) else emptyArray()
            val problem = Problem(
                0, 9, arrayOf(
                    IntDomain(0, 1), IntDomain(2, 3), IntDomain(1, 1), IntDomain(1, 1),
                    IntDomain(0, 1), IntDomain(0, if (protection == "cell-domain") 0 else 1),
                    IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 0),
                ), factors,
            )
            val pins = when (protection) {
                "cell-pin" -> Assumptions.None.withInt(5, 0)
                "root-pin" -> Assumptions.None.withInt(0, 0)
                else -> Assumptions.None
            }
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3), pins)
            state.invariants = assertNotNull(DefinitionalSweep.infer(problem, intArrayOf(1, 2, 3))).network(9, 0)
            for ((variable, value) in longArrayOf(0, 2, 1, 1, 1, 0, 1, 0, 0).withIndex()) {
                state.assignment.setInt(variable, value)
            }
            if (protection == "cell-owner") state.moveSink.setOwners(IntArray(9) { if (it == 5) 1 else -1 })
            state.recompute()
            val before = state.assignment.snapshot()

            state.moveSink.addChannelingIntSet(state, 0, 1)

            assertTrue(state.moveSink.list.none { it is Move.Compound }, protection)
            assertEquals(before, state.assignment.snapshot(), protection)
            assertEquals(1L, state.cost, protection)
        }
    }

    @Test
    fun `Element-preserving proposals reject conflicting shared-cell targets`() {
        val problem = Problem(
            0, 8, arrayOf(
                IntDomain(0, 1), IntDomain(1, 2), IntDomain(1, 1), IntDomain(0, 0),
                IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 0),
            ), arrayOf<Factor>(
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, -1),
                Element(0, 2, longArrayOf(4, 5), true, 0),
                Element(1, 3, longArrayOf(6, 5), true, 1),
                Cumulative(intArrayOf(0, 7), longArrayOf(1, 1), longArrayOf(1, 1), 1),
            ),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        state.invariants = assertNotNull(DefinitionalSweep.infer(problem, intArrayOf(1, 2, 3))).network(8, 0)
        for ((variable, value) in longArrayOf(0, 1, 1, 0, 1, 0, 0, 0).withIndex()) {
            state.assignment.setInt(variable, value)
        }
        state.recompute()
        val before = state.assignment.snapshot()

        state.moveSink.addChannelingIntSet(state, 0, 1)

        assertTrue(state.moveSink.list.none { it is Move.Compound })
        assertEquals(before, state.assignment.snapshot())
        assertEquals(1L, state.cost)
    }

    @Test
    fun `Element-preserving proposals bound the number of selected factors`() {
        for (selections in listOf(32, 33)) {
            val problem = Problem(
                0, 5, arrayOf(IntDomain(0, 1), IntDomain(1, 1), IntDomain(1, 1), IntDomain(0, 1), IntDomain(0, 0)),
                Array<Factor>(selections + 1) {
                    if (it == selections) Cumulative(intArrayOf(0, 4), longArrayOf(1, 1), longArrayOf(1, 1), 1)
                    else Element(0, 1, longArrayOf(2, 3), true, 0)
                },
            )
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
            state.assignment.setInt(1, 1)
            state.assignment.setInt(2, 1)
            state.recompute()

            state.moveSink.addChannelingIntSet(state, 0, 1)

            assertEquals(if (selections == 32) 1 else 0, state.moveSink.list.count { it is Move.Compound })
            assertEquals(1L, state.cost)
        }
    }

    @Test
    fun `Element-preserving probe allowance resets with the candidate collection`() {
        val problem = Problem(
            0, 5, arrayOf(IntDomain(0, 1), IntDomain(1, 1), IntDomain(1, 1), IntDomain(0, 1), IntDomain(0, 0)),
            arrayOf<Factor>(
                Element(0, 1, longArrayOf(2, 3), true, 0),
                Cumulative(intArrayOf(0, 4), longArrayOf(1, 1), longArrayOf(1, 1), 1),
            ),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        state.assignment.setInt(1, 1)
        state.assignment.setInt(2, 1)
        state.recompute()

        repeat(2) {
            state.moveSink.clear()
            repeat(5) { state.moveSink.addChannelingIntSet(state, 0, 1) }

            assertEquals(4, state.moveSink.list.count { it is Move.Compound })
            assertEquals(5, state.moveSink.list.count { it is Move.IntSet })
            assertEquals(1L, state.cost)
        }
    }

    @Test
    fun `violated Element variants repair complete propagated index selections`() {
        for (derivedIndex in listOf(false, true)) {
            val problem = Problem(
                0, 5, arrayOf(IntDomain(0, 1), IntDomain(1, 1), IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1)),
                arrayOf<Factor>(
                    Linear(intArrayOf(1), intArrayOf(if (derivedIndex) 4 else 0), LinearOp.GE, 1),
                    Element(0, 1, longArrayOf(2, 3), true, 0),
                ) + if (derivedIndex) {
                    arrayOf<Factor>(Linear(intArrayOf(1, -1), intArrayOf(4, 0), LinearOp.EQ, 0))
                } else {
                    emptyArray()
                },
            )
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
            if (derivedIndex) {
                state.invariants = assertNotNull(DefinitionalSweep.infer(problem, intArrayOf(0))).network(5, 0)
            }
            state.assignment.setInt(1, 1)
            state.recompute()
            state.shaping.objective = LinearObjective(intCoefficients = longArrayOf(0, 0, 0, -1, 0))
            state.shaping.shapingLambda = 1.0
            state.weights.factorWeights[0] = 2.0
            val before = state.assignment.snapshot()
            val degrees = state.factorDegree.copyOf()
            val payloads = state.intPayload.copyOf()
            val longPayloads = state.longPayload.copyOf()
            val weights = state.weights.factorWeights.copyOf()
            val touched = state.tabu.lastTouched.copyOf()
            val activity = state.tabu.touchCount.copyOf()
            val best = state.bestCostSeen
            val step = state.step
            val membership = state.factorDegree.indices.map { state.violated.contains(it) }

            state.moveSink.addChannelingIntSet(state, if (derivedIndex) 4 else 0, 1)
            val variant = state.moveSink.list.filterIsInstance<Move.Compound>().single()
            val raw = state.netDelta(variant)
            val weighted = state.weightedNetDelta(variant)
            val objective = state.objectiveDelta(assertNotNull(state.shaping.objective), variant)

            assertEquals(before, state.assignment.snapshot())
            assertContentEquals(degrees, state.factorDegree)
            assertContentEquals(payloads, state.intPayload)
            assertContentEquals(longPayloads, state.longPayload)
            assertContentEquals(weights, state.weights.factorWeights)
            assertContentEquals(touched, state.tabu.lastTouched)
            assertContentEquals(activity, state.tabu.touchCount)
            assertEquals(membership, state.factorDegree.indices.map { state.violated.contains(it) })
            assertEquals(best, state.bestCostSeen)
            assertEquals(step, state.step)
            assertEquals(2L, state.cost)
            assertEquals(-2L, raw)
            assertEquals(-3.0, weighted)
            assertEquals(-1.0, objective)
            state.apply(variant)
            assertEquals(1L, state.assignment.intValue(0))
            assertEquals(1L, state.assignment.intValue(3))
            assertEquals(0L, state.cost)
            state.recompute()
            assertEquals(0L, state.cost)
        }
    }

    @Test
    fun `violated Element admission rejects broken factors and worse weighted or objective grades`() {
        for (grade in listOf("raw", "new-break", "weighted", "objective")) {
            val extra = when (grade) {
                "raw" -> arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(3), LinearOp.LE, 0))
                "new-break" -> arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(3), LinearOp.LE, 2))
                "weighted" -> arrayOf<Factor>(
                    Linear(intArrayOf(1), intArrayOf(3), LinearOp.LE, -1),
                    Linear(intArrayOf(1), intArrayOf(3), LinearOp.GE, 3),
                )
                else -> emptyArray()
            }
            val problem = Problem(
                0, 4, arrayOf(IntDomain(0, 1), IntDomain(3, 3), IntDomain(0, 3), IntDomain(0, 3)),
                arrayOf<Factor>(
                    Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1),
                    Element(0, 1, longArrayOf(2, 3), true, 0),
                ) + extra,
            )
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
            state.assignment.setInt(1, 3)
            state.recompute()
            if (grade == "weighted") state.weights.factorWeights[2] = 10.0
            if (grade == "objective") {
                state.shaping.objective = LinearObjective(intCoefficients = longArrayOf(0, 0, 0, 1))
                state.shaping.shapingLambda = 1.0
            }
            val before = state.assignment.snapshot()
            val degrees = state.factorDegree.copyOf()
            val payloads = state.longPayload.copyOf()
            val cost = state.cost

            state.moveSink.addChannelingIntSet(state, 0, 1)

            val root = Move.IntSet(0, 1)
            val rejected = Move.Compound(listOf(root, Move.IntSet(3, 3)))
            assertEquals(listOf(root), state.moveSink.list, grade)
            if (grade == "raw") {
                assertEquals(state.netDelta(root), state.netDelta(rejected))
            } else {
                assertTrue(state.netDelta(rejected) < state.netDelta(root), grade)
            }
            when (grade) {
                "new-break" -> assertEquals(1, state.breakScore(rejected))
                "weighted" -> assertTrue(state.weightedNetDelta(rejected) > state.weightedNetDelta(root))
                "objective" -> assertEquals(
                    3.0, state.objectiveDelta(assertNotNull(state.shaping.objective), rejected),
                )
            }
            assertEquals(before, state.assignment.snapshot(), grade)
            assertContentEquals(degrees, state.factorDegree, grade)
            assertContentEquals(payloads, state.longPayload, grade)
            assertEquals(cost, state.cost, grade)
            state.recompute()
            assertEquals(cost, state.cost, grade)
        }
    }

    @Test
    fun `violated Element proposals protect destinations and stable requested results`() {
        val protections = listOf("pin", "owner", "domain", "definition", "root-cell", "result-change", "fixed", "index")
        for (protection in protections) {
            val problem = Problem(
                0, 4, arrayOf(
                    IntDomain(0, 2), IntDomain(1, 2), IntDomain(0, 1),
                    IntDomain(0, if (protection == "domain") 0 else 1),
                ), arrayOf<Factor>(
                    Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1),
                    Element(0, 1, longArrayOf(2, 3), true, 0),
                ) + if (protection == "definition") arrayOf<Factor>(Product(2, 2, 3)) else emptyArray(),
            )
            val pins = if (protection == "pin") Assumptions.None.withInt(3, 0) else Assumptions.None
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3), pins)
            if (protection == "definition") {
                state.invariants = assertNotNull(DefinitionalSweep.infer(problem, intArrayOf(3))).network(4, 0)
            }
            state.assignment.setInt(1, 1)
            if (protection == "fixed") state.assignment.setInt(3, 1)
            state.recompute()
            if (protection == "owner") {
                state.moveSink.setOwners(intArrayOf(-1, -1, -1, 0))
                state.moveSink.proposer = 0
            }
            val before = state.assignment.snapshot()
            val cost = state.cost

            when (protection) {
                "root-cell" -> state.moveSink.addChannelingCompound(state, listOf(Move.IntSet(0, 1), Move.IntSet(3, 0)))
                "result-change" -> state.moveSink.addChannelingCompound(
                    state, listOf(Move.IntSet(0, 1), Move.IntSet(1, 2)),
                )
                "index" -> state.moveSink.addChannelingIntSet(state, 0, 2)
                else -> state.moveSink.addChannelingIntSet(state, 0, 1)
            }

            assertTrue(state.moveSink.list.none { move ->
                move is Move.Compound && move.parts.any { it == Move.IntSet(3, 1) }
            }, protection)
            assertEquals(before, state.assignment.snapshot(), protection)
            assertEquals(cost, state.cost, protection)
        }
    }

    @Test
    fun `violated Element proposals coalesce compatible cells and reject conflicting requests`() {
        for (compatible in listOf(false, true)) {
            val result = if (compatible) 1L else 2L
            val problem = Problem(
                0, 5, arrayOf(
                    IntDomain(0, 1), IntDomain(1, 1), IntDomain(result, result), IntDomain(0, 2), IntDomain(0, 2),
                ),
                arrayOf<Factor>(
                    Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1),
                    Element(0, 1, longArrayOf(3, 4), true, 0),
                    Element(0, 2, longArrayOf(3, 4), true, 0),
                ),
            )
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
            state.assignment.setInt(1, 1)
            state.assignment.setInt(2, result)
            state.recompute()
            val before = state.assignment.snapshot()

            state.moveSink.addChannelingIntSet(state, 0, 1)

            val variants = state.moveSink.list.filterIsInstance<Move.Compound>()
            assertEquals(if (compatible) 1 else 0, variants.size)
            assertEquals(before, state.assignment.snapshot())
            if (compatible) {
                assertEquals(listOf(Move.IntSet(0, 1), Move.IntSet(4, 1)), variants.single().parts)
                state.apply(variants.single())
                assertEquals(0L, state.cost)
            }
        }
    }

    @Test
    fun `violated Element extensions retain existing satisfied selection variants`() {
        val problem = Problem(
            0, 5, arrayOf(IntDomain(0, 1), IntDomain(1, 1), IntDomain(2, 2), IntDomain(0, 2), IntDomain(0, 2)),
            arrayOf<Factor>(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1),
                Element(0, 1, longArrayOf(3, 4), true, 0),
                Element(0, 2, longArrayOf(3, 4), true, 0),
            ),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        state.assignment.setInt(1, 1)
        state.assignment.setInt(2, 2)
        state.assignment.setInt(3, 1)
        state.recompute()
        val before = state.assignment.snapshot()

        state.moveSink.addChannelingIntSet(state, 0, 1)
        val variant = state.moveSink.list.filterIsInstance<Move.Compound>().single()

        assertEquals(listOf(Move.IntSet(0, 1), Move.IntSet(4, 1)), variant.parts)
        assertEquals(before, state.assignment.snapshot())
        state.apply(variant)
        assertEquals(0, state.factorDegree[1])
        assertEquals(1L, state.cost)
    }

    @Test
    fun `violated Element admission probes share the collection allowance and reset together`() {
        val problem = Problem(
            0, 4, arrayOf(IntDomain(0, 1), IntDomain(1, 1), IntDomain(0, 1), IntDomain(0, 1)),
            arrayOf<Factor>(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1),
                Element(0, 1, longArrayOf(2, 3), true, 0),
            ),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        state.assignment.setInt(1, 1)
        state.recompute()
        val before = state.assignment.snapshot()

        repeat(2) {
            state.moveSink.clear()
            repeat(5) { state.moveSink.addChannelingIntSet(state, 0, 1) }

            assertEquals(2, state.moveSink.list.count { it is Move.Compound })
            assertEquals(5, state.moveSink.list.count { it is Move.IntSet })
            assertEquals(before, state.assignment.snapshot())
            assertEquals(2L, state.cost)
        }
    }

    @Test
    fun `violated Element proposals bound affected selections`() {
        for (selections in listOf(32, 33)) {
            val problem = Problem(
                0, 4, arrayOf(IntDomain(0, 1), IntDomain(1, 1), IntDomain(0, 1), IntDomain(0, 1)),
                Array<Factor>(selections + 1) {
                    if (it == selections) Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1)
                    else Element(0, 1, longArrayOf(2, 3), true, 0)
                },
            )
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
            state.assignment.setInt(1, 1)
            state.recompute()

            state.moveSink.addChannelingIntSet(state, 0, 1)

            assertEquals(if (selections == 32) 1 else 0, state.moveSink.list.count { it is Move.Compound })
            assertEquals(selections + 1L, state.cost)
        }
    }

    @Test
    fun `unrelated primitive repair scores preserve weights and committed state`() {
        for (coordinate in listOf("boolean", "integer", "real")) {
            for (allocated in listOf(false, true)) {
                val problem = Problem(
                    1, 3, arrayOf(IntDomain(0, 0), IntDomain(0, 0), IntDomain(0, 3)),
                    arrayOf<Factor>(
                        Cumulative(intArrayOf(0, 1), longArrayOf(84, 84), longArrayOf(1, 1), 1),
                        Clause(intArrayOf(Lit.make(0, true))),
                        Linear(intArrayOf(2), intArrayOf(2), LinearOp.GE, 6),
                        Linear(intArrayOf(1), intArrayOf(2), LinearOp.GE, 2),
                        Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.GE, 3.0),
                    ),
                    numRealVars = 1,
                    realLower = doubleArrayOf(0.0),
                    realUpper = doubleArrayOf(10.0),
                )
                val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
                repeat(3) { state.assignment.setInt(it, 0L) }
                state.assignment.setBool(0, false)
                state.assignment.setReal(0, 0.0)
                state.recompute()
                state.weights.normalizeWeightsByClass = true
                if (allocated) {
                    for ((fid, weight) in doubleArrayOf(2.0, 3.5, 2.5, 0.75, 1.5).withIndex()) {
                        state.weights.factorWeights[fid] = weight
                    }
                }
                val before = state.assignment.snapshot()
                val cost = state.cost
                val overload = state.intPayload[0]
                val bestCost = state.bestCostSeen
                val step = state.tabu.step
                val touched = state.tabu.lastTouched.copyOf()
                val activity = state.tabu.touchCount.copyOf()
                val weightedBefore = if (allocated) {
                    state.factorDegree.indices.sumOf { state.weights.factorWeights[it] * state.factorDegree[it] }
                } else {
                    cost.toDouble()
                }
                val move = when (coordinate) {
                    "boolean" -> Move.BoolFlip(0)
                    "integer" -> Move.IntSet(2, 1L)
                    else -> Move.RealSet(0, 1.0)
                }

                val raw = state.repairNetDelta(move, weighted = false)
                val weighted = state.repairNetDelta(move, weighted = true)
                repeat(2) {
                    assertEquals(raw, state.repairNetDelta(move, weighted = false), coordinate)
                    assertEquals(weighted, state.repairNetDelta(move, weighted = true), coordinate)
                }

                assertEquals(before, state.assignment.snapshot(), coordinate)
                assertEquals(cost, state.cost, coordinate)
                assertEquals(overload, state.intPayload[0], coordinate)
                assertEquals(bestCost, state.bestCostSeen, coordinate)
                assertEquals(step, state.tabu.step, coordinate)
                assertContentEquals(touched, state.tabu.lastTouched, coordinate)
                assertContentEquals(activity, state.tabu.touchCount, coordinate)
                assertEquals(allocated, state.weights.allocated, coordinate)
                if (!allocated) assertEquals(raw, weighted, coordinate)

                state.apply(move)
                val committed = state.cost
                state.recompute()
                val weightedAfter = if (allocated) {
                    state.factorDegree.indices.sumOf { state.weights.factorWeights[it] * state.factorDegree[it] }
                } else {
                    state.cost.toDouble()
                }

                assertEquals(committed, state.cost, coordinate)
                assertEquals((state.cost - cost).toDouble(), raw, coordinate)
                assertEquals(weightedAfter - weightedBefore, weighted, coordinate)
                assertEquals(overload, state.intPayload[0], coordinate)
            }
        }
    }

    @Test
    fun `presence repair scores retain cumulative plateau refinement`() {
        val problem = Problem(
            2, 2, arrayOf(IntDomain(0, 0), IntDomain(0, 0)),
            arrayOf<Factor>(Cumulative(
                intArrayOf(0, 1), longArrayOf(1, 1), longArrayOf(1, 83), 1,
                presents = intArrayOf(Lit.make(0, true), Lit.make(1, true)),
            )),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        state.assignment.setBool(0, true)
        state.assignment.setBool(1, true)
        state.recompute()
        state.weights.factorWeights[0] = 3.5
        val before = state.assignment.snapshot()
        val cost = state.cost
        val move = Move.BoolFlip(0)

        repeat(2) {
            assertEquals(0L, state.netDelta(move))
            assertEquals(-1.0 / 64.0, state.repairNetDelta(move, weighted = false))
            assertEquals(-3.5 / 64.0, state.repairNetDelta(move, weighted = true))
            assertEquals(before, state.assignment.snapshot())
            assertEquals(cost, state.cost)
            assertEquals(83, state.intPayload[0])
        }
        state.apply(move)
        state.recompute()

        assertEquals(cost, state.cost)
        assertEquals(82, state.intPayload[0])
    }

    @Test
    fun `cumulative repair scores distinguish overload reductions within a degree bucket`() {
        for (coordinate in listOf("start", "duration", "resource", "capacity")) {
            val duration = coordinate == "duration"
            val resource = coordinate == "resource"
            val capacity = coordinate == "capacity"
            val secondAux = if (duration) 84L else if (resource) 42L else 0L
            val domains = arrayOf(
                IntDomain(0, if (coordinate == "start") 1 else 0), IntDomain(0, 0),
                when (coordinate) {
                    "duration" -> IntDomain(83, 84)
                    "resource" -> IntDomain(41, 42)
                    "capacity" -> IntDomain(1, 2)
                    else -> IntDomain(0, 0)
                },
                IntDomain(secondAux, secondAux),
            )
            val problem = Problem(
                0, 4, domains, arrayOf<Factor>(Cumulative(
                    intArrayOf(0, 1), LongArray(2) { if (duration) 84 else 1 },
                    LongArray(2) { if (duration) 1 else 42 }, 1,
                    durationVars = if (duration) intArrayOf(2, 3) else intArrayOf(),
                    resourceVars = if (resource) intArrayOf(2, 3) else intArrayOf(),
                    capacityVar = if (capacity) 2 else -1,
                )),
            )
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
            state.assignment.setInt(2, domains[2].max)
            if (capacity) state.assignment.setInt(2, 1)
            state.assignment.setInt(3, domains[3].min)
            state.recompute()
            state.weights.factorWeights[0] = 3.5
            val before = state.assignment.snapshot()
            val cost = state.cost
            val overload = state.intPayload[0]
            val move = when (coordinate) {
                "start" -> Move.IntSet(0, 1)
                "duration" -> Move.IntSet(2, 83)
                "resource" -> Move.IntSet(2, 41)
                else -> Move.IntSet(2, 2)
            }

            repeat(2) {
                assertEquals(0L, state.netDelta(move), coordinate)
                assertEquals(0.0, state.weightedNetDelta(move), coordinate)
                assertEquals(-1.0 / 64.0, state.repairNetDelta(move, weighted = false), coordinate)
                assertEquals(-3.5 / 64.0, state.repairNetDelta(move, weighted = true), coordinate)
                assertEquals(before, state.assignment.snapshot(), coordinate)
                assertEquals(cost, state.cost, coordinate)
                assertEquals(overload, state.intPayload[0], coordinate)
            }
            state.apply(move)

            assertEquals(cost, state.cost, coordinate)
            assertEquals(overload - 1, state.intPayload[0], coordinate)
            state.recompute()
            assertEquals(cost, state.cost, coordinate)
            assertEquals(overload - 1, state.intPayload[0], coordinate)
        }
    }

    @Test
    fun `cumulative repair scores grade the complete propagated compound`() {
        val problem = Problem(
            0, 7, arrayOf(
                IntDomain(83, 84), IntDomain(83, 84), IntDomain(83, 84), IntDomain(83, 84),
                IntDomain(0, 0), IntDomain(0, 0), IntDomain(1, 1),
            ), arrayOf<Factor>(
                Product(0, 6, 2),
                Product(1, 6, 3),
                Cumulative(
                    intArrayOf(4, 5), longArrayOf(84, 84), longArrayOf(1, 1), 1,
                    durationVars = intArrayOf(2, 3),
                ),
            ),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        state.invariants = assertNotNull(DefinitionalSweep.infer(problem)).network(7, 0)
        for ((variable, value) in longArrayOf(84, 84, 84, 84, 0, 0, 1).withIndex()) {
            state.assignment.setInt(variable, value)
        }
        state.recompute()
        state.weights.factorWeights[2] = 3.5
        val before = state.assignment.snapshot()
        val cost = state.cost
        val compound = Move.Compound(listOf(Move.IntSet(0, 83), Move.IntSet(1, 83)))

        for (move in listOf(Move.IntSet(0, 83), compound)) {
            assertEquals(0L, state.netDelta(move))
            assertEquals(-1.0 / 64.0, state.repairNetDelta(move, weighted = false))
            assertEquals(-3.5 / 64.0, state.repairNetDelta(move, weighted = true))
            assertEquals(before, state.assignment.snapshot())
            assertEquals(cost, state.cost)
            assertEquals(84, state.intPayload[2])
        }
        state.apply(compound)

        assertEquals(83L, state.assignment.intValue(2))
        assertEquals(83L, state.assignment.intValue(3))
        assertEquals(83, state.intPayload[2])
        assertEquals(cost, state.cost)
        state.recompute()
        assertEquals(83, state.intPayload[2])
        assertEquals(cost, state.cost)
    }

    @Test
    fun `cumulative repair scores do not reward crossing payload saturation`() {
        val demand = Int.MAX_VALUE.toLong()
        val problem = Problem(
            0, 2, arrayOf(IntDomain(0, 0), IntDomain(demand, demand + 32)),
            arrayOf<Factor>(Cumulative(
                intArrayOf(0), longArrayOf(1), longArrayOf(demand), 1, resourceVars = intArrayOf(1),
            )),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        state.assignment.setInt(1, demand)
        state.recompute()
        val before = state.assignment.snapshot()
        val cost = state.cost
        val move = Move.IntSet(1, demand + 20)

        val score = state.repairNetDelta(move, weighted = false)

        assertEquals(0L, state.netDelta(move))
        assertTrue(score > 0.0)
        assertEquals(before, state.assignment.snapshot())
        assertEquals(cost, state.cost)
    }

    @Test
    fun `duplicate equality rows maintain the indicator and its binary channel`() {
        val problem = Problem(
            1, 2, arrayOf(IntDomain(0, 2), IntDomain(0, 1)),
            arrayOf<Factor>(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
                ReifiedLinear(0, intArrayOf(1), intArrayOf(1), LinearOp.EQ, 1),
            ),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 0)
        state.assignment.setBool(0, false)
        state.recompute()

        state.apply(state.synthesizeChannelingMove(0, 1))

        assertTrue(state.assignment.boolValue(0))
        assertEquals(1L, state.assignment.intValue(1))
        assertEquals(0L, state.cost)
    }

    @Test
    fun `indicator channels and their product outputs follow coordinate moves`() {
        for (bound in listOf(0, 1)) {
            val problem = Problem(
                1, 6, arrayOf(
                    IntDomain(0, 2), IntDomain(0, 1), IntDomain(3, 3), IntDomain(0, 3),
                    IntDomain(0, 1), IntDomain(0, 3),
                ),
                arrayOf<Factor>(
                    ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
                    ReifiedLinear(0, intArrayOf(1), intArrayOf(1), LinearOp.EQ, bound),
                    Product(1, 2, 3),
                    ReifiedLinear(0, intArrayOf(1), intArrayOf(4), LinearOp.EQ, 1 - bound),
                    Product(4, 2, 5),
                ),
            )
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
            state.assignment.setInt(0, 0)
            state.assignment.setBool(0, false)
            state.assignment.setInt(1, 1L - bound)
            state.assignment.setInt(2, 3)
            state.assignment.setInt(3, 3L * (1L - bound))
            state.assignment.setInt(4, bound.toLong())
            state.assignment.setInt(5, 3L * bound)
            state.invariants = assertNotNull(DefinitionalSweep.infer(problem.factors, 6)).network(6, 1)
            state.recompute()

            for (coordinate in listOf(1L, 2L, 1L)) {
                val move = state.synthesizeChannelingMove(0, coordinate)
                val predicted = state.netDelta(move)
                state.apply(move)
                val cost = state.cost
                val expected = if (coordinate == 1L) bound.toLong() else 1L - bound

                assertEquals(expected, state.assignment.intValue(1))
                assertEquals(3L * expected, state.assignment.intValue(3))
                assertEquals(1L - expected, state.assignment.intValue(4))
                assertEquals(3L * (1L - expected), state.assignment.intValue(5))
                assertEquals(0L, predicted)
                assertEquals(0L, cost)
                state.recompute()
                assertEquals(cost, state.cost)
            }
        }
    }

    @Test
    fun `indicator moves retain protected channel coordinates`() {
        for (protection in listOf("pin", "owner", "definition")) {
            val problem = Problem(
                1, 3, arrayOf(IntDomain(0, 2), IntDomain(0, 1), IntDomain(0, 0)),
                arrayOf<Factor>(
                    ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
                    ReifiedLinear(0, intArrayOf(1), intArrayOf(1), LinearOp.EQ, 1),
                    Product(2, 2, 1),
                ),
            )
            val assumptions = if (protection == "pin") Assumptions.None.withInt(1, 0) else Assumptions.None
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3), assumptions)
            if (protection == "owner") state.moveSink.setOwners(intArrayOf(-1, 1, -1))
            if (protection == "definition") {
                state.invariants = assertNotNull(DefinitionalSweep.infer(problem.factors, 3)).network(3, 1)
            }
            state.assignment.setInt(0, 0)
            state.assignment.setInt(1, 0)
            state.assignment.setInt(2, 0)
            state.assignment.setBool(0, false)
            state.recompute()

            state.apply(state.synthesizeChannelingMove(0, 1))

            assertEquals(0L, state.assignment.intValue(1))
            assertEquals(1L, state.cost)
        }
    }

    @Test
    fun `nonbinary equality inputs retain their independent value`() {
        val problem = Problem(
            1, 2, arrayOf(IntDomain(0, 2), IntDomain(0, 2)),
            arrayOf<Factor>(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
                ReifiedLinear(0, intArrayOf(1), intArrayOf(1), LinearOp.EQ, 1),
            ),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 2)
        state.assignment.setBool(0, false)
        state.recompute()

        state.apply(state.synthesizeChannelingMove(0, 1))

        assertEquals(2L, state.assignment.intValue(1))
        assertEquals(1L, state.cost)
    }

    @Test
    fun `indicator moves cannot set a channel outside its root domain`() {
        val problem = Problem(
            1, 2, arrayOf(IntDomain(0, 2), IntDomain(0, 0)),
            arrayOf<Factor>(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
                ReifiedLinear(0, intArrayOf(1), intArrayOf(1), LinearOp.EQ, 1),
            ),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 0)
        state.assignment.setBool(0, false)
        state.recompute()

        state.apply(state.synthesizeChannelingMove(0, 1))

        assertEquals(0L, state.assignment.intValue(1))
        assertTrue(state.intValuesInDomain())
        assertEquals(1L, state.cost)
    }

    @Test
    fun `reified inequality repairs cross holes with pinned indicators`() {
        for (op in listOf(LinearOp.LE, LinearOp.GE)) {
            for (coefficient in listOf(-1, 1)) {
                for (indicator in listOf(false, true)) {
                    val decreasing = ((op == LinearOp.LE) == (coefficient > 0)) == indicator
                    val target = if (decreasing) 0L else 3L
                    val threshold = if (indicator) {
                        if (decreasing) 2 else 1
                    } else {
                        if (decreasing) 3 else 0
                    }
                    val problem = Problem(
                        1, 1, arrayOf(IntDomain(0, 3).excludeValue(1).excludeValue(2)),
                        arrayOf<Factor>(
                            ReifiedLinear(0, intArrayOf(coefficient), intArrayOf(0), op, coefficient * threshold),
                        ),
                    )
                    val assumptions = Assumptions.None.withBool(0, indicator)
                    val state = LocalSearchState(LocalSearchModel.open(problem), Random(0), assumptions)
                    state.assignment.setInt(0, 3L - target)
                    state.assignment.setBool(0, indicator)
                    state.recompute()
                    val before = state.cost

                    state.factors[0].proposeRepairMoves(state, 0, state.moveSink)
                    val move = state.moveSink.list.single() as Move.IntSet
                    val predicted = state.netDelta(move)
                    state.apply(move)

                    assertEquals(target, move.newValue, "$op $coefficient $indicator")
                    assertEquals(indicator, state.assignment.boolValue(0))
                    assertEquals(-before, predicted)
                    assertEquals(0L, state.cost)
                    state.recompute()
                    assertEquals(0L, state.cost)
                }
            }
        }
    }

    @Test
    fun `reified toggle repairs round holes toward the flipped predicate`() {
        for (op in listOf(LinearOp.LE, LinearOp.GE)) {
            for (coefficient in listOf(-1, 1)) {
                for (indicator in listOf(false, true)) {
                    val desiredTruth = !indicator
                    val decreasing = ((op == LinearOp.LE) == (coefficient > 0)) == desiredTruth
                    val target = if (decreasing) 3L else 9L
                    val threshold = if (desiredTruth) {
                        if (decreasing) 7 else 5
                    } else {
                        if (decreasing) 8 else 4
                    }
                    val domain = assertNotNull(IntDomain(0, 12).excludeValues(longArrayOf(1, 2, 4, 5, 6, 7, 8, 10, 11)))
                    val problem = Problem(
                        1, 1, arrayOf(domain),
                        arrayOf<Factor>(
                            ReifiedLinear(0, intArrayOf(coefficient), intArrayOf(0), op, coefficient * threshold),
                        ),
                    )
                    val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
                    state.assignment.setInt(0, if (decreasing) 0L else 12L)
                    state.assignment.setBool(0, indicator)
                    state.recompute()
                    val before = state.cost

                    state.factors[0].proposeRepairMoves(state, 0, state.moveSink)
                    val move = state.moveSink.list.filterIsInstance<Move.Compound>().single()
                    val coordinate = move.parts.filterIsInstance<Move.IntSet>().single()
                    val predicted = state.netDelta(move)
                    state.apply(move)

                    assertEquals(target, coordinate.newValue, "$op $coefficient $indicator")
                    assertEquals(desiredTruth, state.assignment.boolValue(0))
                    assertEquals(-before, predicted)
                    assertEquals(0L, state.cost)
                    state.recompute()
                    assertEquals(0L, state.cost)
                }
            }
        }
    }

    @Test
    fun `reified repairs retain valid compounds alongside hole alternatives`() {
        val domain = assertNotNull(
            IntDomain(0, 12).excludeValues(longArrayOf(1, 2, 4, 5, 6, 7, 8, 10, 11)),
        )
        val problem = Problem(
            1, 2, arrayOf(domain, domain),
            arrayOf<Factor>(ReifiedLinear(0, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 19)),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
        state.assignment.setInt(0, 12L)
        state.assignment.setInt(1, 12L)
        state.assignment.setBool(0, true)
        state.recompute()

        state.factors[0].proposeRepairMoves(state, 0, state.moveSink)
        val moves = state.moveSink.list.toList()

        assertEquals(
            listOf(
                Move.BoolFlip(0),
                Move.IntSet(0, 3L),
                Move.IntSet(1, 3L),
                Move.Compound(listOf(Move.BoolFlip(0), Move.IntSet(0, 9L))),
                Move.Compound(listOf(Move.BoolFlip(0), Move.IntSet(1, 9L))),
            ),
            moves,
        )
        for (move in moves) {
            state.assignment.setInt(0, 12L)
            state.assignment.setInt(1, 12L)
            state.assignment.setBool(0, true)
            state.recompute()
            val before = state.cost
            val predicted = state.netDelta(move)

            assertEquals(before, state.cost)
            assertEquals(12L, state.assignment.intValue(0))
            assertEquals(12L, state.assignment.intValue(1))
            assertTrue(state.assignment.boolValue(0))
            state.apply(move)

            assertEquals(-before, predicted)
            assertEquals(0L, state.cost)
            state.recompute()
            assertEquals(0L, state.cost)
        }
    }

    @Test
    fun `reified degree updates and scores agree with recompute across moves`() {
        for (op in LinearOp.entries) {
            val problem = Problem(
                1, 1, arrayOf(IntDomain(0, 10)),
                arrayOf<Factor>(ReifiedLinear(0, intArrayOf(2), intArrayOf(0), op, 6)),
            )
            val state = LocalSearchState(problem.bake(), Random(1))
            state.violationSoftCap = 4
            state.assignment.setBool(0, true)
            state.recompute()
            val moves = listOf(
                Move.BoolFlip(0), Move.IntSet(0, 3), Move.BoolFlip(0), Move.IntSet(0, 10),
                Move.Compound(listOf(Move.BoolFlip(0), Move.IntSet(0, 0))),
            )

            for (move in moves) {
                val before = state.cost
                val predicted = state.netDelta(move)
                state.apply(move)
                val cost = state.cost
                val degree = state.factorDegree[0]
                val breaks = state.breakScore(Move.BoolFlip(0))
                val makes = state.makeScore(Move.BoolFlip(0))

                state.recompute()

                assertEquals(predicted, cost - before, "$op $move")
                assertEquals(state.cost, cost, "$op $move")
                assertEquals(state.factorDegree[0], degree, "$op $move")
                assertEquals(state.breakScore(Move.BoolFlip(0)), breaks, "$op $move")
                assertEquals(state.makeScore(Move.BoolFlip(0)), makes, "$op $move")
            }
        }
    }

    private fun parts(move: Move): List<Move> = (move as Move.Compound).parts

    /** `c == p` reified as `b_p` for p in 0..2, encoded as N parallel single-var EQ reifieds. */
    private fun reifiedChannelingProblem(): Problem = Problem(
        numBoolVars = 3,
        numIntVars = 1,
        intDomains = arrayOf(IntDomain(0, 2)),
        factors = arrayOf<Factor>(
            ReifiedLinear(auxBoolVar = 0, coeffs = intArrayOf(1), vars = intArrayOf(0), op = LinearOp.EQ, bound = 0),
            ReifiedLinear(auxBoolVar = 1, coeffs = intArrayOf(1), vars = intArrayOf(0), op = LinearOp.EQ, bound = 1),
            ReifiedLinear(auxBoolVar = 2, coeffs = intArrayOf(1), vars = intArrayOf(0), op = LinearOp.EQ, bound = 2),
        ),
    )

    @Test
    fun `states sharing a projection keep assignments and invariant payloads independent`() {
        val model = LocalSearchModel.of(reifiedChannelingProblem().bake())
        val projection = LocalSearchProblem(model.problem, model.domains)
        val first = LocalSearchState(model, Random(1), projection = projection)
        val second = LocalSearchState(model, Random(2), projection = projection)
        for (state in listOf(first, second)) {
            state.assignment.setInt(0, 0)
            state.assignment.setBool(0, true)
            state.recompute()
        }
        val cost = second.cost
        val delta = second.netDelta(Move.IntSet(0, 1))

        first.apply(Move.IntSet(0, 1))

        assertTrue(first.cost > cost)
        assertEquals(0L, second.assignment.intValue(0))
        assertEquals(cost, second.cost)
        assertEquals(delta, second.netDelta(Move.IntSet(0, 1)))
    }

    @Test
    fun `Linear EQ channeling skips partners whose targets are outside their domains`() {
        for ((domain, current) in listOf(IntDomain(0, 1) to 0L, IntDomain(0, 2).excludeValue(1) to 2L)) {
            val problem = Problem(
                0, 3, arrayOf(IntDomain(0, 1), domain, IntDomain(0, 1)),
                arrayOf<Factor>(Linear(longArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.EQ, current + 1)),
            )
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
            state.assignment.setInt(0, 0)
            state.assignment.setInt(1, current)
            state.assignment.setInt(2, 1)
            state.recompute()

            state.moveSink.addChannelingIntSet(state, 0, 1)
            val move = state.moveSink.list.single()
            val predicted = state.netDelta(move)
            state.apply(move)

            assertEquals(1L, state.assignment.intValue(0))
            assertEquals(current, state.assignment.intValue(1))
            assertEquals(0L, state.assignment.intValue(2))
            assertEquals(0L, predicted)
            assertEquals(0L, state.cost)
            assertTrue(state.intValuesInDomain())
            state.recompute()
            assertEquals(0L, state.cost)
        }
    }

    @Test
    fun `Linear EQ channeling skips protected partners`() {
        for (protection in listOf("pin", "owner", "definition")) {
            val problem = Problem(
                0, 5, arrayOf(
                    IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1), IntDomain(1, 1), IntDomain(1, 1),
                ),
                arrayOf<Factor>(
                    Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.EQ, 2),
                    Product(3, 4, 1),
                ),
            )
            val assumptions = if (protection == "pin") Assumptions.None.withInt(1, 1) else Assumptions.None
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3), assumptions)
            if (protection == "owner") state.moveSink.setOwners(intArrayOf(-1, 7, -1, -1, -1))
            if (protection == "definition") {
                state.invariants = assertNotNull(DefinitionalSweep.infer(problem.factors, 5)).network(5, 0)
            }
            for ((variable, value) in longArrayOf(0, 1, 1, 1, 1).withIndex()) {
                state.assignment.setInt(variable, value)
            }
            state.recompute()

            state.moveSink.addChannelingIntSet(state, 0, 1)
            val move = state.moveSink.list.single()
            val predicted = state.netDelta(move)
            state.apply(move)

            assertEquals(1L, state.assignment.intValue(0))
            assertEquals(1L, state.assignment.intValue(1))
            assertEquals(0L, state.assignment.intValue(2))
            assertEquals(0L, predicted)
            assertEquals(0L, state.cost)
            state.recompute()
            assertEquals(0L, state.cost)
        }
    }

    @Test
    fun `Linear EQ channeling prefers an admissible lower coefficient partner`() {
        val problem = Problem(
            0, 3, arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 2)),
            arrayOf<Factor>(Linear(intArrayOf(2, 2, 1), intArrayOf(0, 1, 2), LinearOp.EQ, 4)),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 1)
        state.assignment.setInt(2, 2)
        state.recompute()

        state.moveSink.addChannelingIntSet(state, 0, 1)
        val move = state.moveSink.list.single()
        val predicted = state.netDelta(move)
        state.apply(move)

        assertEquals(1L, state.assignment.intValue(0))
        assertEquals(1L, state.assignment.intValue(1))
        assertEquals(0L, state.assignment.intValue(2))
        assertEquals(0L, predicted)
        assertEquals(0L, state.cost)
        state.recompute()
        assertEquals(0L, state.cost)
    }

    @Test
    fun `Linear EQ channeling retains the driving move when no partner is admissible`() {
        val problem = Problem(
            0, 2, arrayOf(IntDomain(0, 1), IntDomain(0, 1)),
            arrayOf<Factor>(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 0)),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 0)
        state.recompute()

        state.moveSink.addChannelingIntSet(state, 0, 1)
        val move = state.moveSink.list.single()
        val predicted = state.netDelta(move)
        state.apply(move)

        assertEquals(Move.IntSet(0, 1), move)
        assertEquals(1L, state.assignment.intValue(0))
        assertEquals(0L, state.assignment.intValue(1))
        assertEquals(1L, predicted)
        assertEquals(1L, state.cost)
        state.recompute()
        assertEquals(1L, state.cost)
    }

    @Test
    fun `Linear EQ channeling never counter-shifts a sibling into a hole`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5).excludeValue(3)),
            factors = arrayOf<Factor>(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 4)),
        )
        val state = LocalSearchState(problem.bake(), Random(1))
        state.assignment.setInt(0, 2)
        state.assignment.setInt(1, 2)
        state.recompute()

        val move = state.synthesizeChannelingMove(intVar = 0, newValue = 1)

        assertEquals(Move.IntSet(0, 1), move, "absorbing the drift would put y on the hole at 3")
    }

    @Test
    fun `an int value in a hole is not within the domains`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 5).excludeValue(3)),
            factors = arrayOf<Factor>(),
        )
        val state = LocalSearchState(problem.bake(), Random(1))

        state.assignment.setInt(0, 3)

        assertFalse(state.intValuesInDomain())
    }

    private fun foldedProblem() = Problem(
        numBoolVars = 0,
        numIntVars = 1,
        intDomains = arrayOf(IntDomain(0, 10)),
        factors = arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 3)),
    ).bake()

    @Test
    fun `the search seeds its root domains from the projection's fold rather than the declared box`() {
        val state = LocalSearchState(foldedProblem(), Random(1))

        assertEquals(3L, state.rootDomains[0].max, "search must not propose values the root bake ruled out")
    }

    @Test
    fun `re-summing the real rows reconciles a drifted sum`() {
        val row = Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.LE, 1.0)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(row),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(10.0),
        )
        val state = LocalSearchState(problem.bake(), Random(0))
        state.restart()
        state.doublePayload[0] = 5.0
        state.apply(Move.RealSet(0, 0.5))
        assertTrue(state.cost > 0L, "the drifted sum reads as violated")

        state.refreshRealRows()

        assertEquals(0L, state.cost)
    }
}
