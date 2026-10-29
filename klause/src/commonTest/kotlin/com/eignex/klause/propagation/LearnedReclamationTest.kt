package com.eignex.klause.propagation

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearPropagator
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.SharingMemoryStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LearnedReclamationTest {
    @Test
    fun `reclamation should release unused payload watches and primitive metadata on both stores`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(Problem(4, 0, emptyArray(), emptyList()), nativeSat = native)
            repeat(16) { session.addLearnedClause(Clause(intArrayOf(0, 2)), 2) }
            val before = assertNotNull(session.sharingMemory(maxWork = 1_000_000L).completeValues)

            val reclaimed = session.reclaimLearnedMemory(0L, maxWork = 1_000_000L)
            val after = assertNotNull(session.sharingMemory(maxWork = 1_000_000L).completeValues)

            assertEquals(ReclamationStatus.RECLAIMED, reclaimed.status)
            assertEquals(0, session.learnedClauseCount)
            assertEquals(0L, after.getValue("learnedPayloadBytes"))
            assertTrue(after.getValue("learnedMetadataCapacityBytes") < before.getValue("learnedMetadataCapacityBytes"))
            assertTrue(after.getValue("sessionWatchCapacityBytes") < before.getValue("sessionWatchCapacityBytes"))
            assertTrue(after.getValue("ownedPrimitiveBytes") < before.getValue("ownedPrimitiveBytes"))
            assertTrue(assertNotNull(reclaimed.afterBytes) < assertNotNull(reclaimed.beforeBytes))
            assertTrue(reclaimed.work <= 1_000_000L)
        }
    }

    @Test
    fun `tiny work allowances should defer without changing either learned store`() {
        for (native in listOf(false, true)) for (allowance in listOf(0L, 1L, 7L)) {
            val session = PropagationSession(Problem(4, 0, emptyArray(), emptyList()), nativeSat = native)
            session.addLearnedClause(Clause(intArrayOf(0, 2)), 2)
            val revision = session.learnedClauseRevision

            val reclaimed = session.reclaimLearnedMemory(0L, maxWork = allowance)

            assertEquals(ReclamationStatus.DEFERRED, reclaimed.status)
            assertTrue(reclaimed.work <= allowance)
            assertEquals(1, session.learnedClauseCount)
            assertEquals(revision, session.learnedClauseRevision)
            session.pinBool(0, false)
            assertEquals(true, session.boolValue(1))
        }
    }

    @Test
    fun `tiny scratch allowances should preserve the learned watches on both stores`() {
        for (native in listOf(false, true)) for (allowance in listOf(0L, 1L, 63L)) {
            val session = PropagationSession(Problem(4, 0, emptyArray(), emptyList()), nativeSat = native)
            session.addLearnedClause(Clause(intArrayOf(0, 2)), 2)

            val reclaimed = session.reclaimLearnedMemory(0L, maxWork = 1_000_000L,
                maxScratchBytes = allowance)
            session.pinBool(0, false)

            assertEquals(ReclamationStatus.DEFERRED, reclaimed.status)
            assertEquals(1, session.learnedClauseCount)
            assertEquals(true, session.boolValue(1))
        }
    }

    @Test
    fun `an exhausted preparation allowance should defer before publication on both stores`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(Problem(4, 0, emptyArray(), emptyList()), nativeSat = native)
            session.addLearnedClause(Clause(intArrayOf(0, 2)), 2)
            val allowance = session.sharingMemory(maxWork = 1_000_000L).work + 2L
            val revision = session.learnedClauseRevision

            val reclaimed = session.reclaimLearnedMemory(0L, maxWork = allowance)
            session.pinBool(0, false)

            assertEquals(ReclamationStatus.DEFERRED, reclaimed.status)
            assertNotNull(reclaimed.beforeBytes)
            assertTrue(reclaimed.work <= allowance)
            assertEquals(revision, session.learnedClauseRevision)
            assertEquals(1, session.learnedClauseCount)
            assertEquals(true, session.boolValue(1))
        }
    }

    @Test
    fun `insufficient native arena scratch should defer after completing the observation`() {
        val session = PropagationSession(Problem(4096, 0, emptyArray(), emptyList()), nativeSat = true)
        session.addLearnedClause(Clause(IntArray(4096) { it * 2 }), 2)
        assertEquals(4096, session.learnedClauseLiteralCount(0))

        val reclaimed = session.reclaimLearnedMemory(0L, maxWork = 1_000_000L, maxScratchBytes = 8192L)

        assertEquals(ReclamationStatus.DEFERRED, reclaimed.status)
        assertNotNull(reclaimed.beforeBytes)
        assertEquals(1, session.learnedClauseCount)
        assertEquals(4096, session.learnedClauseLiteralCount(0))
    }

    @Test
    fun `cancellation during reclamation preparation should preserve the complete store`() {
        for (native in listOf(false, true)) {
            var limit = Long.MAX_VALUE
            lateinit var session: PropagationSession
            session = PropagationSession(Problem(4, 0, emptyArray(), emptyList()), nativeSat = native,
                cancellation = Cancellation { limit != Long.MAX_VALUE && session.work >= limit })
            repeat(3) { session.addLearnedClause(Clause(intArrayOf(0, 2)), 2) }
            val observationWork = session.sharingMemory(maxWork = 1_000_000L).work
            val revision = session.learnedClauseRevision
            limit = session.work + observationWork + 3L

            val reclaimed = session.reclaimLearnedMemory(0L, maxWork = 1_000_000L)
            limit = Long.MAX_VALUE
            session.pinBool(0, false)

            assertEquals(ReclamationStatus.CANCELLED, reclaimed.status)
            assertNotNull(reclaimed.beforeBytes)
            assertEquals(observationWork + 3L, reclaimed.work)
            assertEquals(revision, session.learnedClauseRevision)
            assertEquals(3, session.learnedClauseCount)
            assertEquals(true, session.boolValue(1))
        }
    }

    @Test
    fun `reclamation should keep the live reason usable after sibling undo`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(Problem(3, 0, emptyArray(), emptyList()), nativeSat = native)
            session.addLearnedClause(Clause(intArrayOf(4, 5)), 4)
            session.addLearnedClause(Clause(intArrayOf(0, 2)), 2)
            session.pinBool(0, false)
            val reason = assertNotNull(session.boolReasonClause(1)).toList()

            val reclaimed = session.reclaimLearnedMemory(0L, maxWork = 1_000_000L)
            assertEquals(reason, assertNotNull(session.boolReasonClause(1)).toList())
            session.popToLevel(0)
            session.pinBool(0, false)

            assertEquals(ReclamationStatus.RECLAIMED, reclaimed.status)
            assertEquals(1, session.learnedClauseCount)
            assertEquals(true, session.boolValue(1))
            assertEquals(reason, assertNotNull(session.boolReasonClause(1)).toList())
        }
    }

    @Test
    fun `a protected root reason should report its memory floor on both stores`() {
        for (native in listOf(false, true)) {
            val source = Problem(2, 0, emptyArray(), arrayOf(Clause(intArrayOf(1))))
            val session = PropagationSession(source, nativeSat = native)
            session.addLearnedClause(Clause(intArrayOf(0, 2)), 2)
            val reason = assertNotNull(session.boolReasonClause(1)).toList()

            val reclaimed = session.reclaimLearnedMemory(0L, maxWork = 1_000_000L)

            assertEquals(ReclamationStatus.PROTECTED_FLOOR, reclaimed.status)
            assertTrue(assertNotNull(reclaimed.protectedBytes) > 0L)
            assertEquals(1, session.learnedClauseCount)
            assertEquals(true, session.boolValue(1))
            assertEquals(reason, assertNotNull(session.boolReasonClause(1)).toList())
        }
    }

    @Test
    fun `reclamation should remap an integer reason held only in undo history`() {
        val session = PropagationSession(Problem(1, 1, arrayOf(IntDomain(0, 10)),
            arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 10))))
        session.addLearnedClause(Clause(intArrayOf(0, 1)), 4)
        val row = requireNotNull(IntegerConflictRow.source(
            LinearPropagator(intArrayOf(), intArrayOf(0), longArrayOf(1), intArrayOf(0), LinearOp.LE, 8),
            useLo = true))
        session.addLearnedIntegerRow(row, 2)
        session.pinIntAtMost(0, 4)

        val reclaimed = session.reclaimLearnedMemory(0L, maxWork = 1_000_000L)
        session.popLast()

        assertEquals(ReclamationStatus.RECLAIMED, reclaimed.status)
        assertEquals(IntDomain(0, 8), session.intDomain(0))
        assertEquals(1, session.learnedClauseCount)
        assertEquals(1, session.explanationState.intMaxReason[0])
        assertIs<LearnedIntegerPropagator>(session.explanationState.factorAt(1))
    }

    @Test
    fun `a pending first attachment should survive reclamation and consume its root premise`() {
        val session = PropagationSession(Problem(3, 0, emptyArray(), arrayOf(Clause(intArrayOf(1)))))
        session.addLearnedClause(Clause(intArrayOf(4, 5)), 4)
        session.importClause(SharedClause(intArrayOf(0, 2), longArrayOf(), 2), origin = 7)

        val reclaimed = session.reclaimLearnedMemory(0L, maxWork = 1_000_000L)
        session.pinBool(2, true)

        assertEquals(ReclamationStatus.RECLAIMED, reclaimed.status)
        assertEquals(1, session.learnedClauseCount)
        assertEquals(7, session.learnedClauseOrigin(0))
        assertEquals(true, session.boolValue(1))
        assertEquals(1L, session.learnedLifecycle().getValue("learningClauseConsumed"))
    }

    @Test
    fun `reclamation should release an unused pseudo Boolean row`() {
        val session = PropagationSession(Problem(4, 0, emptyArray(), emptyList()), pbLearning = true)
        session.addLearnedPb(longArrayOf(1, 1, 1, 1), intArrayOf(0, 2, 4, 6), 2L, 2)

        val reclaimed = session.reclaimLearnedMemory(0L, maxWork = 1_000_000L)
        val after = assertNotNull(session.sharingMemory(maxWork = 1_000_000L).completeValues)

        assertEquals(ReclamationStatus.RECLAIMED, reclaimed.status)
        assertEquals(0, session.learnedClauseCount)
        assertEquals(0L, after.getValue("learnedPayloadBytes"))
        assertEquals(1L, session.learnedLifecycle().getValue("learningPbRetired"))
    }

    @Test
    fun `releasing a session should make retained memory stale on both stores`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(Problem(4, 0, emptyArray(), emptyList()), nativeSat = native)
            session.addLearnedClause(Clause(intArrayOf(0, 2)), 2)
            val work = session.work

            session.releaseRetainedState()
            session.releaseRetainedState()
            val released = session.sharingMemory()

            assertEquals(SharingMemoryStatus.STALE, released.status)
            assertNull(released.completeValues)
            assertTrue(released.values.isEmpty())
            assertEquals(0L, released.work)
            assertEquals(0, session.learnedClauseCount)
            assertEquals(work, session.work)
            assertEquals(1L, session.learnedLifecycle().getValue("learningClauseAdmitted"))
        }
    }
}
