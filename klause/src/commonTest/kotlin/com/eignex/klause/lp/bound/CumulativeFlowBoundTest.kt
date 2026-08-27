package com.eignex.klause.lp.bound

import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.solver.Problem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/** #454: preemptive max-flow feasibility bound for the scheduling globals. */
class CumulativeFlowBoundTest {

    private fun problem(n: Int, spanHi: Int, durations: LongArray, resources: LongArray, capacity: Long): Problem =
        Problem(
            0,
            n,
            Array(n) { IntDomain(0, spanHi.toLong()) },
            arrayOf<Factor>(Cumulative(IntArray(n) { it }, durations, resources, capacity)),
        )

    @Test
    fun `feasible cumulative is not flagged`() {
        val p = problem(2, 5, longArrayOf(2, 2), longArrayOf(1, 1), capacity = 2)
        assertTrue(!CumulativeFlowBound(p).isInfeasible(PropagationSession(p)))
    }

    @Test
    fun `preemptive over-subscription is detected`() {
        // 3 unit tasks of length 3, capacity 1, starts in [0,3] ⇒ horizon 6, but 9 work needs 9 ⇒ infeasible.
        val p = problem(3, 3, longArrayOf(3, 3, 3), longArrayOf(1, 1, 1), capacity = 1)
        assertTrue(CumulativeFlowBound(p).isInfeasible(PropagationSession(p)))
    }

    @Test
    fun `detects a release-deadline window infeasibility`() {
        // Two unit tasks both pinned to start 0, length 2, capacity 1: forced full overlap ⇒ the
        // [0,2) window must hold 4 work at rate ≤ 1 into capacity·width = 2 ⇒ preemptively infeasible.
        val p = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 0), IntDomain(0, 0)),
            arrayOf<Factor>(Cumulative(intArrayOf(0, 1), longArrayOf(2, 2), longArrayOf(1, 1), capacity = 1)),
        )
        assertTrue(CumulativeFlowBound(p).isInfeasible(PropagationSession(p)))
    }

    @Test
    fun `over-subscription yields a well-formed nogood`() {
        val p = problem(3, 3, longArrayOf(3, 3, 3), longArrayOf(1, 1, 1), capacity = 1)
        val clause = CumulativeFlowBound(p).explain(PropagationSession(p))
        assertTrue(clause != null && clause.isNotEmpty(), "expected a non-empty flow explanation")
        assertTrue(clause.all { it >= 0 }, "every literal must be a well-formed atom")
    }

    @Test
    fun `feasible cumulative has no explanation`() {
        val p = problem(2, 5, longArrayOf(2, 2), longArrayOf(1, 1), capacity = 2)
        assertTrue(CumulativeFlowBound(p).explain(PropagationSession(p)) == null)
    }

    @Test
    fun `never flags a schedulable instance - soundness vs brute force`() {
        val rng = Random(20260613)
        var flagged = 0
        repeat(500) { _ ->
            val n = rng.nextInt(2, 4)
            val spanHi = rng.nextInt(1, 5)
            val durations = LongArray(n) { rng.nextInt(1, 4).toLong() }
            val resources = LongArray(n) { rng.nextInt(1, 3).toLong() }
            val capacity = rng.nextInt(1, 4).toLong()
            val p = problem(n, spanHi, durations, resources, capacity)
            val session = PropagationSession(p)
            if (!CumulativeFlowBound(p).isInfeasible(session)) return@repeat
            flagged++
            val mins = IntArray(n) { session.intDomain(it).min.toInt() }
            val maxs = IntArray(n) { session.intDomain(it).max.toInt() }
            assertTrue(!feasible(n, mins, maxs, durations, resources, capacity), "false infeasible")
        }
        assertTrue(flagged > 20, "only $flagged instances were flagged infeasible")
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
                val horizon = (0 until n).maxOf { start[it] + durations[it] }
                for (t in 0 until horizon) {
                    var load = 0L
                    for (k in 0 until n) if (start[k] <= t && t < start[k] + durations[k]) load += resources[k]
                    if (load > capacity) return false
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
