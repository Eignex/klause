package com.eignex.klause.propagation

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearPropagator
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.SharingMemoryStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharingMemoryTest {
    @Test
    fun `tiny allowances stop observations before completing either learned store`() {
        for (native in listOf(false, true)) for (allowance in listOf(0L, 1L, 7L)) {
            val session = PropagationSession(Problem(32, 0, emptyArray(), emptyList()), nativeSat = native)
            repeat(8) { session.addLearnedClause(Clause(intArrayOf(it * 2, it * 2 + 2)), 2) }
            val before = session.work

            val sample = session.sharingMemory(maxWork = allowance)

            assertEquals(SharingMemoryStatus.PARTIAL, sample.status)
            assertEquals(allowance, sample.work)
            assertEquals(allowance, session.work - before)
            assertNull(sample.completeValues)
            assertEquals(8, session.learnedClauseCount)
        }
    }

    @Test
    fun `cancellation sees each charged diagnostic visit before the next visit`() {
        for (native in listOf(false, true)) for (allowance in listOf(5L, 80L)) {
            var limit = Long.MAX_VALUE
            lateinit var session: PropagationSession
            session = PropagationSession(Problem(32, 0, emptyArray(), emptyList()), nativeSat = native,
                cancellation = Cancellation { limit != Long.MAX_VALUE && session.work >= limit })
            repeat(8) { session.addLearnedClause(Clause(intArrayOf(it * 2, it * 2 + 2)), 2) }
            val before = session.work
            limit = before + allowance

            val sample = session.sharingMemory(maxWork = 10_000L)

            assertEquals(SharingMemoryStatus.PARTIAL, sample.status)
            assertEquals(allowance, sample.work)
            assertEquals(allowance, session.work - before)
            assertNull(sample.completeValues)
        }
    }

    @Test
    fun `scratch exhaustion preserves an active reason through compaction`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(Problem(4, 0, emptyArray(), emptyList()), nativeSat = native)
            session.addLearnedClause(Clause(intArrayOf(0, 2)), 2)
            session.pinBool(0, false)

            val sample = session.sharingMemory(maxScratchEntries = 0)
            session.forgetLearnedClauses { _, _ -> false }

            assertEquals(SharingMemoryStatus.PARTIAL, sample.status)
            assertEquals(0, sample.scratchEntries)
            assertEquals(true, session.boolValue(1))
            assertEquals(1, session.learnedClauseCount)
            assertNotNull(session.boolReasonClause(1))
        }
    }

    @Test
    fun `complete snapshots expose protected reasons on both stores`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(Problem(4, 0, emptyArray(), emptyList()), nativeSat = native)
            session.addLearnedClause(Clause(intArrayOf(0, 2)), 2)
            session.pinBool(0, false)

            val sample = session.sharingMemory()

            assertEquals(SharingMemoryStatus.COMPLETE, sample.status)
            val values = assertNotNull(sample.completeValues)
            assertEquals(1L, values.getValue("reasonProtectedObjects"))
            assertTrue(values.getValue("reasonPayloadBytes") >= 4L)
            assertEquals(1L, values.getValue("learnedObjects"))
        }
    }

    @Test
    fun `partial observation preserves an integer reason saved in undo history`() {
        val session = PropagationSession(Problem(1, 1, arrayOf(IntDomain(0, 10)),
            arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 10))))
        session.addLearnedClause(Clause(intArrayOf(0, 1)), 4)
        val row = requireNotNull(IntegerConflictRow.source(
            LinearPropagator(intArrayOf(), intArrayOf(0), longArrayOf(1), intArrayOf(0), LinearOp.LE, 8),
            useLo = true))
        session.addLearnedIntegerRow(row, 2)
        session.pinIntAtMost(0, 4)
        val complete = session.sharingMemory()
        assertEquals(SharingMemoryStatus.COMPLETE, complete.status)
        assertEquals(1L, assertNotNull(complete.completeValues).getValue("reasonProtectedObjects"))

        val sample = session.sharingMemory(maxScratchEntries = 1)
        session.forgetLearnedClauses { _, _ -> false }
        session.popLast()

        assertEquals(SharingMemoryStatus.PARTIAL, sample.status)
        assertTrue(sample.scratchEntries <= 1)
        assertEquals(IntDomain(0, 8), session.intDomain(0))
        assertIs<LearnedIntegerPropagator>(session.explanationState.factorAt(1))
    }

    @Test
    fun `shared search observation uses the remaining native allowance`() {
        val session = PropagationSession(Problem(0, 0, emptyArray(), emptyList()))
        val native = session.sharingMemory()
        val shared = SearchSession(emptyList())
        session.sharedLearningMemory = shared::retainedLearningMemory
        val before = session.work

        val sample = session.sharingMemory(maxWork = native.work + 2L)

        assertEquals(SharingMemoryStatus.PARTIAL, sample.status)
        assertEquals(native.work + 2L, sample.work)
        assertEquals(sample.work, session.work - before)
        assertNull(sample.completeValues)
    }
}
