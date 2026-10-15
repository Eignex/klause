package com.eignex.klause.factor.global

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.propagation.bake
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SortInvariantTest {

    private fun problem(): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 6,
        intDomains = Array(6) { IntDomain(0, 4) },
        factors = arrayOf<Factor>(Sort(xs = intArrayOf(0, 1, 2), ys = intArrayOf(3, 4, 5))),
    )

    @Test
    fun `violation degree should count positions where ys differs from sorted xs`() {
        val cases = listOf(
            Triple(listOf(2L, 0L, 1L), listOf(0L, 2L, 1L), 2),
            Triple(listOf(1L, 2L, 3L), listOf(3L, 2L, 1L), 2),
        )
        for ((xs, ys, degree) in cases) {
            val state = LocalSearchState(problem().bake(), Random(0))
            for (i in 0..2) state.assignment.setInt(i, xs[i])
            for (i in 0..2) state.assignment.setInt(3 + i, ys[i])
            state.recompute()
            assertTrue(state.factors[0].isViolated(state, 0), "xs=$xs ys=$ys")
            assertEquals(degree, state.factors[0].violationDegree(state, 0), "xs=$xs ys=$ys")
        }
    }

    @Test
    fun `delta should predict degree change when correcting a ys entry`() {
        val p = problem()
        val state = LocalSearchState(p.bake(), Random(0))
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 1)
        state.assignment.setInt(2, 2)
        state.assignment.setInt(3, 2)
        state.assignment.setInt(4, 1)
        state.assignment.setInt(5, 0)
        state.recompute()
        val before = state.factors[0].violationDegree(state, 0)
        val delta = state.factors[0].deltaIfIntSet(state, 0, 3, 0)
        state.apply(Move.IntSet(3, 0))
        val after = state.factors[0].violationDegree(state, 0)
        assertEquals(after - before, delta)
    }
}
