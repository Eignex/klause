package com.eignex.klause.backtrack

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RestartScheduleTest {

    @Test
    fun `luby restart budget follows the Luby sequence`() {
        // Base 1 so the per-run budget equals lubyN(run): 1,1,2,1,1,2,4,...
        val restart = RestartSchedule.from(BacktrackParams(lubyRestartBase = 1L))
        val expected = longArrayOf(1, 1, 2, 1, 1, 2, 4, 1, 1, 2, 1, 1, 2, 4, 8)
        for (budget in expected) {
            restart.beginRun()
            assertFalse(restart.shouldRestart(budget - 1), "should not restart below the run budget $budget")
            assertTrue(restart.shouldRestart(budget), "should restart once the run budget $budget is reached")
            restart.onRestart()
        }
    }

    @Test
    fun `luby budget scales with the restart base`() {
        val restart = RestartSchedule.from(BacktrackParams(lubyRestartBase = 10L))
        restart.beginRun()
        assertFalse(restart.shouldRestart(9))
        assertTrue(restart.shouldRestart(10))
    }

    @Test
    fun `adaptive restart leaves the per-run budget unbounded`() {
        val restart = RestartSchedule.from(BacktrackParams(adaptiveRestart = true))
        restart.beginRun()
        assertFalse(restart.shouldRestart(Long.MAX_VALUE - 1), "adaptive restart must not fire on the Luby budget")
    }

    @Test
    fun `without a schedule a run is unbounded`() {
        val restart = RestartSchedule.from(BacktrackParams())
        restart.beginRun()
        assertFalse(restart.shouldRestart(1_000_000L))
    }

    @Test
    fun `from selects the schedule configured on the params`() {
        assertIs<AdaptiveRestartSchedule>(RestartSchedule.from(BacktrackParams(adaptiveRestart = true)))
        assertIs<LubyRestartSchedule>(RestartSchedule.from(BacktrackParams(lubyRestartBase = 256L)))
        assertIs<NoRestartSchedule>(RestartSchedule.from(BacktrackParams()))
    }

    @Test
    fun `adaptive wins when both adaptive and a luby base are set`() {
        assertIs<AdaptiveRestartSchedule>(
            RestartSchedule.from(BacktrackParams(adaptiveRestart = true, lubyRestartBase = 256L)),
        )
    }
}
