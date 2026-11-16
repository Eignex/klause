package com.eignex.klause.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlin.time.TestTimeSource

class CancellationTest {
    @Test
    fun `an infinite monotonic deadline should remain open`() {
            val stop = Cancellation.after(Duration.INFINITE)

            assertFalse(stop())
        }

    @Test
    fun `an expired monotonic deadline should request a stop`() {
            val stop = Cancellation.until( TimeSource.Monotonic.markNow()
            - 1.seconds)

            assertTrue(stop())
        }

    @Test
    fun `predicate composition should preserve ordered short circuiting`() {
        val calls = ArrayList<Int>()
        val stop = Cancellation {
                    calls += 1
                    false
                } or
                Cancellation{
            calls += 2
            true
        } or Cancellation {
            calls += 3
            false
        }

        assertTrue(stop())

        assertEquals(listOf(1, 2), calls)
    }

    @Test
    fun `a deadline should observe its supplied time source`() {
            val clock = TestTimeSource()
            val stop = Cancellation.until( clock.markNow() + 2.seconds)
            assertFalse(stop())
            clock += 2.seconds
            assertTrue(stop())
    }
}
