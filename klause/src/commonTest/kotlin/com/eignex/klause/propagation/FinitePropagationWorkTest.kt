package com.eignex.klause.propagation

import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FinitePropagationWorkTest {
    @Test
    fun `finite work should poll within sixty four visits`() {
        val work = FinitePropagationWork()
        var polls = 0
        work.cancellation = Cancellation { ++polls == 2 }
        work.checkNow()
        repeat(63) { work.check() }

        assertFailsWith<FinitePropagationInterrupted> { work.check() }

        assertEquals(2, polls)
    }
}
