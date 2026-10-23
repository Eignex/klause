package com.eignex.klause.solver

import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class EncodingPreparationCancellationTest {
    @Test
    fun `an enclosing poll should retain a stop observed by a nested poll`() {
        var nested = false
        lateinit var scope: Cancellation
        scope = Cancellation {
            if (nested) true else {
                nested = true
                assertTrue(scope())
                false
            }
        }.encodingPreparationScope()

        assertTrue(scope())
        assertTrue(scope())
    }

    @Test
    fun `an observed external stop should remain latched`() {
        var cancelled = false
        val scope = Cancellation { cancelled }.encodingPreparationScope()
        assertFalse(scope())

        cancelled = true
        assertTrue(scope())
        cancelled = false

        assertTrue(scope())
    }

    @Test
    fun `polling should preserve ordered parent predicates and short circuiting`() {
        val visited = ArrayList<Int>()
        var cancelled = false
        val scope = (Cancellation { visited += 1; cancelled } or
            Cancellation { visited += 2; false }).encodingPreparationScope()

        assertFalse(scope())
        assertEquals(listOf(1, 2), visited)
        visited.clear()
        cancelled = true
        assertTrue(scope())
        assertTrue(scope())

        assertEquals(listOf(1), visited)
    }

    @Test
    fun `a live parent deadline should remain current before expiry`() {
        val clock = TestTimeSource()
        var end = clock.markNow() + 1.seconds
        val scope = object : Cancellation {
            override fun isCancelled(): Boolean = end.hasPassedNow()
            override fun deadline() = end
        }.encodingPreparationScope()
        assertFalse(scope())

        end = clock.markNow() + 3.seconds
        clock += 2.seconds

        assertEquals(end, scope.deadline())
        assertFalse(scope())
        clock += 1.seconds
        assertTrue(scope())
        end = clock.markNow() + 3.seconds
        assertTrue(scope())
    }

    @Test
    fun `charges should reach the current parent meter exactly once`() {
        var first = 0L
        var second = 0L
        var meter = WorkMeter { first += it }
        val scope = object : Cancellation {
            override fun isCancelled(): Boolean = first + second >= 3
            override fun workMeter(): WorkMeter = meter
        }.encodingPreparationScope()

        scope.charge(1)
        assertFalse(scope())
        meter = WorkMeter { second += it }
        scope.charge(2)

        assertTrue(scope())
        assertEquals(1L, first)
        assertEquals(2L, second)
    }

    @Test
    fun `an expired slice should not stop a fresh containing scope`() {
        val clock = TestTimeSource()
        val parent = Cancellation.until(clock.markNow() + 5.seconds)
        val slice = (Cancellation.until(clock.markNow() + 1.seconds) or parent).encodingPreparationScope()

        clock += 1.seconds
        assertTrue(slice())
        val next = parent.encodingPreparationScope()

        assertFalse(next())
        clock += 4.seconds
        assertTrue(next())
    }
}
