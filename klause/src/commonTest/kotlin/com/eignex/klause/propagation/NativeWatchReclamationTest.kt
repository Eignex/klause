package com.eignex.klause.propagation

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NativeWatchReclamationTest {
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

        session.compactLearnedClauses(40L, 1_048_576L, { session.work >= stopAt }) { true }
        val cancelled = assertNotNull(session.sharingMemory(maxWork = 1_000_000L).completeValues)
        repeat(80) { session.compactLearnedClauses(40L, 1_048_576L, { false }) { true } }
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
            session.compactLearnedClauses(40L, 1_048_576L, { false }) { true }
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
        repeat(80) { session.compactLearnedClauses(40L, 1_048_576L, { false }) { true } }

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
            session.compactLearnedClauses(65_536L, 1_048_576L, { false }) { true }
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
            session.compactLearnedClauses(65_536L, 1_048_576L, { false }) { true }
            assertTrue(session.work - before <= 65_536L)
        }
        val after = assertNotNull(session.sharingMemory(maxWork = 1_000_000L).completeValues)

        assertEquals(baseline.getValue("sessionWatchCapacityBytes"), after.getValue("sessionWatchCapacityBytes"))
    }
}
