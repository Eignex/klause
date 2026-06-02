package com.eignex.klause.portfolio

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.LinearObjective
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.MinimizeResult.Optimal
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class PortfolioTest {

    @Test
    fun `portfolio solve on satisfiable problem returns sat`() = runTest {
        val problem = exactlyOneOver(4)
        val workers = List(4) { i -> BacktrackSolver(problem).session() }
        Portfolio(workers).use { p ->
            val r = p.solve(BacktrackParams(randomSeed = 1L))
            assertTrue(r is SolveResult.Sat, "expected Sat, got $r")
        }
    }

    @Test
    fun `portfolio solve on unsat problem returns unsat`() = runTest {
        // x ∧ ¬x → trivially unsat
        val problem = Problem(
            numBoolVars = 1,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(0, false))),
            ),
        )
        val workers = List(2) { BacktrackSolver(problem).session() }
        Portfolio(workers).use { p ->
            val r = p.solve(BacktrackParams(randomSeed = 0L))
            assertIs<SolveResult.Unsat>(r)
            Unit
        }
    }

    @Test
    fun `portfolio samples fans in from all workers and respects collector cancellation`() = runTest {
        val problem = exactlyOneOver(5)
        val workers = List(4) { LocalSearchSolver(problem).session() }
        Portfolio(workers).use { p ->
            // take(20) cancels the upstream flow after 20 samples — every worker's
            // sequence must terminate promptly when the collector stops.
            val started = TimeSource.Monotonic.markNow()
            val samples = p.samples(LocalSearchParams(maxFlips = Long.MAX_VALUE, randomSeed = 1L))
                .take(20)
                .toList()
            val elapsed = started.elapsedNow().inWholeMilliseconds
            assertEquals(20, samples.size)
            assertTrue(elapsed < 5_000, "take(20) should be quick on a small problem; took ${elapsed}ms")
            // Every sample is a valid exactly-one configuration.
            for (s in samples) {
                assertEquals(1, s.bools.count { it }, "exactly-one violated by $s")
            }
        }
    }

    @Test
    fun `portfolio with one worker behaves like the underlying session`() = runTest {
        val problem = exactlyOneOver(3)
        val solo = LocalSearchSolver(problem).session()
        Portfolio(listOf(solo)).use { p ->
            val samples = p.samples(LocalSearchParams(maxFlips = 5_000, randomSeed = 0L))
                .take(5).toList()
            assertEquals(5, samples.size)
            for (s in samples) assertEquals(1, s.bools.count { it })
        }
    }

    @Test
    fun `portfolio minimize returns the global best across workers`() = runTest {
        // minimize x + 2y subject to x + y >= 3, x ∈ [0..5], y ∈ [0..5]. Optimum = 3.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(
                IntDomain(0, 5),
                IntDomain(0, 5),
            ),
            factors = arrayOf<Factor>(
                Linear(
                    coeffs = intArrayOf(1, 1),
                    vars = intArrayOf(0, 1),
                    op = LinearOp.GE,
                    bound = 3,
                ),
            ),
        )
        val obj = LinearObjective(
            intCoefficients = doubleArrayOf(1.0, 2.0),
        )
        val workers = List(3) { i -> BacktrackSolver(problem).session() }
        Portfolio(workers).use { p ->
            val r = p.minimize(obj, BacktrackParams(randomSeed = 0L)) { params, supplier ->
                params.copy(objectiveBoundSupplier = supplier)
            }
            val optimal = assertIs<Optimal>(r)
            assertEquals(3.0, optimal.objectiveValue)
        }
    }

    @Test
    fun `exhaustive strategy runs every worker to budget`() = runTest {
        val problem = exactlyOneOver(3)
        val workers = List(2) { BacktrackSolver(problem).session() }
        Portfolio(workers, strategy = PortfolioStrategy.Exhaustive).use { p ->
            val r = p.solve(BacktrackParams(randomSeed = 0L))
            assertTrue(r is SolveResult.Sat, "expected Sat from exhaustive run; got $r")
        }
    }

    private fun exactlyOneOver(n: Int): Problem {
        val factor = Cardinality.exactlyOne(IntArray(n) { Lit.make(it, true) })
        return Problem(n, 0, emptyArray(), listOf(factor))
    }
}
