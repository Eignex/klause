package com.eignex.klause.bound

import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.PropagationSession
import kotlin.test.Test
import kotlin.test.assertTrue

class CumulativeEnergeticBoundTest {

    private fun problem(n: Int, spanHi: Int, durations: LongArray, resources: LongArray, capacity: Long): Problem =
        Problem(
            0,
            n,
            Array(n) { IntDomain(0, spanHi.toLong()) },
            arrayOf<Factor>(Cumulative(IntArray(n) { it }, durations, resources, capacity)),
        )

    @Test
    fun `energetic bound should not flag a feasible cumulative`() {
        val p = problem(2, 5, longArrayOf(2, 2), longArrayOf(1, 1), capacity = 2)
        assertTrue(!CumulativeEnergeticBound(p).isInfeasible(PropagationSession(p)))
    }

    @Test
    fun `energetic bound should detect over-subscription with durations beyond Int range`() {
        val p = problem(2, 5, longArrayOf(3_000_000_000L, 3_000_000_000L), longArrayOf(1, 1), capacity = 1)
        assertTrue(CumulativeEnergeticBound(p).isInfeasible(PropagationSession(p)))
    }

    @Test
    fun `energetic bound should explain over-subscription with bound atoms`() {
        val p = problem(3, 3, longArrayOf(3, 3, 3), longArrayOf(1, 1, 1), capacity = 1)
        val clause = CumulativeEnergeticBound(p).explain(PropagationSession(p))
        assertTrue(clause != null && clause.isNotEmpty())
        assertTrue(clause.all { it >= 0 })
    }
}
