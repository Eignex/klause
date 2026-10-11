package com.eignex.klause.factor.table

import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ElementInvariantTest {
    @Test
    fun `grading probes retain the next matching index scan window`() {
        val cells = LongArray(65) { if (it == 1 || it == 40 || it == 64) 1L else 0L }
        val problem = Problem(
            0, 3, arrayOf(IntDomain(0, 64), IntDomain(0, 64), IntDomain(1, 1)),
            arrayOf<Factor>(
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0),
                Element(1, 2, cells, false, 0),
            ),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        state.invariants = assertNotNull(DefinitionalSweep.infer(problem.factors, 3, intArrayOf(1))).network(3, 0)
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 0)
        state.assignment.setInt(2, 1)
        state.recompute()
        val before = state.assignment.snapshot()
        val cost = state.cost

        state.factors[1].proposeRepairMoves(state, 1, state.moveSink)
        val move = state.moveSink.list.single()
        val firstDelta = state.netDelta(move)
        val secondDelta = state.netDelta(move)
        state.moveSink.clear()
        state.factors[1].proposeRepairMoves(state, 1, state.moveSink)
        val nextWindow = state.moveSink.list.filterIsInstance<Move.IntSet>().map { it.newValue }

        assertEquals(-cost, firstDelta)
        assertEquals(firstDelta, secondDelta)
        assertEquals(before, state.assignment.snapshot())
        assertEquals(cost, state.cost)
        assertEquals(listOf(1L, 40L), nextWindow)
    }

    @Test
    fun `failed affine inverses consume the extra matching index allowance`() {
        val cells = LongArray(9) { if (it == 2 || it == 3 || it == 5 || it == 7 || it == 8) 9L else 1L }
        val problem = Problem(
            0, 3, arrayOf(IntDomain(0, 4), IntDomain(0, 8), IntDomain(9, 9)),
            arrayOf<Factor>(
                Linear(intArrayOf(2, -1), intArrayOf(0, 1), LinearOp.EQ, 0),
                Element(1, 2, cells, false, 0),
            ),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        state.invariants = assertNotNull(DefinitionalSweep.infer(problem.factors, 3, intArrayOf(1))).network(3, 0)
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 0)
        state.assignment.setInt(2, 9)
        state.recompute()

        state.factors[1].proposeRepairMoves(state, 1, state.moveSink)
        val first = state.moveSink.list.filterIsInstance<Move.IntSet>().map { it.newValue }
        state.moveSink.clear()
        state.factors[1].proposeRepairMoves(state, 1, state.moveSink)
        val next = state.moveSink.list.filterIsInstance<Move.IntSet>().map { it.newValue }

        assertEquals(listOf(1L), first)
        assertEquals(listOf(1L, 4L), next)
    }

    @Test
    fun `alternative matching indexes retain the first repair and grade every constraint`() {
        for (offset in listOf(Int.MAX_VALUE, Int.MIN_VALUE, -2)) {
            val start = offset.toLong()
            val problem = Problem(
                0, 2, arrayOf(IntDomain(start, start + 3).excludeValue(start + 2), IntDomain(1, 1)),
                arrayOf<Factor>(
                    Element(0, 1, longArrayOf(0, 1, 1, 1), false, offset),
                    Linear(longArrayOf(1), intArrayOf(0), LinearOp.GE, start + 3),
                ),
            )
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
            state.assignment.setInt(0, start)
            state.assignment.setInt(1, 1)
            state.recompute()
            val before = state.cost

            state.factors[0].proposeRepairMoves(state, 0, state.moveSink)
            val moves = state.moveSink.list.filterIsInstance<Move.IntSet>()
            val move = moves.single { it.newValue == start + 3 }
            val predicted = state.netDelta(move)
            state.apply(move)

            assertEquals(start + 1, moves.first().newValue)
            assertTrue(moves.none { it.newValue == start + 2 })
            assertEquals(start + 3, state.assignment.intValue(0))
            assertEquals(-before, predicted)
            assertEquals(0L, state.cost)
            state.recompute()
            assertEquals(0L, state.cost)
        }
    }

    @Test
    fun `matching index scan windows advance independently in shared projections`() {
        val cells = LongArray(65) { if (it == 1 || it == 40 || it == 64) 1L else 0L }
        val problem = Problem(
            0, 2, arrayOf(IntDomain(0, 64), IntDomain(1, 1)),
            arrayOf<Factor>(Element(0, 1, cells, false, 0)),
        )
        val first = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        val second = LocalSearchState(first.model, Random(3), projection = first.projection)
        for (state in listOf(first, second)) {
            state.assignment.setInt(0, 0)
            state.assignment.setInt(1, 1)
            state.recompute()
        }

        first.factors[0].proposeRepairMoves(first, 0, first.moveSink)
        val firstWindow = first.moveSink.list.filterIsInstance<Move.IntSet>().map { it.newValue }
        first.moveSink.clear()
        first.recompute()
        first.factors[0].proposeRepairMoves(first, 0, first.moveSink)
        val nextWindow = first.moveSink.list.filterIsInstance<Move.IntSet>().map { it.newValue }
        second.factors[0].proposeRepairMoves(second, 0, second.moveSink)
        val otherWindow = second.moveSink.list.filterIsInstance<Move.IntSet>().map { it.newValue }

        assertEquals(listOf(1L), firstWindow)
        assertEquals(listOf(1L, 40L), nextWindow)
        assertEquals(firstWindow, otherWindow)
        assertEquals(Random(3).nextLong(), first.rng.nextLong())
        assertEquals(Random(3).nextLong(), second.rng.nextLong())
    }

    @Test
    fun `matching index proposals add at most two further targets`() {
        val cells = LongArray(65) { if (it == 0) 0L else 1L }
        val problem = Problem(
            0, 2, arrayOf(IntDomain(0, 64), IntDomain(1, 1)),
            arrayOf<Factor>(Element(0, 1, cells, false, 0)),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 1)
        state.recompute()

        state.factors[0].proposeRepairMoves(state, 0, state.moveSink)
        val first = state.moveSink.list.filterIsInstance<Move.IntSet>().map { it.newValue }
        state.moveSink.clear()
        state.factors[0].proposeRepairMoves(state, 0, state.moveSink)
        val next = state.moveSink.list.filterIsInstance<Move.IntSet>().map { it.newValue }

        assertEquals(listOf(1L, 2L, 3L), first)
        assertEquals(listOf(1L, 4L, 5L), next)
    }

    @Test
    fun `alternative affine index repairs retain protected root coordinates`() {
        for (protection in listOf("pin", "owner", "definition")) {
            val problem = Problem(
                0, 5, arrayOf(
                    IntDomain(0, 1), IntDomain(0, 2), IntDomain(0, 5), IntDomain(9, 9), IntDomain(0, 0),
                ),
                arrayOf<Factor>(
                    Linear(intArrayOf(3, 1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 0),
                    Element(2, 3, longArrayOf(1, 9, 9, 1, 9, 9), false, 0),
                    Product(4, 4, 0),
                ),
            )
            val assumptions = if (protection == "pin") Assumptions.None.withInt(0, 0) else Assumptions.None
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3), assumptions)
            val factors = if (protection == "definition") problem.factors else problem.factors.copyOfRange(0, 2)
            state.invariants = assertNotNull(DefinitionalSweep.infer(factors, 5, intArrayOf(2))).network(5, 0)
            if (protection == "owner") state.moveSink.setOwners(intArrayOf(7, -1, -1, -1, -1))
            for ((variable, value) in longArrayOf(0, 0, 0, 9, 0).withIndex()) {
                state.assignment.setInt(variable, value)
            }
            state.recompute()
            val before = state.cost

            state.factors[1].proposeRepairMoves(state, 1, state.moveSink)
            val move = state.moveSink.list.filterIsInstance<Move.IntSet>().single { it.varId == 1 && it.newValue == 2L }
            val predicted = state.netDelta(move)
            state.apply(move)

            assertEquals(0L, state.assignment.intValue(0))
            assertEquals(2L, state.assignment.intValue(1))
            assertEquals(2L, state.assignment.intValue(2))
            assertEquals(-before, predicted)
            assertEquals(0L, state.cost)
            state.recompute()
            assertEquals(0L, state.cost)
        }
    }

    @Test
    fun `joint index repairs carry indicator channels into product outputs`() {
        for (bound in listOf(0, 1)) {
            val factors = arrayOf<Factor>(
                Linear(intArrayOf(2, 1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 0),
                Element(2, 3, longArrayOf(1, 1, 1, 9), arrIsVars = false, indexOffset = 0),
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
                ReifiedLinear(0, intArrayOf(1), intArrayOf(4), LinearOp.EQ, bound),
                Product(4, 6, 5),
            )
            val problem = Problem(
                1, 7, arrayOf(
                    IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 3), IntDomain(9, 9),
                    IntDomain(0, 1), IntDomain(0, 3), IntDomain(3, 3),
                ),
                factors,
            )
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
            state.assignment.setInt(0, 0)
            state.assignment.setInt(1, 0)
            state.assignment.setInt(2, 0)
            state.assignment.setInt(3, 9)
            state.assignment.setInt(4, 1L - bound)
            state.assignment.setInt(5, 3L * (1L - bound))
            state.assignment.setInt(6, 3)
            state.assignment.setBool(0, false)
            state.invariants = assertNotNull(DefinitionalSweep.infer(factors, 7, intArrayOf(2))).network(7, 1)
            state.recompute()
            val before = state.cost

            state.factors[1].proposeRepairMoves(state, 1, state.moveSink)
            val move = state.moveSink.list.single()
            val predicted = state.netDelta(move)
            state.apply(move)

            assertEquals(3L, state.assignment.intValue(2))
            assertEquals(bound.toLong(), state.assignment.intValue(4))
            assertEquals(3L * bound, state.assignment.intValue(5))
            assertEquals(-before, predicted)
            assertEquals(0L, state.cost)
            state.recompute()
            assertEquals(0L, state.cost)
        }
    }

    @Test
    fun `index repairs preserve requested coordinates against binary channels`() {
        for ((target, bound) in listOf(2 to 1, 3 to 0)) {
            val cells = LongArray(4) { if (it == target) 9L else 1L }
            val factors = arrayOf<Factor>(
                Linear(intArrayOf(2, 1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 0),
                Element(2, 3, cells, arrIsVars = false, indexOffset = 0),
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
                ReifiedLinear(0, intArrayOf(1), intArrayOf(1), LinearOp.EQ, bound),
            )
            val problem = Problem(
                1, 4, arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 3), IntDomain(9, 9)),
                factors,
            )
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
            state.assignment.setInt(0, 0)
            state.assignment.setInt(1, 0)
            state.assignment.setInt(2, 0)
            state.assignment.setInt(3, 9)
            state.assignment.setBool(0, false)
            state.invariants = assertNotNull(DefinitionalSweep.infer(factors, 4, intArrayOf(2))).network(4, 1)
            state.recompute()

            state.factors[1].proposeRepairMoves(state, 1, state.moveSink)
            state.apply(state.moveSink.list.single())

            assertEquals(target.toLong(), state.assignment.intValue(2))
            assertEquals((target % 2).toLong(), state.assignment.intValue(1))
            assertEquals(0, state.factorDegree[1])
            assertEquals(1L, state.cost)
        }
    }

    @Test
    fun `index repair rejects conflicting targets for a shared coordinate`() {
        val factors = arrayOf<Factor>(
            Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0),
            Linear(intArrayOf(1, -1), intArrayOf(0, 2), LinearOp.EQ, 0),
            Linear(intArrayOf(2, 1, -1), intArrayOf(1, 2, 3), LinearOp.EQ, 0),
            Element(3, 4, longArrayOf(1, 9, 1, 1), arrIsVars = false, indexOffset = 0),
        )
        val problem = Problem(
            0, 5,
            arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 3), IntDomain(9, 9)),
            factors,
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
        state.assignment.setInt(4, 9)
        state.invariants = assertNotNull(DefinitionalSweep.infer(factors, 5, intArrayOf(1, 2, 3))).network(5, 0)
        state.recompute()

        state.factors[3].proposeRepairMoves(state, 3, state.moveSink)

        assertTrue(state.moveSink.list.isEmpty())
    }

    @Test
    fun `affine index repairs reach matching cells through coordinate moves`() {
        for ((rowOffset, colOffset) in listOf(0L to 0L, -3L to 5L, Int.MAX_VALUE.toLong() to 0L)) {
            val factors = arrayOf<Factor>(
                Linear(longArrayOf(2, 1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 2 * rowOffset + colOffset),
                Element(2, 3, longArrayOf(1, 1, 1, 9), arrIsVars = false, indexOffset = 0),
                ReifiedLinear(0, longArrayOf(1), intArrayOf(0), LinearOp.EQ, rowOffset + 1),
            )
            val problem = Problem(
                1, 4,
                arrayOf(
                    IntDomain(rowOffset, rowOffset + 1), IntDomain(colOffset, colOffset + 1),
                    IntDomain(0, 3), IntDomain(9, 9),
                ),
                factors,
            )
            val state = LocalSearchState(problem.bake(), Random(0))
            state.assignment.setInt(0, rowOffset)
            state.assignment.setInt(1, colOffset)
            state.assignment.setInt(2, 0)
            state.assignment.setInt(3, 9)
            state.assignment.setBool(0, false)
            state.invariants = assertNotNull(DefinitionalSweep.infer(factors, 4, intArrayOf(2))).network(4, 1)
            state.recompute()

            state.factors[1].proposeRepairMoves(state, 1, state.moveSink)
            state.apply(state.moveSink.list.single())

            assertEquals(0L, state.cost)
        }
    }

    @Test
    fun `index repairs skip matching cells that require a pinned or owned coordinate`() {
        for (pinned in listOf(true, false)) {
            val factors = arrayOf<Factor>(
                Linear(intArrayOf(2, 1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 0),
                Element(2, 3, longArrayOf(9, 1, 1, 9), arrIsVars = false, indexOffset = 0),
            )
            val problem = Problem(
                0, 4, arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 3), IntDomain(9, 9)), factors,
            )
            val assumptions = if (pinned) Assumptions(ints = mapOf(0 to 1L)) else Assumptions.None
            val state = LocalSearchState(problem.bake(), Random(0), assumptions)
            state.assignment.setInt(0, 1)
            state.assignment.setInt(1, 0)
            state.assignment.setInt(2, 2)
            state.assignment.setInt(3, 9)
            state.invariants = assertNotNull(DefinitionalSweep.infer(factors, 4, intArrayOf(2))).network(4, 0)
            if (!pinned) state.moveSink.setOwners(intArrayOf(7, -1, -1, -1))
            state.recompute()

            state.factors[1].proposeRepairMoves(state, 1, state.moveSink)
            state.apply(state.moveSink.list.single())

            assertEquals(1L, state.assignment.intValue(0))
            assertEquals(0L, state.cost)
        }
    }

    @Test
    fun `an affine index repair cannot select a fractional input value`() {
        val factors = arrayOf<Factor>(
            Linear(intArrayOf(2, -1), intArrayOf(0, 1), LinearOp.EQ, 0),
            Element(1, 2, longArrayOf(1, 1, 1, 9), arrIsVars = false, indexOffset = 0),
        )
        val problem = Problem(0, 3, arrayOf(IntDomain(0, 1), IntDomain(0, 3), IntDomain(9, 9)), factors)
        val state = LocalSearchState(problem.bake(), Random(0))
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 0)
        state.assignment.setInt(2, 9)
        state.invariants = assertNotNull(DefinitionalSweep.infer(factors, 3, intArrayOf(1))).network(3, 0)
        state.recompute()

        state.factors[1].proposeRepairMoves(state, 1, state.moveSink)

        assertTrue(state.moveSink.list.isEmpty())
    }

    @Test
    fun `an overflowing affine inverse does not produce an input move`() {
        val factors = arrayOf<Factor>(
            Linear(longArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, Long.MAX_VALUE),
            Element(1, 2, longArrayOf(1, 9), arrIsVars = false, indexOffset = 0),
        )
        val problem = Problem(0, 3, arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(9, 9)), factors)
        val state = LocalSearchState(problem.bake(), Random(0))
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 0)
        state.assignment.setInt(2, 9)
        state.invariants = assertNotNull(DefinitionalSweep.infer(factors, 3, intArrayOf(1))).network(3, 0)
        state.recompute()

        state.factors[1].proposeRepairMoves(state, 1, state.moveSink)

        assertTrue(state.moveSink.list.isEmpty())
    }

    @Test
    fun `violated when index is out of range`() {
        // arr has 3 elements (indices 0..2); idx=-1 is below indexOffset=0.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(-1, 5), IntDomain(0, 30)),
            factors = arrayOf<Factor>(
                Element(idx = 0, result = 1, arr = longArrayOf(10, 20, 30), arrIsVars = false, indexOffset = 0),
            ),
        )
        val state = LocalSearchState(problem.bake(), Random(0))
        state.assignment.setInt(0, -1)
        state.assignment.setInt(1, 10)
        state.recompute()
        assertTrue(state.factors[0].isViolated(state, 0))
    }

    @Test
    fun `delta predicts improvement when result is set to matching value`() {
        // Violated (result=10 ≠ arr[1]=20); setting result=20 should yield delta < 0.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 30)),
            factors = arrayOf<Factor>(
                Element(idx = 0, result = 1, arr = longArrayOf(10, 20, 30), arrIsVars = false, indexOffset = 0),
            ),
        )
        val state = LocalSearchState(problem.bake(), Random(0))
        state.assignment.setInt(0, 1)
        state.assignment.setInt(1, 10)
        state.recompute()
        assertTrue(state.factors[0].isViolated(state, 0))
        val delta = state.factors[0].deltaIfIntSet(state, 0, intVar = 1, newValue = 20)
        assertTrue(delta < 0, "setting result=20 should reduce violation; delta=$delta")
    }

    @Test
    fun `matching cell repair preserves large and negative offsets`() {
        for (offset in listOf(Int.MAX_VALUE, Int.MIN_VALUE, -2)) {
            val start = offset.toLong()
            val problem = Problem(
                0,
                2,
                arrayOf(IntDomain(start, start + 1), IntDomain(10, 20)),
                arrayOf<Factor>(Element(0, 1, longArrayOf(10, 20), false, offset)),
            )
            val state = LocalSearchState(problem.bake(), Random(0))
            state.assignment.setInt(0, start)
            state.assignment.setInt(1, 20)
            state.recompute()
            state.factors[0].proposeRepairMoves(state, 0, state.moveSink)
            assertTrue(state.moveSink.list.any { it is Move.IntSet && it.varId == 0 && it.newValue == start + 1 })
        }
    }

    @Test
    fun `out of range repair snaps to Long endpoints on both sides`() {
        for (offset in listOf(Int.MAX_VALUE, Int.MIN_VALUE, -2)) {
            val start = offset.toLong()
            for (index in listOf(start - 1, start + 2)) {
                val problem = Problem(
                    0,
                    2,
                    arrayOf(IntDomain(start - 1, start + 2), IntDomain(0, 30)),
                    arrayOf<Factor>(Element(0, 1, longArrayOf(10, 20), false, offset)),
                )
                val state = LocalSearchState(problem.bake(), Random(0))
                state.assignment.setInt(0, index)
                state.assignment.setInt(1, 30)
                state.recompute()
                state.factors[0].proposeRepairMoves(state, 0, state.moveSink)
                val repair = state.moveSink.list.single() as Move.IntSet
                assertEquals(if (index < start) start else start + 1, repair.newValue)
            }
        }
    }

    @Test
    fun `matching cell repairs respect domains and index pins`() {
        val start = Int.MAX_VALUE.toLong()
        for (pins in listOf(Assumptions.None, Assumptions(ints = mapOf(0 to start)))) {
            val domain = if (pins.isEmpty) {
                IntDomain(start, start + 2).excludeValue(start + 1)
            } else {
                IntDomain(start, start + 2)
            }
            val problem = Problem(
                0,
                2,
                arrayOf(domain, IntDomain(10, 20)),
                arrayOf<Factor>(Element(0, 1, longArrayOf(10, 20, 10), false, Int.MAX_VALUE)),
            )
            val state = LocalSearchState(problem.bake(), Random(0), pins)
            state.assignment.setInt(0, start)
            state.assignment.setInt(1, 20)
            state.recompute()
            state.factors[0].proposeRepairMoves(state, 0, state.moveSink)
            assertTrue(state.moveSink.list.none { it is Move.IntSet && it.varId == 0 })
        }
    }

    @Test
    fun `index repairs backsolve affine aliases over retained extrema`() {
        val problem = Problem(
            0, 5,
            arrayOf(IntDomain(0, 2), IntDomain(2, 2), IntDomain(0, 2), IntDomain(0, 4), IntDomain(9, 9)),
            arrayOf<Factor>(
                ArrayMinMax(2, intArrayOf(0, 1), false),
                Linear(intArrayOf(2, -1), intArrayOf(2, 3), LinearOp.EQ, 0),
                Element(3, 4, longArrayOf(1, 1, 9, 1, 1), false, 0),
            ),
        )
        val sweep = assertNotNull(DefinitionalSweep.infer(problem.factors, 5, intArrayOf(2, 3)))
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
        state.invariants = sweep.network(5, 0)
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 2)
        state.assignment.setInt(4, 9)
        sweep.sweep(state.assignment, state.rootDomains)
        state.recompute()
        val before = state.cost

        state.factors[2].proposeRepairMoves(state, 2, state.moveSink)
        val move = state.moveSink.list.single()
        val predicted = state.netDelta(move)
        state.apply(move)

        assertEquals(1L, state.assignment.intValue(0))
        assertEquals(1L, state.assignment.intValue(2))
        assertEquals(2L, state.assignment.intValue(3))
        assertEquals(-before, predicted)
        assertEquals(0L, state.cost)
        state.recompute()
        assertEquals(0L, state.cost)
    }

    @Test
    fun `index repairs skip unreachable matching cells in a retained extremum cone`() {
        val problem = Problem(
            0,
            5,
            arrayOf(IntDomain(0, 2), IntDomain(0, 4), IntDomain(0, 4), IntDomain(4, 4), IntDomain(10, 20)),
            arrayOf<Factor>(
                Linear(intArrayOf(2, -1), intArrayOf(0, 1), LinearOp.EQ, 0),
                ArrayMinMax(2, intArrayOf(1, 3), false),
                Element(2, 4, longArrayOf(10, 20, 20, 10, 10), false, 0),
            ),
        )
        val sweep = assertNotNull(DefinitionalSweep.infer(problem.factors, 5, intArrayOf(1, 2)))
        val state = LocalSearchState(problem.bake(), Random(0), Assumptions(ints = mapOf(4 to 20L)))
        state.invariants = sweep.network(5, 0)
        state.assignment.setInt(0, 0L)
        state.assignment.setInt(3, 4L)
        state.assignment.setInt(4, 20L)
        sweep.sweep(state.assignment, state.rootDomains)
        state.recompute()

        state.factors[2].proposeRepairMoves(state, 2, state.moveSink)
        state.apply(state.moveSink.list.single())

        assertEquals(1L, state.assignment.intValue(0))
        assertEquals(2L, state.assignment.intValue(2))
        assertEquals(0L, state.cost)
    }
}
