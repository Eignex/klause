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

class GlobalCardinalityTest {

    @Test
    fun `gcc with count vars`() {
        // xs ∈ [0..2]^5, cover = [0,1,2], count vars are last 3 vars. Each count must equal
        // the # of xs taking that cover value.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 8,
            intDomains = Array(8) { i -> if (i < 5) IntDomain(0, 2) else IntDomain(0, 5) },
            factors = arrayOf<Factor>(
                GlobalCardinality(
                    xs = intArrayOf(0, 1, 2, 3, 4),
                    cover = intArrayOf(0, 1, 2),
                    countVars = intArrayOf(5, 6, 7),
                ),
            ),
        )
        val r = BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        val xs = (0..4).map { sat.assignment.ints[it] }
        for (k in 0..2) {
            val expected = xs.count { it == k }
            assertEquals(expected, sat.assignment.ints[5 + k], "count[$k] mismatch")
        }
    }

    @Test
    fun `gcc low_up enforces bounds`() {
        // 6 xs ∈ [0..2], cover = [0,1,2], lo=[1,1,1], up=[3,3,3].
        // Every value must appear ≥ 1 and ≤ 3 times.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 6,
            intDomains = Array(6) { IntDomain(0, 2) },
            factors = arrayOf<Factor>(
                GlobalCardinality(
                    xs = intArrayOf(0, 1, 2, 3, 4, 5),
                    cover = intArrayOf(0, 1, 2),
                    countLow = intArrayOf(1, 1, 1),
                    countHigh = intArrayOf(3, 3, 3),
                ),
            ),
        )
        val r = BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        val xs = (0..5).map { sat.assignment.ints[it] }
        for (k in 0..2) {
            val c = xs.count { it == k }
            assertTrue(c in 1..3, "count[$k]=$c out of [1, 3]; xs=$xs")
        }
    }

    @Test
    fun `closed variant rejects values outside cover`() {
        // 3 xs ∈ [0..5]; cover = {1, 2, 3}; closed → xs must each be in {1, 2, 3}.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = Array(3) { IntDomain(0, 5) },
            factors = arrayOf<Factor>(
                GlobalCardinality(
                    xs = intArrayOf(0, 1, 2),
                    cover = intArrayOf(1, 2, 3),
                    countLow = intArrayOf(0, 0, 0),
                    countHigh = intArrayOf(3, 3, 3),
                    closed = true,
                ),
            ),
        )
        val r = BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        for (i in 0..2) {
            assertTrue(
                sat.assignment.ints[i] in setOf(1, 2, 3),
                "closed gcc: xs[$i] = ${sat.assignment.ints[i]} not in cover",
            )
        }
    }
}
