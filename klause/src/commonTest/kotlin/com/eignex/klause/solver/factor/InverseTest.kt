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

class InverseTest {

    @Test
    fun `0-based inverse pair`() {
        // 3 vars on each side, 0-based. Any valid permutation pair satisfies.
        val problem = Problem(
            numBoolVars = 0, numIntVars = 6,
            intDomains = Array(6) { IntDomain(0, 2) },
            factors = arrayOf<Factor>(Inverse(f = intArrayOf(0, 1, 2), g = intArrayOf(3, 4, 5))),
        )
        val r = BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        val f = listOf(sat.assignment.ints[0], sat.assignment.ints[1], sat.assignment.ints[2])
        val g = listOf(sat.assignment.ints[3], sat.assignment.ints[4], sat.assignment.ints[5])
        // For each i: g[f[i]] = i.
        for (i in 0..2) assertEquals(i, g[f[i]], "g[f[$i]]=g[${f[i]}]=${g[f[i]]} ≠ $i")
    }

    @Test
    fun `inverse with 1-based offsets`() {
        // f, g both 1-indexed, domain [1..3].
        val problem = Problem(
            numBoolVars = 0, numIntVars = 6,
            intDomains = Array(6) { IntDomain(1, 3) },
            factors = arrayOf<Factor>(Inverse(
                f = intArrayOf(0, 1, 2), g = intArrayOf(3, 4, 5),
                fOffset = 1, gOffset = 1,
            )),
        )
        val r = BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        val f = listOf(sat.assignment.ints[0], sat.assignment.ints[1], sat.assignment.ints[2])
        val g = listOf(sat.assignment.ints[3], sat.assignment.ints[4], sat.assignment.ints[5])
        // For each i (1-based): g[f[i] - 1] = i.
        for (i in 1..3) assertEquals(i, g[f[i - 1] - 1], "g[f[$i]]=${g[f[i - 1] - 1]} ≠ $i")
    }

    @Test
    fun `singleton on one side forces the other`() {
        // f[0] = 2 pinned ⇒ g[2] = 0 forced.
        val problem = Problem(
            numBoolVars = 0, numIntVars = 6,
            intDomains = arrayOf(
                IntDomain(2, 2), IntDomain(0, 2), IntDomain(0, 2),
                IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2),
            ),
            factors = arrayOf<Factor>(Inverse(f = intArrayOf(0, 1, 2), g = intArrayOf(3, 4, 5))),
        )
        val r = BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        assertEquals(0, sat.assignment.ints[5], "g[2] (= var 5) must equal 0")
    }
}
