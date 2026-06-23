package com.eignex.klause.solver.factor.global

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.factor.FactorPropagationOracle
import com.eignex.klause.solver.factor.global.Sort
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SortPropagatorTest {

    private fun sortProblem(domains: Array<IntDomain>): Problem {
        val n = domains.size / 2
        return Problem(
            numBoolVars = 0,
            numIntVars = domains.size,
            intDomains = domains,
            factors = arrayOf<Factor>(
                Sort(xs = IntArray(n) { it }, ys = IntArray(n) { n + it }),
            ),
        )
    }

    @Test
    fun `mehlhorn-thiel filtering never over-prunes`() {
        // Brute-force oracle: every bound the propagator tightens must hold on all sortedness
        // solutions. Catches an over-pruning transcription bug in the matching / SCC narrowing.
        // Instances are kept small so BruteForceSolver stays under its 2^18 enumeration cap.
        val rng = Random(0x5021)
        repeat(400) { iter ->
            val n = 2 + rng.nextInt(2) // 2 or 3
            val maxVal = if (n == 3) 5 else 6
            val domains = Array(2 * n) {
                val a = rng.nextInt(maxVal + 1)
                val b = rng.nextInt(maxVal + 1)
                IntDomain(minOf(a, b), maxOf(a, b))
            }
            FactorPropagationOracle.assertSound(sortProblem(domains), "sort#$iter")
        }
    }

    @Test
    fun `mehlhorn-thiel filtering never over-prunes on width-four vectors`() {
        val rng = Random(0x404)
        repeat(200) { iter ->
            val domains = Array(8) {
                val a = rng.nextInt(4)
                val b = rng.nextInt(4)
                IntDomain(minOf(a, b), maxOf(a, b))
            }
            FactorPropagationOracle.assertSound(sortProblem(domains), "sort4#$iter")
        }
    }

    @Test
    fun `sort matches sorted xs`() {
        // xs pinned to (3, 1, 2); ys = sorted(xs) = (1, 2, 3).
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 6,
            intDomains = arrayOf(
                IntDomain(3, 3),
                IntDomain(1, 1),
                IntDomain(2, 2),
                IntDomain(0, 9),
                IntDomain(0, 9),
                IntDomain(0, 9),
            ),
            factors = arrayOf<Factor>(Sort(xs = intArrayOf(0, 1, 2), ys = intArrayOf(3, 4, 5))),
        )
        val r = BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        assertEquals(1, sat.assignment.ints[3])
        assertEquals(2, sat.assignment.ints[4])
        assertEquals(3, sat.assignment.ints[5])
    }

    @Test
    fun `sort with duplicates`() {
        // xs = (1, 2, 1) → ys = (1, 1, 2).
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 6,
            intDomains = arrayOf(
                IntDomain(1, 1),
                IntDomain(2, 2),
                IntDomain(1, 1),
                IntDomain(0, 9),
                IntDomain(0, 9),
                IntDomain(0, 9),
            ),
            factors = arrayOf<Factor>(Sort(xs = intArrayOf(0, 1, 2), ys = intArrayOf(3, 4, 5))),
        )
        val r = BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        assertEquals(listOf(1, 1, 2), listOf(sat.assignment.ints[3], sat.assignment.ints[4], sat.assignment.ints[5]))
    }
}
