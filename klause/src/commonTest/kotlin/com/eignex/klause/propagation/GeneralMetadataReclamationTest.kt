package com.eignex.klause.propagation

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GeneralMetadataReclamationTest {
    @Test
    fun `permanent floors should allow bounded policy and payload reference release`() {
        for (permanentCount in listOf(4000, 20_000)) {
            val clause = Clause(intArrayOf(8, 10))
            val session = PropagationSession(Problem(6, 0, emptyArray(), arrayOf(clause)), nativeSat = false)
            repeat(permanentCount) { session.addLearnedClause(clause, 2, permanent = true) }
            repeat(64) { session.addLearnedClause(Clause(intArrayOf(0, 2)), 9) }
            repeat(8) { session.maintainCapWindow(0, 32_768L, 4096L, { false }) { false } }
            val rows = session.explanationState.learned.store
            val before = rows.primitiveBytes
            val references = rows.referenceCapacity
            val generation = session.learnedRetirementGeneration

            repeat(24) {
                val work = session.work
                session.compactLearnedClauses(32_768L, 4096L, { false }) { _, _ -> true }
                assertTrue(session.work - work <= 32_768L)
            }

            assertEquals(permanentCount, session.activeLearnedClauseCount)
            assertTrue(rows.primitiveBytes < before)
            assertTrue(rows.referenceCapacity < references)
            assertEquals(generation, session.learnedRetirementGeneration)
            session.pinBool(4, false)
            assertEquals(true, session.boolValue(5))
        }
    }

    @Test
    fun `growing retirement queues should release metadata while retaining a root reason`() {
        val clause = Clause(intArrayOf(8, 10))
        val session = PropagationSession(Problem(6, 0, emptyArray(),
            arrayOf(clause, Clause(intArrayOf(1)))), nativeSat = false)
        repeat(16) { session.addLearnedClause(clause, 2, permanent = true) }
        session.addLearnedClause(Clause(intArrayOf(0, 2)), 9)
        val reason = assertNotNull(session.boolReasonClause(1)).toList()
        val generation = session.learnedRetirementGeneration
        repeat(3) {
            repeat(64) { session.addLearnedClause(Clause(intArrayOf(4, 6)), 9, origin = 7) }
            repeat(8) { session.maintainCapWindow(0, 32_768L, 4096L, { false }) { false } }
            repeat(8) { session.compactLearnedClauses(40L, 256L, { false }) { _, _ -> true } }
        }
        assertEquals(17, session.activeLearnedClauseCount)
        val rows = session.explanationState.learned.store
        val before = rows.primitiveBytes
        val stopAt = session.work + 4L

        session.compactLearnedClauses(40L, 256L, { session.work >= stopAt }) { _, _ -> true }
        assertEquals(before, rows.primitiveBytes)
        repeat(512) {
            val work = session.work
            session.compactLearnedClauses(256L, 256L, { false }) { _, _ -> true }
            assertTrue(session.work - work <= 256L)
        }

        assertTrue(rows.primitiveBytes < before)
        assertEquals(-1, session.explanationState.learned.retiredMetadataHead)
        assertEquals(17, session.activeLearnedClauseCount)
        assertEquals(generation, session.learnedRetirementGeneration)
        assertEquals(reason, assertNotNull(session.boolReasonClause(1)).toList())
        val observed = assertNotNull(session.sharingMemory(maxWork = 1_000_000L).completeValues)
        assertEquals(192L, observed.getValue("retainedPeerImports"))
        session.pinBool(4, false)
        session.popLast()
        assertEquals(reason, assertNotNull(session.boolReasonClause(1)).toList())
    }
}
