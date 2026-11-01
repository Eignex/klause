package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.result.SearchStatsSink
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InprocessingBudgetTest {
    @Test
    fun `containing exhaustion should withhold optional work without creating class debt`() {
        val session = PropagationSession(Problem(2, 0, emptyArray(),
            arrayOf<Factor>(Clause(intArrayOf(0, 2)))).bake())
        val spending = InprocessingSpending(InprocessingKind.Root)
        val params = BacktrackParams(inprocessingRootWork = 10L)
        spending.run(session, params, null, containingWork = 3L) { budget ->
            budget.charge(5)
            assertEquals(0L, budget.remaining)
            assertTrue(budget.stopped())
        }

        var remaining = 0L
        spending.run(session, params, null, containingWork = 3L) { remaining = it.remaining }

        assertEquals(3L, remaining)
    }

    @Test
    fun `remaining work should include class debt before a containing grant`() {
        val session = PropagationSession(Problem(2, 0, emptyArray(),
            arrayOf<Factor>(Clause(intArrayOf(0, 2)))).bake())
        val spending = InprocessingSpending(InprocessingKind.Root)
        val params = BacktrackParams(inprocessingRootWork = 3L)
        spending.run(session, params, null, containingWork = 10L) { it.charge(5) }

        var remaining = 0L
        spending.run(session, params, null, containingWork = 10L) { remaining = it.remaining }

        assertEquals(1L, remaining)
    }

    @Test
    fun `zero class allowances should withhold all enabled passes`() {
        for (native in listOf(false, true)) {
            val problem = Problem(3, 0, emptyArray(), arrayOf<Factor>(
                Clause(intArrayOf(1)), Clause(intArrayOf(0, 2, 4)),
            ))
            val session = PropagationSession(problem.bake(), nativeSat = native)
            val stats = SearchStatsSink()
            val params = BacktrackParams(rootSimplification = true, rootProbeBatch = 4, vivification = true,
                subsumption = true, inprocessingRootWork = 0L, inprocessingProbeWork = 0L,
                inprocessingSubsumeWork = 0L, inprocessingVivifyWork = 0L)
            val loop = assertNotNull(Inprocessing.from(params, stats))
            val before = session.work

            loop.onRestart(session, params)

            assertEquals(before, session.work)
            assertEquals(0, session.learnedClauseCount)
            assertEquals(0L, stats.inprocessVisits)
            assertEquals(0L, stats.inprocessProbes)
        }
    }

    @Test
    fun `subsumption should resume preparation and charge attachment and retirement`() {
        for (native in listOf(false, true)) {
            val problem = Problem(3, 0, emptyArray(), arrayOf<Factor>(Clause(intArrayOf(0, 2, 4))))
            val session = PropagationSession(problem.bake(), nativeSat = native)
            session.addLearnedClause(Clause(intArrayOf(0, 3)), 2)
            session.addLearnedClause(Clause(intArrayOf(0, 2, 4)), 3)
            val stats = SearchStatsSink()
            val params = BacktrackParams(subsumption = true, inprocessingSubsumeWork = 5L, inprocessingWork = 128L)
            val loop = assertNotNull(Inprocessing.from(params, stats))
            val before = session.work

            repeat(100) { loop.onRestart(session, params) }

            assertTrue((0 until session.learnedClauseCount).any {
                session.learnedClauseLiterals(it).toSet() == setOf(0, 4)
            })
            assertTrue(session.work > before)
            assertTrue(stats.snapshot().learning.getValue("inprocessSubsumptionAttachmentWork") > 0L)
            assertTrue(stats.snapshot().learning.getValue("inprocessSubsumptionRetirementWork") > 0L)
            assertEquals(0, session.decisionLevel)
            assertFalse(session.fixpointCancelled)
        }
    }

    @Test
    fun `interrupted vivification should retract probes before another invocation`() {
        for (native in listOf(false, true)) {
            val problem = Problem(3, 0, emptyArray(), arrayOf<Factor>(
                Clause(intArrayOf(1, 2)), Clause(intArrayOf(3, 4)),
            ))
            val session = PropagationSession(problem.bake(), nativeSat = native)
            session.addLearnedClause(Clause(intArrayOf(1, 3, 4)), 3)
            val pass = VivificationPass()
            val stats = SearchStatsSink()
            var cancelled = false
            val cancellation = Cancellation {
                cancelled = cancelled || (session.decisionLevel > 0 && session.boolValue(1) == true)
                cancelled
            }

            pass.run(session, BacktrackParams(cancellation = cancellation), stats)

            assertEquals(0, session.decisionLevel)
            assertFalse(session.fixpointCancelled)
            assertTrue(stats.inprocessProbes > 0L)
            repeat(2) { pass.run(session, BacktrackParams(), stats) }
            assertEquals(1, session.learnedClauseCount)
            assertEquals(setOf(1, 4), session.learnedClauseLiterals(0).toSet())
        }
    }

    @Test
    fun `interrupted root attachment should complete before the root is exposed`() {
        for (native in listOf(false, true)) {
            val problem = Problem(3, 0, emptyArray(), arrayOf<Factor>(
                Clause(intArrayOf(0, 2)), Clause(intArrayOf(0, 3)), Clause(intArrayOf(1, 4)),
            ))
            val session = PropagationSession(problem.bake(), nativeSat = native)
            session.withCancellation(Cancellation { true }) {
                session.addRootDerivedClause(Clause(intArrayOf(0)), 1, 0, false)
            }
            assertTrue(session.fixpointCancelled)

            session.resumeInprocessingRoot()

            assertFalse(session.fixpointCancelled)
            assertEquals(true, session.boolValue(2))
            assertEquals(0, session.decisionLevel)
        }
    }
}
