package com.eignex.klause.propagation

import com.eignex.klause.util.ExecutionControl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class FinitePropagationWorkTest {
    @Test
    fun `a failed nested operation should restore the containing stop`() {
        val work = FinitePropagationWork()
        val parent = ExecutionControl { false }
        work.cancellation = parent

        assertFailsWith<IllegalStateException> {
            work.withCancellation(ExecutionControl { true }) { error("failed operation") }
        }

        assertSame(parent, work.cancellation)
        work.checkNow()
    }

    @Test
    fun `finite work should poll within sixty four visits`() {
        val work = FinitePropagationWork()
        var polls = 0
        work.cancellation = ExecutionControl { ++polls == 2 }
        work.checkNow()
        repeat(63) { work.check() }

        assertFailsWith<FinitePropagationInterrupted> { work.check() }

        assertEquals(2, polls)
    }
}
