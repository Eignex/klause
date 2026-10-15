package com.eignex.klause.backtrack

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GlucoseRestartTest {

    @Test
    fun `Glucose restart should fire when recent LBD exceeds the global average`() {
        val g = GlucoseRestart(lbdWindow = 4, trailWindow = 1000, restartMargin = 0.8, blockingFactor = 1.4)
        repeat(20) { assertFalse(g.recordConflict(lbd = 2, trailSize = 10)) }
        var fired = false
        repeat(20) { if (g.recordConflict(lbd = 20, trailSize = 10)) fired = true }
        assertTrue(fired)
    }

    @Test
    fun `Glucose restart should be blocked by a trail size spike`() {
        fun warmedPolicy(): GlucoseRestart {
            val g = GlucoseRestart(lbdWindow = 4, trailWindow = 4, restartMargin = 0.8, blockingFactor = 1.4)
            repeat(20) { g.recordConflict(lbd = 2, trailSize = 10) }
            return g
        }
        assertTrue(warmedPolicy().recordConflict(lbd = 50, trailSize = 10))
        assertFalse(warmedPolicy().recordConflict(lbd = 50, trailSize = 100))
    }
}
