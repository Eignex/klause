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

class AllDifferentExceptTest {

    /**
     * Soundness gate for the shared [reginFilter] matching pass under CDCL learning. Enumeration
     * (VSIDS + clause forgetting, exercising the Hall-set hole-aware antecedents) must equal the
     * brute-force solution set. The previous bounds-only antecedents made this drop solutions.
     */
    @Test
    fun `backtrack learning enumerates exactly the brute-force solution set`() {
        // (ranges, except). var ids = 0..k-1.
        val instances = listOf(
            Pair(listOf(0 to 3, 0 to 3, 0 to 3, 0 to 3), intArrayOf(0)),
            Pair(listOf(0 to 2, 1 to 2, 0 to 1, 0 to 2, 0 to 2), intArrayOf(0)),
            Pair(listOf(1 to 3, 1 to 3, 1 to 3), intArrayOf(2)), // except a non-zero value
            Pair(listOf(0 to 2, 0 to 2, 0 to 2, 0 to 2), intArrayOf(0, 1)), // two excepted values
        )
        for ((idx, inst) in instances.withIndex()) {
            val (ranges, except) = inst
            val exceptSet = except.toHashSet()
            val k = ranges.size
            val brute = HashSet<List<Int>>()
            fun rec(i: Int, acc: IntArray) {
                if (i == k) {
                    val nonExcept = acc.filter { it !in exceptSet }
                    if (nonExcept.distinct().size == nonExcept.size) brute.add(acc.toList())
                    return
                }
                for (v in ranges[i].first..ranges[i].second) {
                    acc[i] = v
                    rec(i + 1, acc)
                }
            }
            rec(0, IntArray(k))
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = k,
                intDomains = Array(k) { IntDomain(ranges[it].first, ranges[it].second) },
                factors = arrayOf<Factor>(AllDifferentExcept(IntArray(k) { it }, except = except)),
            )
            val params = BacktrackParams(randomSeed = 1L, variableHeuristic = Vsids(), maxLearnedClauses = 1_000)
            val found = BacktrackSolver(problem).enumerate(params).take(100_000).map { it.ints.toList() }.toHashSet()
            assertEquals(brute, found, "instance #$idx (except=${except.toList()}): solver set must equal brute")
        }
    }

    @Test
    fun `non-except values must be pairwise distinct`() {
        // 3 vars over {1,2} with except={5}: {1,2} can't host 3 distinct ⟹ UNSAT.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(1, 2), IntDomain(1, 2), IntDomain(1, 2)),
            factors = arrayOf<Factor>(AllDifferentExcept(intArrayOf(0, 1, 2), except = intArrayOf(5))),
        )
        assertIs<SolveResult.Unsat>(BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L)))
    }
}
