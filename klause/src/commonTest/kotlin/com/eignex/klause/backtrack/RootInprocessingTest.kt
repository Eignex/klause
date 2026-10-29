package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.SharedClause
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.forEachBinaryPartner
import com.eignex.klause.solver.result.SearchStatsSink
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RootInprocessingTest {
    private fun problem(vararg clauses: IntArray): Problem = Problem(4, 0, emptyArray(),
        Array<Factor>(clauses.size) { Clause(clauses[it]) })

    @Test
    fun `source simplification should install a permanent binary with stable coordinates`() {
        for (native in listOf(false, true)) {
            val problem = problem(intArrayOf(1), intArrayOf(0, 2, 4))
            val session = PropagationSession(problem.bake(), nativeSat = native)

            RootSimplificationPass().run(session, BacktrackParams(), null)
            session.forgetLearnedClauses { _, _ -> false }
            val partners = ArrayList<Int>()
            session.explanationState.forEachBinaryPartner(2) { partners.add(it) }
            session.pinBool(1, false)

            assertEquals(listOf(4), partners)
            assertEquals(true, session.boolValue(2))
            assertEquals(setOf(2, 4), assertNotNull(session.boolReasonClause(2)).toSet())
            assertEquals(4, session.problem.numBoolVars)
            assertTrue(session.learnedClausePermanent(0))
            assertEquals(listOf(0, 2, 4), (problem.factors[1] as Clause).literals.toList())
        }
    }

    @Test
    fun `a root slice should advance within its configured clause limit`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(problem(intArrayOf(1), intArrayOf(0, 2, 4)).bake(), nativeSat = native)
            val pass = RootSimplificationPass()
            val params = BacktrackParams(rootSimplifyBatch = 1)

            pass.run(session, params, null)
            assertEquals(0, session.learnedClauseCount)
            pass.run(session, params, null)

            assertEquals(1, session.learnedClauseCount)
            assertEquals(setOf(2, 4), session.learnedClauseLiterals(0).toSet())
        }
    }

    @Test
    fun `root refresh should preserve imported origin through compaction`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(problem(intArrayOf(1)).bake(), nativeSat = native)
            session.importClause(SharedClause(intArrayOf(0, 2, 4), longArrayOf(), 3), origin = 7)

            RootSimplificationPass().run(session, BacktrackParams(), null)
            session.pinBool(1, false)
            var credited = 0L
            session.drainImportUses { origin, uses -> if (origin == 7) credited += uses }

            assertEquals(true, session.boolValue(2))
            assertEquals(1, session.learnedClauseCount)
            assertEquals(7, session.learnedClauseOrigin(0))
            assertTrue(credited > 0L)
        }
    }

    @Test
    fun `a retained active reason should not starve later root candidates`() {
        for ((native, work) in listOf(false to Long.MAX_VALUE, true to Long.MAX_VALUE, false to 10L, true to 10L)) {
            val session = PropagationSession(problem(intArrayOf(1)).bake(), nativeSat = native)
            session.addLearnedClause(Clause(intArrayOf(0, 2)), 2)
            session.addLearnedClause(Clause(intArrayOf(0, 4, 6)), 3)
            val pass = RootSimplificationPass()
            val params = BacktrackParams(rootSimplifyBatch = 2, inprocessingRootWork = work)
            val stats = SearchStatsSink()

            repeat(100) { pass.run(session, params, stats) }

            assertTrue((0 until session.learnedClauseCount).any {
                session.learnedClauseLiterals(it).toSet() == setOf(4, 6)
            }, "native=$native work=$work learning=${stats.snapshot().learning}")
        }
    }

    @Test
    fun `a satisfied root clause should retain its live historical reason`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(problem(intArrayOf(1)).bake(), nativeSat = native)
            session.addLearnedClause(Clause(intArrayOf(0, 2)), 2)
            val reason = assertNotNull(session.boolReasonClause(1)).toList()

            RootSimplificationPass().run(session, BacktrackParams(), null)
            session.forgetLearnedClauses { _, _ -> false }

            assertEquals(reason, assertNotNull(session.boolReasonClause(1)).toList())
            assertEquals(1, session.learnedClauseCount)
        }
    }

    @Test
    fun `failed literal probing should assert a consumed unconditional unit`() {
        for ((native, positive) in listOf(false to false, false to true, true to false, true to true)) {
            val literal = Lit.make(0, positive)
            val session = PropagationSession(
                problem(intArrayOf(literal, 2), intArrayOf(literal, 3)).bake(), nativeSat = native,
            )
            val stats = SearchStatsSink()

            RootProbingPass().run(session, BacktrackParams(rootProbeBatch = 2), stats)

            assertEquals(positive, session.boolValue(0))
            assertEquals(0, session.decisionLevel)
            assertTrue(session.learnedLifecycle().getValue("learningClauseConsumed") > 0L)
            assertEquals(2L, stats.inprocessProbes)
            assertIs<PropagationResult.Unsat>(session.pinBool(0, !positive))
        }
    }

    @Test
    fun `a locally capped probe should restore the complete root for another invocation`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(problem(intArrayOf(0, 2), intArrayOf(0, 3)).bake(), nativeSat = native)

            RootProbingPass().run(session, BacktrackParams(rootProbeBatch = 2, rootProbeWork = 1), null)

            assertEquals(0, session.decisionLevel)
            assertFalse(session.fixpointCancelled)
            assertFalse(session.probeCancelled)
            RootProbingPass().run(session, BacktrackParams(rootProbeBatch = 2), null)
            assertEquals(true, session.boolValue(0))
        }
    }

    @Test
    fun `assumption and scoped roots should not produce unconditional units`() {
        for (native in listOf(false, true)) {
            val problem = problem(intArrayOf(1, 2, 4), intArrayOf(1, 2, 5))
            val seeded = PropagationSession(problem.bake(), nativeSat = native)
            seeded.seed(Assumptions(bools = mapOf(0 to true)))
            val scoped = PropagationSession(problem.bake(), nativeSat = native)
            scoped.addLearnedClause(Clause(intArrayOf(0)), 1, permanent = true)
            for (session in listOf(seeded, scoped)) {
                val count = session.learnedClauseCount
                RootProbingPass().run(session, BacktrackParams(rootProbeBatch = 8), null)
                RootSimplificationPass().run(session, BacktrackParams(), null)
                assertEquals(count, session.learnedClauseCount)
                assertNull(session.boolValue(1))
            }
            assertNull(Inprocessing.from(BacktrackParams(rootSimplification = true, rootProbeBatch = 8,
                assumptions = Assumptions(bools = mapOf(0 to true)))))
        }
    }

    @Test
    fun `cancelled probing should leave no deduction or standing decision`() {
        val session = PropagationSession(problem(intArrayOf(0, 2), intArrayOf(0, 3)).bake())
        var polls = 0
        session.withCancellation(Cancellation { ++polls >= 2 }) {
            RootProbingPass().run(session, BacktrackParams(rootProbeBatch = 2), null)
        }
        assertEquals(0, session.learnedClauseCount)
        assertEquals(0, session.decisionLevel)
        assertFalse(session.fixpointCancelled)
    }
}
