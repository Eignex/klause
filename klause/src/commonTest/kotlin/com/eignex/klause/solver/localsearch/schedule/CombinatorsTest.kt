package com.eignex.klause.solver.localsearch.schedule

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Sequencing/looping of sub-schedules. All deterministic — no RNG. */
class CombinatorsTest {

    private fun geo(initial: Double) = Geometric(
        initial,
        coolingRate = 0.5,
        minTemperature = 1.0,
        maxTemperature = initial,
    )

    @Test
    fun `sequence runs the first leg then switches to the second at its start temperature`() {
        val s = SequenceSchedule(listOf(Segment(geo(100.0), 2), Segment(geo(10.0), 3)))
        assertEquals(100.0, s.temperature, 1e-9)
        s.step()
        assertEquals(50.0, s.temperature, 1e-9)
        s.step()
        // Budget of leg 0 spent: switch to leg 1, reset to its own start temperature.
        assertEquals(10.0, s.temperature, 1e-9)
        s.step()
        assertEquals(5.0, s.temperature, 1e-9)
    }

    @Test
    fun `sequence holds and keeps cooling the final leg past its budget`() {
        val s = SequenceSchedule(listOf(Segment(geo(8.0), 1)))
        s.step()
        assertEquals(4.0, s.temperature, 1e-9)
        // Past the leg budget, with no loop, the last leg simply keeps stepping.
        s.step()
        assertEquals(2.0, s.temperature, 1e-9)
        s.step()
        assertEquals(1.0, s.temperature, 1e-9)
    }

    @Test
    fun `loop wraps back to the first leg and resets it`() {
        val s = LoopSchedule(listOf(Segment(geo(100.0), 2), Segment(geo(10.0), 2)))
        s.step()
        s.step()
        // Through leg 0, now on leg 1 at its start.
        assertEquals(10.0, s.temperature, 1e-9)
        s.step()
        s.step()
        // Through leg 1, wrapped back to leg 0 reset to its start.
        assertEquals(100.0, s.temperature, 1e-9)
        s.step()
        assertEquals(50.0, s.temperature, 1e-9)
    }

    @Test
    fun `sequence forwards observe to the active adaptive leg`() {
        val adaptive = AdaptiveCooling(initialRate = 0.95, minRate = 0.5, maxRate = 0.999, adjustStep = 0.2)
        val s = SequenceSchedule(listOf(Segment(adaptive, 5), Segment(geo(10.0), 5)))
        // acceptance 0.9, error +0.5 → rate *= 0.9 → 0.855.
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
        assertEquals(0.855, adaptive.coolingRate, 1e-9)
    }

    @Test
    fun `sequence forwards reheat to the active leg`() {
        val s = SequenceSchedule(listOf(Segment(geo(100.0), 10)))
        s.step()
        s.step()
        assertEquals(25.0, s.temperature, 1e-9)
        s.reheat(2.0)
        assertEquals(50.0, s.temperature, 1e-9)
    }

    @Test
    fun `reset returns to the first leg and restores every leg`() {
        val s = SequenceSchedule(listOf(Segment(geo(100.0), 2), Segment(geo(10.0), 3)))
        repeat(4) { s.step() }
        assertTrue(s.temperature < 100.0)
        s.reset()
        assertEquals(100.0, s.temperature, 1e-9)
        // And leg 1 is fresh again on the next pass.
        s.step()
        s.step()
        assertEquals(10.0, s.temperature, 1e-9)
    }

    @Test
    fun `empty segment list and non-positive steps are rejected`() {
        assertFailsWith<IllegalArgumentException> { SequenceSchedule(emptyList()) }
        assertFailsWith<IllegalArgumentException> { Segment(geo(1.0), 0) }
        assertFailsWith<IllegalArgumentException> { Segment(geo(1.0), -1) }
    }
}
