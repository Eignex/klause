package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.arithmetic.ReifiedPseudoBoolean
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.model.PbOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression for issue #72 — the local-search incremental payloads accumulate weighted sums
 * and products that, with coefficients ~2^20 and domains ~2^12, exceed 32 bits. A 32-bit
 * accumulator wraps and makes `isViolated` / `violationDegree` / the `deltaIf*` gradient
 * silently wrong. Every case here is constructed so the *true* (Long) total is well past
 * `Int.MAX_VALUE` while a naive Int product would wrap to a misleading value.
 */
class IntOverflowTest {

    private companion object {
        const val BIG = 1 shl 22
        const val WIDE = 1 shl 10 // 1_024 — few literals, same overflowing 2^32 product
        const val PRODUCT = BIG.toLong() * WIDE // 2^32 = 4_294_967_296
    }

    private fun stateFor(numBool: Int, domains: Array<IntDomain>, factor: Factor): LocalSearchState {
        val problem = Problem(numBool, domains.size, domains, listOf(factor))
        return LocalSearchState(problem, Random(0))
    }

    @Test
    fun `Linear running sum is Long-clean`() {
        val factor = Linear(intArrayOf(BIG), intArrayOf(0), LinearOp.LE, bound = 1000)
        val state = stateFor(0, arrayOf(IntDomain(0, WIDE.toLong())), factor)
        state.assignment.setInt(0, WIDE.toLong())
        state.recompute()
        assertEquals(PRODUCT, state.longPayload[0])
        assertTrue(state.factors[0].isViolated(state, 0), "2^32 > 1000 must be violated")
        assertTrue(state.factors[0].violationDegree(state, 0) > 0)
    }

    @Test
    fun `Linear deltaIfIntSet predicts the Long degree change`() {
        // EQ at bound 0: var=0 satisfies, var=WIDE drives the sum to 2^32 (violated).
        val factor = Linear(intArrayOf(BIG), intArrayOf(0), LinearOp.EQ, bound = 0)
        val state = stateFor(0, arrayOf(IntDomain(0, WIDE.toLong())), factor)
        state.assignment.setInt(0, 0)
        state.recompute()
        assertFalse(state.factors[0].isViolated(state, 0))

        val predicted = state.factors[0].deltaIfIntSet(state, 0, 0, WIDE.toLong())
        state.apply(Move.IntSet(0, WIDE.toLong()))
        val observed = state.factors[0].violationDegree(state, 0)
        assertTrue(observed > 0, "sum 2^32 != 0 must be violated")
        assertEquals(observed, predicted, "delta must predict the true Long degree, not a wrapped one")
    }

    @Test
    fun `ReifiedLinear body sum is Long-clean`() {
        // aux(bool 0) ↔ (BIG·x ≤ 1000); x=WIDE makes the body 2^32 (does not hold).
        val factor = ReifiedLinear(
            auxBoolVar = 0,
            coeffs = intArrayOf(BIG),
            vars = intArrayOf(0),
            op = LinearOp.LE,
            bound = 1000,
        )
        val state = stateFor(1, arrayOf(IntDomain(0, WIDE.toLong())), factor)
        state.assignment.setBool(0, true) // aux demands the body hold
        state.assignment.setInt(0, WIDE.toLong())
        state.recompute()
        assertEquals(PRODUCT, state.longPayload[0])
        assertTrue(state.factors[0].isViolated(state, 0), "aux=true but body 2^32 > 1000 does not hold")
    }

    @Test
    fun `PseudoBoolean weighted sum is Long-clean`() {
        // WIDE literals, each weight BIG, all true → Σ = 2^32; LE 1000 is violated.
        val weights = IntArray(WIDE) { BIG }
        val literals = IntArray(WIDE) { Lit.make(it, true) }
        val factor = PseudoBoolean(weights, literals, PbOp.LE, bound = 1000)
        val state = stateFor(WIDE, emptyArray(), factor)
        for (v in 0 until WIDE) state.assignment.setBool(v, true)
        state.recompute()
        assertEquals(PRODUCT, state.longPayload[0])
        assertTrue(state.factors[0].isViolated(state, 0))
    }

    @Test
    fun `ReifiedPseudoBoolean weighted sum is Long-clean`() {
        // bool 0 = aux; bools 1..WIDE = body literals, each weight BIG.
        val n = WIDE
        val weights = IntArray(n) { BIG }
        val literals = IntArray(n) { Lit.make(it + 1, true) }
        val factor = ReifiedPseudoBoolean(
            auxBoolVar = 0,
            weights = weights,
            literals = literals,
            op = PbOp.LE,
            bound = 1000,
        )
        val state = stateFor(n + 1, emptyArray(), factor)
        state.assignment.setBool(0, true) // aux demands the relation hold
        for (v in 1..n) state.assignment.setBool(v, true)
        state.recompute()
        assertEquals(PRODUCT, state.longPayload[0])
        assertTrue(state.factors[0].isViolated(state, 0), "aux=true but Σ 2^32 > 1000 does not hold")
    }

    @Test
    fun `Product compares operands in Long`() {
        // a = b = 2^20 → a·b = 2^40 (which wraps to 0 in Int). result pinned to 0 must be violated.
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
        assertEquals(0, BIG * BIG, "2^40 wraps to 0 in Int") // documents the hazard
        assertTrue(state.factors[0].isViolated(state, 0), "true product 2^40 != 0 must be violated")
    }
}
