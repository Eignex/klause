package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.ClauseExchange
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.ResumableSolve
import com.eignex.klause.solver.SearchInitializationCancelled
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.PreparingSearchComponent
import com.eignex.klause.solver.search.SearchComponent
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResumableSatisfactionTest {
    @Test
    fun `ordinary and resumed search should reclaim learned memory without restarts`() {
        for (native in listOf(false, true)) for (resumed in listOf(false, true)) {
            var injected = false
            val exchange = object : ClauseExchange {
                override fun onSearchStart(session: PropagationSession) {
                    if (injected) return
                    injected = true
                    repeat(16) { session.addLearnedClause(Clause(intArrayOf(0, 1)), 9) }
                }
                override fun onRestart(session: PropagationSession) = Unit
            }
            val params = BacktrackParams(nativeSat = native, randomSeed = 1L, pbLearning = false,
                clauseExchange = exchange, learnedMemoryBytes = 0L, learnedMemoryWork = 1_000_000L)
            val solver = BacktrackSolver(pigeonhole(3, 2).bake())

            val result = if (resumed) solver.resumableSolve(params).use { runToVerdict(it, 1L) }
            else solver.solve(params)

            assertIs<SolveResult.Unsat>(result)
            assertEquals(0.0, result.stats.search.restarts.sum)
            assertTrue(result.stats.search.learning.getValue("learnedMemoryRECLAIMED") > 0L)
            assertTrue(result.stats.search.learning.getValue("learningClauseRetired") >= 16L)
        }
    }

    @Test
    fun `ordinary and resumed solves publish attributed first uip consumption`() {
        val solver = BacktrackSolver(pigeonhole(3, 2).bake())
        for (nativeSat in listOf(false, true)) {
            val params = BacktrackParams(randomSeed = 1L, nativeSat = nativeSat, pbLearning = false,
                propagationAttribution = true)
            val ordinary = assertIs<SolveResult.Unsat>(solver.solve(params.copy(propagationAttribution = false)))
            val complete = assertIs<SolveResult.Unsat>(solver.solve(params))
            val resumed = solver.resumableSolve(params).use { runToVerdict(it, 1L) }

            assertIs<SolveResult.Unsat>(resumed)
            assertTrue(ordinary.stats.search.learning.keys.none { it.startsWith("factor") })
            for (result in listOf(complete, resumed)) {
                assertTrue(result.stats.search.learning.getValue("factorClauseUipReasons") > 0)
                assertTrue(result.stats.search.learning.getValue("factorClauseConsumed") > 0)
            }
        }
    }

    @Test
    fun `closing a deferred root should dispose its peer once`() {
        var closed = 0
        val component = object : PreparingSearchComponent, AutoCloseable {
            override fun prepareInitialization(context: SearchContext): Iterator<ComponentResult?> =
                sequenceOf(ComponentResult.Consistent).iterator()
            override fun close() {
                closed++
            }
        }
        val search = BacktrackSolver(pigeonhole(3, 2).bake()).resumableSolve(
            BacktrackParams(componentFactory = { listOf(component) }),
        )
        assertNull(search.runSlice(Cancellation.Never, 0L, 1L))

        search.close()
        search.close()

        assertEquals(1, closed)
    }

    @Test
    fun `component preparation should wait for a slice and initialize once`() {
        var batches = 0
        val component = object : PreparingSearchComponent {
            override fun prepareInitialization(context: SearchContext): Iterator<ComponentResult?> = sequence {
                repeat(3) {
                    batches++
                    yield(null)
                }
                yield(ComponentResult.Consistent)
            }.iterator()
        }
        val search = BacktrackSolver(pigeonhole(3, 2).bake()).resumableSolve(
            BacktrackParams(componentFactory = { listOf(component) }),
        )
        assertEquals(0, batches)
        assertNull(search.runSlice(Cancellation.Never, 0L, 1L))
        assertEquals(0, batches)

        val verdict = search.use { runToVerdict(it, 1L) }

        assertIs<SolveResult.Unsat>(verdict)
        assertEquals(3, batches)
    }

    private fun pigeonhole(pigeons: Int, holes: Int): Problem {
        fun lit(p: Int, h: Int) = Lit.make(p * holes + h, true)
        val factors = ArrayList<Factor>()
        for (p in 0 until pigeons) factors += Clause(IntArray(holes) { h -> lit(p, h) })
        for (h in 0 until holes) {
            for (a in 0 until pigeons) {
                for (b in a + 1 until pigeons) {
                    factors += Clause(intArrayOf(Lit.negate(lit(a, h)), Lit.negate(lit(b, h))))
                }
            }
        }
        return Problem(pigeons * holes, 0, emptyArray(), factors.toTypedArray())
    }

    private fun handle(problem: Problem): ResumableSolve =
        BacktrackSolver(problem.bake()).resumableSolve(BacktrackParams(randomSeed = 0L))

    private fun runToVerdict(search: ResumableSolve, sliceWork: Long): SolveResult {
        while (true) search.runSlice(Cancellation.Never, Long.MAX_VALUE, sliceWork)?.let { return it }
    }

    @Test
    fun `resuming should preserve native learning and deduction counters`() {
        val solver = BacktrackSolver(pigeonhole(4, 3).bake())
        val params = BacktrackParams(randomSeed = 1L)
        val complete = solver.solve(params)

        val resumed = solver.resumableSolve(params).use { runToVerdict(it, 1L) }

        assertIs<SolveResult.Unsat>(complete)
        assertIs<SolveResult.Unsat>(resumed)
        assertTrue(complete.stats.search.learnedClauses.sum > 0.0)
        assertTrue(complete.stats.search.propagations.sum > 0.0)
        assertEquals(complete.stats.search.learnedClauses.sum, resumed.stats.search.learnedClauses.sum)
        assertEquals(complete.stats.search.propagations.sum, resumed.stats.search.propagations.sum)
    }

    @Test
    fun `a work-budgeted slice should pause before the search finishes`() {
        val search = handle(pigeonhole(6, 5))

        assertNull(search.runSlice(Cancellation.Never, Long.MAX_VALUE, sliceNodes = 1L))
        assertFalse(search.isDone)
    }

    @Test
    fun `a fired run token should end the search instead of pausing it`() {
        val search = handle(pigeonhole(6, 5))

        val verdict = search.runSlice(Cancellation { true }, Long.MAX_VALUE, sliceNodes = 1_000_000L)

        assertIs<SolveResult.Unknown>(verdict)
        assertTrue(search.isDone)
    }

    @Test
    fun `a constructor refutation should return even with no slice work allowance`() {
        val search = handle(pigeonhole(2, 1))

        val verdict = search.runSlice(Cancellation.Never, Long.MAX_VALUE, sliceNodes = 0L)

        assertIs<SolveResult.Unsat>(verdict)
    }

    @Test
    fun `cancelled construction should not report a partial root verdict`() {
        val solver = BacktrackSolver(pigeonhole(3, 2).bake())

        assertFailsWith<SearchInitializationCancelled> {
            solver.resumableSolve(BacktrackParams(cancellation = Cancellation { true }))
        }

        assertIs<SolveResult.Unsat>(runToVerdict(solver.resumableSolve(BacktrackParams()), 1L))
    }

    @Test
    fun `an indeterminate node-only root should end with no slice work`() {
        val component = object : SearchComponent {
            override fun initialize(context: SearchContext): ComponentResult = ComponentResult.Indeterminate
        }
        val search = BacktrackSolver(pigeonhole(3, 2).bake()).resumableSolve(
            BacktrackParams(componentFactory = { listOf(component) }),
        )

        val result = search.use { it.runSlice(Cancellation.Never, Long.MAX_VALUE, 0L) }

        assertIs<SolveResult.Unknown>(result)
        assertTrue(search.isDone)
    }

    @Test
    fun `resuming satisfaction should not renew the solve node allowance`() {
        val budget = NodeBudget(3L)
        val search = BacktrackSolver(pigeonhole(6, 5).bake()).resumableSolve(BacktrackParams(nodeBudget = budget))

        val result = search.use { runToVerdict(it, 1L) }

        assertIs<SolveResult.Unknown>(result)
        assertEquals(3L, budget.spent)
        assertEquals(3.0, result.stats.search.nodes.sum)
    }

    @Test
    fun `a resumed handle should retain its bound lifetime cancellation scope`() {
        var cancelled = false
        val search = BacktrackSolver(pigeonhole(6, 5).bake()).resumableSolve(
            BacktrackParams().withLifetimeCancellation(Cancellation { cancelled }),
        )
        assertNull(search.runSlice(Cancellation.Never, Long.MAX_VALUE, 1L))
        cancelled = true

        val result = search.use { it.runSlice(Cancellation.Never, Long.MAX_VALUE, 1L) }

        assertIs<SolveResult.Unknown>(result)
        assertTrue(search.isDone)
    }
}
