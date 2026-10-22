package com.eignex.klause.factor.global

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.propagation.bake
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlobalCardinalityInvariantTest {

    private fun problem(
        xs: IntArray,
        cover: IntArray,
        countLow: IntArray,
        countHigh: IntArray,
        domainHi: Int = 5,
    ): Problem {
        val n = xs.size
        return Problem(
            numBoolVars = 0,
            numIntVars = n,
            intDomains = Array(n) { IntDomain(0, domainHi.toLong()) },
            factors = arrayOf<Factor>(
                GlobalCardinality(
                    xs,
                    LongArray(cover.size) { cover[it].toLong() },
                    countLow = countLow,
                    countHigh = countHigh,
                ),
            ),
        )
    }

    @Test
    fun `extended structured moves should include count-preserving 3-cycles`() {
        val p = problem(
            xs = intArrayOf(0, 1, 2, 3),
            cover = intArrayOf(1, 2, 3),
            countLow = intArrayOf(1, 1, 1),
            countHigh = intArrayOf(1, 1, 1),
            domainHi = 3,
        )
        fun seeded(seed: Long): LocalSearchState {
            val state = LocalSearchState(p.bake(), Random(seed))
            for (i in 0 until 4) state.assignment.setInt(i, i.toLong())
            state.recompute()
            return state
        }
        var sawRotation = false
        for (seed in longArrayOf(1L, 2L, 3L, 7L, 11L, 29L)) {
            val state = seeded(seed)
            assertEquals(0L, state.cost)
            val sink = MoveSink()
            state.factors[0].proposeExtendedStructuredMoves(state, 0, sink)
            for (m in sink.list) {
                if (m is Move.Compound && m.parts.size == 3) sawRotation = true
                val check = seeded(0)
                check.apply(m)
                assertEquals(0L, check.cost, "$m")
            }
        }
        assertTrue(sawRotation)
    }

    @Test
    fun `violation degree should equal total count deviation`() {
        // Both covered values are 2 short of their lower count.
        val p = problem(intArrayOf(0, 1, 2), intArrayOf(1, 2), intArrayOf(2, 2), intArrayOf(3, 3))
        val state = LocalSearchState(p.bake(), Random(0))
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 0)
        state.assignment.setInt(2, 0)
        state.recompute()
        assertTrue(state.factors[0].isViolated(state, 0))
        assertEquals(4, state.factors[0].violationDegree(state, 0))
    }

    @Test
    fun `over-count repair should target only values below their upper bound when the bounds are tight`() {
        val p = problem(
            xs = intArrayOf(0, 1, 2, 3, 4, 5),
            cover = intArrayOf(1, 2, 3),
            countLow = intArrayOf(1, 1, 1),
            countHigh = intArrayOf(2, 2, 2),
            domainHi = 3,
        )
        val state = LocalSearchState(p.bake(), Random(0))
        for ((x, v) in longArrayOf(1, 1, 1, 2, 2, 3).withIndex()) state.assignment.setInt(x, v)
        state.recompute()
        val sink = MoveSink()

        state.factors[0].proposeRepairMoves(state, 0, sink)

        assertEquals(setOf<Move>(Move.IntSet(0, 3), Move.IntSet(1, 3), Move.IntSet(2, 3)), sink.list.toSet())
    }

    @Test
    fun `over-count repair should fall back to a value outside the cover when every cover value is full`() {
        val p = problem(
            xs = intArrayOf(0, 1, 2),
            cover = intArrayOf(0, 1),
            countLow = intArrayOf(0, 0),
            countHigh = intArrayOf(1, 1),
            domainHi = 3,
        )
        val state = LocalSearchState(p.bake(), Random(0))
        for ((x, v) in longArrayOf(0, 0, 1).withIndex()) state.assignment.setInt(x, v)
        state.recompute()
        val sink = MoveSink()

        state.factors[0].proposeRepairMoves(state, 0, sink)

        assertEquals(setOf<Move>(Move.IntSet(0, 2), Move.IntSet(1, 2)), sink.list.toSet())
    }

    @Test
    fun `delta should predict degree change when reassigning to a cover value`() {
        val p = problem(intArrayOf(0, 1, 2), intArrayOf(1), intArrayOf(2), intArrayOf(2))
        val state = LocalSearchState(p.bake(), Random(0))
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 0)
        state.assignment.setInt(2, 0)
        state.recompute()
        val before = state.factors[0].violationDegree(state, 0)
        val delta = state.factors[0].deltaIfIntSet(state, 0, 0, 1)
        state.apply(Move.IntSet(0, 1))
        val after = state.factors[0].violationDegree(state, 0)
        assertEquals(after - before, delta)
    }
}
