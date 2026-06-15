package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.backtrack.selector.Vsids
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Soundness gate for [AllDifferent]'s excepted-value support (the `alldifferent_except_0` family,
 * #433). Excepted values route through the shared [reginFilter] matching pass modelled as
 * capacity-n value copies, so enumeration under CDCL learning (VSIDS + clause forgetting,
 * exercising the hole-aware Hall-set antecedents) must equal the brute-force solution set — the
 * bounds-only antecedents #99 fixed used to drop solutions here. The except logic lives on
 * [AllDifferent] itself (no dedicated factor).
 */
class AllDifferentExceptTest {

    private fun allDiffExcept(ranges: List<Pair<Int, Int>>, except: IntArray): Problem {
        val lo = ranges.minOf { it.first }
        val hi = ranges.maxOf { it.second }
        val k = ranges.size
        return Problem(
            numBoolVars = 0,
            numIntVars = k,
            intDomains = Array(k) { IntDomain(ranges[it].first, ranges[it].second) },
            factors = arrayOf<Factor>(
                AllDifferent(IntArray(k) { it }, domainMin = lo, domainSize = hi - lo + 1, exceptSet = except),
            ),
        )
    }

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
            val params = BacktrackParams(randomSeed = 1L, variableSelector = Vsids(), maxLearnedClauses = 1_000)
            val found = BacktrackSolver(allDiffExcept(ranges, except)).enumerate(params)
                .take(100_000).map { it.ints.toList() }.toHashSet()
            assertEquals(brute, found, "instance #$idx (except=${except.toList()}): solver set must equal brute")
        }
    }

    @Test
    fun `non-except values must be pairwise distinct`() {
        // 3 vars over {1,2} with except={5}: {1,2} can't host 3 distinct ⟹ UNSAT.
        val problem = allDiffExcept(listOf(1 to 2, 1 to 2, 1 to 2), intArrayOf(5))
        assertIs<SolveResult.Unsat>(BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L)))
    }
}
