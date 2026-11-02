package com.eignex.klause.propagation

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NativeWatchReclamationTest {
    @Test
    fun `a completed cleanup scan should stay complete until another clause retires`() {
        val session = PropagationSession(Problem(6, 0, emptyArray(),
            arrayOf(Clause(intArrayOf(8, 10)))), nativeSat = true)
        assertTrue(session.usesNativeSat)
        repeat(64) { session.addLearnedClause(Clause(intArrayOf(0, 2)), 9) }
        session.maintainCapWindow(0, 65_536L, 1_048_576L, { false }) { false }
        repeat(160) { session.compactLearnedClauses(40L, 1_048_576L, { false }) { _, _ -> true } }
        val before = session.work

        session.compactLearnedClauses(40L, 1_048_576L, { false }) { _, _ -> true }

        assertTrue(session.work - before < 16L)
        repeat(64) { session.addLearnedClause(Clause(intArrayOf(0, 2)), 9) }
        session.maintainCapWindow(0, 65_536L, 1_048_576L, { false }) { false }
        repeat(160) { session.compactLearnedClauses(40L, 1_048_576L, { false }) { _, _ -> true } }
        val after = assertNotNull(session.sharingMemory(maxWork = 1_000_000L).completeValues)
        assertEquals(2L, after.getValue("sessionWatchEntries"))
    }

    @Test
    fun `cancelled watch cleanup should resume without changing its retained generation`() {
        val session = PropagationSession(Problem(6, 0, emptyArray(),
            arrayOf(Clause(intArrayOf(8, 10)))), nativeSat = true)
        assertTrue(session.usesNativeSat)
        repeat(64) { session.addLearnedClause(Clause(intArrayOf(0, 2)), 9) }
        session.maintainCapWindow(0, 65_536L, 1_048_576L, { false }) { false }
        val initial = assertNotNull(session.sharingMemory(maxWork = 1_000_000L).completeValues)
        val generation = session.learnedRetirementGeneration
        val stopAt = session.work + 4L

        session.compactLearnedClauses(40L, 1_048_576L, { session.work >= stopAt }) { _, _ -> true }
        val cancelled = assertNotNull(session.sharingMemory(maxWork = 1_000_000L).completeValues)
        repeat(80) { session.compactLearnedClauses(40L, 1_048_576L, { false }) { _, _ -> true } }
        val resumed = assertNotNull(session.sharingMemory(maxWork = 1_000_000L).completeValues)

        assertEquals(initial.getValue("sessionWatchEntries"), cancelled.getValue("sessionWatchEntries"))
        assertEquals(2L, resumed.getValue("sessionWatchEntries"))
        assertEquals(generation, session.learnedRetirementGeneration)
    }

    @Test
    fun `small compaction grants should release watches without remapping learned handles`() {
        val session = PropagationSession(Problem(6, 0, emptyArray(),
            arrayOf(Clause(intArrayOf(8, 10)))), nativeSat = true)
        assertTrue(session.usesNativeSat)
        val baseline = assertNotNull(session.sharingMemory(maxWork = 1_000_000L).completeValues)
        repeat(64) { session.addLearnedClause(Clause(intArrayOf(0, 2)), 9) }
        session.maintainCapWindow(0, 65_536L, 1_048_576L, { false }) { false }
        val generation = session.learnedRetirementGeneration

        repeat(80) {
            val before = session.work
            session.compactLearnedClauses(40L, 1_048_576L, { false }) { _, _ -> true }
            assertTrue(session.work - before <= 40L)
        }
        val after = assertNotNull(session.sharingMemory(maxWork = 1_000_000L).completeValues)

        assertEquals(baseline.getValue("sessionWatchCapacityBytes"), after.getValue("sessionWatchCapacityBytes"))
        assertEquals(generation, session.learnedRetirementGeneration)
    }

    @Test
    fun `bounded watch reclamation should preserve a permanent clause`() {
        val session = PropagationSession(Problem(6, 0, emptyArray(),
            arrayOf(Clause(intArrayOf(8, 10)))), nativeSat = true)
        assertTrue(session.usesNativeSat)
        session.addLearnedClause(Clause(intArrayOf(0, 2)), 9, permanent = true)
        repeat(64) { session.addLearnedClause(Clause(intArrayOf(4, 6)), 9) }
        session.maintainCapWindow(0, 65_536L, 1_048_576L, { false }) { false }
        repeat(80) { session.compactLearnedClauses(40L, 1_048_576L, { false }) { _, _ -> true } }

        session.pinBool(0, false)

        assertEquals(true, session.boolValue(1))
    }

    @Test
    fun `four thousand retired rows should release watch capacity under production grants`() {
        val session = PropagationSession(Problem(6, 0, emptyArray(),
            arrayOf(Clause(intArrayOf(8, 10)))), nativeSat = true)
        assertTrue(session.usesNativeSat)
        val baseline = assertNotNull(session.sharingMemory(maxWork = 1_000_000L).completeValues)
        repeat(4000) { session.addLearnedClause(Clause(intArrayOf(0, 2)), 9) }
        repeat(40) { session.maintainCapWindow(0, 65_536L, 1_048_576L, { false }) { false } }
        assertEquals(0, session.activeLearnedClauseCount)

        repeat(16) {
            val before = session.work
            session.compactLearnedClauses(65_536L, 1_048_576L, { false }) { _, _ -> true }
            assertTrue(session.work - before <= 65_536L)
        }
        val after = assertNotNull(session.sharingMemory(maxWork = 1_000_000L).completeValues)

        assertEquals(baseline.getValue("sessionWatchCapacityBytes"), after.getValue("sessionWatchCapacityBytes"))
    }

    @Test
    fun `twenty thousand retired rows should release watch capacity under production grants`() {
        val session = PropagationSession(Problem(6, 0, emptyArray(),
            arrayOf(Clause(intArrayOf(8, 10)))), nativeSat = true)
        assertTrue(session.usesNativeSat)
        val baseline = assertNotNull(session.sharingMemory(maxWork = 1_000_000L).completeValues)
        repeat(20_000) { session.addLearnedClause(Clause(intArrayOf(0, 2)), 9) }
        repeat(40) { session.maintainCapWindow(0, 65_536L, 1_048_576L, { false }) { false } }
        assertEquals(0, session.activeLearnedClauseCount)

        repeat(16) {
            val before = session.work
            session.compactLearnedClauses(65_536L, 1_048_576L, { false }) { _, _ -> true }
            assertTrue(session.work - before <= 65_536L)
        }
        val after = assertNotNull(session.sharingMemory(maxWork = 1_000_000L).completeValues)

        assertEquals(baseline.getValue("sessionWatchCapacityBytes"), after.getValue("sessionWatchCapacityBytes"))
    }
    @Test
    fun `permanent floors should permit metadata page release within ordinary small grants`() {
        for (permanentCount in listOf(4000, 20_000)) {
            val base = Clause(intArrayOf(8, 10))
            val session = PropagationSession(Problem(6, 0, emptyArray(), arrayOf(base)), nativeSat = true)
            repeat(permanentCount) { session.addLearnedClause(base, 2, permanent = true) }
            repeat(64) { session.addLearnedClause(Clause(intArrayOf(0, 2)), 9) }
            repeat(8) { session.maintainCapWindow(0, 32_768L, 4096L, { false }) { false } }
            val retired = assertNotNull(session.sharingMemory(maxWork = 1_000_000L).completeValues)
            val generation = session.learnedRetirementGeneration

            repeat(32) {
                val before = session.work
                session.compactLearnedClauses(32_768L, 4096L, { false }) { _, _ -> true }
                assertTrue(session.work - before <= 32_768L)
            }
            val released = assertNotNull(session.sharingMemory(maxWork = 1_000_000L).completeValues)

            assertEquals(permanentCount, session.activeLearnedClauseCount)
            assertTrue(released.getValue("learnedMetadataCapacityBytes") <
                retired.getValue("learnedMetadataCapacityBytes"))
            assertEquals(generation, session.learnedRetirementGeneration)
            session.pinBool(4, false)
            assertEquals(true, session.boolValue(5))
        }
    }

    @Test
    fun `appended retirements should resume metadata cleanup while retaining a live reason`() {
        val base = Clause(intArrayOf(8, 10))
        val session = PropagationSession(Problem(6, 0, emptyArray(), arrayOf(base, Clause(intArrayOf(1)))), nativeSat = true)
        repeat(16) { session.addLearnedClause(base, 2, permanent = true) }
        session.addLearnedClause(Clause(intArrayOf(0, 2)), 9)
        val reason = assertNotNull(session.boolReasonClause(1)).toList()
        val generation = session.learnedRetirementGeneration
        repeat(3) {
            repeat(64) { session.addLearnedClause(Clause(intArrayOf(4, 6)), 9) }
            repeat(8) { session.maintainCapWindow(0, 32_768L, 4096L, { false }) { false } }
            repeat(8) { session.compactLearnedClauses(40L, 256L, { false }) { _, _ -> true } }
        }
        assertEquals(17, session.activeLearnedClauseCount)
        val before = assertNotNull(session.sharingMemory(maxWork = 1_000_000L).completeValues)
        val stopAt = session.work + 4L

        session.compactLearnedClauses(40L, 256L, { session.work >= stopAt }) { _, _ -> true }
        val cancelled = assertNotNull(session.sharingMemory(maxWork = 1_000_000L).completeValues)
        repeat(512) {
            val work = session.work
            session.compactLearnedClauses(40L, 256L, { false }) { _, _ -> true }
            assertTrue(session.work - work <= 40L)
        }
        val released = assertNotNull(session.sharingMemory(maxWork = 1_000_000L).completeValues)

        assertEquals(before.getValue("learnedMetadataCapacityBytes"),
            cancelled.getValue("learnedMetadataCapacityBytes"))
        assertTrue(released.getValue("learnedMetadataCapacityBytes") < before.getValue("learnedMetadataCapacityBytes"))
        assertEquals(17, session.activeLearnedClauseCount)
        assertEquals(generation, session.learnedRetirementGeneration)
        assertEquals(reason, assertNotNull(session.boolReasonClause(1)).toList())
        session.pinBool(4, false)
        session.popLast()
        assertEquals(reason, assertNotNull(session.boolReasonClause(1)).toList())
    }

}
