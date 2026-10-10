package com.eignex.klause.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class CancellationTest {
    @Test
    fun `both polling surfaces observe rearmed adapter deadlines`() {
        for (direct in listOf(false, true)) {
            val clock = TestTimeSource()
            var deadline = clock.markNow() + 2.seconds
            val token = cancelledWhen({ deadline }) { deadline.hasPassedNow() }
            val read = if (direct) token::isCancelled else token::invoke
            assertFalse(read())

            deadline = clock.markNow() + 4.seconds
            clock += 3.seconds

            assertEquals(deadline, token.deadline())
            assertFalse(read())
            clock += 1.seconds
            assertTrue(read())
        }
    }

    @Test
    fun `both polling surfaces observe adapter external cancellation`() {
        for (direct in listOf(false, true)) {
            var external = false
            val token = cancelledWhen({ null }) { external }
            val read = if (direct) token::isCancelled else token::invoke
            assertFalse(read())

            external = true

            assertTrue(read())
        }
    }

    @Test
    fun `both polling surfaces observe external cancellation`() {
        for (direct in listOf(false, true)) {
            var stopped = false
            val token = (Cancellation { stopped } or Cancellation.Never) or Cancellation { false }
            val read = if (direct) token::isCancelled else token::invoke
            assertFalse(read())

            stopped = true

            assertTrue(read())
        }
    }

    @Test
    fun `nested or compositions preserve predicate order and short circuiting`() {
        val visited = ArrayList<Int>()
        var stopped = false
        val first = Cancellation { visited += 1; false }
        val second = Cancellation { visited += 2; stopped }
        val third = Cancellation { visited += 3; false }
        val fourth = Cancellation { visited += 4; false }
        val token = (first or second) or (third or fourth)

        assertFalse(token())
        assertEquals(listOf(1, 2, 3, 4), visited)
        visited.clear()
        stopped = true
        assertTrue(token())
        assertEquals(listOf(1, 2), visited)
    }

    @Test
    fun `nested or compositions retain a deadline snapshot while adapters remain live`() {
        val clock = TestTimeSource()
        val first = clock.markNow() + 2.seconds
        var deadline = first
        val dynamic = cancelledWhen({ deadline }) { deadline.hasPassedNow() }
        val token = (Cancellation.Never or dynamic) or Cancellation.until(clock.markNow() + 5.seconds)
        deadline = clock.markNow() + 4.seconds

        assertEquals(first, token.deadline())
        clock += 3.seconds
        assertFalse(token())
        clock += 1.seconds
        assertTrue(token())
    }

    @Test
    fun `nested or compositions charge the first work meter and observe its stop`() {
        var firstWork = 0L
        var secondWork = 0L
        val first = object : Cancellation {
            private val meter = WorkMeter { firstWork += it }
            override fun isCancelled(): Boolean = firstWork >= 7L
            override fun workMeter(): WorkMeter = meter
        }
        val second = object : Cancellation {
            private val meter = WorkMeter { secondWork += it }
            override fun isCancelled(): Boolean = secondWork >= 3L
            override fun workMeter(): WorkMeter = meter
        }
        val token = (Cancellation.Never or first) or (second or Cancellation.Never)

        assertSame(first.workMeter(), token.workMeter())
        token.charge(6L)
        assertFalse(token())
        token.charge(1L)
        assertTrue(token())
        assertEquals(7L, firstWork)
        assertEquals(0L, secondWork)
    }

    @Test
    fun `or composition leaves and predicate semantics intact`() {
        var external = false
        val token = ((Cancellation { external } and Cancellation { false }) or Cancellation.Never) or
            Cancellation { external }

        assertFalse(token())
        external = true
        assertTrue(token())
    }
}
