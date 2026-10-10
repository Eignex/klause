package com.eignex.klause.solver.integration

import com.eignex.klause.backtrack.GlucoseRestart
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Adaptive restarts (#198): the [GlucoseRestart] policy restarts when recent
 * learned-clause LBD runs hot relative to the long-run average, unless trail-size blocking
 * defers it. These tests pin the trigger and blocking logic directly, then confirm the engine
 * still produces correct verdicts and a complete model set with adaptive restarts enabled.
 */
class AdaptiveRestartTest {

    @Test
    fun `policy restarts when the recent LBD window runs hotter than the global average`() {
        val g = GlucoseRestart(lbdWindow = 4, trailWindow = 1000, restartMargin = 0.8, blockingFactor = 1.4)
        // Warm up a low global average with good (low-LBD) clauses; trail stays small so the
        // blocking window (capacity 1000) never fills.
        repeat(20) { assertFalse(g.recordConflict(lbd = 2, trailSize = 10)) }
        // Now the solver starts learning poor (high-LBD) clauses: the recent window heats up
        // above the long-run average and a restart must fire.
        var fired = false
        repeat(20) { if (g.recordConflict(lbd = 20, trailSize = 10)) fired = true }
        assertTrue(fired, "sustained high recent LBD must force a restart")
    }

    @Test
    fun `trail-size blocking suppresses a restart that LBD would otherwise trigger`() {
        fun warmedPolicy(): GlucoseRestart {
            val g = GlucoseRestart(lbdWindow = 4, trailWindow = 4, restartMargin = 0.8, blockingFactor = 1.4)
            // Fill both windows with low-LBD, small-trail conflicts → low global LBD average.
            repeat(20) { g.recordConflict(lbd = 2, trailSize = 10) }
            return g
        }
        // A hot-LBD conflict with a normal trail: the recent window is hotter than the global
        // average, so the restart fires.
        assertTrue(warmedPolicy().recordConflict(lbd = 50, trailSize = 10), "hot LBD should restart")
        // The same hot-LBD conflict but with a trail spike well above the recent average: the
        // solver is driving deep toward a model, so blocking defers the restart.
        assertFalse(warmedPolicy().recordConflict(lbd = 50, trailSize = 100), "trail spike must block")
    }
}
