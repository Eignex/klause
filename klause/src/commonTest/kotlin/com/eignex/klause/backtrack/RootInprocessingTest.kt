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
import com.eignex.klause.util.SharingMemoryObservation
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
    fun `a root without Boolean facts should not spend the simplification allowance`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(problem(intArrayOf(0, 2, 4)).bake(), nativeSat = native)
            val work = session.work

            RootSimplificationPass().run(session, BacktrackParams(), null)

            assertEquals(work, session.work)
            assertEquals(0, session.learnedClauseCount)
        }
    }

    @Test
    fun `an unchanged complete root scan should not spend another scan allowance`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(
                problem(intArrayOf(0, 2, 4), intArrayOf(6)).bake(), nativeSat = native,
            )
            val pass = RootSimplificationPass()
            pass.run(session, BacktrackParams(), null)
            val work = session.work

            repeat(3) { pass.run(session, BacktrackParams(), null) }

            assertEquals(work, session.work)
        }
    }

    @Test
    fun `new learned clauses should be scanned without replaying unchanged source clauses`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(
                problem(intArrayOf(0, 2, 4), intArrayOf(6)).bake(), nativeSat = native,
            )
            val pass = RootSimplificationPass()
            val stats = SearchStatsSink()
            pass.run(session, BacktrackParams(), stats)
            session.addLearnedClause(Clause(intArrayOf(0, 3, 4)), 3)

            pass.run(session, BacktrackParams(), stats)

            assertEquals(3L, stats.snapshot().learning.getValue("inprocessRootCandidates"))
        }
    }

    @Test
    fun `new unconditional root facts should revisit earlier source clauses`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(problem(intArrayOf(0, 2, 4)).bake(), nativeSat = native)
            val pass = RootSimplificationPass()
            pass.run(session, BacktrackParams(), null)
            session.addRootDerivedClause(Clause(intArrayOf(1)), 1, 0, false)

            pass.run(session, BacktrackParams(), null)
            session.pinBool(1, false)

            assertEquals(true, session.boolValue(2))
            assertEquals(setOf(2, 4), assertNotNull(session.boolReasonClause(2)).toSet())
        }
    }

    @Test
    fun `a completed tiny root scan should wait for changed input`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(
                problem(intArrayOf(0, 2, 4), intArrayOf(6)).bake(), nativeSat = native,
            )
            val pass = RootSimplificationPass()
            val params = BacktrackParams(inprocessingRootWork = 1L)
            repeat(12) { pass.run(session, params, null) }
            val work = session.work

            repeat(12) { pass.run(session, params, null) }

            assertEquals(work, session.work)
        }
    }

    @Test
    fun `a clause beyond the remaining literal space should wait for the next slice`() {
        for (native in listOf(false, true)) {
            val source = Problem(5001, 0, emptyArray(), arrayOf<Factor>(
                Clause(IntArray(5000) { it * 2 }), Clause(IntArray(5000) { it * 2 + 1 }),
                Clause(intArrayOf(10000)),
            ))
            val session = PropagationSession(source.bake(), nativeSat = native)
            val pass = RootSimplificationPass()
            val stats = SearchStatsSink()

            pass.run(session, BacktrackParams(), stats)
            assertEquals(1L, stats.snapshot().learning.getValue("inprocessRootEligible"))

            pass.run(session, BacktrackParams(), stats)

            assertEquals(2L, stats.snapshot().learning.getValue("inprocessRootEligible"))
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
        for ((native, work) in listOf(false to Long.MAX_VALUE, true to Long.MAX_VALUE,
            false to 10L, true to 10L, false to 1L, true to 1L)) {
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
    fun `a short learned neighborhood should reach a failed literal within one funded probe`() {
        for (native in listOf(false, true)) {
            val source = Problem(8, 0, emptyArray(), arrayOf<Factor>(
                Clause(intArrayOf(14, 2)), Clause(intArrayOf(14, 3)),
            ))
            val session = PropagationSession(source.bake(), nativeSat = native)
            session.addLearnedClause(Clause(intArrayOf(14, 12)), 2)

            RootProbingPass().run(session, BacktrackParams(rootProbeBatch = 1), null)

            assertEquals(true, session.boolValue(7))
            assertEquals(0, session.decisionLevel)
        }
    }

    @Test
    fun `reset should release a retained probe neighborhood`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(problem(intArrayOf(0, 2)).bake(), nativeSat = native)
            session.addLearnedClause(Clause(intArrayOf(0, 2)), 2)
            val pass = RootProbingPass()
            pass.run(session, BacktrackParams(rootProbeBatch = 1), null)
            val before = SharingMemoryObservation(1024L, 128, { false }, {})
            val retained = before.snapshot { pass.retainedMemory(before, false) }

            pass.reset()
            val after = SharingMemoryObservation(1024L, 128, { false }, {})
            val released = after.snapshot { pass.retainedMemory(after, false) }

            assertTrue(assertNotNull(retained.completeValues).getValue("ownedPrimitiveBytes") >= 8L)
            assertEquals(0L, assertNotNull(released.completeValues).getValue("ownedPrimitiveBytes"))
        }
    }

    @Test
    fun `a root slice should refresh binaries using facts proved by its funded probe`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(problem(intArrayOf(0, 2), intArrayOf(0, 3),
                intArrayOf(1, 4, 6)).bake(), nativeSat = native)
            val params = BacktrackParams(rootSimplification = true, rootProbeBatch = 2)
            val loop = assertNotNull(Inprocessing.from(params))

            loop.onRestart(session, params)
            session.pinBool(2, false)

            assertEquals(true, session.boolValue(3))
            assertEquals(setOf(4, 6), assertNotNull(session.boolReasonClause(3)).toSet())
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

    @Test
    fun `pass buffer resets should preserve unfinished source retirement`() {
        for ((native, release) in listOf(false to false, false to true, true to false, true to true)) {
            val session = PropagationSession(problem(intArrayOf(0, 2, 4), intArrayOf(1)).bake(), nativeSat = native)
            val pass = RootSimplificationPass()
            pass.run(session, BacktrackParams(inprocessingRootWork = 11L), null)
            assertEquals(1, session.learnedClauseCount)

            if (release) pass.releaseRetainedBuffers() else pass.reset()
            pass.run(session, BacktrackParams(), null)
            session.pinBool(1, false)

            assertEquals(1, session.learnedClauseCount)
            assertEquals(true, session.boolValue(2))
        }
    }

    @Test
    fun `reset should release a root candidate retained across a tiny slice`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(
                problem(intArrayOf(0, 2, 4), intArrayOf(6)).bake(), nativeSat = native,
            )
            val pass = RootSimplificationPass()
            pass.run(session, BacktrackParams(inprocessingRootWork = 1L), null)
            val before = SharingMemoryObservation(1024L, 128, { false }, {})
            val retained = before.snapshot { pass.retainedMemory(before, false) }

            pass.reset()
            val after = SharingMemoryObservation(1024L, 128, { false }, {})
            val released = after.snapshot { pass.retainedMemory(after, false) }

            assertTrue(assertNotNull(retained.completeValues).getValue("ownedPrimitiveBytes") >= 24L)
            assertEquals(0L, assertNotNull(released.completeValues).getValue("ownedPrimitiveBytes"))
            assertEquals(0, session.learnedClauseCount)
        }
    }

    @Test
    fun `an empty general learned generation should release an obsolete root candidate`() {
        val session = PropagationSession(problem(intArrayOf(6)))
        session.addLearnedClause(Clause(intArrayOf(0, 2)), 2)
        val pass = RootSimplificationPass()
        repeat(8) { pass.run(session, BacktrackParams(inprocessingRootWork = 1L), null) }
        val before = SharingMemoryObservation(1024L, 128, { false }, {})
        val retained = before.snapshot { pass.retainedMemory(before, false) }
        assertTrue(assertNotNull(retained.completeValues).getValue("ownedPrimitiveBytes") > 0L)

        session.forgetLearnedClauses { _, _ -> false }
        pass.run(session, BacktrackParams(), null)
        val after = SharingMemoryObservation(1024L, 128, { false }, {})
        val released = after.snapshot { pass.retainedMemory(after, false) }

        assertEquals(0, session.learnedClauseCount)
        assertFalse(session.fixpointCancelled)
        assertEquals(0L, assertNotNull(released.completeValues).getValue("ownedPrimitiveBytes"))
    }
}
