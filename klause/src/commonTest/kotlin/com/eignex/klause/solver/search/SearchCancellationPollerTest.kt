package com.eignex.klause.solver.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

class SearchCancellationPollerTest {
    @Test
    fun `poller should poll on the first call then back off`() {
        val poller = SearchCancellationPoller(TestTimeSource())
        assertTrue(poller.due())
        poller.rearm()
        assertFalse(poller.due())
    }

    private fun pollsOver(nodes: Int, gapMs: Long): Int {
        val clock = TestTimeSource()
        val poller = SearchCancellationPoller(clock)
        var polls = 0
        repeat(nodes) {
            if (poller.due()) {
                polls++
                clock += gapMs.milliseconds
                poller.rearm()
            }
        }
        return polls
    }

    @Test
    fun `cheap gaps should grow the interval to the ceiling`() {
        val polls = pollsOver(nodes = 256 * 4, gapMs = 0)
        assertTrue(polls < 20, "cheap gaps should keep polls sparse, got $polls")
    }

    @Test
    fun `expensive gaps should poll close to every node`() {
        val nodes = 100
        val polls = pollsOver(nodes = nodes, gapMs = 50)
        assertEquals(true, polls >= nodes / 2, "expensive gaps should poll at least every other node, got $polls")
    }
}
