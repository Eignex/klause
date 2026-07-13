package com.eignex.klause.backtrack

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Feasibility-first gate: [DiveUntilFeasibleRestartSchedule] suppresses all restarts until the first
 * solution, then delegates to its inner schedule. A stub inner that always wants to restart makes the
 * hand-over observable.
 */
class DiveUntilFeasibleRestartScheduleTest {

    /** An inner that always asks to restart and reports a FOCUSED regime, so the gate is observable. */
    private object AlwaysRestartFocused : RestartSchedule {
        override fun shouldRestart(decisionsThisRun: Long): Boolean = true
        override fun phaseMode(): PhaseMode = PhaseMode.FOCUSED
    }

    @Test
    fun `dives without restarting until the first solution`() {
        val s = DiveUntilFeasibleRestartSchedule(AlwaysRestartFocused)
        s.beginRun()
        s.recordConflict(lbd = 3, trailSize = 40)
        assertFalse(s.shouldRestart(1_000_000L), "no restart before a solution, even when the inner asks")
        assertEquals(PhaseMode.STABLE, s.phaseMode(), "dives on the stable/target phase while pre-solution")
    }

    @Test
    fun `delegates to the inner schedule once a solution is found`() {
        val s = DiveUntilFeasibleRestartSchedule(AlwaysRestartFocused)
        s.onSolution()
        assertTrue(s.shouldRestart(0), "the inner schedule governs restarts after the first solution")
        assertEquals(PhaseMode.FOCUSED, s.phaseMode(), "the inner phase regime takes over after the first solution")
    }
}
