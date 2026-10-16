package com.eignex.klause.theory.qflra

import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class LinearOperationCancellationTest {
    @Test
    fun `unmetered numerical polls should observe a containing stop within one batch`() {
        var cancelled = false
        val token = LinearOperationCancellation({ Cancellation.Never }) { cancelled }
        token.begin()
        assertFalse(token())
        cancelled = true

        repeat(63) { assertFalse(token()) }

        assertTrue(token())
    }

    @Test
    fun `metered numerical polls should retain every charge and stop boundary`() {
        var charged = 0L
        val meter = WorkMeter { charged += it }
        val source = object : Cancellation {
            override fun isCancelled() = charged >= 3L
            override fun workMeter() = meter
        }
        val token = LinearOperationCancellation({ source }) { false }
        token.begin()

        repeat(2) {
            token.charge(1L)
            assertFalse(token())
        }
        token.charge(1L)

        assertTrue(token())
        assertEquals(3L, charged)
    }

    @Test
    fun `an operation stop should remain latched until the next operation`() {
        var expired = true
        val token = LinearOperationCancellation({ Cancellation { expired } }) { false }
        token.begin()
        assertTrue(token())
        expired = false
        assertTrue(token())

        token.end()
        token.begin()

        assertFalse(token())
    }

    @Test
    fun `a private deadline should release without renewing the containing deadline`() {
        val clock = TestTimeSource()
        val containing = Cancellation.until(clock.markNow() + 4.seconds)
        var operation = containing or Cancellation.until(clock.markNow() + 1.seconds)
        val token = LinearOperationCancellation({ operation }) { containing() }
        token.begin()
        clock += 2.seconds
        assertTrue(token())
        assertFalse(containing())
        token.end()
        operation = containing or Cancellation.until(clock.markNow() + 8.seconds)
        token.begin()
        assertEquals(containing.deadline(), token.deadline())
        assertFalse(token())
        clock += 3.seconds
        token.end()

        assertTrue(token())
    }

    @Test
    fun `operation exit should poll a stop before the next numerical batch`() {
        var cancelled = false
        val token = LinearOperationCancellation({ Cancellation { cancelled } }) { false }
        token.begin()
        assertFalse(token())
        cancelled = true

        token.end()

        assertTrue(token())
    }
}
