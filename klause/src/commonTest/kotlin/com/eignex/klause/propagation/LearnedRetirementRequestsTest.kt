package com.eignex.klause.propagation

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LearnedRetirementRequestsTest {
    @Test
    fun `selected retirement should wait for completed publication`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(Problem(2, 0, emptyArray(), emptyArray()), nativeSat = native)
            session.addLearnedClause(Clause(intArrayOf(0, 2)), 9)
            val captured = session.queueLearnedRetirement(0, session.learnedRetirementGeneration,
                256L, 256L, 1_024L) { false }
            val handle = assertNotNull(captured.handle)

            val withheld = session.authorizeLearnedRetirement(handle, false, 256L, 256L) { false }
            session.advanceLearnedRetirements(256L, 256L, { false }, publicationPending = false)

            assertEquals(LearnedRetirementStatus.Deferred, withheld.status)
            assertEquals(LearnedRetirementStatus.Captured, handle.status)
            assertEquals(1, session.activeLearnedClauseCount)

            session.authorizeLearnedRetirement(handle, true, 256L, 256L) { false }
            val pending = session.advanceLearnedRetirements(256L, 256L, { false }, publicationPending = true)
            assertEquals(LearnedRetirementStatus.Deferred, pending.status)
            assertTrue(session.hasLearnedRetirementContinuation)
            assertEquals(1, session.activeLearnedClauseCount)

            val retired = session.advanceLearnedRetirements(256L, 256L, { false }, publicationPending = false)
            assertEquals(LearnedRetirementStatus.Retired, retired.status)
            assertEquals(0, session.activeLearnedClauseCount)
            assertEquals(!native, retired.payloadReleased)
            assertFalse(retired.physicalReleased)
        }
    }

    @Test
    fun `captured identities should survive physical compaction`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(Problem(4, 0, emptyArray(), emptyArray()), nativeSat = native)
            session.addLearnedClause(Clause(intArrayOf(0, 2)), 9)
            session.addLearnedClause(Clause(intArrayOf(4, 6)), 8)
            val handle = assertNotNull(session.queueLearnedRetirement(1, session.learnedRetirementGeneration,
                256L, 256L, 1_024L, { false }).handle)

            session.forgetLearnedClauses { index, _ -> index == 1 }
            session.authorizeLearnedRetirement(handle, true, 256L, 256L) { false }
            val result = session.advanceLearnedRetirements(256L, 256L, { false }, publicationPending = false)

            assertEquals(LearnedRetirementStatus.Retired, result.status)
            assertEquals(0, session.activeLearnedClauseCount)
            assertEquals(8, session.learnedClauseLbd(0))
        }
    }

    @Test
    fun `compaction should reject a stale uncaptured selection`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(Problem(4, 0, emptyArray(), emptyArray()), nativeSat = native)
            session.addLearnedClause(Clause(intArrayOf(0, 2)), 9)
            session.addLearnedClause(Clause(intArrayOf(4, 6)), 8)
            val generation = session.learnedRetirementGeneration
            session.forgetLearnedClauses { index, _ -> index == 1 }

            val result = session.queueLearnedRetirement(0, generation, 256L, 256L, 1_024L) { false }

            assertEquals(LearnedRetirementStatus.StaleSelection, result.status)
            assertNull(result.handle)
            assertEquals(1, session.activeLearnedClauseCount)
        }
    }

    @Test
    fun `protected requests should allow later eligible requests to advance`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(Problem(4, 0, emptyArray(), emptyArray()), nativeSat = native)
            session.addLearnedClause(Clause(intArrayOf(0, 2)), 9)
            session.addLearnedClause(Clause(intArrayOf(4, 6)), 9)
            session.addLearnedClause(Clause(intArrayOf(1)), 1, permanent = true)
            for (index in 0..1) {
                val handle = assertNotNull(session.queueLearnedRetirement(index, session.learnedRetirementGeneration,
                    256L, 256L, 1_024L, { false }).handle)
                session.authorizeLearnedRetirement(handle, true, 256L, 256L) { false }
            }

            val protected = session.advanceLearnedRetirements(256L, 256L, { false }, publicationPending = false)
            val retired = session.advanceLearnedRetirements(256L, 256L, { false }, publicationPending = false)

            assertEquals(LearnedRetirementStatus.ProtectedFloor, protected.status)
            assertEquals(LearnedRetirementStatus.Retired, retired.status)
            assertTrue(session.isLearnedClause(0))
            assertFalse(session.isLearnedClause(1))
            assertTrue(session.hasLearnedRetirementContinuation)
        }
    }

    @Test
    fun `cancellation after the debit should preserve an authorized request`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(Problem(2, 0, emptyArray(), emptyArray()), nativeSat = native)
            session.addLearnedClause(Clause(intArrayOf(0, 2)), 9)
            val handle = assertNotNull(session.queueLearnedRetirement(0, session.learnedRetirementGeneration,
                256L, 256L, 1_024L, { false }).handle)
            session.authorizeLearnedRetirement(handle, true, 256L, 256L) { false }
            val before = session.work

            val stopped = session.advanceLearnedRetirements(256L, 256L,
                { session.work - before >= 32L }, publicationPending = false)

            assertEquals(LearnedRetirementStatus.Deferred, stopped.status)
            assertEquals(32L, stopped.work)
            assertEquals(1, session.activeLearnedClauseCount)
            assertEquals(LearnedRetirementStatus.Pending, handle.status)

            val resumed = session.advanceLearnedRetirements(256L, 256L, { false }, publicationPending = false)
            assertEquals(LearnedRetirementStatus.Retired, resumed.status)
        }
    }

    @Test
    fun `acknowledged cancellation should release the request owner within a later slice`() {
        val session = PropagationSession(Problem(2, 0, emptyArray(), emptyArray()), nativeSat = false)
        session.addLearnedClause(Clause(intArrayOf(0, 2)), 9)
        val handle = assertNotNull(session.queueLearnedRetirement(0, session.learnedRetirementGeneration,
            256L, 256L, 1_024L, { false }).handle)

        session.cancelLearnedRetirement(handle, 256L, 256L) { false }
        val acknowledged = session.acknowledgeLearnedRetirement(handle, 256L, 256L) { false }
        session.advanceLearnedRetirements(256L, 256L, { false }, publicationPending = false)
        val ownership = assertNotNull(session.sharingMemory(maxWork = 1_000_000L).completeValues)

        assertTrue(acknowledged.acknowledged)
        assertEquals(0L, ownership["selectedRetirementRequests"])
        assertEquals(53L, ownership["selectedRetirementOwnedBytes"])
        assertFalse(session.hasLearnedRetirementContinuation)
        assertEquals(1, session.activeLearnedClauseCount)
    }

    @Test
    fun `capture should defer when a containing resource cannot fund its request`() {
        for ((work, scratch, retained) in listOf(Triple(0L, 256L, 1_024L),
            Triple(256L, 64L, 1_024L), Triple(256L, 256L, 53L))) {
            val session = PropagationSession(Problem(2, 0, emptyArray(), emptyArray()), nativeSat = false)
            session.addLearnedClause(Clause(intArrayOf(0, 2)), 9)

            val result = session.queueLearnedRetirement(0, session.learnedRetirementGeneration,
                work, scratch, retained) { false }

            assertEquals(LearnedRetirementStatus.Deferred, result.status)
            assertNull(result.handle)
            assertTrue(result.work <= work)
            assertFalse(session.hasLearnedRetirementContinuation)
        }
    }

    @Test
    fun `integer atom clauses should decline the Boolean retirement contract`() {
        val session = PropagationSession(Problem(1, 1, arrayOf(IntDomain(0, 10)), emptyArray()), nativeSat = false)
        val atom = session.explanationState.atomVarLe(0, 5)
        val index = session.learnedClauseCount
        session.addLearnedClause(Clause(intArrayOf(0, Lit.make(atom, true))), 9)

        val result = session.queueLearnedRetirement(index, session.learnedRetirementGeneration,
            256L, 256L, 1_024L) { false }

        assertEquals(LearnedRetirementStatus.Unsupported, result.status)
        assertNull(result.handle)
        assertTrue(session.isLearnedClause(index))
    }

    @Test
    fun `scope reset should invalidate unpublished and authorized handles`() {
        for (authorize in listOf(false, true)) {
            val session = PropagationSession(Problem(2, 0, emptyArray(), emptyArray()), nativeSat = false)
            session.addLearnedClause(Clause(intArrayOf(0, 2)), 9)
            val handle = assertNotNull(session.queueLearnedRetirement(0, session.learnedRetirementGeneration,
                256L, 256L, 1_024L, { false }).handle)
            if (authorize) session.authorizeLearnedRetirement(handle, true, 256L, 256L) { false }

            session.seed(Assumptions.None)

            assertEquals(LearnedRetirementStatus.ScopeReleased, handle.status)
            assertFalse(session.hasLearnedRetirementContinuation)
        }
    }

    @Test
    fun `selected retirement should preserve suspended ranking priorities`() {
        for (native in listOf(false, true)) for (grant in listOf(32L, 50L, 64L, 80L, 96L, 128L)) {
            val session = PropagationSession(Problem(2, 0, emptyArray(), emptyArray()), nativeSat = native)
            repeat(7) { session.addLearnedClause(Clause(intArrayOf(0, 2)), it + 1) }
            session.maintainCapWindow(4, grant, 64L, { false }) { false }
            val handle = assertNotNull(session.queueLearnedRetirement(0, session.learnedRetirementGeneration,
                256L, 256L, 1_024L, { false }).handle)
            session.authorizeLearnedRetirement(handle, true, 256L, 256L) { false }

            val selected = session.advanceLearnedRetirements(256L, 256L, { false }, publicationPending = false)
            repeat(16) {
                val before = session.work
                session.maintainCapWindow(4, 256L, 64L, { false }) { false }
                assertTrue(session.work - before <= 256L)
            }

            assertEquals(LearnedRetirementStatus.Retired, selected.status)
            assertEquals(4, session.activeLearnedClauseCount)
            for (index in 1..4) assertTrue(session.isLearnedClause(index))
        }
    }

    @Test
    fun `selected retirement should remove a suspended policy survivor from the floor`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(Problem(2, 0, emptyArray(), emptyArray()), nativeSat = native)
            repeat(3) { session.addLearnedClause(Clause(intArrayOf(0, 2)), 9 - it) }
            session.maintainCapWindow(1, 50L, 64L, { false }) { index -> index == 0 }
            val handle = assertNotNull(session.queueLearnedRetirement(0, session.learnedRetirementGeneration,
                256L, 256L, 1_024L, { false }).handle)
            session.authorizeLearnedRetirement(handle, true, 256L, 256L) { false }

            session.advanceLearnedRetirements(256L, 256L, { false }, publicationPending = false)
            repeat(8) { session.maintainCapWindow(1, 256L, 64L, { false }) { index -> index == 0 } }

            assertEquals(1, session.activeLearnedClauseCount)
            assertFalse(session.isLearnedClause(1))
            assertTrue(session.isLearnedClause(2))
        }
    }

    @Test
    fun `a compacted target should not alias a later admission`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(Problem(4, 0, emptyArray(), emptyArray()), nativeSat = native)
            session.addLearnedClause(Clause(intArrayOf(0, 2)), 9)
            val handle = assertNotNull(session.queueLearnedRetirement(0, session.learnedRetirementGeneration,
                256L, 256L, 1_024L, { false }).handle)
            session.forgetLearnedClauses { _, _ -> false }
            session.addLearnedClause(Clause(intArrayOf(4, 6)), 8)
            session.authorizeLearnedRetirement(handle, true, 256L, 256L) { false }

            val result = session.advanceLearnedRetirements(256L, 256L, { false }, publicationPending = false)

            assertEquals(LearnedRetirementStatus.AlreadyGone, result.status)
            assertTrue(result.payloadReleased)
            assertTrue(result.physicalReleased)
            assertEquals(1, session.activeLearnedClauseCount)
            assertEquals(8, session.learnedClauseLbd(0))
        }
    }

    @Test
    fun `closing the store should release outstanding request scopes`() {
        val session = PropagationSession(Problem(2, 0, emptyArray(), emptyArray()), nativeSat = false)
        session.addLearnedClause(Clause(intArrayOf(0, 2)), 9)
        val handle = assertNotNull(session.queueLearnedRetirement(0, session.learnedRetirementGeneration,
            256L, 256L, 1_024L, { false }).handle)
        session.authorizeLearnedRetirement(handle, true, 256L, 256L) { false }

        session.releaseRetainedState()

        assertEquals(LearnedRetirementStatus.ScopeReleased, handle.status)
        assertFalse(session.hasLearnedRetirementContinuation)
    }
}
