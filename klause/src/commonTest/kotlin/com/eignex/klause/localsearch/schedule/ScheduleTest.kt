package com.eignex.klause.localsearch.schedule

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Temperature trajectories for the SA schedules. All deterministic — no RNG involved. */
class ScheduleTest {

    @Test
    fun `geometric cools by the rate each step and floors at the minimum`() {
        val s = Geometric(initialTemperature = 100.0, coolingRate = 0.5, minTemperature = 1.0, maxTemperature = 100.0)
        assertEquals(100.0, s.temperature, 1e-9)
        s.step()
        assertEquals(50.0, s.temperature, 1e-9)
        s.step()
        assertEquals(25.0, s.temperature, 1e-9)
        repeat(100) { s.step() }
        assertEquals(1.0, s.temperature, 1e-9)
    }

    @Test
    fun `reheat raises the temperature and is capped at the ceiling`() {
        val s = Geometric(initialTemperature = 100.0, coolingRate = 0.5, minTemperature = 1.0, maxTemperature = 100.0)
        s.step()
        s.step()
        assertEquals(25.0, s.temperature, 1e-9)
        s.reheat(2.0)
        assertEquals(50.0, s.temperature, 1e-9)
        // 50 * 4 = 200, clamped to the 100 ceiling.
        s.reheat(4.0)
        assertEquals(100.0, s.temperature, 1e-9)
    }

    @Test
    fun `adaptive cooling cools faster when acceptance is above target`() {
        val s = AdaptiveCooling(
            initialTemperature = 1.0,
            targetAcceptance = 0.4,
            initialRate = 0.95,
            minRate = 0.5,
            maxRate = 0.999,
            adjustStep = 0.2,
        )
        // acceptance 0.9, error +0.5 → rate *= (1 - 0.2*0.5) = 0.9 → 0.95 * 0.9 = 0.855.
        s.observe(
            RoundLog(
                proposed = 10,
                accepted = 9,
                costMean = 0.0,
                costVariance = 0.0,
                bestCost = 0.0,
                temperature = 1.0,
            ),
        )
        assertEquals(0.855, s.coolingRate, 1e-9)
    }

    @Test
    fun `adaptive cooling cools slower when acceptance is below target`() {
        val s = AdaptiveCooling(
            initialTemperature = 1.0,
            targetAcceptance = 0.4,
            initialRate = 0.95,
            minRate = 0.5,
            maxRate = 0.999,
            adjustStep = 0.2,
        )
        // acceptance 0.2, error -0.2 → rate *= (1 + 0.2*0.2) = 1.04 → 0.95 * 1.04 = 0.988.
        s.observe(
            RoundLog(
                proposed = 10,
                accepted = 2,
                costMean = 0.0,
                costVariance = 0.0,
                bestCost = 0.0,
                temperature = 1.0,
            ),
        )
        assertEquals(0.988, s.coolingRate, 1e-9)
    }

    @Test
    fun `adaptive cooling reset restores the start rate and temperature`() {
        val s = AdaptiveCooling(
            initialTemperature = 2.0,
            initialRate = 0.95,
            minRate = 0.5,
            maxRate = 0.999,
            adjustStep = 0.2,
        )
        s.observe(
            RoundLog(
                proposed = 10,
                accepted = 9,
                costMean = 0.0,
                costVariance = 0.0,
                bestCost = 0.0,
                temperature = 2.0,
            ),
        )
        s.step()
        assertTrue(s.coolingRate != 0.95)
        assertTrue(s.temperature < 2.0)
        s.reset()
        assertEquals(0.95, s.coolingRate, 1e-9)
        assertEquals(2.0, s.temperature, 1e-9)
    }

    @Test
    fun `reheating reset clears its counter and resets the base`() {
        val base = Geometric(
            initialTemperature = 100.0,
            coolingRate = 0.5,
            minTemperature = 1.0,
            maxTemperature = 100.0,
        )
        val s = Reheating(base, period = 3, reheatFactor = 2.0)
        s.step()
        s.reset()
        assertEquals(100.0, s.temperature, 1e-9)
        // Counter was cleared: the next reheat lands a full period later, not one step early.
        s.step()
        s.step()
        assertTrue(s.temperature < 100.0)
    }

    @Test
    fun `degenerate temperature bounds are rejected`() {
        assertFailsWith<IllegalArgumentException> { Geometric(initialTemperature = -1.0) }
        assertFailsWith<IllegalArgumentException> {
            Geometric(initialTemperature = 1.0, minTemperature = 2.0, maxTemperature = 1.0)
        }
        assertFailsWith<IllegalArgumentException> {
            Geometric(initialTemperature = 1.0, maxTemperature = 0.5)
        }
    }
}
