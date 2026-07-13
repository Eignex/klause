package com.eignex.klause.backtrack

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Decision-level moving-average restart (CP-SAT DL): [DecisionLevelRestart] restarts when recent
 * conflict decision levels run above the long-run average, once the recent window has filled.
 */
class DecisionLevelRestartTest {

    @Test
    fun `restarts when recent decision levels run deeper than the long-run average`() {
        val dl = DecisionLevelRestart(window = 4, ratio = 1.0)
        // Steady shallow conflicts: recent and global averages match, so no restart.
        repeat(20) { assertFalse(dl.recordConflict(decisionLevel = 5), "steady depth must not restart") }
        // The search starts conflicting much deeper: the recent window outruns the long-run average.
        var fired = false
        repeat(5) { if (dl.recordConflict(decisionLevel = 50)) fired = true }
        assertTrue(fired, "sustained deep conflicts must force a restart")
    }

    @Test
    fun `does not restart before the recent window is full`() {
        val dl = DecisionLevelRestart(window = 8, ratio = 1.0)
        repeat(7) { assertFalse(dl.recordConflict(decisionLevel = 100), "must not restart on a cold window") }
    }

    @Test
    fun `clearing the window defers the next restart until it refills`() {
        val dl = DecisionLevelRestart(window = 4, ratio = 1.0)
        repeat(20) { dl.recordConflict(decisionLevel = 5) }
        assertTrue(dl.recordConflict(decisionLevel = 50), "warmed window with a deep conflict restarts")
        dl.clearWindow()
        repeat(3) { assertFalse(dl.recordConflict(decisionLevel = 50), "cleared window must refill first") }
    }
}
