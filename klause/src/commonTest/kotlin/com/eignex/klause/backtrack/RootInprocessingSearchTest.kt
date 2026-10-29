package com.eignex.klause.backtrack

import com.eignex.klause.backtrack.selector.IndomainMax
import com.eignex.klause.backtrack.selector.InputOrder
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.ClauseExchange
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.SharedClause
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.ResumableSolve
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchComponent
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RootInprocessingSearchTest {
    private fun problem(): Problem = Problem(5, 0, emptyArray(), arrayOf<Factor>(
        Clause(intArrayOf(9)),
        Clause(intArrayOf(0, 2, 8)),
        Clause(intArrayOf(4, 6)),
        Clause(intArrayOf(4, 7)),
    ))

    private fun params(native: Boolean, exchange: ClauseExchange): BacktrackParams = BacktrackParams(
        nativeSat = native, variableSelector = InputOrder, valueSelector = IndomainMax, randomSeed = 1L,
        lubyRestartBase = 1L, rootSimplification = true, rootProbeBatch = 10, clauseExchange = exchange,
    )

    @Test
    fun `real restarts should consume probing units and refreshed imported binaries`() {
        for (native in listOf(false, true)) {
            var retained: PropagationSession? = null
            val exchange = object : ClauseExchange {
                override fun onRestart(session: PropagationSession) {
                    if (retained == null) session.importClause(SharedClause(intArrayOf(0, 3, 8), longArrayOf(), 3), 6)
                    retained = session
                }
            }

            val result = BacktrackSolver(problem().bake()).solve(params(native, exchange))

            assertIs<SolveResult.Sat>(result)
            val session = assertNotNull(retained)
            assertEquals(true, session.boolValue(2))
            assertTrue(session.learnedLifecycle().getValue("learningClauseConsumed") > 0L)
            assertTrue(result.stats.search.inprocessProbes.sum > 0.0)
            assertTrue(result.stats.search.inprocessVisits.sum > 0.0)
            var importedUses = 0L
            session.drainImportUses { origin, uses -> if (origin == 6) importedUses += uses }
            assertTrue(importedUses > 0L)
        }
    }

    @Test
    fun `suspension should preserve the root deductions and logical search result`() {
        for (native in listOf(false, true)) {
            var retained: PropagationSession? = null
            val exchange = object : ClauseExchange {
                override fun onRestart(session: PropagationSession) { retained = session }
            }
            val search = BacktrackSolver(problem().bake()).resumableSolve(params(native, exchange))
            val result = search.use {
                assertNull(it.runSlice(Cancellation.Never, Long.MAX_VALUE, 0L))
                finish(it)
            }

            assertIs<SolveResult.Sat>(result)
            val session = assertNotNull(retained)
            assertEquals(true, session.boolValue(2))
            assertTrue(session.learnedLifecycle().getValue("learningClauseConsumed") > 0L)
            assertTrue(result.stats.search.inprocessProbes.sum > 0.0)
        }
    }

    @Test
    fun `a probing unit should reach peer components at the shared root`() {
        for (native in listOf(false, true)) {
            var consumed = false
            val peer = object : SearchComponent {
                override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult {
                    if (decision == SearchDecision.Bool(4) && context.decisionLevel == 0) consumed = true
                    return ComponentResult.Consistent
                }
            }
            val exchange = object : ClauseExchange {
                override fun onRestart(session: PropagationSession) {}
            }
            val params = params(native, exchange).copy(componentFactory = { listOf(peer) })

            val result = BacktrackSolver(problem().bake()).solve(params)

            assertIs<SolveResult.Sat>(result)
            assertTrue(consumed)
        }
    }

    private fun finish(search: ResumableSolve): SolveResult {
        repeat(100) {
            search.runSlice(Cancellation.Never, Long.MAX_VALUE, 1L)?.let { return it }
        }
        error("small root inprocessing fixture did not finish")
    }
}
