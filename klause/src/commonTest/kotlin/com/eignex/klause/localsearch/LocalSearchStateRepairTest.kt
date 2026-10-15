package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.factor.table.Element
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LocalSearchStateRepairTest {
    @Test
    fun `root repairs should preserve multiple selected Element requirements`() {
        for (definedResults in listOf(false, true)) {
            val problem = Problem(
                0,
                9,
                arrayOf(
                    IntDomain(0, 1), IntDomain(2, 3), IntDomain(1, 1), IntDomain(1, 1),
                    IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 0),
                ),
                arrayOf<Factor>(
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

            state.moveSink.addChannelingIntSet(state, 0, 1)
            val coordinated = state.moveSink.list.filterIsInstance<Move.Compound>().single()
            val predicted = state.netDelta(coordinated)
            state.apply(coordinated)

            assertEquals(-1L, predicted)
            assertEquals(1L, state.assignment.intValue(5))
            assertEquals(1L, state.assignment.intValue(7))
            state.recompute()
            assertEquals(0L, state.cost)
        }
    }

    @Test
    fun `joint root repairs should select cells from the complete propagated move`() {
        val problem = Problem(
            0,
            11,
            arrayOf(
                IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 2), IntDomain(1, 1), IntDomain(1, 1),
                IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 0),
            ),
            arrayOf<Factor>(
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
    fun `Element-preserving proposals should respect protected roots and cells`() {
        for (protection in listOf("cell-pin", "cell-owner", "cell-domain", "cell-definition", "root-pin")) {
            val factors = arrayOf<Factor>(
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, -2),
                Element(0, 2, longArrayOf(4, 5), true, 0),
                Element(1, 3, longArrayOf(6, 7), true, 2),
                Cumulative(intArrayOf(0, 8), longArrayOf(1, 1), longArrayOf(1, 1), 1),
            ) + if (protection == "cell-definition") arrayOf<Factor>(Product(7, 7, 5)) else emptyArray()
            val problem = Problem(
                0,
                9,
                arrayOf(
                    IntDomain(0, 1), IntDomain(2, 3), IntDomain(1, 1), IntDomain(1, 1),
                    IntDomain(0, 1), IntDomain(0, if (protection == "cell-domain") 0 else 1),
                    IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 0),
                ),
                factors,
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
    fun `Element-preserving proposals should reject conflicting shared-cell targets`() {
        val problem = Problem(
            0,
            8,
            arrayOf(
                IntDomain(0, 1),
                IntDomain(1, 2),
                IntDomain(1, 1),
                IntDomain(0, 0),
                IntDomain(0, 1),
                IntDomain(0, 1),
                IntDomain(0, 1),
                IntDomain(0, 0),
            ),
            arrayOf<Factor>(
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
    fun `unrelated primitive repair scores should preserve weights and committed state`() {
        for (coordinate in listOf("boolean", "integer", "real")) {
            for (allocated in listOf(false, true)) {
                val problem = Problem(
                    1,
                    3,
                    arrayOf(IntDomain(0, 0), IntDomain(0, 0), IntDomain(0, 3)),
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
                val weightedBefore = weightedCost(state, allocated)
                val move = when (coordinate) {
                    "boolean" -> Move.BoolFlip(0)
                    "integer" -> Move.IntSet(2, 1L)
                    else -> Move.RealSet(0, 1.0)
                }

                val raw = state.repairNetDelta(move, weighted = false)
                val weighted = state.repairNetDelta(move, weighted = true)

                assertEquals(before, state.assignment.snapshot(), coordinate)
                assertEquals(cost, state.cost, coordinate)
                assertEquals(allocated, state.weights.allocated, coordinate)
                state.apply(move)
                state.recompute()
                assertEquals((state.cost - cost).toDouble(), raw, coordinate)
                assertEquals(weightedCost(state, allocated) - weightedBefore, weighted, coordinate)
                assertEquals(overload, state.intPayload[0], coordinate)
            }
        }
    }

    private fun weightedCost(state: LocalSearchState, allocated: Boolean): Double = if (allocated) {
        state.factorDegree.indices.sumOf { state.weights.factorWeights[it] * state.factorDegree[it] }
    } else {
        state.cost.toDouble()
    }

    @Test
    fun `presence repair scores should retain cumulative plateau refinement`() {
        val problem = Problem(
            2,
            2,
            arrayOf(IntDomain(0, 0), IntDomain(0, 0)),
            arrayOf<Factor>(
                Cumulative(
                    intArrayOf(0, 1),
                    longArrayOf(1, 1),
                    longArrayOf(1, 83),
                    1,
                    presents = intArrayOf(Lit.make(0, true), Lit.make(1, true)),
                ),
            ),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        state.assignment.setBool(0, true)
        state.assignment.setBool(1, true)
        state.recompute()
        state.weights.factorWeights[0] = 3.5
        val before = state.assignment.snapshot()
        val cost = state.cost
        val move = Move.BoolFlip(0)

        assertEquals(0L, state.netDelta(move))
        assertEquals(-1.0 / 64.0, state.repairNetDelta(move, weighted = false))
        assertEquals(-3.5 / 64.0, state.repairNetDelta(move, weighted = true))
        assertEquals(before, state.assignment.snapshot())
        state.apply(move)
        state.recompute()

        assertEquals(cost, state.cost)
        assertEquals(82, state.intPayload[0])
    }

    @Test
    fun `cumulative repair scores should distinguish overload reductions within a degree bucket`() {
        for (coordinate in listOf("start", "duration", "resource", "capacity")) {
            val duration = coordinate == "duration"
            val resource = coordinate == "resource"
            val capacity = coordinate == "capacity"
            val secondAux = if (duration) {
                84L
            } else if (resource) {
                42L
            } else {
                0L
            }
            val domains = arrayOf(
                IntDomain(0, if (coordinate == "start") 1 else 0),
                IntDomain(0, 0),
                when (coordinate) {
                    "duration" -> IntDomain(83, 84)
                    "resource" -> IntDomain(41, 42)
                    "capacity" -> IntDomain(1, 2)
                    else -> IntDomain(0, 0)
                },
                IntDomain(secondAux, secondAux),
            )
            val problem = Problem(
                0,
                4,
                domains,
                arrayOf<Factor>(
                    Cumulative(
                        intArrayOf(0, 1),
                        LongArray(2) { if (duration) 84 else 1 },
                        LongArray(2) { if (duration) 1 else 42 },
                        1,
                        durationVars = if (duration) intArrayOf(2, 3) else intArrayOf(),
                        resourceVars = if (resource) intArrayOf(2, 3) else intArrayOf(),
                        capacityVar = if (capacity) 2 else -1,
                    ),
                ),
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

            assertEquals(0L, state.netDelta(move), coordinate)
            assertEquals(0.0, state.weightedNetDelta(move), coordinate)
            assertEquals(-1.0 / 64.0, state.repairNetDelta(move, weighted = false), coordinate)
            assertEquals(-3.5 / 64.0, state.repairNetDelta(move, weighted = true), coordinate)
            assertEquals(before, state.assignment.snapshot(), coordinate)
            state.apply(move)
            state.recompute()
            assertEquals(cost, state.cost, coordinate)
            assertEquals(overload - 1, state.intPayload[0], coordinate)
        }
    }

    @Test
    fun `cumulative repair scores should grade the complete propagated compound`() {
        val problem = Problem(
            0,
            7,
            arrayOf(
                IntDomain(83, 84),
                IntDomain(83, 84),
                IntDomain(83, 84),
                IntDomain(83, 84),
                IntDomain(0, 0),
                IntDomain(0, 0),
                IntDomain(1, 1),
            ),
            arrayOf<Factor>(
                Product(0, 6, 2),
                Product(1, 6, 3),
                Cumulative(
                    intArrayOf(4, 5),
                    longArrayOf(84, 84),
                    longArrayOf(1, 1),
                    1,
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
        }
        state.apply(compound)
        state.recompute()

        assertEquals(83L, state.assignment.intValue(3))
        assertEquals(83, state.intPayload[2])
        assertEquals(cost, state.cost)
    }

    @Test
    fun `cumulative repair scores should not reward crossing payload saturation`() {
        val demand = Int.MAX_VALUE.toLong()
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 0), IntDomain(demand, demand + 32)),
            arrayOf<Factor>(
                Cumulative(
                    intArrayOf(0),
                    longArrayOf(1),
                    longArrayOf(demand),
                    1,
                    resourceVars = intArrayOf(1),
                ),
            ),
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
    fun `reified inequality repairs should cross holes with pinned indicators`() {
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
                        1,
                        1,
                        arrayOf(IntDomain(0, 3).excludeValue(1).excludeValue(2)),
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
    fun `reified toggle repairs should round holes toward the flipped predicate`() {
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
                        1,
                        1,
                        arrayOf(domain),
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
    fun `reified repairs should retain valid compounds alongside hole alternatives`() {
        val domain = assertNotNull(
            IntDomain(0, 12).excludeValues(longArrayOf(1, 2, 4, 5, 6, 7, 8, 10, 11)),
        )
        val problem = Problem(
            1,
            2,
            arrayOf(domain, domain),
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
            state.apply(move)

            assertEquals(-before, predicted)
            assertEquals(0L, state.cost)
        }
    }

    @Test
    fun `reified degree updates and scores should agree with recompute across moves`() {
        for (op in LinearOp.entries) {
            val problem = Problem(
                1,
                1,
                arrayOf(IntDomain(0, 10)),
                arrayOf<Factor>(ReifiedLinear(0, intArrayOf(2), intArrayOf(0), op, 6)),
            )
            val state = LocalSearchState(problem.bake(), Random(1))
            state.violationSoftCap = 4
            state.assignment.setBool(0, true)
            state.recompute()
            val moves = listOf(
                Move.BoolFlip(0),
                Move.IntSet(0, 3),
                Move.BoolFlip(0),
                Move.IntSet(0, 10),
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
}
