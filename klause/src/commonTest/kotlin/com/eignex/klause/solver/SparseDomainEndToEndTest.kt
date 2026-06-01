package com.eignex.klause.solver

import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.backtrack.InputOrder
import com.eignex.klause.solver.factor.AllDifferent
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * End-to-end coverage that drives the engines through propagation paths producing
 * sparse domains and verifies the resulting search still terminates correctly with
 * a valid model.
 */
class SparseDomainEndToEndTest {

    @Test
    fun `BacktrackSolver finds model with mid-search sparse domain via AllDifferent`() {
        // v0 ∈ [1, 5]; v1, v2 are singletons pinning values 3 and 5 respectively.
        // AllDifferent propagation punches 3 and 5 out of v0's domain via the new
        // sparse-aware singleton path → v0 must be 1, 2, or 4.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(1, 5), IntDomain(3, 3), IntDomain(5, 5)),
            factors = arrayOf<Factor>(
                AllDifferent(intArrayOf(0, 1, 2), domainMin = 1, domainSize = 5),
            ),
        )
        val r = BacktrackSolver(problem).solve(
            BacktrackParams(
                variableHeuristic = InputOrder,
                randomSeed = 0L,
            ),
        )
        val sat = assertIs<SolveResult.Sat>(r)
        assertTrue(
            sat.assignment.ints[0] in intArrayOf(1, 2, 4),
            "v0 should be in {1, 2, 4} (3 and 5 punched out); got ${sat.assignment.ints[0]}",
        )
        assertEquals(3, sat.assignment.ints[1])
        assertEquals(5, sat.assignment.ints[2])
    }

    @Test
    fun `BacktrackSolver enumerates all models with sparse domain`() {
        // Same shape as above; enumerate all assignments. Only 3 valid models:
        //   (1, 3, 5), (2, 3, 5), (4, 3, 5).
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(1, 5), IntDomain(3, 3), IntDomain(5, 5)),
            factors = arrayOf<Factor>(
                AllDifferent(intArrayOf(0, 1, 2), domainMin = 1, domainSize = 5),
            ),
        )
        val models = BacktrackSolver(problem).enumerate(
            BacktrackParams(
                variableHeuristic = InputOrder,
                randomSeed = 0L,
            ),
        ).toList()
        val v0Values = models.map { it.ints[0] }.sorted()
        assertEquals(
            listOf(1, 2, 4),
            v0Values,
            "should enumerate exactly the 3 valid assignments; got $v0Values",
        )
    }

    @Test
    fun `BacktrackSolver respects sparse hole produced by Linear NE`() {
        // x + y != 5, y singleton at 2 → x != 3. Domain originally [1, 5], becomes
        // [1, 5] - {3}. Search should never pick x = 3.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(1, 5), IntDomain(2, 2)),
            factors = arrayOf<Factor>(
                Linear(
                    coeffs = intArrayOf(1, 1),
                    vars = intArrayOf(0, 1),
                    op = LinearOp.NE,
                    bound = 5,
                ),
            ),
        )
        // Enumerate all models, verify none has x = 3.
        val models = BacktrackSolver(problem).enumerate(BacktrackParams(randomSeed = 0L)).toList()
        assertEquals(4, models.size, "expected 4 models (x ∈ {1, 2, 4, 5}); got ${models.map { it.ints.toList() }}")
        for (m in models) {
            assertTrue(
                m.ints[0] != 3,
                "x = 3 violates the disequality; got $m",
            )
        }
    }

    @Test
    fun `LocalSearchSolver finds model when AllDifferent punches sparse holes`() {
        // Same problem as the first test. LS should reach a satisfying assignment.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(1, 5), IntDomain(3, 3), IntDomain(5, 5)),
            factors = arrayOf<Factor>(
                AllDifferent(intArrayOf(0, 1, 2), domainMin = 1, domainSize = 5),
            ),
        )
        val solver = LocalSearchSolver(problem)
        val r = solver.solve(LocalSearchParams(maxFlips = 5_000, randomSeed = 7L))
        val sat = assertIs<SolveResult.Sat>(r)
        // Verify legal assignment: all-different, and (v1, v2) at their singletons.
        assertEquals(3, sat.assignment.ints[1])
        assertEquals(5, sat.assignment.ints[2])
        assertTrue(
            sat.assignment.ints[0] != 3 && sat.assignment.ints[0] != 5,
            "v0 must differ from v1=3 and v2=5; got ${sat.assignment.ints[0]}",
        )
    }

    @Test
    fun `LocalSearchSolver random restart uses sparse-aware value picks`() {
        // Drive many restarts on a sparse-domain problem; if the random-value picker
        // ignored holes, the cost would oscillate around an invalid baseline. Here we
        // just verify it terminates correctly across multiple restarts.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(
                IntDomain(1, 10),
                IntDomain(3, 3),
                IntDomain(5, 5),
                IntDomain(7, 7),
            ),
            factors = arrayOf<Factor>(
                AllDifferent(intArrayOf(0, 1, 2, 3), domainMin = 1, domainSize = 10),
            ),
        )
        val solver = LocalSearchSolver(problem)
        val r = solver.solve(LocalSearchParams(maxFlips = 5_000, randomSeed = 42L))
        val sat = assertIs<SolveResult.Sat>(r)
        // v0 must not be 3, 5, or 7.
        val v0 = sat.assignment.ints[0]
        assertTrue(
            v0 !in setOf(3, 5, 7),
            "v0 must avoid singleton-taken values; got $v0",
        )
        assertTrue(v0 in 1..10, "v0 must be in domain bounds; got $v0")
    }
}
