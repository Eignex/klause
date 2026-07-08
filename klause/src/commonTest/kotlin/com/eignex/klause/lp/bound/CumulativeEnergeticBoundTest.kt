package com.eignex.klause.lp.bound

import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/** #22/#23: Cumulative energetic-reasoning infeasibility check. */
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
    fun `energetic over-subscription is detected`() {
        // 3 unit-demand tasks of length 3, capacity 1 (disjunctive), starts in [0,3] → horizon 6,
        // but 3×3 = 9 energy needs 9 units of capacity-1 time. Energetically infeasible.
        val p = problem(3, 3, longArrayOf(3, 3, 3), longArrayOf(1, 1, 1), capacity = 1)
        assertTrue(CumulativeEnergeticBound(p).isInfeasible(PropagationSession(p)))
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

    @Test
    fun `feasible cumulative has no explanation`() {
        val p = problem(2, 5, longArrayOf(2, 2), longArrayOf(1, 1), capacity = 2)
        assertTrue(CumulativeEnergeticBound(p).explain(PropagationSession(p)) == null)
    }

    @Test
    fun `never flags a schedulable instance - soundness vs brute force`() {
        val rng = Random(20260609)
        var flagged = 0
        repeat(400) { _ ->
            val n = rng.nextInt(2, 4)
            val spanHi = rng.nextInt(1, 5)
            val durations = LongArray(n) { rng.nextInt(1, 4).toLong() }
            val resources = LongArray(n) { rng.nextInt(1, 3).toLong() }
            val capacity = rng.nextInt(1, 4).toLong()
            val p = problem(n, spanHi, durations, resources, capacity)
            val session = PropagationSession(p)
            if (!CumulativeEnergeticBound(p).isInfeasible(session)) return@repeat
            flagged++
            // Brute force: if the check says infeasible, no start assignment within the live domains
            // may form a profile that respects capacity at every time point.
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
