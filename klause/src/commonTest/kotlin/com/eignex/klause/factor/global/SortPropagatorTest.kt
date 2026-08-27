package com.eignex.klause.factor.global

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.FactorPropagationOracle
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
                IntDomain(minOf(a, b).toLong(), maxOf(a, b).toLong())
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
                IntDomain(minOf(a, b).toLong(), maxOf(a, b).toLong())
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
        val r = BacktrackSolver(problem.bake()).solve(BacktrackParams(randomSeed = 0L))
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
        val r = BacktrackSolver(problem.bake()).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        assertEquals(listOf(1L, 1L, 2L), listOf(sat.assignment.ints[3], sat.assignment.ints[4], sat.assignment.ints[5]))
    }

    @Test
    fun `two searches sharing one propagator keep independent per-search state`() {
        // A Propagator instance is cached once per Problem and shared across every PropagationState,
        // including the concurrent arms of a parallel portfolio. So the sort working state (matchings,
        // SCC scratch, active-state ref) must live per-search in refPayload, never as propagator
        // fields — else two arms racing on one instance corrupt each other. Two states over one
        // problem drive the *same* propagator with different pinned xs; each must sort independently
        // and own a distinct SortWork.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 6,
            intDomains = arrayOf(
                IntDomain(1, 3),
                IntDomain(1, 3),
                IntDomain(1, 3),
                IntDomain(0, 9),
                IntDomain(0, 9),
                IntDomain(0, 9),
            ),
            factors = arrayOf<Factor>(Sort(xs = intArrayOf(0, 1, 2), ys = intArrayOf(3, 4, 5))),
        )
        val prop = problem.propagators[0]

        val a = PropagationState(problem, Assumptions.None).apply { undoLogging = true }
        val b = PropagationState(problem, Assumptions.None).apply { undoLogging = true }
        // xs pinned differently per search: a → (3,1,2) sorts to (1,2,3); b → (3,3,3) sorts to (3,3,3).
        for ((v, value) in listOf(0 to 3L, 1 to 1L, 2 to 2L)) check(a.setInt(v, value))
        for ((v, value) in listOf(0 to 3L, 1 to 3L, 2 to 3L)) check(b.setInt(v, value))

        // Interleave fires so a field-held active-state would let one search read the other's domains.
        a.currentFactor = 0
        b.currentFactor = 0
        assertTrue(prop.propagate(a, 0))
        assertTrue(prop.propagate(b, 0))
        assertTrue(prop.propagate(a, 0))

        assertEquals(listOf(1L, 2L, 3L), listOf(3, 4, 5).map { a.intDomains[it].min })
        assertEquals(listOf(1L, 2L, 3L), listOf(3, 4, 5).map { a.intDomains[it].max })
        assertEquals(listOf(3L, 3L, 3L), listOf(3, 4, 5).map { b.intDomains[it].min })

        val workA = assertNotNull(a.refPayload[0], "search a must hold its sort working state in refPayload")
        val workB = assertNotNull(b.refPayload[0], "search b must hold its sort working state in refPayload")
        assertTrue(workA !== workB, "each search must own a distinct SortWork; a shared instance is the race")
    }
}
