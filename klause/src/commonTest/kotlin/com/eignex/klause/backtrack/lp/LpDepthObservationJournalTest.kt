package com.eignex.klause.backtrack.lp

import com.eignex.klause.solver.search.VarRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LpDepthObservationJournalTest {
    @Test
    fun `depth replay keeps the latest matching observations across the ring boundary`() {
        val journal = LpDepthObservationJournal({}, {}, maxAge = 64L)
        val target = VarRef.IntVar(0)
        val unrelated = VarRef.IntVar(755)
        val depth = 0
        val sampleNodes = setOf(62L, 64L, 66L, 68L)
        for (node in 1L..70L) {
            val matches = node in sampleNodes
            val up = node == 64L || node == 68L
            val parent = LpDirectionalHistory.Parent(2.0, if (matches || node == 69L) target else unrelated, 0.5)
            val child = LpDirectionalHistory.Pending(parent, up, 0.5, if (node == 69L) depth + 2 else depth + 1)
            journal.record(child, node, node.toDouble(), null) { true }
            journal.record(child, node, Double.NaN, node <= 64L) { true }
        }

        val restored = assertNotNull(journal.restore(target, depth, 70L) { true })

        assertEquals(62L to 66L, restored.down.previousStamp to restored.down.stamp)
        assertEquals(64L to 68L, restored.up.previousStamp to restored.up.stamp)
        assertEquals(62L to 66L, restored.down.outcomePreviousStamp to restored.down.outcomeStamp)
        assertEquals(64L to 68L, restored.up.outcomePreviousStamp to restored.up.outcomeStamp)
    }

    @Test
    fun `full depth window keeps the oldest distinct in age observations`() {
        val events = ArrayList<String>()
        val journal = LpDepthObservationJournal({}, events::add, maxAge = 256L)
        val target = VarRef.IntVar(0)
        val targetParent = LpDirectionalHistory.Parent(2.0, target, 0.5)
        val otherParent = LpDirectionalHistory.Parent(2.0, VarRef.Bool(0), 0.5)
        for (node in 1L..4L) {
            val up = node % 2L == 0L
            val child = LpDirectionalHistory.Pending(targetParent, up, 0.5, 1)
            repeat(2) {
                journal.record(child, node, if (up) 3.0 else 1.0, null) { true }
                journal.record(child, node, Double.NaN, true) { true }
            }
            if (node < 4L) assertFalse(journal.mayHavePairs(target, 0))
        }
        assertTrue(journal.mayHavePairs(target, 0))
        val other = LpDirectionalHistory.Pending(otherParent, false, 0.5, 1)
        for (node in 5L..257L) {
            journal.record(other, node, 1.0, null) { true }
        }

        val atBoundary = assertNotNull(journal.restore(target, 0, 257L) { true })
        assertTrue(journal.mayHavePairs(target, 0))
        journal.record(other, 258L, 1.0, null) { true }
        val expired = assertNotNull(journal.restore(target, 0, 258L) { true })

        assertEquals(2, atBoundary.down.count)
        assertEquals(2, atBoundary.down.outcomeCount)
        assertEquals(1, expired.down.count)
        assertEquals(1, expired.down.outcomeCount)
        assertFalse("DEPTH_RETENTION_JOURNAL_EVICTED_IN_AGE" in events)
        assertFalse(journal.mayHavePairs(target, 0))
        journal.clear()
        assertFalse(journal.mayHavePairs(target, 0))
    }
}
