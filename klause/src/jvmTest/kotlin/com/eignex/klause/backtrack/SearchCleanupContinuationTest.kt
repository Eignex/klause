package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.ClauseExchange
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.ExecutionControl
import org.junit.BeforeClass
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
    fun `consecutive cleanup attempts should keep full token reads bounded`() {
        val problem = Problem(4, 0, emptyArray(),
            arrayOf(Clause(intArrayOf(0, 2)), Clause(intArrayOf(4, 6)))).bake()
        var polls = 0
        val exchange = object : ClauseExchange {
            override fun onSearchStart(session: PropagationSession) {
                repeat(4000) { session.addLearnedClause(Clause(intArrayOf(0, 2)), 9) }
                forgetIfOverCap(session, BacktrackParams(maxLearnedClauses = 128,
                    learnedMemoryWork = grant, learnedMemoryScratchBytes = 64L, tieredLearnedDb = false))
                assertTrue(session.nativeCapWindowPending)
                polls = 0
            }

            override fun onRestart(session: PropagationSession) = Unit
        }
        val params = BacktrackParams(nativeSat = true, pbLearning = false, randomSeed = 1L,
            maxLearnedClauses = 128, learnedMemoryWork = grant, learnedMemoryScratchBytes = 64L,
            tieredLearnedDb = false, clauseExchange = exchange)

        val result = BacktrackSolver(problem).resumableSolve(params).use {
            it.runSlice(ExecutionControl {
                polls++
                false
            }, Long.MAX_VALUE, -1L)
        }

        assertIs<SolveResult.Sat>(result)
        assertTrue(polls <= 512, "cleanup with grant $grant read the full token $polls times")
    }

    @Test
    fun `a run cancellation during cleanup continuation should end the search`() {
        val problem = Problem(4, 0, emptyArray(),
            arrayOf(Clause(intArrayOf(0, 2)), Clause(intArrayOf(4, 6)))).bake()
        var active: PropagationSession? = null
        var stopAfter = Long.MAX_VALUE
        var cancelled = false
        val exchange = object : ClauseExchange {
            override fun onSearchStart(session: PropagationSession) {
                repeat(4000) { session.addLearnedClause(Clause(intArrayOf(0, 2)), 9) }
                forgetIfOverCap(session, BacktrackParams(maxLearnedClauses = 128,
                    learnedMemoryWork = grant, learnedMemoryScratchBytes = 64L, tieredLearnedDb = false))
                active = session
                stopAfter = session.nativeCapTraversalVisits + 128L
            }

            override fun onRestart(session: PropagationSession) = Unit
        }
        val params = BacktrackParams(nativeSat = true, pbLearning = false, randomSeed = 1L,
            maxLearnedClauses = 128, learnedMemoryWork = grant, learnedMemoryScratchBytes = 64L,
            tieredLearnedDb = false, clauseExchange = exchange)

        val result = BacktrackSolver(problem).resumableSolve(params).use {
            it.runSlice(ExecutionControl {
                if (!cancelled) cancelled = (active?.nativeCapTraversalVisits ?: 0L) >= stopAfter
                cancelled
            }, Long.MAX_VALUE, -1L)
        }

        assertEquals(TerminationReason.Cancelled, assertIs<SolveResult.Unknown>(result).reason)
    }
}
