package com.eignex.klause.factor.arithmetic

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.propagation.bake
import com.eignex.klause.util.times
import com.eignex.klause.util.unaryMinus
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ExactLinearInvariantTest {

    private fun problem(factor: Factor, numBoolVars: Int = 0): Problem =
        Problem(numBoolVars, 2, arrayOf(IntDomain(-4, 4), IntDomain(-4, 4)), arrayOf(factor))

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
    fun `a row whose sum fits a Long keeps the Long invariant`() {
        val row = Linear(longArrayOf(3, -2), intArrayOf(0, 1), LinearOp.LE, 5L)

        val state = LocalSearchState(problem(row).bake(), Random(0))

        assertIs<LinearInvariant>(state.factors[0])
    }
}
