package com.eignex.klause.localsearch.schedule

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StallScheduleTest {
    @Test
    fun `weight bumps should restart their own window while plateau noise stays hot`() {
        val stall = StallSchedule(2, 0.0, 1.0)
        val weights = WeightSchedule(bumpAfter = 2)
        val values = doubleArrayOf(0.0)
        val base = doubleArrayOf(0.0)
        val violated = intArrayOf(0)
        val rng = Random(0)
        for (step in 0L..2L) {
            stall.update(step, 10L)
            weights.maintain(step, 10L, values, base, violated, rng)
        }

        stall.update(3L, 10L)
        weights.maintain(3L, 10L, values, base, violated, rng)

        assertTrue(stall.stalled)
        assertEquals(1.0, stall.level)
        assertEquals(1.0, values[0])
    }

    @Test
    fun `same-step cost improvements should not cool plateau noise`() {
        val stall = StallSchedule(1, 0.0, 1.0)
        stall.update(0L, 10L)
        stall.update(1L, 10L)

        stall.update(1L, 9L)

        assertTrue(stall.stalled)
    }

    @Test
    fun `a restart should cool plateau noise before the next stall window`() {
        val stall = StallSchedule(2, 0.0, 1.0)
        stall.update(10L, 1L)
        stall.update(12L, 1L)

        stall.update(0L, 10L)
        stall.update(1L, 10L)

        assertFalse(stall.stalled)
        assertEquals(0.0, stall.level)
    }
}
