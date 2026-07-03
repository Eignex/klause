package com.eignex.klause.backtrack

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RestartControllerTest {

    @Test
    fun `luby restart budget follows the Luby sequence`() {
        // Base 1 so the per-run budget equals lubyN(run): 1,1,2,1,1,2,4,...
        val restart = RestartController(BacktrackParams(lubyRestartBase = 1L))
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
        val restart = RestartController(BacktrackParams(lubyRestartBase = 10L))
        restart.beginRun()
        assertFalse(restart.shouldRestart(9))
        assertTrue(restart.shouldRestart(10))
    }

    @Test
    fun `onRestart returns the completed run's index`() {
        val restart = RestartController(BacktrackParams(lubyRestartBase = 1L))
        assertEquals(1L, restart.onRestart())
        assertEquals(2L, restart.onRestart())
        assertEquals(3L, restart.onRestart())
    }

    @Test
    fun `adaptive restart leaves the per-run budget unbounded`() {
        val restart = RestartController(BacktrackParams(adaptiveRestart = true))
        restart.beginRun()
        assertFalse(restart.shouldRestart(Long.MAX_VALUE - 1), "adaptive restart must not fire on the Luby budget")
    }

    @Test
    fun `without a schedule a run is unbounded`() {
        val restart = RestartController(BacktrackParams())
        restart.beginRun()
        assertFalse(restart.shouldRestart(1_000_000L))
    }
}
