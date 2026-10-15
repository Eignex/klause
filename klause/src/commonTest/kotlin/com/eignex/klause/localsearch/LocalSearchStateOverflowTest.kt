package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.arithmetic.ReifiedPseudoBoolean
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.model.PbOp
import com.eignex.klause.propagation.bake
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalSearchStateOverflowTest {

    private companion object {
        const val BIG = 1 shl 22
        const val WIDE = 1 shl 10
        const val PRODUCT = BIG.toLong() * WIDE // 2^32
    }

    private fun stateFor(numBool: Int, domains: Array<IntDomain>, factor: Factor): LocalSearchState {
        val problem = Problem(numBool, domains.size, domains, listOf(factor))
        return LocalSearchState(problem.bake(), Random(0))
    }

    @Test
    fun `Linear running sum should not overflow Int`() {
        val factor = Linear(intArrayOf(BIG), intArrayOf(0), LinearOp.LE, bound = 1000)
        val state = stateFor(0, arrayOf(IntDomain(0, WIDE.toLong())), factor)
        state.assignment.setInt(0, WIDE.toLong())
        state.recompute()
        assertEquals(PRODUCT, state.longPayload[0])
        assertTrue(state.factors[0].isViolated(state, 0))
        assertTrue(state.factors[0].violationDegree(state, 0) > 0)
    }

    @Test
    fun `Linear deltaIfIntSet should predict the unwrapped degree change`() {
        val factor = Linear(intArrayOf(BIG), intArrayOf(0), LinearOp.EQ, bound = 0)
        val state = stateFor(0, arrayOf(IntDomain(0, WIDE.toLong())), factor)
        state.assignment.setInt(0, 0)
        state.recompute()
        assertFalse(state.factors[0].isViolated(state, 0))

        val predicted = state.factors[0].deltaIfIntSet(state, 0, 0, WIDE.toLong())
        state.apply(Move.IntSet(0, WIDE.toLong()))
        val observed = state.factors[0].violationDegree(state, 0)
        assertTrue(observed > 0)
        assertEquals(observed, predicted)
    }

    @Test
    fun `ReifiedLinear body sum should not overflow Int`() {
        val factor = ReifiedLinear(
            auxBoolVar = 0,
            coeffs = intArrayOf(BIG),
            vars = intArrayOf(0),
            op = LinearOp.LE,
            bound = 1000,
        )
        val state = stateFor(1, arrayOf(IntDomain(0, WIDE.toLong())), factor)
        state.assignment.setBool(0, true)
        state.assignment.setInt(0, WIDE.toLong())
        state.recompute()
        assertEquals(PRODUCT, state.longPayload[0])
        assertTrue(state.factors[0].isViolated(state, 0))
    }

    @Test
    fun `PseudoBoolean weighted sum should not overflow Int`() {
        val weights = LongArray(WIDE) { BIG.toLong() }
        val literals = IntArray(WIDE) { Lit.make(it, true) }
        val factor = PseudoBoolean(weights, literals, PbOp.LE, bound = 1000L)
        val state = stateFor(WIDE, emptyArray(), factor)
        for (v in 0 until WIDE) state.assignment.setBool(v, true)
        state.recompute()
        assertEquals(PRODUCT, state.longPayload[0])
        assertTrue(state.factors[0].isViolated(state, 0))
    }

    @Test
    fun `ReifiedPseudoBoolean weighted sum should not overflow Int`() {
        val n = WIDE
        val weights = LongArray(n) { BIG.toLong() }
        val literals = IntArray(n) { Lit.make(it + 1, true) }
        val factor = ReifiedPseudoBoolean(
            auxBoolVar = 0,
            weights = weights,
            literals = literals,
            op = PbOp.LE,
            bound = 1000L,
        )
        val state = stateFor(n + 1, emptyArray(), factor)
        state.assignment.setBool(0, true)
        for (v in 1..n) state.assignment.setBool(v, true)
        state.recompute()
        assertEquals(PRODUCT, state.longPayload[0])
        assertTrue(state.factors[0].isViolated(state, 0))
    }

    @Test
    fun `Product should compare operands without Int overflow`() {
        val factor = Product(a = 0, b = 1, result = 2)
        val state = stateFor(
            0,
            arrayOf(IntDomain(0, BIG.toLong()), IntDomain(0, BIG.toLong()), IntDomain(0, 0)),
            factor,
        )
        state.assignment.setInt(0, BIG.toLong())
        state.assignment.setInt(1, BIG.toLong())
        state.assignment.setInt(2, 0)
        state.recompute()
        assertTrue(state.factors[0].isViolated(state, 0))
    }
}
