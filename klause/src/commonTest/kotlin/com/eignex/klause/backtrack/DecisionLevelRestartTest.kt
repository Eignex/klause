package com.eignex.klause.backtrack

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DecisionLevelRestartTest {

    @Test
    fun `restart should fire when recent decision levels run deeper than the long-run average`() {
        val dl = DecisionLevelRestart(window = 4, ratio = 1.0)
        repeat(20) { assertFalse(dl.recordConflict(decisionLevel = 5)) }
        var fired = false
        repeat(5) { if (dl.recordConflict(decisionLevel = 50)) fired = true }
        assertTrue(fired)
    }

    @Test
    fun `restart should not fire before the recent window is full`() {
        val dl = DecisionLevelRestart(window = 8, ratio = 1.0)
        repeat(7) { assertFalse(dl.recordConflict(decisionLevel = 100)) }
    }

    @Test
    fun `clearing the window should defer the next restart until it refills`() {
        val dl = DecisionLevelRestart(window = 4, ratio = 1.0)
        repeat(20) { dl.recordConflict(decisionLevel = 5) }
        assertTrue(dl.recordConflict(decisionLevel = 50))
        dl.clearWindow()
        repeat(3) { assertFalse(dl.recordConflict(decisionLevel = 50)) }
    }
}
