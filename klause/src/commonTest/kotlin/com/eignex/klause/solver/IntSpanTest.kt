package com.eignex.klause.solver

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.randomValue
import com.eignex.klause.ir.values
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The boundary between a domain and its values: a domain answers bounds and holes however wide it is,
 * while enumeration is only offered when the values can be indexed and the caller can afford them.
 */
class IntSpanTest {

    @Test
    fun `a narrow domain hands back its values`() {
        val span = assertNotNull(IntDomain(3, 6).spanOrNull())

        assertEquals(4, span.size)
        assertEquals(listOf(3L, 4L, 5L, 6L), (0 until span.size).map { span.valueAt(it) })
    }

    @Test
    fun `a span skips the holes carved out of its domain`() {
        val span = assertNotNull(IntDomain(1, 5).excludeValue(3).spanOrNull())

        val seen = mutableListOf<Long>()
        span.forEach { seen.add(it) }
        assertEquals(listOf(1L, 2L, 4L, 5L), seen)
    }

    @Test
    fun `a domain wider than an index declines to be enumerated`() {
        assertNull(IntDomain(0, 5_000_000_000L).spanOrNull())
    }

    @Test
    fun `a caller states what it can afford`() {
        val d = IntDomain(1, 100)

        assertNotNull(d.spanOrNull(maxValues = 100))
        assertNull(d.spanOrNull(maxValues = 99))
    }

    @Test
    fun `a domain that cannot be enumerated still answers bounds and holes`() {
        val wide = IntDomain(0, 5_000_000_000L).excludeValue(4_000_000_000L)

        assertNull(wide.spanOrNull())
        assertEquals(1L, wide.holeCount)
        assertEquals(0L, wide.min)
        assertEquals(5_000_000_000L, wide.max)
    }

    @Test
    fun `requiring values from a domain that has too many fails loudly`() {
        assertFailsWith<IllegalStateException> { IntDomain(0, 5_000_000_000L).values.size }
    }

    @Test
    fun `a single-value domain is fixed whatever its representation`() {
        assertEquals(true, IntDomain(7, 7).isFixed)
        assertEquals(false, IntDomain(7, 8).isFixed)
        assertEquals(false, IntDomain(0, 5_000_000_000L).isFixed)
    }

    @Test
    fun `a value count is exact when the values can be counted`() {
        assertEquals(4L, IntDomain(3, 6).valueCount)
        assertEquals(5_000_000_001L, IntDomain(0, 5_000_000_000L).valueCount)
        assertEquals(3L, IntDomain(1, 5).excludeValue(2).excludeValue(4).valueCount)
    }

    @Test
    fun `a value count saturates rather than wrapping on a span wider than it can state`() {
        assertEquals(Long.MAX_VALUE, IntDomain(Long.MIN_VALUE, Long.MAX_VALUE).valueCount)
        assertEquals(Long.MAX_VALUE, IntDomain(0, Long.MAX_VALUE).valueCount)
        assertEquals(Long.MAX_VALUE, IntDomain(-(1L shl 62), 1L shl 62).valueCount)
    }

    @Test
    fun `a domain wider than an index space declines to hand over its values`() {
        assertNull(IntDomain(0, Long.MAX_VALUE).spanOrNull())
        assertNull(IntDomain(Long.MIN_VALUE, Long.MAX_VALUE).spanOrNull())
        assertNull(IntDomain(-(1L shl 62), 1L shl 62).spanOrNull())
    }

    @Test
    fun `excluding a value from a domain too wide to enumerate keeps its far bound`() {
        val carved = assertNotNull(IntDomain(0, Long.MAX_VALUE).excludeValues(longArrayOf(5L)))

        assertEquals(Long.MAX_VALUE, carved.max)
        assertEquals(0L, carved.min)
        assertEquals(false, carved.contains(5L))
    }

    @Test
    fun `a random value of a domain too wide to enumerate stays inside it`() {
        val d = IntDomain(Long.MIN_VALUE, Long.MAX_VALUE).excludeValue(0L)
        val rng = Random(1234)

        repeat(64) {
            val v = d.randomValue(rng)
            assertEquals(true, d.contains(v), "randomValue produced ${'$'}v, which is not in the domain")
        }
    }
}
