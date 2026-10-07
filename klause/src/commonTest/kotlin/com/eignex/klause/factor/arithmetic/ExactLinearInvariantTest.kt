package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.DegreeConsistencyOracle
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.propagation.bake
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.parseBigInt
import com.eignex.klause.util.times
import com.eignex.klause.util.unaryMinus
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ExactLinearInvariantTest {

    private val w = parseBigInt("18446744073709551616")

    private fun problem(factor: Factor, numBoolVars: Int = 0): Problem =
        Problem(numBoolVars, 2, arrayOf(IntDomain(-4, 4), IntDomain(-4, 4)), arrayOf(factor))

    @Test
    fun `a wide row keeps its degree and deltas consistent`() {
        for (op in LinearOp.entries) {
            val row = Linear(intArrayOf(0, 1), arrayOf(w, -w * bigIntOf(3)), op, w * bigIntOf(2))
            DegreeConsistencyOracle.assertConsistent(problem(row), label = "wide $op", exactProbe = true)
        }
    }

    @Test
    fun `a reified wide row keeps its degree and deltas consistent`() {
        for (op in LinearOp.entries) {
            val row = ReifiedLinear(0, intArrayOf(0, 1), arrayOf(w, w), op, w)
            DegreeConsistencyOracle.assertConsistent(problem(row, 1), label = "reified wide $op", exactProbe = true)
        }
    }

    @Test
    fun `a sum that crosses the Long range keeps its degree and deltas consistent`() {
        // Coefficients near 2^62 over small domains: some assignments sum past 64 bits and a one-step move brings the
        // sum back, so moves cross the range in both directions.
        val big = 3L shl 61
        for (op in LinearOp.entries) {
            val row = Linear(longArrayOf(big, big), intArrayOf(0, 1), op, 5L)
            val p = Problem(0, 2, arrayOf(IntDomain(-2, 2), IntDomain(-2, 2)), arrayOf<Factor>(row))
            DegreeConsistencyOracle.assertConsistent(p, label = "crossing $op", exactProbe = true)
        }
    }

    @Test
    fun `a sum driven past 128 bits and back keeps its cost and deltas exact`() {
        // Eight terms of Long.MAX_VALUE · 2^62 sum past 2^127; setting them back to zero brings the sum home.
        val n = 8
        val far = 1L shl 62
        val row = Linear(LongArray(n) { Long.MAX_VALUE }, IntArray(n) { it }, LinearOp.LE, 0L)
        val baked = Problem(0, n, Array(n) { IntDomain(-far, far) }, arrayOf<Factor>(row)).bake()
        val state = LocalSearchState(baked, Random(0))
        for (v in 0 until n) state.assignment.setInt(v, 0L)
        state.recompute()
        val path = List(n) { it to far } + List(n) { it to 0L }

        for ((v, value) in path) {
            val move = Move.IntSet(v, value)
            val predicted = state.netDelta(move)
            val before = state.cost
            state.apply(move)
            val fresh = LocalSearchState(baked, Random(0))
            for (u in 0 until n) fresh.assignment.setInt(u, state.assignment.intValue(u))
            fresh.recompute()

            assertEquals(fresh.cost, state.cost, "x$v = $value")
            assertEquals(predicted, state.cost - before, "x$v = $value")
        }
    }

    @Test
    fun `a row whose sum can outrun a Long is kept exactly`() {
        val wide = 1L shl 40
        val row = Linear(longArrayOf(wide, wide), intArrayOf(0, 1), LinearOp.LE, 0L)
        val p = Problem(0, 2, arrayOf(IntDomain(-wide, wide), IntDomain(-wide, wide)), arrayOf<Factor>(row))

        val state = LocalSearchState(p.bake(), Random(0))

        assertIs<ExactLinearInvariant>(state.factors[0])
    }

    @Test
    fun `a row whose sum fits a Long keeps the Long invariant`() {
        val row = Linear(longArrayOf(3, -2), intArrayOf(0, 1), LinearOp.LE, 5L)

        val state = LocalSearchState(problem(row).bake(), Random(0))

        assertIs<LinearInvariant>(state.factors[0])
    }
}
