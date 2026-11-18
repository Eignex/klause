package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.ClauseExchange
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.search.SearchBranchAttemptObserver
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.util.ExecutionControl
import org.junit.BeforeClass
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class SearchCleanupContinuationTest(private val grant: Long) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "grant={0}")
        fun parameters(): List<Array<Long>> = listOf(64L, 256L, 4096L, 65_536L).map { arrayOf(it) }

        @JvmStatic
        @BeforeClass
        fun initializeSearch() {
            BacktrackSolver(Problem(2, 0, emptyArray(), arrayOf(Clause(intArrayOf(0, 2)))).bake())
                .resumableSolve(BacktrackParams(nativeSat = true, pbLearning = false)).use {
                    it.runSlice(ExecutionControl.Never, Long.MAX_VALUE, -1L)
                }
        }
    }

    @Test
    fun `tiny cleanup attempts should share a bounded grant before search branches`() {
        val problem = Problem(4, 0, emptyArray(),
            arrayOf(Clause(intArrayOf(0, 2)), Clause(intArrayOf(4, 6)))).bake()
        var native: PropagationSession? = null
        var beforeWork = 0L
        var beforeVisits = 0L
        var observed = false
        val exchange = object : ClauseExchange {
            override fun onSearchStart(session: PropagationSession) {
                native = session
                repeat(4000) { session.addLearnedClause(Clause(intArrayOf(0, 2)), 9) }
                forgetIfOverCap(session, BacktrackParams(maxLearnedClauses = 128,
                    learnedMemoryWork = grant, learnedMemoryScratchBytes = 64L, tieredLearnedDb = false))
                assertTrue(session.nativeCapWindowPending)
                beforeWork = session.learnedLifecycle().getValue("learnedCapWork")
                beforeVisits = session.nativeCapTraversalVisits
            }

            override fun onRestart(session: PropagationSession) = Unit
        }
        val params = BacktrackParams(nativeSat = true, pbLearning = false, randomSeed = 1L,
            maxLearnedClauses = 128, learnedMemoryWork = grant, learnedMemoryScratchBytes = 64L,
            tieredLearnedDb = false, clauseExchange = exchange,
            branchObserver = object : SearchBranchAttemptObserver {
                override fun onAttempt(decision: SearchDecision, context: SearchContext) {
                    if (observed) return
                    val session = assertNotNull(native)
                    val work = session.learnedLifecycle().getValue("learnedCapWork") - beforeWork
                    assertTrue(session.nativeCapTraversalVisits > beforeVisits)
                    if (grant < 65_536L) assertTrue(work <= 65_536L, "cleanup charged $work work before branching")
                    else assertFalse(session.nativeCapWindowPending)
                    observed = true
                }
            })

        val result = BacktrackSolver(problem).resumableSolve(params).use {
            it.runSlice(ExecutionControl.Never, Long.MAX_VALUE, -1L)
        }

        assertIs<SolveResult.Sat>(result)
        assertTrue(observed)
    }

    @Test
    fun `unfinished cleanup should retain one pass across root pauses`() {
        val problem = Problem(4, 0, emptyArray(),
            arrayOf(Clause(intArrayOf(0, 2)), Clause(intArrayOf(4, 6)))).bake()
        val exchange = object : ClauseExchange {
            override fun onSearchStart(session: PropagationSession) {
                repeat(4000) { session.addLearnedClause(Clause(intArrayOf(0, 2)), 9) }
                forgetIfOverCap(session, BacktrackParams(maxLearnedClauses = 128,
                    learnedMemoryWork = grant, learnedMemoryScratchBytes = 64L, tieredLearnedDb = false))
            }

            override fun onRestart(session: PropagationSession) = Unit
        }
        val params = BacktrackParams(nativeSat = true, pbLearning = false, randomSeed = 1L,
            maxLearnedClauses = 128, learnedMemoryWork = grant, learnedMemoryScratchBytes = 64L,
            tieredLearnedDb = false, clauseExchange = exchange)
        var checkpoint = 0L
        var pauses = 0
        var completedCount: Int? = null

        val result = ResumableSatisfaction(BacktrackSolver(problem), params, atRoot = { session ->
            val work = session.learnedLifecycle().getValue("learnedCapWork")
            if (session.nativeCapWindowPending && work - checkpoint >= 60_000L) {
                checkpoint = work
                pauses++
                true
            } else {
                if (!session.nativeCapWindowPending) completedCount = session.activeLearnedClauseCount
                false
            }
        }).use { search ->
            var verdict: SolveResult? = null
            repeat(32) { if (verdict == null) verdict = search.runSlice(ExecutionControl.Never, Long.MAX_VALUE, -1L) }
            assertNotNull(verdict)
        }

        assertIs<SolveResult.Sat>(result)
        assertEquals(128, completedCount)
        assertEquals(1L, result.stats.search.learning.getValue("learnedCapRankedPasses"))
        if (grant < 65_536L) assertTrue(pauses > 0)
    }
}
