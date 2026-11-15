package com.eignex.klause.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class StopPollingTest {
    @Test
    fun `nested polls share one worker countdown`() {
        var polls = 0
        val worker = ExecutionControl { polls++; false }.bindStopControl()
        val nested = worker.withAccounting(ExecutionControl.Never) or ExecutionControl.Never
        assertFalse(worker.pollStop())

        repeat(3) { assertFalse(nested.pollStop(256)) }
        assertEquals(1, polls)
        assertFalse(worker.pollStop(256))

        assertEquals(2, polls)
    }

    @Test
    fun `full checkpoints bypass the worker countdown`() {
        var stopped = false
        val worker = ExecutionControl { stopped }.bindStopControl()
        assertFalse(worker.pollStop())

        stopped = true

        assertTrue(worker())
    }

    @Test
    fun `unbound controls observe each requested poll`() {
        var polls = 0
        val control = ExecutionControl { ++polls == 2 }

        assertFalse(control.pollStop())
        assertTrue(control.pollStop())
    }

    @Test
    fun `dynamic adapters follow the current worker countdown`() {
        var polls = 0
        var current = ExecutionControl { polls++; false }.bindStopControl()
        val adapted = cancelledWhen({ current.deadline() }, pollingOf = { current.stopPolling }) { current() }

        assertFalse(adapted.pollStop())
        assertFalse(current.pollStop(256))
        assertEquals(1, polls)

        current = ExecutionControl { polls++; false }.bindStopControl()
        assertFalse(adapted.pollStop())
        assertEquals(2, polls)
    }

    @Test
    fun `workers created from the same control have independent countdowns`() {
        var polls = 0
        val control = ExecutionControl { polls++; false }
        val first = control.bindStopControl()
        val second = first.bindStopControl()

        assertFalse(first.pollStop())
        assertFalse(second.pollStop())

        assertNotSame(first.stopPolling, second.stopPolling)
        assertEquals(2, polls)
    }
}
