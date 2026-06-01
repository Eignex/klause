package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
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
