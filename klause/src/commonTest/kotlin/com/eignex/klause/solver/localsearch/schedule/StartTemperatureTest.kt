package com.eignex.klause.solver.localsearch.schedule

import kotlin.math.exp
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Warm-up start-temperature calibration (#699 item 4). Deterministic — no RNG. */
class StartTemperatureTest {

    @Test
    fun `calibrated temperature accepts an average worsening move at the target probability`() {
        val deltas = doubleArrayOf(2.0, 4.0, 6.0) // mean positive = 4.0
        val target = 0.8
        val t0 = StartTemperature.calibrate(deltas, targetAcceptance = target)
        // exp(-meanPositive / T0) must equal the target acceptance.
        assertEquals(target, exp(-4.0 / t0), 1e-9)
        // Closed form: T0 = -mean / ln(target).
        assertEquals(-4.0 / ln(target), t0, 1e-9)
    }

    @Test
    fun `only worsening deltas inform the calibration`() {
        // Improving (≤ 0) deltas are ignored; mean of the positive {2, 6} is 4.
        val deltas = doubleArrayOf(-10.0, 2.0, 0.0, 6.0, -3.0)
        val t0 = StartTemperature.calibrate(deltas, targetAcceptance = 0.5)
        assertEquals(-4.0 / ln(0.5), t0, 1e-9)
    }

    @Test
    fun `a hotter target yields a higher start temperature`() {
        val deltas = doubleArrayOf(5.0, 5.0)
        val cool = StartTemperature.calibrate(deltas, targetAcceptance = 0.3)
        val hot = StartTemperature.calibrate(deltas, targetAcceptance = 0.9)
        assertTrue(hot > cool, "target 0.9 should be hotter than 0.3, got hot=$hot cool=$cool")
    }

    @Test
    fun `larger move-cost swings give a proportionally hotter start`() {
        val small = StartTemperature.calibrate(doubleArrayOf(1.0, 1.0), targetAcceptance = 0.8)
        val large = StartTemperature.calibrate(doubleArrayOf(10.0, 10.0), targetAcceptance = 0.8)
        assertEquals(10.0, large / small, 1e-9)
    }

    @Test
    fun `warm start scales the temperature down to protect the seed`() {
        val deltas = doubleArrayOf(4.0, 4.0)
        val cold = StartTemperature.calibrate(deltas, targetAcceptance = 0.8, warmStart = false)
        val warm = StartTemperature.calibrate(deltas, targetAcceptance = 0.8, warmStart = true, warmStartFactor = 0.25)
        assertEquals(0.25 * cold, warm, 1e-9)
    }

    @Test
    fun `no worsening samples falls back to the minimum temperature`() {
        val deltas = doubleArrayOf(-1.0, 0.0, -5.0)
        assertEquals(0.01, StartTemperature.calibrate(deltas, minTemperature = 0.01), 1e-12)
        assertEquals(0.01, StartTemperature.calibrate(doubleArrayOf(), minTemperature = 0.01), 1e-12)
    }

    @Test
    fun `result never drops below the minimum temperature`() {
        // Tiny deltas with a near-1 target would give a microscopic T0; the floor wins.
        val t0 = StartTemperature.calibrate(doubleArrayOf(1e-6), targetAcceptance = 0.999, minTemperature = 0.5)
        assertEquals(0.5, t0, 1e-12)
    }

    @Test
    fun `degenerate parameters are rejected`() {
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
