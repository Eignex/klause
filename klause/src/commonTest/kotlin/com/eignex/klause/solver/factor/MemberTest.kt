package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.backtrack.Vsids
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MemberTest {

    /**
     * Soundness gate for the bound-style sharpened singleton-y conflict reason. Under the
     * full CDCL backtracker (VSIDS + clause forgetting) enumeration must equal the brute-force
     * solution set. An unsound reason — citing too weak a per-candidate exclusion literal —
     * would prune a feasible subtree and drop a solution.
     */
    @Test
    fun `backtrack learning enumerates exactly the brute-force solution set`() {
        // var ids: xs = 0..n-1, y = n. Triple(n, xsRanges, yRange).
        val instances = listOf(
            Triple(2, listOf(0 to 1, 2 to 3), 0 to 3),
            Triple(2, listOf(0 to 2, 1 to 3), 0 to 3),
            Triple(3, listOf(0 to 1, 1 to 2, 2 to 3), 0 to 3),
            Triple(2, listOf(1 to 1, 3 to 3), 0 to 3), // both candidates singleton
            Triple(3, listOf(0 to 0, 2 to 2, 1 to 3), 0 to 3),
        )
        for ((idx, inst) in instances.withIndex()) {
            val (n, xr, yr) = inst
            val k = n + 1
            val brute = HashSet<List<Int>>()
            val acc = IntArray(k)
            fun rec(p: Int) {
                if (p == k) {
                    val yv = acc[n]
                    if ((0 until n).any { acc[it] == yv }) brute.add(acc.toList())
                    return
                }
                val range = if (p < n) xr[p] else yr
                for (v in range.first..range.second) { acc[p] = v; rec(p + 1) }
            }
            rec(0)

            val doms = Array(k) {
                if (it < n) IntDomain(xr[it].first, xr[it].second) else IntDomain(yr.first, yr.second)
            }
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = k,
                intDomains = doms,
                factors = arrayOf<Factor>(Member(xs = IntArray(n) { it }, y = n)),
            )
            val params = BacktrackParams(randomSeed = 1L, variableHeuristic = Vsids(), maxLearnedClauses = 1_000)
            val found = BacktrackSolver(problem).enumerate(params).take(100_000)
                .map { it.ints.toList() }.toHashSet()
            assertEquals(brute, found, "instance #$idx: backtrack solution set must equal brute force")
        }
    }

    @Test
    fun `member_int forces y to match one of xs`() {
        // xs pinned to (1, 3, 7); y ∈ [0..10] → y must be 1, 3, or 7.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(1, 1), IntDomain(3, 3), IntDomain(7, 7), IntDomain(0, 10)),
            factors = arrayOf<Factor>(Member(xs = intArrayOf(0, 1, 2), y = 3)),
        )
        BacktrackSolver(problem).enumerate(BacktrackParams(randomSeed = 0L)).take(10).forEach { sample ->
            assertTrue(
                sample.ints[3] in setOf(1, 3, 7),
                "y = ${sample.ints[3]} not in {1, 3, 7}",
            )
        }
    }

    @Test
    fun `union hull prunes y to values some candidate can take`() {
        // xs domains {2,3} ∪ {7,8}; y ∈ [0,10] must land in {2,3,7,8}.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(2, 3), IntDomain(7, 8), IntDomain(0, 10)),
            factors = arrayOf<Factor>(Member(xs = intArrayOf(0, 1), y = 2)),
        )
        BacktrackSolver(problem).enumerate(BacktrackParams(randomSeed = 0L)).take(50).forEach { sample ->
            assertTrue(sample.ints[2] in setOf(2, 3, 7, 8), "y = ${sample.ints[2]} outside union hull")
        }
    }

    @Test
    fun `unique support forces the only candidate that can match y`() {
        // y pinned to 5; only x1 can be 5 (x0,x2 exclude it) ⟹ x1 forced to 5.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 1), IntDomain(4, 5), IntDomain(0, 1), IntDomain(5, 5)),
            factors = arrayOf<Factor>(Member(xs = intArrayOf(0, 1, 2), y = 3)),
        )
        val sat = assertIs<SolveResult.Sat>(BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L)))
        assertEquals(5, sat.assignment.ints[1], "the unique candidate for y=5 must be forced to 5")
    }

    @Test
    fun `singleton y forces some xs to match`() {
        // y pinned to 5; xs are free. Some xs[i] must take value 5.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 9), IntDomain(0, 9), IntDomain(0, 9), IntDomain(5, 5)),
            factors = arrayOf<Factor>(Member(xs = intArrayOf(0, 1, 2), y = 3)),
        )
        val r = BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        assertTrue(
            5 in sat.assignment.ints.take(3),
            "expected some xs to hold 5; got ${sat.assignment.ints.toList()}",
        )
    }
}
