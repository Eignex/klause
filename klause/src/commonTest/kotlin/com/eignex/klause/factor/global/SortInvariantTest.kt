package com.eignex.klause.factor.global

import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SortInvariantTest {

    // xs = vars 0..2, ys = vars 3..5
    private fun problem(): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 6,
        intDomains = Array(6) { IntDomain(0, 4) },
        factors = arrayOf<Factor>(Sort(xs = intArrayOf(0, 1, 2), ys = intArrayOf(3, 4, 5))),
    )

    @Test
    fun `not violated when ys equals sorted xs`() {
        val p = problem()
        val state = LocalSearchState(p, Random(0))
        // xs=[2,0,1], sorted=[0,1,2], ys=[0,1,2]
        state.assignment.setInt(0, 2)
        state.assignment.setInt(1, 0)
        state.assignment.setInt(2, 1)
        state.assignment.setInt(3, 0)
        state.assignment.setInt(4, 1)
        state.assignment.setInt(5, 2)
        state.recompute()
        assertFalse(state.factors[0].isViolated(state, 0))
        assertEquals(0, state.factors[0].violationDegree(state, 0))
    }

    @Test
    fun `violated when ys is not sorted xs with degree counting mismatched positions`() {
        val cases = listOf(
            // sorted(xs)=[0,1,2], ys differs at positions 1 and 2.
            Triple(listOf(2L, 0L, 1L), listOf(0L, 2L, 1L), 2),
            // sorted(xs)=[1,2,3], ys differs at positions 0 and 2.
            Triple(listOf(1L, 2L, 3L), listOf(3L, 2L, 1L), 2),
        )
        for ((xs, ys, degree) in cases) {
            val state = LocalSearchState(problem(), Random(0))
            for (i in 0..2) state.assignment.setInt(i, xs[i])
            for (i in 0..2) state.assignment.setInt(3 + i, ys[i])
            state.recompute()
            assertTrue(state.factors[0].isViolated(state, 0), "xs=$xs ys=$ys must violate sort")
            assertEquals(degree, state.factors[0].violationDegree(state, 0), "xs=$xs ys=$ys")
        }
    }

    @Test
    fun `delta predicts degree change when correcting a ys entry`() {
        val p = problem()
        val state = LocalSearchState(p, Random(0))
        // xs=[0,1,2], ys=[2,1,0] — all wrong
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 1)
        state.assignment.setInt(2, 2)
        state.assignment.setInt(3, 2)
        state.assignment.setInt(4, 1)
        state.assignment.setInt(5, 0)
        state.recompute()
        val before = state.factors[0].violationDegree(state, 0)
        // Fix ys[0]=2 → ys[0]=0 (correct)
        val delta = state.factors[0].deltaIfIntSet(state, 0, 3, 0)
        state.apply(Move.IntSet(3, 0))
        val after = state.factors[0].violationDegree(state, 0)
        assertEquals(after - before, delta)
    }
}
