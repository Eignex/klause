package com.eignex.klause.solver

import com.eignex.klause.config.DEFAULT_BITSET_THRESHOLD
import com.eignex.klause.util.LongArrayList
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IntDomainTest {

    @Test
    fun `wide span carved down to a sparse survivor set`() {
        val survivors = intArrayOf(3, 7, 1_000_000, 19_999_998, 20_000_000)
        val toExclude = LongArrayList()
        var next = 0
        for (s in survivors) {
            for (v in next until s) toExclude.add(v.toLong())
            next = s + 1
        }
        val d = IntDomain(0, 20_000_000).excludeValues(toExclude.toLongArray())!!
        assertEquals(3, d.min)
        assertEquals(20_000_000, d.max)
        assertEquals(survivors.size, d.size)
        for (s in survivors) assertTrue(s.toLong() in d, "survivor $s present")
        assertFalse(8 in d)
        assertFalse(999_999 in d)
        val seen = mutableListOf<Long>()
        d.forEach { seen.add(it) }
        assertEquals(survivors.map { it.toLong() }, seen)
        for ((i, s) in survivors.withIndex()) assertEquals(s.toLong(), d.valueAt(i))
        assertEquals(1_000_000, d.withMinAtLeast(8).min)
        assertEquals(7, d.withMaxAtMost(999_999).max)
    }

    @Test
    fun `excludeValues matches folding excludeValue across reps`() {
        val rng = Random(0xE7C1)
        repeat(400) { _ ->
            val lo = rng.nextInt(-20, 20)
            val width = rng.nextInt(2, 600)
            val hi = lo + width
            val base = IntDomain(lo.toLong(), hi.toLong())
            val picked = mutableSetOf<Int>()
            val k = rng.nextInt(0, width + 2)
            repeat(k) { _ -> picked.add(rng.nextInt(lo, hi + 1)) }
            val values = picked.map { it.toLong() }.toLongArray().also { arr -> arr.sort() }

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
                assertEquals(folded.size, bulk!!.size)
                assertEquals(folded.min, bulk.min)
                assertEquals(folded.max, bulk.max)
            }
        }
    }

    @Test
    fun `wide reps agree with a brute-force set across operations`() {
        val rng = Random(0x5A17)
        repeat(60) { _ ->
            val lo = rng.nextInt(0, 1000)
            val width = rng.nextInt(DEFAULT_BITSET_THRESHOLD + 1, 20_000)
            val hi = lo + width
            val present = (lo..hi).toMutableSet()
            val carveFraction = rng.nextDouble()
            val toExclude = (lo..hi).filter { _ -> rng.nextDouble() < carveFraction }.sorted()
            for (v in toExclude) present.remove(v)
            if (present.isEmpty()) return@repeat

            val d = IntDomain(lo.toLong(), hi.toLong()).excludeValues(toExclude.map { it.toLong() }.toLongArray())
                ?: error("non-empty present set must not yield null")
            assertEquals(present.size, d.size, "size")
            assertEquals(present.min().toLong(), d.min, "min")
            assertEquals(present.max().toLong(), d.max, "max")

            for (v in (lo - 2)..(hi + 2)) assertEquals(v in present, v.toLong() in d, "contains($v)")

            val ordered = present.sorted()
            val seen = mutableListOf<Long>()
            d.forEach { value -> seen.add(value) }
            assertEquals(ordered.map { it.toLong() }, seen, "forEach order")
            for (i in ordered.indices) assertEquals(ordered[i].toLong(), d.valueAt(i), "valueAt($i)")

            val holes = mutableListOf<Long>()
            d.forEachHole { hole -> holes.add(hole) }
            assertEquals((d.min + 1 until d.max).filter { value -> value.toInt() !in present }, holes, "forEachHole")
            assertEquals(holes.size.toLong(), d.holeCount, "holeCount")

            val tMin = rng.nextInt(lo, hi + 1)
            val expectMin = present.filter { value -> value >= tMin }
            if (expectMin.isNotEmpty()) {
                val e = d.withMinAtLeast(tMin.toLong())
                assertEquals(expectMin.min().toLong(), e.min, "withMinAtLeast($tMin).min")
                assertEquals(expectMin.size, e.size, "withMinAtLeast($tMin).size")
            }
            val tMax = rng.nextInt(lo, hi + 1)
            val expectMax = present.filter { value -> value <= tMax }
            if (expectMax.isNotEmpty()) {
                val e = d.withMaxAtMost(tMax.toLong())
                assertEquals(expectMax.max().toLong(), e.max, "withMaxAtMost($tMax).max")
                assertEquals(expectMax.size, e.size, "withMaxAtMost($tMax).size")
            }
        }
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
    fun `clamp snaps an interior hole to the nearest present value`() {
        val d = IntDomain(-1, 1).excludeValue(0)
        // 0 is an interior hole; nearest present values are -1 and 1 (tie → smaller).
        assertEquals(-1, d.clamp(0))
        assertEquals(-1, d.clamp(-1))
        assertEquals(1, d.clamp(1))
        // Out of bounds still clamps to the endpoints.
        assertEquals(-1, d.clamp(-5))
        assertEquals(1, d.clamp(5))
    }

    @Test
    fun `clamp is identity inside a contiguous domain`() {
        val d = IntDomain(0, 10)
        for (v in 0..10) assertEquals(v.toLong(), d.clamp(v.toLong()))
        assertEquals(0, d.clamp(-3))
        assertEquals(10, d.clamp(42))
    }

    @Test
    fun `lower and higher skip interior holes`() {
        val d = IntDomain(0, 10).excludeValue(3).excludeValue(4).excludeValue(5)
        assertEquals(2, d.lower(3))
        assertEquals(6, d.higher(5))
        assertEquals(6, d.higher(3))
        assertEquals(2, d.lower(6))
        // Contiguous neighbours are the immediate predecessor/successor.
        assertEquals(1, d.lower(2))
        assertEquals(7, d.higher(6))
    }
}
