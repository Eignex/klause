package com.eignex.klause.backtrack.lp

import com.eignex.klause.solver.search.VarRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class LpDepthObservationJournalTest {
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
            journal.record(child, node, if (up) 3.0 else 1.0, null) { true }
            journal.record(child, node, Double.NaN, true) { true }
        }
        val other = LpDirectionalHistory.Pending(otherParent, false, 0.5, 1)
        for (node in 5L..257L) {
            journal.record(other, node, 1.0, null) { true }
        }

        val atBoundary = assertNotNull(journal.restore(target, 0, 257L) { true })
        journal.record(other, 258L, 1.0, null) { true }
        val expired = assertNotNull(journal.restore(target, 0, 258L) { true })

        assertEquals(2, atBoundary.down.count)
        assertEquals(2, atBoundary.down.outcomeCount)
        assertEquals(1, expired.down.count)
        assertEquals(1, expired.down.outcomeCount)
        assertFalse("DEPTH_RETENTION_JOURNAL_EVICTED_IN_AGE" in events)
    }
}
