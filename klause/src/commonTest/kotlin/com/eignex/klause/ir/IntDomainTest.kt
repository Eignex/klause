package com.eignex.klause.ir

import com.eignex.klause.config.DEFAULT_BITSET_THRESHOLD
import com.eignex.klause.ir.intdomain.intDomainFromSurvivors
import com.eignex.klause.util.LongArrayList
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IntDomainTest {

    @Test
    fun `excludeValues should carve a wide span down to sparse survivors`() {
        val survivors = intArrayOf(3, 7, 100_000, 1_999_998, 2_000_000)
        val toExclude = LongArrayList()
        var next = 0
        for (s in survivors) {
            for (v in next until s) toExclude.add(v.toLong())
            next = s + 1
        }
        val d = IntDomain(0, 2_000_000).excludeValues(toExclude.toLongArray())!!
        assertEquals(3, d.min)
        assertEquals(2_000_000, d.max)
        assertEquals(survivors.size, d.values.size)
        for (s in survivors) assertTrue(s.toLong() in d, "survivor $s present")
        assertFalse(8 in d)
        assertFalse(99_999 in d)
        val seen = mutableListOf<Long>()
        d.values.forEach { seen.add(it) }
        assertEquals(survivors.map { it.toLong() }, seen)
        for ((i, s) in survivors.withIndex()) assertEquals(s.toLong(), d.values.valueAt(i))
        assertEquals(100_000, d.withMinAtLeast(8).min)
        assertEquals(7, d.withMaxAtMost(99_999).max)
    }

    @Test
    fun `excludeValues on a non-enumerable domain should match folded excludeValue`() {
        val wide = IntDomain(0, 3_000_000_000L)
        assertFalse((wide.spanOrNull() != null))
        val holes = longArrayOf(5L, 1_000_000_000L, 2_999_999_999L)
        val d = wide.excludeValues(holes)!!
        assertEquals(0L, d.min)
        assertEquals(3_000_000_000L, d.max)
        for (h in holes) assertFalse(h in d, "hole $h excluded")
        assertTrue(4L in d && 6L in d && 1_500_000_000L in d)
        var folded: IntDomain = wide
        for (h in holes) folded = folded.excludeValue(h)
        assertEquals(folded, d)
    }

    @Test
    fun `bitset domain should agree with a brute-force set`() {
        val hi = 10L + DEFAULT_BITSET_THRESHOLD + 50
        val multiplesOfThree = (10L..hi).filter { it % 3 == 0L }
        val present = (10L..hi).filter { it % 3 != 0L }

        val d = IntDomain(10L, hi).excludeValues(multiplesOfThree.toLongArray())!!

        val seen = mutableListOf<Long>()
        d.values.forEach { seen.add(it) }
        val holes = mutableListOf<Long>()
        d.forEachHole { holes.add(it) }
        assertEquals(present, seen)
        assertEquals(present[1000], d.values.valueAt(1000))
        assertEquals(multiplesOfThree, holes)
        assertEquals(multiplesOfThree.size.toLong(), d.holeCount)
        assertEquals(3001L, d.withMinAtLeast(3000).min)
        assertEquals(2999L, d.withMaxAtMost(3000).max)
    }

    @Test
    fun `equality should follow set semantics regardless of exclusion order`() {
        val a = IntDomain(0, 30).excludeValue(10).excludeValue(20)
        val b = IntDomain(0, 30).excludeValue(20).excludeValue(10)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        val c = IntDomain(0, 30).excludeValue(10).excludeValue(21)
        assertTrue(a != c)
    }

    @Test
    fun `bitset domain should handle holes at word boundaries`() {
        val d = IntDomain(0, 199)
            .excludeValue(63)
            .excludeValue(64)
            .excludeValue(127)
            .excludeValue(128)
            .excludeValue(199)
        assertEquals(0, d.min)
        assertEquals(198, d.max)
        assertFalse(63 in d)
        assertFalse(64 in d)
        assertFalse(127 in d)
        assertFalse(128 in d)
        assertFalse(199 in d)
        assertTrue(62 in d)
        assertTrue(65 in d)
        assertTrue(198 in d)
        assertEquals(195, d.values.size)
    }

    @Test
    fun `clamp should snap an interior hole to the nearest present value`() {
        val d = IntDomain(-1, 1).excludeValue(0)
        // equidistant neighbours: the smaller one wins
        assertEquals(-1, d.clamp(0))
        assertEquals(-1, d.clamp(-1))
        assertEquals(1, d.clamp(1))
        assertEquals(-1, d.clamp(-5))
        assertEquals(1, d.clamp(5))
    }

    @Test
    fun `clamp should compare distances beyond the signed word range`() {
        val domain = intDomainFromSurvivors(
            longArrayOf(Long.MIN_VALUE, Long.MAX_VALUE),
        )
        val cases = listOf(
            -1L to Long.MIN_VALUE,
            0L to Long.MAX_VALUE,
            1L to Long.MAX_VALUE,
        )

        for ((value, expected) in cases) assertEquals(expected, domain.clamp(value))
    }

    @Test
    fun `clamp should be identity inside a contiguous domain`() {
        val d = IntDomain(0, 10)
        for (v in 0..10) assertEquals(v.toLong(), d.clamp(v.toLong()))
        assertEquals(0, d.clamp(-3))
        assertEquals(10, d.clamp(42))
    }

    @Test
    fun `lower and higher should skip interior holes`() {
        val d = IntDomain(0, 10).excludeValue(3).excludeValue(4).excludeValue(5)
        assertEquals(2, d.lower(3))
        assertEquals(6, d.higher(5))
        assertEquals(6, d.higher(3))
        assertEquals(2, d.lower(6))
        assertEquals(1, d.lower(2))
        assertEquals(7, d.higher(6))
    }

    private fun sparseWideDomain(): IntDomain =
        IntDomain(0, 2_000_000).excludeValues(longArrayOf(0, 1, 2) + LongArray(99_992) { 8L + it })!!

    @Test
    fun `ceilingOrNull should round a hole up to the next present value`() {
        val d = sparseWideDomain()

        assertEquals(100_000L, d.ceilingOrNull(8))
    }

    @Test
    fun `floorOrNull should round a hole down to the previous present value`() {
        val d = sparseWideDomain()

        assertEquals(7L, d.floorOrNull(99_999))
    }

    @Test
    fun `spanOrNull should list the values of a narrow domain`() {
        val span = assertNotNull(IntDomain(3, 6).spanOrNull())

        assertEquals(4, span.size)
        assertEquals(listOf(3L, 4L, 5L, 6L), (0 until span.size).map { span.valueAt(it) })
    }

    @Test
    fun `span should skip domain holes`() {
        val span = assertNotNull(IntDomain(1, 5).excludeValue(3).spanOrNull())

        val seen = mutableListOf<Long>()
        span.forEach { seen.add(it) }
        assertEquals(listOf(1L, 2L, 4L, 5L), seen)
    }

    @Test
    fun `spanOrNull should return null above maxValues`() {
        val d = IntDomain(1, 100)

        assertNotNull(d.spanOrNull(maxValues = 100))
        assertNull(d.spanOrNull(maxValues = 99))
    }

    @Test
    fun `wide domain should report bounds and holes without a span`() {
        val wide = IntDomain(0, 5_000_000_000L).excludeValue(4_000_000_000L)

        assertNull(wide.spanOrNull())
        assertEquals(1L, wide.holeCount)
        assertEquals(0L, wide.min)
        assertEquals(5_000_000_000L, wide.max)
    }

    @Test
    fun `values should fail on a domain too large to enumerate`() {
        assertFailsWith<IllegalStateException> { IntDomain(0, 5_000_000_000L).values.size }
    }

    @Test
    fun `isFixed should hold only for single-value domains`() {
        assertEquals(true, IntDomain(7, 7).isFixed)
        assertEquals(false, IntDomain(7, 8).isFixed)
        assertEquals(false, IntDomain(0, 5_000_000_000L).isFixed)
    }

    @Test
    fun `valueCount should be exact for countable domains`() {
        assertEquals(4L, IntDomain(3, 6).valueCount)
        assertEquals(5_000_000_001L, IntDomain(0, 5_000_000_000L).valueCount)
        assertEquals(3L, IntDomain(1, 5).excludeValue(2).excludeValue(4).valueCount)
    }

    @Test
    fun `valueCount should saturate on spans wider than Long`() {
        assertEquals(Long.MAX_VALUE, IntDomain(Long.MIN_VALUE, Long.MAX_VALUE).valueCount)
        assertEquals(Long.MAX_VALUE, IntDomain(0, Long.MAX_VALUE).valueCount)
        assertEquals(Long.MAX_VALUE, IntDomain(-(1L shl 62), 1L shl 62).valueCount)
    }

    @Test
    fun `spanOrNull should return null for domains wider than an index space`() {
        assertNull(IntDomain(0, Long.MAX_VALUE).spanOrNull())
        assertNull(IntDomain(Long.MIN_VALUE, Long.MAX_VALUE).spanOrNull())
        assertNull(IntDomain(-(1L shl 62), 1L shl 62).spanOrNull())
    }

    @Test
    fun `excludeValues should keep the far bound of a very wide domain`() {
        val carved = assertNotNull(IntDomain(0, Long.MAX_VALUE).excludeValues(longArrayOf(5L)))

        assertEquals(Long.MAX_VALUE, carved.max)
        assertEquals(0L, carved.min)
        assertEquals(false, carved.contains(5L))
    }

    @Test
    fun `randomValue should stay inside a very wide domain`() {
        val d = IntDomain(Long.MIN_VALUE, Long.MAX_VALUE).excludeValue(0L)
        val rng = Random(1234)

        repeat(64) {
            val v = d.randomValue(rng)
            assertEquals(true, d.contains(v), "randomValue produced $v")
        }
    }
}
