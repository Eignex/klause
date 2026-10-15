package com.eignex.klause.localsearch.schedule

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CombinatorsTest {

    private fun geo(initial: Double) = Geometric(
        initial,
        coolingRate = 0.5,
        minTemperature = 1.0,
        maxTemperature = initial,
    )

    @Test
    fun `sequence should run the first leg then switch to the second at its start temperature`() {
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
    fun `loop should wrap back to the first leg and reset it`() {
        val s = LoopSchedule(listOf(Segment(geo(100.0), 2), Segment(geo(10.0), 2)))
        s.step()
        s.step()
        // Leg 0 spent: leg 1 at its start.
        assertEquals(10.0, s.temperature, 1e-9)
        s.step()
        s.step()
        // Through leg 1, wrapped back to leg 0 reset to its start.
        assertEquals(100.0, s.temperature, 1e-9)
        s.step()
        assertEquals(50.0, s.temperature, 1e-9)
    }

    @Test
    fun `reset should return to the first leg and restore every leg`() {
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
    fun `empty segment lists and non-positive steps should be rejected`() {
        assertFailsWith<IllegalArgumentException> { SequenceSchedule(emptyList()) }
        assertFailsWith<IllegalArgumentException> { Segment(geo(1.0), 0) }
        assertFailsWith<IllegalArgumentException> { Segment(geo(1.0), -1) }
    }
}
