package com.eignex.klause.backtrack

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RootLpDutyCycleTest {
    @Test
    fun `allows the first root run`() {
        assertTrue(RootLpDutyCycle().allows(0L))
    }

    @Test
    fun `skips a repeated root until its cost is repaid`() {
        val cycle = RootLpDutyCycle()
        cycle.record(0L, 10L)
        assertFalse(cycle.allows(19L))
        assertTrue(cycle.allows(20L))
    }

    @Test
    fun `accumulates work across skipped root attempts`() {
        val cycle = RootLpDutyCycle()
        cycle.record(10L, 20L)
        assertFalse(cycle.allows(25L))
        assertTrue(cycle.allows(30L))
    }

    @Test
    fun `a zero cost root does not block the next attempt`() {
        val cycle = RootLpDutyCycle()
        cycle.record(5L, 5L)
        assertTrue(cycle.allows(5L))
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
