package com.eignex.klause.backtrack

import com.eignex.klause.backtrack.selector.IndomainMax
import com.eignex.klause.backtrack.selector.InputOrder
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.ClauseExchange
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.SharedClause
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.ResumableSolve
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
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
    private fun problem(): Problem = Problem(6, 0, emptyArray(), arrayOf<Factor>(
        Clause(intArrayOf(9)),
        Clause(intArrayOf(0, 2, 8)),
        Clause(intArrayOf(4, 6)),
        Clause(intArrayOf(4, 7)),
        Clause(intArrayOf(2, 6, 10, 8)),
        Clause(intArrayOf(2, 6, 11, 8)),
    ))

    private fun params(native: Boolean, exchange: ClauseExchange? = null): BacktrackParams = BacktrackParams(
        nativeSat = native, variableSelector = InputOrder, valueSelector = IndomainMax, randomSeed = 1L,
        lubyRestartBase = 1L, rootSimplification = true, rootProbeBatch = 10, clauseExchange = exchange,
    )

    @Test
    fun `ordinary search should consume root probes without a restart schedule`() {
        for (native in listOf(false, true)) {
            val configured = params(native).copy(lubyRestartBase = null, inprocessingInterval = 1L,
                inprocessingWork = 4096L, inprocessingRootWork = 512L, inprocessingProbeWork = 512L,
                rootRebuildCaptureVisits = 1024)

            val result = BacktrackSolver(problem().bake()).solve(configured)

            assertIs<SolveResult.Sat>(result)
            assertEquals(0.0, result.stats.search.restarts.sum)
            assertTrue(result.stats.search.learning.getValue("inprocessWorkTriggers") > 0L)
            assertTrue(result.stats.search.learning.getValue("inprocessProbeUnits") > 0L)
            assertTrue(result.stats.search.learning.getValue("inprocessRootRebuildComplete") > 0L)
        }
    }

    @Test
    fun `resumed search should retain root work without a restart schedule`() {
        for (native in listOf(false, true)) {
            val configured = params(native).copy(lubyRestartBase = null, inprocessingInterval = 1L,
                inprocessingWork = 4096L, inprocessingRootWork = 512L, inprocessingProbeWork = 512L)
            val search = BacktrackSolver(problem().bake()).resumableSolve(configured)

            val result = search.use { finish(it) }

            assertIs<SolveResult.Sat>(result)
            assertEquals(0.0, result.stats.search.restarts.sum)
            assertTrue(result.stats.search.learning.getValue("inprocessProbeUnits") > 0L)
        }
    }

    @Test
    fun `root publication should retain its scan across work slices`() {
        for (native in listOf(false, true)) {
            val source = Problem(5000, 0, emptyArray(), problem().factors)
            val configured = params(native).copy(lubyRestartBase = null, inprocessingInterval = 1L,
                inprocessingWork = 4096L, inprocessingRootWork = 512L, inprocessingProbeWork = 512L)
            val search = BacktrackSolver(source.bake()).resumableSolve(configured)

            val result = search.use { finish(it) }

            assertIs<SolveResult.Sat>(result)
            val work = result.stats.search.learning.getValue("inprocessPublicationWork")
            assertTrue(work in 5000L until 10_000L)
        }
    }

    @Test
    fun `optimization should charge growth triggered probing after source clause import`() {
        for ((native, resumed) in listOf(false to false, false to true, true to false, true to true)) {
            val exchange = object : ClauseExchange {
                override fun onSearchStart(session: PropagationSession) {
                    session.importClause(SharedClause(intArrayOf(0, 2, 8), longArrayOf(), 3), 6)
                }
                override fun onRestart(session: PropagationSession) = Unit
            }
            val configured = params(native, exchange).copy(lubyRestartBase = null, inprocessingGrowth = 1,
                inprocessingWork = 4096L, inprocessingRootWork = 512L, inprocessingProbeWork = 512L,
                lpConfig = LpConfig.OFF)
            val solver = BacktrackSolver(problem().bake())
            val objective = LinearObjective(boolWeights = LongArray(6) { 1L })

            val result = if (!resumed) solver.minimize(objective, configured) else {
                solver.resumable(objective, configured).use { search ->
                    var result: MinimizeResult? = null
                    repeat(100) {
                        if (result == null) result = search.runSlice(Cancellation.Never, Long.MAX_VALUE, 1L) { }
                    }
                    assertNotNull(result)
                }
            }

            assertIs<MinimizeResult.Optimal>(result)
            assertEquals(0.0, result.stats.search.restarts.sum)
            val learning = result.stats.search.learning
            val detail = "native=$native resumed=$resumed learning=$learning"
            assertTrue((learning["inprocessWorkTriggers"] ?: 0L) > 0L, detail)
            assertTrue((learning["inprocessProbeWork"] ?: 0L) > 0L, detail)
        }
    }

    @Test
    fun `real restarts should consume probing units and refreshed imported binaries`() {
        for (native in listOf(false, true)) {
            var retained: PropagationSession? = null
            var rootValue: Boolean? = null
            var importedUses = 0L
            val exchange = object : ClauseExchange {
                override fun onRestart(session: PropagationSession) {
                    if (retained == null) session.importClause(SharedClause(intArrayOf(2, 6, 8), longArrayOf(), 3), 6)
                    retained = session
                }
                override fun onSearchClose(session: PropagationSession) {
                    rootValue = session.boolValue(2)
                    session.drainImportUses { origin, uses -> if (origin == 6) importedUses += uses }
                }
            }

            val result = BacktrackSolver(problem().bake()).solve(params(native, exchange))

            assertIs<SolveResult.Sat>(result)
            val session = assertNotNull(retained)
            assertEquals(true, rootValue)
            assertTrue(session.learnedLifecycle().getValue("learningClauseConsumed") > 0L)
            assertTrue(result.stats.search.inprocessProbes.sum > 0.0)
            assertTrue(result.stats.search.inprocessVisits.sum > 0.0)
            assertTrue(importedUses > 0L)
        }
    }

    @Test
    fun `suspension should preserve the root deductions and logical search result`() {
        for (native in listOf(false, true)) {
            var retained: PropagationSession? = null
            var rootValue: Boolean? = null
            val exchange = object : ClauseExchange {
                override fun onRestart(session: PropagationSession) { retained = session }
                override fun onSearchClose(session: PropagationSession) { rootValue = session.boolValue(2) }
            }
            val search = BacktrackSolver(problem().bake()).resumableSolve(params(native, exchange))
            val result = search.use {
                assertNull(it.runSlice(Cancellation.Never, Long.MAX_VALUE, 0L))
                finish(it)
            }

            assertIs<SolveResult.Sat>(result)
            val session = assertNotNull(retained)
            assertEquals(true, rootValue)
            assertTrue(session.learnedLifecycle().getValue("learningClauseConsumed") > 0L)
            assertTrue(result.stats.search.inprocessProbes.sum > 0.0)
        }
    }

    @Test
    fun `a probing unit should reach peers without a private decision level`() {
        for (native in listOf(false, true)) {
            var retained: PropagationSession? = null
            var observedLevel = -1
            val exchange = object : ClauseExchange {
                override fun onSearchStart(session: PropagationSession) { retained = session }
                override fun onRestart(session: PropagationSession) = Unit
            }
            val peer = object : SearchComponent {
                override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult {
                    if (decision == SearchDecision.Bool(4) && context.decisionLevel == 0) {
                        observedLevel = assertNotNull(retained).decisionLevel
                    }
                    return ComponentResult.Consistent
                }
            }
            val params = params(native, exchange).copy(componentFactory = { listOf(peer) })

            val result = BacktrackSolver(problem().bake()).solve(params)

            assertIs<SolveResult.Sat>(result)
            assertEquals(0, observedLevel)
        }
    }

    @Test
    fun `restarting assumption searches should keep root passes disabled`() {
        for (native in listOf(false, true)) {
            val guarded = Problem(5, 0, emptyArray(), arrayOf<Factor>(
                Clause(intArrayOf(0, 2)), Clause(intArrayOf(5, 6, 8)), Clause(intArrayOf(5, 6, 9)),
            ))
            val configured = params(native).copy(assumptions = Assumptions(bools = mapOf(2 to true)))

            val result = BacktrackSolver(guarded.bake()).solve(configured)

            assertIs<SolveResult.Sat>(result)
            assertTrue(result.assignment.bools[3])
            assertEquals(0.0, result.stats.search.inprocessProbes.sum)
            assertEquals(0.0, result.stats.search.inprocessVisits.sum)
        }
    }

    @Test
    fun `a completed probing contradiction should close the real root`() {
        for (native in listOf(false, true)) {
            val unsat = Problem(4, 0, emptyArray(), arrayOf<Factor>(
                Clause(intArrayOf(0, 2)), Clause(intArrayOf(4, 6)), Clause(intArrayOf(4, 7)),
                Clause(intArrayOf(5, 6)), Clause(intArrayOf(5, 7)),
            ))
            var retained: PropagationSession? = null
            val exchange = object : ClauseExchange {
                override fun onRestart(session: PropagationSession) { retained = session }
            }

            val result = BacktrackSolver(unsat.bake()).solve(params(native, exchange))

            assertIs<SolveResult.Unsat>(result)
            assertTrue(assertNotNull(retained).isUnsatAtRoot)
        }
    }

    private fun finish(search: ResumableSolve): SolveResult {
        repeat(100) {
            search.runSlice(Cancellation.Never, Long.MAX_VALUE, 1L)?.let { return it }
        }
        error("small root inprocessing fixture did not finish")
    }
}
