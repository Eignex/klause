package com.eignex.klause.theory.qflra

import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PendingSourceRowsTest {
    @Test
    fun `a one shot stop cannot produce a prepared frontier`() {
        var observed = false
        val stop = Cancellation {
            if (observed) false else {
                observed = true
                true
            }
        }

        assertNull(PendingSourceRows.prepare(3, stop))

        assertTrue(observed)
    }

    @Test
    fun `a declined assertion cannot consume its pending source row`() {
        val frontier = assertNotNull(PendingSourceRows.prepare(3, Cancellation.Never))

        assertFalse(frontier.visit(Cancellation.Never) { false })
        val remaining = ArrayList<Int>()
        assertTrue(frontier.visit(Cancellation.Never) { remaining.add(it); true })

        assertEquals(listOf(0, 1, 2), remaining)
    }

    @Test
    fun `pending rows retain source order across packed word boundaries`() {
        val frontier = assertNotNull(PendingSourceRows.prepare(131, Cancellation.Never))
        val retained = listOf(7, 63, 65, 129)
        for (index in 0 until 131) if (index !in retained) frontier.asserted(index)
        val visited = ArrayList<Int>()

        assertTrue(frontier.visit(Cancellation.Never) { visited.add(it); true })

        assertEquals(retained, visited)
    }

    @Test
    fun `restored assertions precede later pending source rows`() {
        val frontier = assertNotNull(PendingSourceRows.prepare(4, Cancellation.Never))
        frontier.asserted(1)
        frontier.asserted(2)
        frontier.restored(1)
        val visited = ArrayList<Int>()

        assertTrue(frontier.visit(Cancellation.Never) { visited.add(it); true })

        assertEquals(listOf(0, 1, 3), visited)
    }

    @Test
    fun `visiting an unresolved row cannot remove it from the frontier`() {
        val frontier = assertNotNull(PendingSourceRows.prepare(3, Cancellation.Never))
        repeat(2) {
            val visited = ArrayList<Int>()

            assertTrue(frontier.visit(Cancellation.Never) { visited.add(it); true })

            assertEquals(listOf(0, 1, 2), visited)
        }
    }

    @Test
    fun `interrupted visits retain every uncommitted source row`() {
        val frontier = assertNotNull(PendingSourceRows.prepare(3, Cancellation.Never))
        var stopped = false
        val stop = Cancellation { stopped }

        assertFalse(frontier.visit(stop) {
            frontier.asserted(it)
            stopped = true
            true
        })
        val remaining = ArrayList<Int>()
        assertTrue(frontier.visit(Cancellation.Never) { remaining.add(it); true })

        assertEquals(listOf(1, 2), remaining)
    }

    @Test
    fun `stopped preparation withholds the frontier without refunding work`() {
        var charged = 0L
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean = charged > 0L
            override fun workMeter(): WorkMeter = WorkMeter { charged += it }
        }

        assertNull(PendingSourceRows.prepare(131, stop))

        assertTrue(charged > 0L)
    }

    @Test
    fun `oversized catalogues decline optional frontier allocation`() {
        assertNull(PendingSourceRows.prepare(524289, Cancellation.Never))
    }
}
