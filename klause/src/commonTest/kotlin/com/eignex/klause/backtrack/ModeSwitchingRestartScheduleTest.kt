package com.eignex.klause.backtrack

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Stable/focused mode-switching schedule: the search starts in the focused mode (its sub-schedule
 * governs restarts), switches to the stable dive mode once the focused segment's conflict budget is
 * spent, and switches back after the stable segment. A stub focused schedule that always asks to
 * restart makes the active mode observable through [ModeSwitchingRestartSchedule.shouldRestart].
 */
class ModeSwitchingRestartScheduleTest {

    /** A focused stub that always wants to restart, so the active mode is visible via shouldRestart. */
    private object AlwaysRestart : RestartSchedule {
        override fun shouldRestart(decisionsThisRun: Long): Boolean = true
    }

    // switchBase 3, so segment budgets follow lubyN(seg)*3: 3 (focused), 3 (stable), 6 (focused), ...
    private fun schedule() = ModeSwitchingRestartSchedule(
        stages = listOf(
            RestartStage(AlwaysRestart, PhaseMode.FOCUSED),
            RestartStage(NoRestartSchedule, PhaseMode.STABLE),
        ),
        switchBase = 3,
    )

    private fun RestartSchedule.recordConflicts(n: Int) = repeat(n) { recordConflict(lbd = 2, trailSize = 10) }

    @Test
    fun `starts in the focused mode`() {
        val s = schedule()
        s.recordConflicts(1)
        assertTrue(s.shouldRestart(0), "the focused mode governs restarts from the first conflict")
    }

    @Test
    fun `switches to the stable dive mode after the focused budget`() {
        val s = schedule()
        s.recordConflicts(2)
        assertTrue(s.shouldRestart(0), "still focused within the first segment")
        s.recordConflicts(1)
        assertFalse(s.shouldRestart(0), "the stable dive mode suppresses restarts after the focused budget")
    }

    @Test
    fun `returns to the focused mode after the stable segment`() {
        val s = schedule()
        s.recordConflicts(6)
        assertTrue(s.shouldRestart(0), "restarts resume once the stable segment is spent")
    }

    @Test
    fun `cycles through more than two stages and wraps to the first`() {
        // Distinguish stages by their restart behaviour: only stage 1 asks to restart.
        val s = ModeSwitchingRestartSchedule(
            stages = listOf(
                RestartStage(NoRestartSchedule, PhaseMode.STABLE),
                RestartStage(AlwaysRestart, PhaseMode.FOCUSED),
                RestartStage(NoRestartSchedule, PhaseMode.STABLE),
            ),
            switchBase = 2,
        )
        // Segment budgets follow lubyN(seg)*2: 2, 2, 4, ...
        s.recordConflicts(2)
        assertTrue(s.shouldRestart(0), "advanced to the restart-heavy second stage")
        s.recordConflicts(2)
        assertFalse(s.shouldRestart(0), "advanced to the third, no-restart stage")
        s.recordConflicts(4)
        assertFalse(s.shouldRestart(0), "wrapped back to the first, no-restart stage")
    }

    @Test
    fun `phase mode tracks the active segment`() {
        val s = schedule()
        assertEquals(PhaseMode.FOCUSED, s.phaseMode(), "the focused segment runs the focused phase")
        s.recordConflicts(3)
        assertEquals(PhaseMode.STABLE, s.phaseMode(), "the stable dive segment runs the stable phase")
        s.recordConflicts(3)
        assertEquals(PhaseMode.FOCUSED, s.phaseMode(), "focused again after the stable segment")
    }
}
