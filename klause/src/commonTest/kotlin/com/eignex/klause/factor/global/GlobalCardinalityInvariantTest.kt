package com.eignex.klause.factor.global

import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun `extended structured moves include count-preserving 3-cycles`() {
        val p = problem(
            xs = intArrayOf(0, 1, 2, 3),
            cover = intArrayOf(1, 2, 3),
            countLow = intArrayOf(1, 1, 1),
            countHigh = intArrayOf(1, 1, 1),
            domainHi = 3,
        )
        fun seeded(seed: Long): LocalSearchState {
            val state = LocalSearchState(p, Random(seed))
            for (i in 0 until 4) state.assignment.setInt(i, i.toLong())
            state.recompute()
            return state
        }
        var sawRotation = false
        for (seed in longArrayOf(1L, 2L, 3L, 7L, 11L, 29L)) {
            val state = seeded(seed)
            assertTrue(state.cost == 0L, "the seed assignment must satisfy the global cardinality")
            val sink = MoveSink()
            state.factors[0].proposeExtendedStructuredMoves(state, 0, sink)
            for (m in sink.list) {
                if (m is Move.Compound && m.parts.size == 3) sawRotation = true
                val check = seeded(0)
                check.apply(m)
                assertTrue(check.cost == 0L, "extended move $m broke the count constraint")
            }
        }
        assertTrue(sawRotation, "count-preserving 3-cycle rotations must be emitted")
    }

    @Test
    fun `satisfied when all counts within bounds`() {
        val p = problem(intArrayOf(0, 1, 2), intArrayOf(1, 2), intArrayOf(1, 1), intArrayOf(2, 2))
        val state = LocalSearchState(p, Random(0))
        // xs = [1, 2, 1]: count(1)=2, count(2)=1 — both within [1,2]
        state.assignment.setInt(0, 1)
        state.assignment.setInt(1, 2)
        state.assignment.setInt(2, 1)
        state.recompute()
        assertFalse(state.factors[0].isViolated(state, 0))
        assertEquals(0, state.factors[0].violationDegree(state, 0))
    }

    @Test
    fun `violated when count falls below lower bound`() {
        val p = problem(intArrayOf(0, 1, 2), intArrayOf(3), intArrayOf(2), intArrayOf(3))
        val state = LocalSearchState(p, Random(0))
        // xs = [3, 0, 0]: count(3)=1, one short of lo=2
        state.assignment.setInt(0, 3)
        state.assignment.setInt(1, 0)
        state.assignment.setInt(2, 0)
        state.recompute()
        assertTrue(state.factors[0].isViolated(state, 0))
        assertEquals(1, state.factors[0].violationDegree(state, 0))
    }

    @Test
    fun `violation degree equals total count deviation`() {
        // cover=[1,2], lo=[2,2], hi=[3,3]. xs=[0,0,0]: count(1)=0 (2 short), count(2)=0 (2 short) → degree 4
        val p = problem(intArrayOf(0, 1, 2), intArrayOf(1, 2), intArrayOf(2, 2), intArrayOf(3, 3))
        val state = LocalSearchState(p, Random(0))
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 0)
        state.assignment.setInt(2, 0)
        state.recompute()
        assertTrue(state.factors[0].isViolated(state, 0))
        assertEquals(4, state.factors[0].violationDegree(state, 0))
    }

    @Test
    fun `delta predicts degree change when reassigning to cover value`() {
        // cover=[1], lo=[2], hi=[2]. xs=[0,0,0]: violated, count(1)=0, need 2.
        val p = problem(intArrayOf(0, 1, 2), intArrayOf(1), intArrayOf(2), intArrayOf(2))
        val state = LocalSearchState(p, Random(0))
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
