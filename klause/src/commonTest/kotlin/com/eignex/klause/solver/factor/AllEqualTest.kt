package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AllEqualTest {

    @Test
    fun `every var takes the same value`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = Array(3) { IntDomain(0, 4) },
            factors = arrayOf<Factor>(AllEqual(intArrayOf(0, 1, 2))),
        )
        BacktrackSolver(problem).enumerate(BacktrackParams(randomSeed = 0L)).take(20).forEach { sample ->
            val v = sample.ints[0]
            for (i in 1..2) assertTrue(sample.ints[i] == v, "all_equal violated: $sample")
        }
    }

    @Test
    fun `propagation intersects domains`() {
        // v0 ∈ [1, 5], v1 ∈ [3, 7], v2 ∈ [4, 8]. Intersection [4, 5].
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(1, 5), IntDomain(3, 7), IntDomain(4, 8)),
            factors = arrayOf<Factor>(AllEqual(intArrayOf(0, 1, 2))),
        )
        val r = BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        val v = sat.assignment.ints[0]
        assertTrue(v in 4..5, "value $v outside intersection [4,5]")
        for (i in 1..2) assertTrue(sat.assignment.ints[i] == v)
    }

    @Test
    fun `disjoint domains is Unsat`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(5, 9)),
            factors = arrayOf<Factor>(AllEqual(intArrayOf(0, 1))),
        )
        assertIs<SolveResult.Unsat>(BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L)))
    }

    @Test
    fun `holey domains intersect to common members only`() {
        // v0,v1 ∈ {0,2}, v2 ∈ {0,1,2}: the only feasible shared values are 0 and 2 (never 1),
        // which a bounds-only intersection would miss. Exercises hole pruning end-to-end.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(
                IntDomain(0, 2).excludeValue(1),
                IntDomain(0, 2).excludeValue(1),
                IntDomain(0, 2),
            ),
            factors = arrayOf<Factor>(AllEqual(intArrayOf(0, 1, 2))),
        )
        val sat = assertIs<SolveResult.Sat>(BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L)))
        val v = sat.assignment.ints[0]
        assertTrue(v == 0 || v == 2, "shared value $v is not a common member")
        for (i in 1..2) assertTrue(sat.assignment.ints[i] == v)
    }

    @Test
    fun `empty hole-intersection is Unsat`() {
        // dom(v0) = {0,2}, dom(v1) = {1}: overlapping bounds but no common member. The
        // hole-aware conflict reason must drive a sound UNSAT, not a bounds-only pin to 1.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 2).excludeValue(1), IntDomain(1, 1)),
            factors = arrayOf<Factor>(AllEqual(intArrayOf(0, 1))),
        )
        assertIs<SolveResult.Unsat>(BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L)))
    }
}
