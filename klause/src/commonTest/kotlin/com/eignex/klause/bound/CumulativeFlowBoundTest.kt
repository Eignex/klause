package com.eignex.klause.bound

import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.PropagationSession
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CumulativeFlowBoundTest {

    private fun problem(n: Int, spanHi: Int, durations: LongArray, resources: LongArray, capacity: Long): Problem =
        Problem(
            0,
            n,
            Array(n) { IntDomain(0, spanHi.toLong()) },
            arrayOf<Factor>(Cumulative(IntArray(n) { it }, durations, resources, capacity)),
        )

    @Test
    fun `flow bound should not flag a feasible cumulative`() {
        val p = problem(2, 5, longArrayOf(2, 2), longArrayOf(1, 1), capacity = 2)
        assertTrue(!CumulativeFlowBound(p).isInfeasible(PropagationSession(p)))
    }

    @Test
    fun `flow bound declines an unrepresentable interval width`() {
        val p = Problem(
            0,
            2,
            Array(2) { IntDomain(-(1L shl 62), 1L shl 62) },
            arrayOf<Factor>(Cumulative(intArrayOf(0, 1), longArrayOf(1, 1), longArrayOf(1, 1), capacity = 2)),
        )
        assertFalse(CumulativeFlowBound(p).isInfeasible(PropagationSession(p)))
    }

    @Test
    fun `flow bound supplies no conflict for an unrepresentable interval width`() {
        val p = Problem(
            0,
            2,
            Array(2) { IntDomain(-(1L shl 62), 1L shl 62) },
            arrayOf<Factor>(Cumulative(intArrayOf(0, 1), longArrayOf(1, 1), longArrayOf(1, 1), capacity = 2)),
        )
        assertNull(CumulativeFlowBound(p).explain(PropagationSession(p)))
    }

    @Test
    fun `flow bound should detect a release-deadline window infeasibility`() {
        // Two tasks pinned to start 0 with length 2 put 4 work into a capacity-2 window.
        val p = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 0), IntDomain(0, 0)),
            arrayOf<Factor>(Cumulative(intArrayOf(0, 1), longArrayOf(2, 2), longArrayOf(1, 1), capacity = 1)),
        )
        assertTrue(CumulativeFlowBound(p).isInfeasible(PropagationSession(p)))
    }

    @Test
    fun `flow bound should explain over-subscription with a well-formed nogood`() {
        val p = problem(3, 3, longArrayOf(3, 3, 3), longArrayOf(1, 1, 1), capacity = 1)
        val clause = CumulativeFlowBound(p).explain(PropagationSession(p))
        assertTrue(clause != null && clause.isNotEmpty())
        assertTrue(clause.all { it >= 0 })
    }
}
