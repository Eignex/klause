package com.eignex.klause.portfolio

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.selector.IndomainMin
import com.eignex.klause.backtrack.selector.InputOrder
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.search.BooleanProofContext
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BooleanProofPortfolioTest {
    private val context = BooleanProofContext(SourceScope(SourceRevision("fixture", "exact-cnf")), "identity:0")
    private val problem = Problem(5, 0, emptyArray(), Array(8) { mask ->
        Clause(IntArray(3) { bit -> Lit.make(bit + 2, mask and (1 shl bit) != 0) })
    }).bake()

    @Test
    fun `bound deterministic workers should preserve ordinary portfolio proof admission`() {
        val params = BacktrackParams(variableSelector = InputOrder, valueSelector = IndomainMin, nativeSat = false)
        val scenario = PortfolioScenario(1, 2, Kind.CSP, EngineMix.BACKTRACK,
            btPool = listOf { BacktrackWorkerConfig.ofParams("fixed", params) })
        val workers = PortfolioBuilder.build(problem, scenario)
        Portfolio.thompson(workers, diagnostics = PortfolioDiagnostics(100000L, 1L)).use { portfolio ->
            portfolio.disjointBooleanProof = true
            val result = portfolio.solve(Cancellation.Never)

            assertTrue(result is SolveResult.Unsat)
            assertEquals(4, portfolio.lastBooleanProofTasks)
            assertEquals(4, portfolio.lastBooleanProofCompleted)
        }
    }

    @Test
    fun `a task lane should retain the source session exclusive owner until retirement`() {
        val params = BacktrackParams(variableSelector = InputOrder, valueSelector = IndomainMin, nativeSat = false)
        val scenario = PortfolioScenario(1, 2, Kind.CSP, EngineMix.BACKTRACK,
            btPool = listOf { BacktrackWorkerConfig.ofParams("fixed", params) })
        val workers = PortfolioBuilder.build(problem, scenario)
        try {
            assertNotNull(BooleanProofPortfolio.forWorkers(workers)).use { tasks ->
                val first = tasks.open(0, Cancellation.Never)
                assertNull(first.runSlice(Cancellation.Never, Long.MAX_VALUE, 1L))

                assertFailsWith<IllegalStateException> { workers[0].newResumableSolve() }
                first.close()
                assertNotNull(workers[0].newResumableSolve()).close()
            }
        } finally {
            workers.forEach { it.close() }
        }
    }

    @Test
    fun `separately bound invocations should decline a shared task board`() {
        val params = BacktrackParams(variableSelector = InputOrder, valueSelector = IndomainMin, nativeSat = false)
        val scenario = PortfolioScenario(1, 2, Kind.CSP, EngineMix.BACKTRACK,
            btPool = listOf { BacktrackWorkerConfig.ofParams("fixed", params) })
        val first = PortfolioBuilder.build(problem, scenario)
        val second = PortfolioBuilder.build(problem, scenario)
        try {
            assertNull(BooleanProofPortfolio.forWorkers(listOf(first[0], second[1])))
        } finally {
            (first + second).forEach { it.close() }
        }
    }

    @Test
    fun `a lane should publish source exhaustion only after every disjoint cube closes`() {
        val pool = SharedClausePool()
        val params = List(2) { worker -> BacktrackParams(nativeSat = false,
            clauseExchange = PoolClauseExchange(pool, origin = worker).conditioned()) }
        BooleanProofPortfolio(problem, context, listOf(0, 1), params).use { tasks ->
            val first = tasks.open(0, Cancellation.Never)
            val second = tasks.open(1, Cancellation.Never)
            assertNull(first.runSlice(Cancellation.Never, Long.MAX_VALUE, 5000L))
            assertNull(second.runSlice(Cancellation.Never, Long.MAX_VALUE, 5000L))
            assertNull(first.runSlice(Cancellation.Never, Long.MAX_VALUE, 5000L))
            val result = second.runSlice(Cancellation.Never, Long.MAX_VALUE, 5000L)
            val work = second.work

            assertTrue(result is SolveResult.Unsat)
            assertTrue(second.isDone)
            assertEquals(result, second.runSlice(Cancellation.Never, Long.MAX_VALUE, 5000L))
            assertEquals(work, second.work)
        }
    }

    @Test
    fun `closing a paused lane should return its unresolved coverage to its peer`() {
        val params = List(2) { BacktrackParams(nativeSat = false) }
        BooleanProofPortfolio(problem, context, listOf(0, 1), params).use { tasks ->
            val first = tasks.open(0, Cancellation.Never)
            val second = tasks.open(1, Cancellation.Never)
            assertNull(first.runSlice(Cancellation.Never, Long.MAX_VALUE, 1L))
            first.close()
            val spent = first.work
            repeat(3) { assertNull(second.runSlice(Cancellation.Never, Long.MAX_VALUE, 5000L)) }
            val result = second.runSlice(Cancellation.Never, Long.MAX_VALUE, 5000L)

            assertTrue(result is SolveResult.Unsat)
            assertEquals(spent, first.work)
            assertFalse(tasks.available(0))
        }
    }

    @Test
    fun `a lane with no available task should leave the schedule to its pending peer`() {
        val params = List(2) { BacktrackParams(nativeSat = false) }
        BooleanProofPortfolio(problem, context, listOf(0), params).use { tasks ->
            val first = tasks.open(0, Cancellation.Never)
            val second = tasks.open(1, Cancellation.Never)
            assertNull(first.runSlice(Cancellation.Never, Long.MAX_VALUE, 1L))
            assertNull(second.runSlice(Cancellation.Never, Long.MAX_VALUE, 5000L))

            assertTrue(tasks.available(0))
            assertFalse(tasks.available(1))
            assertTrue(first.runSlice(Cancellation.Never, Long.MAX_VALUE, 5000L) is SolveResult.Unsat)
        }
    }
}
