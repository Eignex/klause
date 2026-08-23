package com.eignex.klause.backtrack

import com.eignex.klause.solver.search.SearchCancellationPoller
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

class DeadlinePollerTest {

    @Test
    fun `polls on the first call then backs off`() {
        val poller = SearchCancellationPoller(TestTimeSource())
        assertTrue(poller.due(), "first call should poll immediately")
        poller.rearm()
        assertFalse(poller.due(), "should not poll again on the very next call after backing off")
    }

    // Drive `nodes` loop iterations, elapsing `gapMs` of wall-clock per poll, and count the polls.
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
    fun `expensive poll gaps drive a tighter cadence than cheap ones`() {
        val cheap = pollsOver(nodes = 2000, gapMs = 0)
        val expensive = pollsOver(nodes = 2000, gapMs = 50)
        assertTrue(
            expensive > cheap,
            "gaps over the target should poll more often than cheap gaps (expensive=$expensive cheap=$cheap)",
        )
    }

    @Test
    fun `cheap gaps grow the interval to the ceiling`() {
        // With sub-target gaps the interval doubles until it saturates at the shared ceiling, so the
        // poll count over N nodes approaches N / ceiling — far below one poll per node.
        val polls = pollsOver(nodes = 256 * 4, gapMs = 0)
        assertTrue(polls < 20, "cheap gaps should keep polls sparse, got $polls")
    }

    @Test
    fun `expensive gaps poll close to every node`() {
        // Gaps over the target shrink the interval to 1, so nearly every node polls.
        val nodes = 100
        val polls = pollsOver(nodes = nodes, gapMs = 50)
        assertEquals(true, polls >= nodes / 2, "expensive gaps should poll at least every other node, got $polls")
    }
}
