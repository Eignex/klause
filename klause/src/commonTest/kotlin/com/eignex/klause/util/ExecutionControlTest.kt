package com.eignex.klause.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlin.time.TimeSource

class ExecutionControlTest {
    @Test
    fun `adding a metered stop should retain an unbound accounting owner`() {
        var work = 0L
        val owner = ExecutionControl(Cancellation { work >= 7L }, WorkMeter { work += it })
        val combined = ExecutionControl.Never or owner

        combined.charge(7L)

        assertNull(combined.accounting)
        assertEquals(0L, work)
        owner.charge(7L)
        assertTrue(combined())
    }

    @Test
    fun `adding stop conditions should not adopt a later accounting sink`() {
        var work = 0L
        var meter: WorkMeter? = null
        val owner = object : ExecutionControl() {
            override val accounting get() = meter
        }
        val combined = ExecutionControl.Never or owner
        meter = WorkMeter { work += it }

        combined.charge(7L)

        assertNull(combined.accounting)
        assertEquals(0L, work)
    }

    @Test
    fun `copying an owner should retain its adapted accounting sink`() {
        var originalWork = 0L
        var adaptedWork = 0L
        val original = ExecutionControl(Cancellation.Never, WorkMeter { originalWork += it })
        val adapted = object : ExecutionControl(original) {
            override val accounting = WorkMeter { adaptedWork += it }
        }
        val copy = ExecutionControl(adapted)

        copy.charge(7L)

        assertEquals(0L, originalWork)
        assertEquals(7L, adaptedWork)
    }

    @Test
    fun `recording work should never evaluate the stop predicate`() {
        var polls = 0
        var charged = 0L
        val controls = ExecutionControl(
            Cancellation {
                polls++
                false
            },
            WorkMeter { charged += it },
        )

        repeat(256) { controls.charge(1) }

        assertEquals(256L, charged)
        assertEquals(0, polls)
    }

    @Test
    fun `adjacent monotonic deadlines should stop before following predicates`() {
        for (reverse in listOf(false, true)) {
            val now = TimeSource.Monotonic.markNow()
            val future = ExecutionControl.until(now + 10.minutes)
            val expired = ExecutionControl.until(now - 10.minutes)
            val clocks = if (reverse) expired or future else future or expired
            val visited = ArrayList<Int>()
            val token = (
                ExecutionControl {
                    visited += 1
                    false
                } or clocks
                ) or ExecutionControl {
                visited += 2
                false
            }

            assertTrue(token())

            assertEquals(listOf(1), visited)
        }
    }

    @Test
    fun `adjacent monotonic deadlines should retain the earliest deadline snapshot`() {
        for (reverse in listOf(false, true)) {
            val now = TimeSource.Monotonic.markNow()
            val early = now + 1.minutes
            val late = now + 2.minutes
            val first = ExecutionControl.until(if (reverse) late else early)
            val second = ExecutionControl.until(if (reverse) early else late)

            val token = first or second

            assertEquals(early, token.deadline())
        }
    }

    @Test
    fun `predicates between monotonic deadlines should retain their evaluation order`() {
        val now = TimeSource.Monotonic.markNow()
        val visited = ArrayList<Int>()
        val token = (
            (
                ExecutionControl.until(now + 10.minutes) or ExecutionControl {
                    visited += 1
                    false
                }
                ) or
                ExecutionControl.until(now - 10.minutes)
            ) or ExecutionControl {
            visited += 2
            false
        }

        assertTrue(token())

        assertEquals(listOf(1), visited)
    }

    @Test
    fun `both polling surfaces should observe rearmed adapter deadlines`() {
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
    fun `both polling surfaces should observe adapter external cancellation`() {
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
    fun `both polling surfaces should observe external cancellation`() {
        for (direct in listOf(false, true)) {
            var stopped = false
            val token = (ExecutionControl { stopped } or ExecutionControl.Never) or ExecutionControl { false }
            val read = if (direct) token::isCancelled else token::invoke
            assertFalse(read())

            stopped = true

            assertTrue(read())
        }
    }

    @Test
    fun `nested or compositions should preserve predicate order and short circuiting`() {
        val visited = ArrayList<Int>()
        var stopped = false
        val first = ExecutionControl {
            visited += 1
            false
        }
        val second = ExecutionControl {
            visited += 2
            stopped
        }
        val third = ExecutionControl {
            visited += 3
            false
        }
        val fourth = ExecutionControl {
            visited += 4
            false
        }
        val token = (first or second) or (third or fourth)

        assertFalse(token())
        assertEquals(listOf(1, 2, 3, 4), visited)
        visited.clear()
        stopped = true
        assertTrue(token())
        assertEquals(listOf(1, 2), visited)
    }

    @Test
    fun `nested or compositions should retain a deadline snapshot while adapters remain live`() {
        val clock = TestTimeSource()
        val first = clock.markNow() + 2.seconds
        var deadline = first
        val dynamic = cancelledWhen({ deadline }) { deadline.hasPassedNow() }
        val token = (ExecutionControl.Never or dynamic) or ExecutionControl.until(clock.markNow() + 5.seconds)
        deadline = clock.markNow() + 4.seconds

        assertEquals(first, token.deadline())
        clock += 3.seconds
        assertFalse(token())
        clock += 1.seconds
        assertTrue(token())
    }

    @Test
    fun `explicit accounting should survive nested stop conditions`() {
        var firstWork = 0L
        var secondWork = 0L
        val first = object : ExecutionControl() {
            private val meter = WorkMeter { firstWork += it }
            override fun isCancelled(): Boolean = firstWork >= 7L
            override val accounting: WorkMeter get() = meter
        }
        val second = object : ExecutionControl() {
            private val meter = WorkMeter { secondWork += it }
            override fun isCancelled(): Boolean = secondWork >= 3L
            override val accounting: WorkMeter get() = meter
        }
        val token = (ExecutionControl.Never.withAccounting(first) or first) or (second or ExecutionControl.Never)

        assertSame(first.accounting, token.accounting)
        token.charge(6L)
        assertFalse(token())
        token.charge(1L)
        assertTrue(token())
        assertEquals(7L, firstWork)
        assertEquals(0L, secondWork)
    }

    @Test
    fun `or composition should leave and predicate semantics intact`() {
        var external = false
        val token = ((ExecutionControl { external } and ExecutionControl { false }) or ExecutionControl.Never) or
            ExecutionControl { external }

        assertFalse(token())
        external = true
        assertTrue(token())
    }

    @Test
    fun `or should fire when either side cancels`() {
        var a = false
        var b = false
        val combined = ExecutionControl { a } or ExecutionControl { b }
        assertFalse(combined())
        a = true
        assertTrue(combined())
        a = false
        b = true
        assertTrue(combined())
    }

    @Test
    fun `and should fire only when both sides cancel`() {
        var a = false
        var b = false
        val combined = ExecutionControl { a } and ExecutionControl { b }
        assertFalse(combined())
        a = true
        assertFalse(combined())
        b = true
        assertTrue(combined())
    }

    @Test
    fun `after should fire only once the duration has elapsed`() {
        assertFalse(ExecutionControl.after(10.minutes)())
        assertTrue(ExecutionControl.after((-1).seconds)())
    }

    @Test
    fun `predicate token should report its current deadline`() {
        val first = TimeSource.Monotonic.markNow() + 1.hours
        var current: ComparableTimeMark = first
        val token = cancelledWhen({ current }) { false }

        assertEquals(first, token.deadline())
        current = first + 1.hours
        assertEquals(first + 1.hours, token.deadline())
        assertTrue(assertNotNull(token.shorten(0.5).deadline()) < first + 1.hours)
    }

    @Test
    fun `only deadline-backed tokens should expose a deadline`() {
        assertNotNull(ExecutionControl.after(1.seconds).deadline())
        assertNull(ExecutionControl.Never.deadline())
        assertNull(ExecutionControl { false }.deadline())
    }

    @Test
    fun `shorten should move the deadline earlier and leave deadline-less tokens untouched`() {
        val full = ExecutionControl.after(100.seconds)
        val short = full.shorten(0.1)
        assertTrue(short.deadline()!! < full.deadline()!!)
        val bare = ExecutionControl.Never.shorten(0.1)
        assertNull(bare.deadline())
        assertFalse(bare.isCancelled())
    }

    @Test
    fun `or should report the earlier deadline`() {
        val soon = ExecutionControl.after(1.seconds)
        val late = ExecutionControl.after(100.seconds)
        assertTrue((late or soon).deadline()!! <= soon.deadline()!!)
    }
}
