package com.eignex.klause.solver

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IntDomainTest {

    @Test
    fun `contiguous domain basics`() {
        val d = IntDomain(1, 10)
        assertEquals(1, d.min)
        assertEquals(10, d.max)
        assertEquals(10, d.size)
        assertTrue(5 in d)
        assertFalse(0 in d)
        assertFalse(11 in d)
        assertEquals(5, d.clamp(5))
        assertEquals(1, d.clamp(-5))
        assertEquals(10, d.clamp(100))
    }

    @Test
    fun `excludeValue absent is identity`() {
        val d = IntDomain(1, 5)
        val e = d.excludeValue(99)
        assertTrue(e === d, "absent-value exclusion should return the same instance")
    }

    @Test
    fun `excludeValue at min advances and trims`() {
        val d = IntDomain(1, 5)
        val e = d.excludeValue(1)
        assertEquals(2, e.min)
        assertEquals(5, e.max)
        assertEquals(4, e.size)
        assertFalse(1 in e)
        assertTrue(2 in e)
    }

    @Test
    fun `excludeValue at max retreats and trims`() {
        val d = IntDomain(1, 5)
        val e = d.excludeValue(5)
        assertEquals(1, e.min)
        assertEquals(4, e.max)
        assertEquals(4, e.size)
        assertFalse(5 in e)
        assertTrue(4 in e)
    }

    @Test
    fun `excludeValue interior creates sparse domain`() {
        val d = IntDomain(1, 5)
        val e = d.excludeValue(3)
        assertEquals(1, e.min)
        assertEquals(5, e.max)
        assertEquals(4, e.size, "[1,5] minus {3} should have 4 values")
        assertTrue(2 in e)
        assertFalse(3 in e)
        assertTrue(4 in e)
        val seen = mutableListOf<Int>()
        e.forEach { seen.add(it) }
        assertEquals(listOf(1, 2, 4, 5), seen)
    }

    @Test
    fun `excludeValue stacking accumulates holes`() {
        val d = IntDomain(1, 10).excludeValue(4).excludeValue(7).excludeValue(5)
        assertEquals(1, d.min)
        assertEquals(10, d.max)
        assertEquals(7, d.size)
        assertFalse(4 in d)
        assertFalse(5 in d)
        assertFalse(7 in d)
        assertTrue(6 in d)
        val seen = mutableListOf<Int>()
        d.forEach { seen.add(it) }
        assertEquals(listOf(1, 2, 3, 6, 8, 9, 10), seen)
    }

    @Test
    fun `excludeValue at min jumps past pre-existing adjacent holes`() {
        val d = IntDomain(1, 10).excludeValue(2).excludeValue(3)
        val e = d.excludeValue(1)
        assertEquals(4, e.min)
        assertEquals(10, e.max)
        assertEquals(7, e.size)
        val seen = mutableListOf<Int>()
        e.forEach { seen.add(it) }
        assertEquals(listOf(4, 5, 6, 7, 8, 9, 10), seen)
    }

    @Test
    fun `excludeValue collapsing to single value`() {
        val d = IntDomain(1, 3).excludeValue(2).excludeValue(3)
        assertEquals(1, d.min)
        assertEquals(1, d.max)
        assertEquals(1, d.size)
        assertTrue(1 in d)
    }

    @Test
    fun `excludeValue that would empty domain throws`() {
        val d = IntDomain(5, 5)
        assertFails { d.excludeValue(5) }
    }

    @Test
    fun `withMinAtLeast skips holes`() {
        val d = IntDomain(1, 10).excludeValue(4).excludeValue(5)
        val e = d.withMinAtLeast(4)
        assertEquals(6, e.min)
        assertEquals(10, e.max)
        assertEquals(5, e.size)
    }

    @Test
    fun `withMaxAtMost skips holes`() {
        val d = IntDomain(1, 10).excludeValue(6).excludeValue(7)
        val e = d.withMaxAtMost(7)
        assertEquals(1, e.min)
        assertEquals(5, e.max)
        assertEquals(5, e.size)
    }

    @Test
    fun `equals respects holes`() {
        val a = IntDomain(1, 5).excludeValue(3)
        val b = IntDomain(1, 5).excludeValue(3)
        val c = IntDomain(1, 5).excludeValue(4)
        val d = IntDomain(1, 5)
        assertEquals(a, b)
        assertTrue(a != c)
        assertTrue(a != d)
    }

    @Test
    fun `forEach skips holes and preserves order`() {
        val d = IntDomain(1, 7).excludeValue(3).excludeValue(5)
        val seen = mutableListOf<Int>()
        d.forEach { seen.add(it) }
        assertEquals(listOf(1, 2, 4, 6, 7), seen)
    }

    @Test
    fun `narrow span interior exclude switches to bitset rep`() {
        // Span 50 is well under BITSET_THRESHOLD=256.
        val d = IntDomain(1, 50).excludeValue(25)
        assertEquals(1, d.min)
        assertEquals(50, d.max)
        assertEquals(49, d.size)
        assertFalse(25 in d)
        assertTrue(24 in d)
        assertTrue(26 in d)
    }

    @Test
    fun `wide span interior exclude stays in holes rep`() {
        // Span 1000 > BITSET_THRESHOLD.
        val d = IntDomain(0, 999).excludeValue(500)
        assertEquals(0, d.min)
        assertEquals(999, d.max)
        assertEquals(999, d.size)
        assertFalse(500 in d)
        assertTrue(499 in d)
        assertTrue(501 in d)
    }

    @Test
    fun `bitset rep excludeValue updates min when removing the current min`() {
        var d = IntDomain(10, 30).excludeValue(15)
        assertEquals(10, d.min)
        d = d.excludeValue(10)
        assertEquals(11, d.min)
        d = d.excludeValue(11)
        assertEquals(12, d.min)
    }

    @Test
    fun `bitset rep excludeValue updates max when removing the current max`() {
        var d = IntDomain(10, 30).excludeValue(15)
        d = d.excludeValue(30)
        assertEquals(29, d.max)
        d = d.excludeValue(29)
        assertEquals(28, d.max)
    }

    @Test
    fun `bitset rep excludeValue advances past a chain of removed endpoints`() {
        var d = IntDomain(0, 30).excludeValue(20)
        for (v in 0..5) d = d.excludeValue(v)
        assertEquals(6, d.min)
        d = d.excludeValue(6)
        assertEquals(7, d.min)
        assertFalse(20 in d)
    }

    @Test
    fun `bitset rep withMinAtLeast clears bits below and updates min`() {
        var d = IntDomain(0, 100).excludeValue(50)
        d = d.withMinAtLeast(40)
        assertEquals(40, d.min)
        assertEquals(100, d.max)
        assertTrue(40 in d)
        assertFalse(39 in d)
        assertFalse(50 in d, "previously-excluded interior should remain excluded")
    }

    @Test
    fun `bitset rep withMaxAtMost clears bits above and updates max`() {
        var d = IntDomain(0, 100).excludeValue(50)
        d = d.withMaxAtMost(60)
        assertEquals(0, d.min)
        assertEquals(60, d.max)
        assertFalse(50 in d)
        assertFalse(61 in d)
        assertTrue(60 in d)
    }

    @Test
    fun `bitset rep valueAt walks set bits in order`() {
        val d = IntDomain(5, 20).excludeValue(10).excludeValue(15)
        val expected = (5..20).filter { it != 10 && it != 15 }
        for ((i, v) in expected.withIndex()) {
            assertEquals(v, d.valueAt(i), "valueAt($i)")
        }
    }

    @Test
    fun `bitset rep forEach matches expected sequence`() {
        val d = IntDomain(0, 80).excludeValue(40) // span 81 → bitset
        val seen = mutableListOf<Int>()
        d.forEach { seen.add(it) }
        val expected = (0..80).filter { it != 40 }
        assertEquals(expected, seen)
    }

    @Test
    fun `mixed rep equality respects set semantics`() {
        val a = IntDomain(0, 30).excludeValue(10).excludeValue(20)
        val b = IntDomain(0, 30).excludeValue(20).excludeValue(10)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        val c = IntDomain(0, 30).excludeValue(10).excludeValue(21)
        assertTrue(a != c)
    }

    @Test
    fun `bitset rep handles spans that span multiple words`() {
        // Span = 200 covers 4 longs; chosen bits sit on word boundaries.
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
        assertEquals(195, d.size)
    }

    @Test
    fun `bitset rep withMinAtLeast that hits a cleared bit advances further`() {
        var d = IntDomain(0, 100).excludeValue(50)
        d = d.withMinAtLeast(50)
        assertEquals(51, d.min)
    }

    @Test
    fun `bitset rep refuses excludeValue that would empty the domain`() {
        var d = IntDomain(0, 4).excludeValue(2)
        d = d.excludeValue(0)
        d = d.excludeValue(4)
        d = d.excludeValue(3)
        assertEquals(1, d.size)
        assertFails { d.excludeValue(1) }
    }

    @Test
    fun `excludeValues empty list is identity`() {
        val d = IntDomain(1, 5)
        assertTrue(d.excludeValues(IntArray(0)) === d)
    }

    @Test
    fun `excludeValues with no present value is identity`() {
        val d = IntDomain(1, 5).excludeValue(3)
        assertTrue(d.excludeValues(intArrayOf(-1, 0, 3, 6, 9)) === d, "all absent → same instance")
    }

    @Test
    fun `excludeValues emptying the domain returns null`() {
        assertEquals(null, IntDomain(2, 4).excludeValues(intArrayOf(2, 3, 4)))
    }

    @Test
    fun `excludeValues matches folding excludeValue across reps`() {
        // Cover contiguous, bitset-span (<= threshold) and wide-holes domains, plus
        // edge-only, interior-only and mixed exclusion sets — the result must equal
        // (by membership) folding excludeValue over the same sorted list.
        val rng = Random(0xE7C1)
        repeat(400) {
            val lo = rng.nextInt(-20, 20)
            val width = rng.nextInt(2, 600) // straddles BITSET_THRESHOLD (256)
            val hi = lo + width
            val base = IntDomain(lo, hi)
            // Pick a sorted, distinct subset of [lo, hi] to exclude.
            val picked = mutableSetOf<Int>()
            val k = rng.nextInt(0, width + 2)
            repeat(k) { picked.add(rng.nextInt(lo, hi + 1)) }
            val values = picked.toIntArray().also { arr -> arr.sort() }

            val folded = run {
                var d: IntDomain? = base
                for (v in values) {
                    val cur = d ?: break
                    d = if (cur.size == 1 && v in cur) null else cur.excludeValue(v)
                }
                d
            }
            val bulk = base.excludeValues(values)

            if (folded == null) {
                assertEquals(null, bulk, "values=${values.toList()} on $base should empty")
            } else {
                assertEquals(folded, bulk, "values=${values.toList()} on $base")
                // equals() is membership-based across reps; also spot-check size + bounds.
                assertEquals(folded.size, bulk!!.size)
                assertEquals(folded.min, bulk.min)
                assertEquals(folded.max, bulk.max)
            }
        }
    }
}
