package com.eignex.klause.backtrack

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmaRestartTest {

    private fun detector(warmup: Int = 8) =
        EmaRestart(fastAlpha = 0.5, slowAlpha = 1.0 / 1024, restartMargin = 0.8, blockingFactor = 1.4, warmup = warmup)

    @Test
    fun `ema should restart when the recent LBD average runs hotter than the long-run average`() {
        val ema = detector()
        repeat(30) { assertFalse(ema.recordConflict(lbd = 2, trailSize = 10)) }
        var fired = false
        repeat(10) { if (ema.recordConflict(lbd = 40, trailSize = 10)) fired = true }
        assertTrue(fired)
    }

    @Test
    fun `trail-size blocking should suppress a restart that LBD would trigger`() {
        fun warmed(): EmaRestart = detector().also { repeat(30) { _ -> it.recordConflict(lbd = 2, trailSize = 10) } }
        assertTrue(warmed().recordConflict(lbd = 40, trailSize = 10))
        assertFalse(warmed().recordConflict(lbd = 40, trailSize = 100))
    }
}
