package com.eignex.klause.backtrack

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * EMA-based adaptive restart (Biere-Fröhlich): [EmaRestart] restarts when the fast LBD average runs
 * hotter than the slow one, unless trail-size blocking defers it. A fast fast-alpha and slow slow-alpha
 * make the trigger deterministic within a few conflicts.
 */
class EmaRestartTest {

    private fun detector(warmup: Int = 8) =
        EmaRestart(fastAlpha = 0.5, slowAlpha = 1.0 / 1024, restartMargin = 0.8, blockingFactor = 1.4, warmup = warmup)

    @Test
    fun `restarts when the recent LBD average runs hotter than the long-run average`() {
        val ema = detector()
        // Warm up a low long-run average with good (low-LBD) clauses on a small, steady trail.
        repeat(30) { assertFalse(ema.recordConflict(lbd = 2, trailSize = 10), "steady low LBD must not restart") }
        // The solver starts learning poor (high-LBD) clauses: the fast average heats up above the slow
        // one and a restart fires.
        var fired = false
        repeat(10) { if (ema.recordConflict(lbd = 40, trailSize = 10)) fired = true }
        assertTrue(fired, "sustained high recent LBD must force a restart")
    }

    @Test
    fun `trail-size blocking suppresses a restart that LBD would otherwise trigger`() {
        fun warmed(): EmaRestart = detector().also { repeat(30) { _ -> it.recordConflict(lbd = 2, trailSize = 10) } }
        // A hot-LBD conflict with a normal trail: the fast average outruns the slow one, so restart.
        assertTrue(warmed().recordConflict(lbd = 40, trailSize = 10), "hot LBD should restart")
        // The same hot-LBD conflict but with a trail spike well above the average: the solver is
        // driving deep toward a model, so blocking defers the restart.
        assertFalse(warmed().recordConflict(lbd = 40, trailSize = 100), "trail spike must block")
    }
}
