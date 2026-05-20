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

class NValueTest {

    @Test
    fun `nvalue counts distinct values exactly`() {
        // xs of size 4, n must equal distinct values. Pin some duplicates.
        val problem = Problem(
            numBoolVars = 0, numIntVars = 5,
            intDomains = arrayOf(
                IntDomain(1, 1), IntDomain(1, 1), IntDomain(2, 2), IntDomain(3, 3),
                IntDomain(0, 5),
            ),
            factors = arrayOf<Factor>(NValue(n = 4, xs = intArrayOf(0, 1, 2, 3), mode = NValue.Mode.Eq)),
        )
        val r = BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        assertEquals(3, sat.assignment.ints[4], "distinct = {1, 2, 3} = 3")
    }

    @Test
    fun `atleast_nvalues enforces n ≤ distinct`() {
        // 4 xs each ∈ [0, 3]. n = 4 forces all-different. 4 distinct values within [0,3].
        val problem = Problem(
            numBoolVars = 0, numIntVars = 5,
            intDomains = arrayOf(
                IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3),
                IntDomain(4, 4),
            ),
            factors = arrayOf<Factor>(NValue(n = 4, xs = intArrayOf(0, 1, 2, 3), mode = NValue.Mode.AtLeast)),
        )
        val r = BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        // Distinct count must be ≥ 4 → all-different.
        val vs = listOf(sat.assignment.ints[0], sat.assignment.ints[1], sat.assignment.ints[2], sat.assignment.ints[3])
        assertEquals(vs.distinct().size, vs.size, "atleast_nvalues 4 → all-different; got $vs")
    }

    @Test
    fun `atmost_nvalues caps distinct count`() {
        // 4 xs ∈ [1, 5], n = 2: at most 2 distinct values used.
        val problem = Problem(
            numBoolVars = 0, numIntVars = 5,
            intDomains = arrayOf(
                IntDomain(1, 5), IntDomain(1, 5), IntDomain(1, 5), IntDomain(1, 5),
                IntDomain(2, 2),
            ),
            factors = arrayOf<Factor>(NValue(n = 4, xs = intArrayOf(0, 1, 2, 3), mode = NValue.Mode.AtMost)),
        )
        val r = BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        val vs = listOf(sat.assignment.ints[0], sat.assignment.ints[1], sat.assignment.ints[2], sat.assignment.ints[3])
        assertTrue(vs.distinct().size <= 2, "atmost_nvalues 2 violated: got $vs")
    }
}
