package com.eignex.klause.localsearch.schedule

import kotlin.math.exp
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StartTemperatureTest {

    @Test
    fun `calibrated temperature should accept an average worsening move at the target probability`() {
        val deltas = doubleArrayOf(2.0, 4.0, 6.0) // mean positive = 4.0
        val target = 0.8
        val t0 = StartTemperature.calibrate(deltas, targetAcceptance = target)
        // exp(-meanPositive / T0) must equal the target acceptance.
        assertEquals(target, exp(-4.0 / t0), 1e-9)
        // Closed form: T0 = -mean / ln(target).
        assertEquals(-4.0 / ln(target), t0, 1e-9)
    }

    @Test
    fun `calibration without worsening samples should fall back to the minimum temperature`() {
        val deltas = doubleArrayOf(-1.0, 0.0, -5.0)
        assertEquals(0.01, StartTemperature.calibrate(deltas, minTemperature = 0.01), 1e-12)
        assertEquals(0.01, StartTemperature.calibrate(doubleArrayOf(), minTemperature = 0.01), 1e-12)
    }

    @Test
    fun `degenerate parameters should be rejected`() {
        assertFailsWith<IllegalArgumentException> {
            StartTemperature.calibrate(
                doubleArrayOf(1.0),
                targetAcceptance = 1.0,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            StartTemperature.calibrate(
                doubleArrayOf(1.0),
                targetAcceptance = 0.0,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            StartTemperature.calibrate(doubleArrayOf(1.0), warmStart = true, warmStartFactor = 1.5)
        }
    }
}
