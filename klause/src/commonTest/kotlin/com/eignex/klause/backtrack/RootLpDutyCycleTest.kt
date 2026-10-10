package com.eignex.klause.backtrack

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RootLpDutyCycleTest {
    @Test
    fun `skips a repeated root until its cost is repaid`() {
        val cycle = RootLpDutyCycle()
        cycle.record(0L, 10L)
        assertFalse(cycle.allows(19L))
        assertTrue(cycle.allows(20L))
    }

    @Test
    fun `a partial root run charges the work it spent`() {
        val cycle = RootLpDutyCycle()
        cycle.record(100L, 103L)
        assertFalse(cycle.allows(105L))
        assertTrue(cycle.allows(106L))
    }

    @Test
    fun `saturated root work does not permit repeated attempts`() {
        val cycle = RootLpDutyCycle()
        cycle.record(Long.MAX_VALUE, Long.MAX_VALUE)
        assertFalse(cycle.allows(Long.MAX_VALUE))
    }
}
