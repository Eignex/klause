package com.eignex.klause.factor.bool

import com.eignex.klause.factor.arithmetic.ReifiedPseudoBoolean
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.model.PbOp
import com.eignex.klause.propagation.bake
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.parseBigInt
import com.eignex.klause.util.plus
import com.eignex.klause.util.unaryMinus
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PseudoBooleanInvariantTest {

    @Test
    fun `structured swaps should preserve weighted sums across literal polarities`() {
        for (firstPositive in listOf(false, true)) for (secondPositive in listOf(false, true)) {
            val factor = PseudoBoolean(longArrayOf(2, 2, 3, 5, 7), intArrayOf(
                Lit.make(0, firstPositive), Lit.make(1, secondPositive),
                Lit.make(2, true), Lit.make(3, true), Lit.make(4, true),
            ), PbOp.EQ, 7)
            val problem = Problem(5, 0, emptyArray(), listOf(factor))
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
            state.assignment.setBool(0, firstPositive)
            state.assignment.setBool(1, !secondPositive)
            state.assignment.setBool(2, false)
            state.assignment.setBool(3, true)
            state.assignment.setBool(4, false)
            state.recompute()
            val sink = MoveSink()

            state.factors[0].proposeStructuredMoves(state, 0, sink)
            state.apply(sink.list.single())

            assertEquals(listOf(!firstPositive, secondPositive, false, true, false),
                (0..4).map { state.assignment.boolValue(it) })
            assertEquals(0L, state.cost)
        }
    }

    @Test
    fun `structured swaps should combine repeated literal contributions`() {
        for (complementary in listOf(false, true)) {
            val weights = if (complementary) longArrayOf(4, 1, 4, 3, 7) else longArrayOf(2, 3, 2, 5, 7)
            val factor = PseudoBoolean(weights, intArrayOf(
                Lit.make(0, true), Lit.make(0, !complementary), Lit.make(1, true),
                Lit.make(2, true), Lit.make(3, true),
            ), PbOp.EQ, if (complementary) 4L else 5L)
            val problem = Problem(4, 0, emptyArray(), listOf(factor))
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
            for (v in 0..3) state.assignment.setBool(v, v == 0)
            state.recompute()
            val sink = MoveSink()

            state.factors[0].proposeStructuredMoves(state, 0, sink)
            state.apply(sink.list.single())

            assertEquals(listOf(false, false, true, false), (0..3).map { state.assignment.boolValue(it) })
            assertEquals(0L, state.cost)
        }
    }

    @Test
    fun `repair offers should use aggregate complementary contributions`() {
        for (op in listOf(PbOp.LE, PbOp.GE, PbOp.EQ)) {
            val factor = PseudoBoolean(longArrayOf(4, 1, 3, 7), intArrayOf(
                Lit.make(0, true), Lit.make(0, false), Lit.make(1, true), Lit.make(2, true),
            ), op, if (op == PbOp.LE) 3L else 6L)
            val problem = Problem(3, 0, emptyArray(), listOf(factor))
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
            state.assignment.setBool(0, op != PbOp.LE)
            state.assignment.setBool(1, op == PbOp.LE)
            state.assignment.setBool(2, false)
            state.recompute()
            val sink = MoveSink()

            state.factors[0].proposeRepairMoves(state, 0, sink)

            val proposed = sink.list.filterIsInstance<Move.BoolFlip>().map { it.varId }.toSet()
            assertEquals(if (op == PbOp.GE) setOf(1, 2) else setOf(1), proposed)
            assertTrue(sink.list.all { state.netDelta(it) <= 0L })
        }
    }

    @Test
    fun `slack offers should preserve rows with complementary occurrences`() {
        for (op in listOf(PbOp.LE, PbOp.GE)) {
            val factor = PseudoBoolean(longArrayOf(4, 1, 3, 7), intArrayOf(
                Lit.make(0, true), Lit.make(0, false), Lit.make(1, true), Lit.make(2, true),
            ), op, if (op == PbOp.LE) 2L else 3L)
            val problem = Problem(3, 0, emptyArray(), listOf(factor))
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
            state.assignment.setBool(0, op == PbOp.GE)
            state.assignment.setBool(1, false)
            state.assignment.setBool(2, false)
            state.recompute()
            val sink = MoveSink()

            state.factors[0].proposeStructuredMoves(state, 0, sink)

            assertEquals(op == PbOp.LE, sink.list.isEmpty())
            assertTrue(sink.list.all { state.netDelta(it) == 0L })
        }
    }

    @Test
    fun `wide repeated and complementary literals should retain exact move costs`() {
        val wide = parseBigInt("18446744073709551616")
        val weights = arrayOf(wide, -wide, bigIntOf(3))
        val literals = intArrayOf(Lit.make(0, true), Lit.make(0, false), Lit.make(1, true))
        val factor = PseudoBoolean(weights, literals, PbOp.EQ, wide)
        val problem = Problem(2, 0, emptyArray(), arrayOf(factor))
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
        for (mask in 0 until 4) for (variable in 0..1) {
            for (v in 0..1) state.assignment.setBool(v, mask and (1 shl v) != 0)
            state.recompute()
            var sourceSum = BIG_ZERO
            for (k in literals.indices) {
                if (Lit.evaluate(literals[k], state.assignment.boolValue(Lit.variable(literals[k])))) {
                    sourceSum += weights[k]
                }
            }
            assertEquals(sourceSum == wide, state.cost == 0L)
            val before = state.cost
            val predicted = state.netDelta(Move.BoolFlip(variable))

            state.apply(Move.BoolFlip(variable))

            assertEquals(predicted, state.cost - before)
        }
    }

    @Test
    fun `wide reified body checks should read the exact source assignment`() {
        val wide = parseBigInt("18446744073709551616")
        val factor = ReifiedPseudoBoolean(0, arrayOf(wide), intArrayOf(Lit.make(1, false)), PbOp.EQ, wide)
        val problem = Problem(2, 0, emptyArray(), arrayOf(factor))
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
        for (indicator in listOf(false, true)) for (body in listOf(false, true)) {
            state.assignment.setBool(0, indicator)
            state.assignment.setBool(1, body)
            state.recompute()

            assertEquals(!body, factor.holdsNow(state, 0))
            assertEquals(indicator == !body, state.cost == 0L)
            assertEquals(body, factor.residualNow(state, 0, state.violationSoftCap) > 0)
        }
    }

    @Test
    fun `LE row should be violated for weights and bound beyond Int range`() {
        val factor = PseudoBoolean(
            weights = longArrayOf(3_000_000_000L, 3_000_000_000L),
            literals = IntArray(2) { Lit.make(it, true) },
            op = PbOp.LE,
            bound = 5_000_000_000L,
        )
        val problem = Problem(2, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem.bake(), Random(0))
        for (v in 0..1) state.assignment.setBool(v, true)
        state.recompute()
        assertTrue(state.factors[0].isViolated(state, 0))
    }

    @Test
    fun `apply should eliminate a GE violation and zero the cost`() {
        val factor = PseudoBoolean(
            weights = longArrayOf(2, 3),
            literals = IntArray(2) { Lit.make(it, true) },
            op = PbOp.GE,
            bound = 4L,
        )
        val problem = Problem(2, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem.bake(), Random(0))
        state.assignment.setBool(0, true)
        state.assignment.setBool(1, false)
        state.recompute()
        assertTrue(state.factors[0].isViolated(state, 0))
        state.apply(Move.BoolFlip(1))
        assertFalse(state.factors[0].isViolated(state, 0))
        assertEquals(0, state.cost)
    }

    @Test
    fun `repair should propose only flips that reduce distance`() {
        val factor = PseudoBoolean(
            weights = longArrayOf(3, 2, 1),
            literals = IntArray(3) { Lit.make(it, true) },
            op = PbOp.LE,
            bound = 4L,
        )
        val problem = Problem(3, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem.bake(), Random(0))
        for (v in 0..2) state.assignment.setBool(v, true)
        state.recompute()
        assertTrue(state.factors[0].isViolated(state, 0))
        val sink = MoveSink()
        state.factors[0].proposeRepairMoves(state, 0, sink)
        val proposed = sink.list.filterIsInstance<Move.BoolFlip>().map { it.varId }.toSet()
        assertTrue(proposed.isNotEmpty())
        assertTrue(proposed.all { v -> state.assignment.boolValue(v) }, "$proposed")
    }
}
