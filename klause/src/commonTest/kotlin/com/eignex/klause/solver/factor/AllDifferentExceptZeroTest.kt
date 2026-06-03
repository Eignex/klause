package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.backtrack.Vsids
import com.eignex.klause.solver.propagation.PropagationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AllDifferentExceptZeroTest {

    @Test
    fun `non-zero values must be distinct`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = Array(4) { IntDomain(0, 3) },
            factors = arrayOf<Factor>(AllDifferentExceptZero(intArrayOf(0, 1, 2, 3))),
        )
        BacktrackSolver(problem).enumerate(BacktrackParams(randomSeed = 0L)).take(20).forEach { sample ->
            // Tally non-zero values.
            val nonZero = sample.ints.filter { it != 0 }
            assertEquals(nonZero.distinct().size, nonZero.size, "non-zero dup in $sample")
        }
    }

    @Test
    fun `multiple zero values are allowed`() {
        // 5 vars, but only 3 non-zero values possible — must use zero on at least 2.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 5,
            intDomains = Array(5) { IntDomain(0, 3) },
            factors = arrayOf<Factor>(AllDifferentExceptZero(intArrayOf(0, 1, 2, 3, 4))),
        )
        val r = BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        val ints = sat.assignment.ints.toList()
        val zeros = ints.count { it == 0 }
        assertTrue(zeros >= 2, "expected ≥ 2 zeros for 5-var/3-value problem; got $ints")
    }

    /**
     * Soundness gate for the sharpened (pair / single-owner) conflict and punch-out
     * explanations. Under the full CDCL backtracker (VSIDS + LBD clause forgetting, so the
     * sharpened antecedents are exercised by clause learning) enumeration must equal the
     * brute-force solution set on a battery of singleton-prone instances. An unsound reason —
     * e.g. citing too few owners — would prune a feasible subtree and drop a solution.
     */
    @Test
    fun `backtrack learning enumerates exactly the brute-force solution set`() {
        val instances = listOf(
            listOf(0 to 3, 0 to 3, 0 to 3, 0 to 3), // free 4-var
            listOf(1 to 1, 1 to 3, 1 to 3, 0 to 3), // singleton v0=1 punches 1 out of others
            listOf(2 to 2, 2 to 2, 0 to 3), // clashing singletons → no solution
            listOf(1 to 2, 1 to 2, 1 to 2, 0 to 2), // 3 vars over {1,2} non-zero → forces zeros
            listOf(0 to 2, 1 to 2, 0 to 1, 0 to 2, 0 to 2), // 5 vars, overlapping tight set
        )
        for ((idx, ranges) in instances.withIndex()) {
            val k = ranges.size
            val brute = HashSet<List<Int>>()
            fun rec(i: Int, acc: IntArray) {
                if (i == k) {
                    val nz = acc.filter { it != 0 }
                    if (nz.distinct().size == nz.size) brute.add(acc.toList())
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
                factors = arrayOf<Factor>(AllDifferentExceptZero(IntArray(k) { it })),
            )
            val params = BacktrackParams(
                randomSeed = 1L,
                variableHeuristic = Vsids(),
                maxLearnedClauses = 1_000,
            )
            val found = BacktrackSolver(problem).enumerate(params).take(100_000)
                .map { it.ints.toList() }.toHashSet()
            assertEquals(brute, found, "instance #$idx: backtrack solution set must equal brute force")
        }
    }

    @Test
    fun `Régin detects a non-zero Hall violation that singleton-take misses`() {
        // Three vars over {1,2} with no zero available: 3 distinct non-zero values needed from
        // a 2-value set ⟹ UNSAT. No var is singleton, so singleton-take can't see it; the
        // shared Régin matching pass does. Asserted at propagation time (root).
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(1, 2), IntDomain(1, 2), IntDomain(1, 2)),
            factors = arrayOf<Factor>(AllDifferentExceptZero(intArrayOf(0, 1, 2))),
        )
        assertIs<PropagationResult.Unsat>(problem.propagate(Assumptions.None))
    }

    @Test
    fun `Régin prunes Hall-set values from a free var`() {
        // x0,x1 ∈ {1,2} (no zero) cover {1,2}; x2 ∈ {1,2,3} must therefore take 3. Pure Régin
        // inference — singleton-take alone prunes nothing here.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(1, 2), IntDomain(1, 2), IntDomain(1, 3)),
            factors = arrayOf<Factor>(AllDifferentExceptZero(intArrayOf(0, 1, 2))),
        )
        val impl = assertIs<PropagationResult.Implied>(problem.propagate(Assumptions.None))
        assertEquals(3, impl.intValueOrNull(2), "x2 must be pruned to 3 by the Hall set {1,2}")
    }

    @Test
    fun `zeros stay shareable under Régin`() {
        // Régin must NOT prune the excepted value 0: three vars can all be 0.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 0), IntDomain(0, 0), IntDomain(0, 2)),
            factors = arrayOf<Factor>(AllDifferentExceptZero(intArrayOf(0, 1, 2))),
        )
        val impl = assertIs<PropagationResult.Implied>(problem.propagate(Assumptions.None))
        // x2 keeps 0 (sharing zero is allowed); it is not forced off 0.
        assertEquals(null, impl.intValueOrNull(2), "x2 should not be pinned; 0 stays available")
    }

    @Test
    fun `two non-zero singletons clashing → Unsat`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(2, 2), IntDomain(2, 2), IntDomain(0, 3)),
            factors = arrayOf<Factor>(AllDifferentExceptZero(intArrayOf(0, 1, 2))),
        )
        assertIs<SolveResult.Unsat>(BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L)))
    }
}
