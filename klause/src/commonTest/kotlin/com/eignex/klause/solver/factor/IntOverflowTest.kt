package com.eignex.klause.solver.factor

import com.eignex.klause.ast.PbOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Move
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState
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
        const val BIG = 1 shl 20 // 1_048_576
        const val WIDE = 1 shl 12 // 4_096
        const val PRODUCT = BIG.toLong() * WIDE // 2^32 = 4_294_967_296
    }

    @Test
    fun `naive Int product would wrap - documents the hazard`() {
        // 2^20 * 2^12 = 2^32, which is exactly 0 in 32-bit two's complement. This is the
        // wrap the Long payloads must avoid.
        assertEquals(0, BIG * WIDE)
        assertEquals(4_294_967_296L, PRODUCT)
    }

    private fun stateFor(numBool: Int, domains: Array<IntDomain>, factor: LocalSearchFactor): LocalSearchState {
        val problem = Problem(numBool, domains.size, domains, listOf(factor as Factor))
        return LocalSearchState(problem, Random(0))
    }

    @Test
    fun `Linear running sum is Long-clean`() {
        val factor = Linear(intArrayOf(BIG), intArrayOf(0), LinearOp.LE, bound = 1000)
        val state = stateFor(0, arrayOf(IntDomain(0, WIDE)), factor)
        state.assignment.setInt(0, WIDE)
        state.recompute()
        assertEquals(PRODUCT, state.longPayload[0])
        assertTrue(factor.isViolated(state, 0), "2^32 > 1000 must be violated")
        assertTrue(factor.violationDegree(state, 0) > 0)
    }

    @Test
    fun `Linear deltaIfIntSet predicts the Long degree change`() {
        // EQ at bound 0: var=0 satisfies, var=WIDE drives the sum to 2^32 (violated).
        val factor = Linear(intArrayOf(BIG), intArrayOf(0), LinearOp.EQ, bound = 0)
        val state = stateFor(0, arrayOf(IntDomain(0, WIDE)), factor)
        state.assignment.setInt(0, 0)
        state.recompute()
        assertFalse(factor.isViolated(state, 0))

        val predicted = factor.deltaIfIntSet(state, 0, 0, WIDE)
        state.apply(Move.IntSet(0, WIDE))
        val observed = factor.violationDegree(state, 0)
        assertTrue(observed > 0, "sum 2^32 != 0 must be violated")
        assertEquals(observed, predicted, "delta must predict the true Long degree, not a wrapped one")
    }

    @Test
    fun `ReifiedLinear body sum is Long-clean`() {
        // aux(bool 0) ↔ (BIG·x ≤ 1000); x=WIDE makes the body 2^32 (does not hold).
        val factor = ReifiedLinear(auxBoolVar = 0, coeffs = intArrayOf(BIG), vars = intArrayOf(0), op = LinearOp.LE, bound = 1000)
        val state = stateFor(1, arrayOf(IntDomain(0, WIDE)), factor)
        state.assignment.setBool(0, true) // aux demands the body hold
        state.assignment.setInt(0, WIDE)
        state.recompute()
        assertEquals(PRODUCT, state.longPayload[0])
        assertTrue(factor.isViolated(state, 0), "aux=true but body 2^32 > 1000 does not hold")
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
        assertTrue(factor.isViolated(state, 0))
    }

    @Test
    fun `ReifiedPseudoBoolean weighted sum is Long-clean`() {
        // bool 0 = aux; bools 1..WIDE = body literals, each weight BIG.
        val n = WIDE
        val weights = IntArray(n) { BIG }
        val literals = IntArray(n) { Lit.make(it + 1, true) }
        val factor = ReifiedPseudoBoolean(auxBoolVar = 0, weights = weights, literals = literals, op = PbOp.LE, bound = 1000)
        val state = stateFor(n + 1, emptyArray(), factor)
        state.assignment.setBool(0, true) // aux demands the relation hold
        for (v in 1..n) state.assignment.setBool(v, true)
        state.recompute()
        assertEquals(PRODUCT, state.longPayload[0])
        assertTrue(factor.isViolated(state, 0), "aux=true but Σ 2^32 > 1000 does not hold")
    }

    @Test
    fun `Product compares operands in Long`() {
        // a = b = 2^20 → a·b = 2^40 (which wraps to 0 in Int). result pinned to 0 must be violated.
        val factor = Product(a = 0, b = 1, result = 2)
        val state = stateFor(0, arrayOf(IntDomain(0, BIG), IntDomain(0, BIG), IntDomain(0, 0)), factor)
        state.assignment.setInt(0, BIG)
        state.assignment.setInt(1, BIG)
        state.assignment.setInt(2, 0)
        state.recompute()
        assertEquals(0, BIG * BIG, "2^40 wraps to 0 in Int") // documents the hazard
        assertTrue(factor.isViolated(state, 0), "true product 2^40 != 0 must be violated")
    }

    @Test
    fun `Knapsack maintained weight is Long-clean`() {
        // single item, weight BIG, xs ∈ [0, WIDE]. xs=WIDE drives the total weight to 2^32.
        val factor = Knapsack(
            weights = intArrayOf(BIG),
            profits = intArrayOf(0),
            xs = intArrayOf(0),
            w = 1,
            p = 2,
        )
        val state = stateFor(0, arrayOf(IntDomain(0, WIDE), IntDomain(0, 100), IntDomain(0, 0)), factor)
        state.assignment.setInt(0, 0) // xs
        state.assignment.setInt(1, 0) // w
        state.assignment.setInt(2, 0) // p
        state.recompute()
        assertFalse(factor.isViolated(state, 0))

        val predicted = factor.deltaIfIntSet(state, 0, 0, WIDE)
        state.apply(Move.IntSet(0, WIDE))
        assertTrue(factor.isViolated(state, 0), "weight total 2^32 != w(0) must be violated")
        assertEquals(1, predicted, "delta must see the true Long weight, not a wrapped 0")
    }
}
