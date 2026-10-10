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
    fun `feasible cumulative is not flagged`() {
        // 2 tasks, demand 1 each, capacity 2: they may always run concurrently — never infeasible.
        val p = problem(2, 5, longArrayOf(2, 2), longArrayOf(1, 1), capacity = 2)
        assertTrue(!CumulativeEnergeticBound(p).isInfeasible(PropagationSession(p)))
    }

    @Test
    fun `energetic over-subscription is detected for durations beyond Int range`() {
        // 2 tasks of duration 3e9 on capacity 1, starts in [0,5]: horizon 5 but energy 6e9 far exceeds
        // it. The energetic sum is computed in Long, so a duration past 2^31 is handled soundly.
        val p = problem(2, 5, longArrayOf(3_000_000_000L, 3_000_000_000L), longArrayOf(1, 1), capacity = 1)
        assertTrue(CumulativeEnergeticBound(p).isInfeasible(PropagationSession(p)))
    }

    @Test
    fun `over-subscription yields a bound-atom explanation`() {
        // Same disjunctive over-subscription as above; explain must return a well-formed nogood.
        val p = problem(3, 3, longArrayOf(3, 3, 3), longArrayOf(1, 1, 1), capacity = 1)
        val clause = CumulativeEnergeticBound(p).explain(PropagationSession(p))
        assertTrue(clause != null && clause.isNotEmpty(), "expected a non-empty energetic explanation")
        assertTrue(clause.all { it >= 0 }, "every literal must be a well-formed atom")
    }

    /** Brute force: does any start assignment keep every time point within capacity? */
    private fun feasible(
        n: Int,
        mins: IntArray,
        maxs: IntArray,
        durations: LongArray,
        resources: LongArray,
        capacity: Long,
    ): Boolean {
        val start = IntArray(n)
        fun rec(i: Int): Boolean {
            if (i == n) {
                var t = 0L
                val horizon = (0 until n).maxOf { start[it] + durations[it] }
                while (t < horizon) {
                    var load = 0L
                    for (k in 0 until n) if (start[k] <= t && t < start[k] + durations[k]) load += resources[k]
                    if (load > capacity) return false
                    t++
                }
                return true
            }
            for (s in mins[i]..maxs[i]) {
                start[i] = s
                if (rec(i + 1)) return true
            }
            return false
        }
        return rec(0)
    }
}
