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

class RegularTest {

    /**
     * Soundness gate for the sharpened forward-collapse conflict reason. Uses a DFA with dead
     * transitions ("no two consecutive 1s") so early-prefix infeasibility fires the collapse
     * path. Under the full CDCL backtracker (VSIDS + clause forgetting) enumeration must equal
     * the brute-force accepted set; an unsound prefix reason would drop a feasible suffix.
     */
    @Test
    fun `backtrack learning enumerates exactly the brute-force solution set`() {
        // alphabet {1,2}; states {1,2}; q0=1; F={1,2}. δ: (1,1)→2 (1,2)→1 (2,1)→0(dead) (2,2)→1.
        // Accepts exactly the strings with no two consecutive 1s.
        val transitions = intArrayOf(2, 1, 0, 1)
        fun delta(q: Int, s: Int): Int =
            if (q in 1..2 && s in 1..2) transitions[(q - 1) * 2 + (s - 1)] else 0
        fun accepts(vals: IntArray): Boolean {
            var q = 1
            for (s in vals) { q = delta(q, s); if (q == 0) return false }
            return q == 1 || q == 2
        }
        // Each instance: per-position symbol range over {1,2}.
        val instances = listOf(
            listOf(1 to 2, 1 to 2, 1 to 2, 1 to 2), // free 4-seq
            listOf(1 to 1, 1 to 1, 1 to 2, 1 to 2), // forces 1,1 prefix → no solution
            listOf(1 to 2, 1 to 1, 1 to 1, 1 to 2), // forces consecutive 1s mid-seq
            listOf(1 to 2, 1 to 2, 1 to 2), // free 3-seq
        )
        for ((idx, ranges) in instances.withIndex()) {
            val n = ranges.size
            val brute = HashSet<List<Int>>()
            val acc = IntArray(n)
            fun rec(p: Int) {
                if (p == n) { if (accepts(acc)) brute.add(acc.toList()); return }
                for (v in ranges[p].first..ranges[p].second) { acc[p] = v; rec(p + 1) }
            }
            rec(0)

            val problem = Problem(
                numBoolVars = 0,
                numIntVars = n,
                intDomains = Array(n) { IntDomain(ranges[it].first, ranges[it].second) },
                factors = arrayOf<Factor>(
                    Regular(
                        seq = IntArray(n) { it },
                        numStates = 2,
                        alphabetSize = 2,
                        transitions = transitions,
                        q0 = 1,
                        accepting = intArrayOf(1, 2),
                    ),
                ),
            )
            val params = BacktrackParams(randomSeed = 1L, variableHeuristic = Vsids(), maxLearnedClauses = 1_000)
            val found = BacktrackSolver(problem).enumerate(params).take(100_000)
                .map { it.ints.toList() }.toHashSet()
            assertEquals(brute, found, "instance #$idx: backtrack solution set must equal brute force")
        }
    }

    @Test
    fun `regular accepts strings matching the DFA`() {
        // DFA: alphabet = {1, 2}; states = {1, 2}; q0 = 1, F = {2}.
        // δ(1, 1) = 1, δ(1, 2) = 2, δ(2, 1) = 1, δ(2, 2) = 2.
        // Accepts strings ending in 2. 4-length seq ∈ {1, 2}^4.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = Array(4) { IntDomain(1, 2) },
            factors = arrayOf<Factor>(
                Regular(
                    seq = intArrayOf(0, 1, 2, 3),
                    numStates = 2,
                    alphabetSize = 2,
                    // T[(q-1)*S + (s-1)] :  (1,1)→1, (1,2)→2, (2,1)→1, (2,2)→2
                    transitions = intArrayOf(1, 2, 1, 2),
                    q0 = 1,
                    accepting = intArrayOf(2),
                ),
            ),
        )
        // Every accepted string must end in 2.
        BacktrackSolver(problem).enumerate(BacktrackParams(randomSeed = 0L)).take(20).forEach { sample ->
            assertEquals(2, sample.ints[3], "regular violated: ints=${sample.ints.toList()}")
        }
    }

    @Test
    fun `regular rejects pinned-to-fail strings`() {
        // Same DFA. Pin seq = (1, 1, 1, 1) → ends in state 1 ∉ F. Unsat.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = Array(4) { IntDomain(1, 1) },
            factors = arrayOf<Factor>(
                Regular(
                    seq = intArrayOf(0, 1, 2, 3),
                    numStates = 2,
                    alphabetSize = 2,
                    transitions = intArrayOf(1, 2, 1, 2),
                    q0 = 1,
                    accepting = intArrayOf(2),
                ),
            ),
        )
        assertIs<SolveResult.Unsat>(BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L)))
    }
}
