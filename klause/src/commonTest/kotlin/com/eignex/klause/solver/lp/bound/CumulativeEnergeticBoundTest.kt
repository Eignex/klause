package com.eignex.klause.solver.lp.bound

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.Cumulative
import com.eignex.klause.solver.propagation.PropagationSession
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/** #22/#23: Cumulative energetic-reasoning infeasibility check. */
class CumulativeEnergeticBoundTest {

    private fun problem(n: Int, spanHi: Int, durations: IntArray, resources: IntArray, capacity: Int): Problem =
        Problem(
            0,
            n,
            Array(n) { IntDomain(0, spanHi) },
            arrayOf<Factor>(Cumulative(IntArray(n) { it }, durations, resources, capacity)),
        )

    @Test
    fun `feasible cumulative is not flagged`() {
        // 2 tasks, demand 1 each, capacity 2: they may always run concurrently — never infeasible.
        val p = problem(2, 5, intArrayOf(2, 2), intArrayOf(1, 1), capacity = 2)
        assertTrue(!CumulativeEnergeticBound(p).isInfeasible(PropagationSession(p)))
    }

    @Test
    fun `energetic over-subscription is detected`() {
        // 3 unit-demand tasks of length 3, capacity 1 (disjunctive), starts in [0,3] → horizon 6,
        // but 3×3 = 9 energy needs 9 units of capacity-1 time. Energetically infeasible.
        val p = problem(3, 3, intArrayOf(3, 3, 3), intArrayOf(1, 1, 1), capacity = 1)
        assertTrue(CumulativeEnergeticBound(p).isInfeasible(PropagationSession(p)))
    }

    @Test
    fun `over-subscription yields a bound-atom explanation`() {
        // Same disjunctive over-subscription as above; explain must return a well-formed nogood.
        val p = problem(3, 3, intArrayOf(3, 3, 3), intArrayOf(1, 1, 1), capacity = 1)
        val clause = CumulativeEnergeticBound(p).explain(PropagationSession(p))
        assertTrue(clause != null && clause.isNotEmpty(), "expected a non-empty energetic explanation")
        assertTrue(clause.all { it >= 0 }, "every literal must be a well-formed atom")
    }

    @Test
    fun `feasible cumulative has no explanation`() {
        val p = problem(2, 5, intArrayOf(2, 2), intArrayOf(1, 1), capacity = 2)
        assertTrue(CumulativeEnergeticBound(p).explain(PropagationSession(p)) == null)
    }

    @Test
    fun `never flags a schedulable instance - soundness vs brute force`() {
        val rng = Random(20260609)
        var flagged = 0
        repeat(400) { _ ->
            val n = rng.nextInt(2, 4)
            val spanHi = rng.nextInt(1, 5)
            val durations = IntArray(n) { rng.nextInt(1, 4) }
            val resources = IntArray(n) { rng.nextInt(1, 3) }
            val capacity = rng.nextInt(1, 4)
            val p = problem(n, spanHi, durations, resources, capacity)
            val session = PropagationSession(p)
            if (!CumulativeEnergeticBound(p).isInfeasible(session)) return@repeat
            flagged++
            // Brute force: if the check says infeasible, no start assignment within the live domains
            // may form a profile that respects capacity at every time point.
            val mins = IntArray(n) { session.intDomain(it).min }
            val maxs = IntArray(n) { session.intDomain(it).max }
            assertTrue(!feasible(n, mins, maxs, durations, resources, capacity), "false infeasible")
        }
        assertTrue(flagged > 20, "only $flagged instances were flagged infeasible")
    }

    /** Brute force: does any start assignment keep every time point within capacity? */
    private fun feasible(
        n: Int,
        mins: IntArray,
        maxs: IntArray,
        durations: IntArray,
        resources: IntArray,
        capacity: Int,
    ): Boolean {
        val start = IntArray(n)
        fun rec(i: Int): Boolean {
            if (i == n) {
                var t = 0
                val horizon = (0 until n).maxOf { start[it] + durations[it] }
                while (t < horizon) {
                    var load = 0
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
