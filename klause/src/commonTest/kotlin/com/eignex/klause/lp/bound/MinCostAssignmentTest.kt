package com.eignex.klause.lp.bound

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** #23: exact min-cost bipartite assignment (the AllDifferent Lagrangian subproblem). */
class MinCostAssignmentTest {

    @Test
    fun `simple assignment picks the cheapest distinct values`() {
        // 2 vars, 2 values; the cheap diagonal (0,1) costs 1+1, the anti-diagonal 5+5.
        val a = MinCostAssignment(2, 2)
        a.addOption(0, 0, 1)
        a.addOption(0, 1, 5)
        a.addOption(1, 0, 5)
        a.addOption(1, 1, 1)
        val r = a.solve()
        assertTrue(r.feasible)
        assertEquals(2L, r.cost)
        assertEquals(0, r.assignedValue[0])
        assertEquals(1, r.assignedValue[1])
    }

    @Test
    fun `detects infeasibility when no perfect matching exists`() {
        // Both variables can only take value 0 — no distinct assignment.
        val a = MinCostAssignment(2, 2)
        a.addOption(0, 0, 1)
        a.addOption(1, 0, 1)
        assertTrue(!a.solve().feasible)
    }

    @Test
    fun `handles negative costs`() {
        val a = MinCostAssignment(2, 3)
        a.addOption(0, 0, -3)
        a.addOption(0, 1, -1)
        a.addOption(1, 1, -1)
        a.addOption(1, 2, -5)
        val r = a.solve()
        assertTrue(r.feasible)
        assertEquals(-8L, r.cost) // var0→val0 (-3), var1→val2 (-5)
    }

    @Test
    fun `randomized parity with brute-force permutation search`() {
        val rng = Random(20260608)
        var checked = 0
        repeat(600) { _ ->
            val n = rng.nextInt(1, 5)
            val m = rng.nextInt(n, n + 4)
            // Random sparse options: present[i][j] flags an allowed (i,j); cost[i][j] its cost.
            val present = Array(n) { BooleanArray(m) }
            val cost = Array(n) { LongArray(m) }
            val a = MinCostAssignment(n, m)
            for (i in 0 until n) {
                for (j in 0 until m) {
                    if (rng.nextInt(3) != 0) { // ~2/3 of options present
                        present[i][j] = true
                        cost[i][j] = rng.nextInt(-5, 10).toLong()
                        a.addOption(i, j, cost[i][j])
                    }
                }
            }
            val r = a.solve()
            val (feasible, best) = bruteForce(present, cost, n, m)
            assertEquals(feasible, r.feasible, "feasibility mismatch")
            if (feasible) {
                assertEquals(best, r.cost, "cost mismatch")
                // Verify the returned assignment is distinct and valid.
                val used = HashSet<Int>()
                var sum = 0L
                for (i in 0 until n) {
                    val v = r.assignedValue[i]
                    assertTrue(v in 0 until m && present[i][v], "invalid option ($i,$v)")
                    assertTrue(used.add(v), "value $v assigned twice")
                    sum += cost[i][v]
                }
                assertEquals(best, sum, "recovered assignment cost mismatch")
            }
            checked++
        }
        assertTrue(checked == 600)
    }

    /** Min cost over all injective var→value maps; (false, 0) if none exists. */
    private fun bruteForce(present: Array<BooleanArray>, cost: Array<LongArray>, n: Int, m: Int): Pair<Boolean, Long> {
        var best: Long? = null
        val used = BooleanArray(m)
        fun rec(i: Int, acc: Long) {
            if (i == n) {
                val b = best
                if (b == null || acc < b) best = acc
                return
            }
            for (j in 0 until m) {
                if (present[i][j] && !used[j]) {
                    used[j] = true
                    rec(i + 1, acc + cost[i][j])
                    used[j] = false
                }
            }
        }
        rec(0, 0L)
        return (best != null) to (best ?: 0L)
    }
}
