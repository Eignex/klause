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
