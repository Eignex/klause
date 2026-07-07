package com.eignex.klause.solver.intdomain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BitsetDomainTest {

    @Test
    fun `bitset rep excludeValue updates min when removing the current min`() {
        var d = ContiguousDomain(10, 30).excludeValue(15)
        assertEquals(10, d.min)
        d = d.excludeValue(10)
        assertEquals(11, d.min)
        d = d.excludeValue(11)
        assertEquals(12, d.min)
    }

    @Test
    fun `bitset rep excludeValue updates max when removing the current max`() {
        var d = ContiguousDomain(10, 30).excludeValue(15)
        d = d.excludeValue(30)
        assertEquals(29, d.max)
        d = d.excludeValue(29)
        assertEquals(28, d.max)
    }

    @Test
    fun `bitset rep excludeValue advances past a chain of removed endpoints`() {
        var d = ContiguousDomain(0, 30).excludeValue(20)
        for (v in 0..5) d = d.excludeValue(v.toLong())
        assertEquals(6, d.min)
        d = d.excludeValue(6)
        assertEquals(7, d.min)
        assertFalse(20 in d)
    }

    @Test
    fun `withMinAtLeast should advance past cleared bits`() {
        val d = ContiguousDomain(0, 20).excludeValue(3).excludeValue(4)
        val tightened = d.withMinAtLeast(3)
        assertEquals(5, tightened.min)
        assertTrue(5 in tightened)
        assertTrue(4 !in tightened)
    }

    @Test
    fun `includeInteriorValue should restore removed interior value`() {
        val d = ContiguousDomain(0, 20).excludeValue(10)
        val restored = d.includeInteriorValue(10)
        assertTrue(10 in restored)
        assertEquals(21, restored.size)
    }

    @Test
    fun `forEachHoleInRange should emit only holes in requested range`() {
        val d = ContiguousDomain(0, 20).excludeValue(5).excludeValue(9)
        val holes = mutableListOf<Long>()
        d.forEachHoleInRange(-10, 8) { holes.add(it) }
        assertEquals(listOf(5L), holes)
    }

    @Test
    fun `bitset rep withMinAtLeast clears bits below and updates min`() {
        var d = ContiguousDomain(0, 100).excludeValue(50)
        d = d.withMinAtLeast(40)
        assertEquals(40, d.min)
        assertEquals(100, d.max)
        assertTrue(40 in d)
        assertFalse(39 in d)
        assertFalse(50 in d)
    }

    @Test
    fun `bitset rep withMaxAtMost clears bits above and updates max`() {
        var d = ContiguousDomain(0, 100).excludeValue(50)
        d = d.withMaxAtMost(60)
        assertEquals(0, d.min)
        assertEquals(60, d.max)
        assertFalse(50 in d)
        assertFalse(61 in d)
        assertTrue(60 in d)
    }

    @Test
    fun `bitset rep valueAt walks set bits in order`() {
        val d = ContiguousDomain(5, 20).excludeValue(10).excludeValue(15)
        val expected = (5..20).filter { it != 10 && it != 15 }
        for ((i, v) in expected.withIndex()) {
            assertEquals(v.toLong(), d.valueAt(i), "valueAt($i)")
        }
    }

    @Test
    fun `bitset rep forEach matches expected sequence`() {
        val d = ContiguousDomain(0, 80).excludeValue(40)
        val seen = mutableListOf<Long>()
        d.forEach { seen.add(it) }
        val expected = (0..80).filter { it != 40 }.map { it.toLong() }
        assertEquals(expected, seen)
    }

    @Test
    fun `bitset rep withMinAtLeast that hits a cleared bit advances further`() {
        var d = ContiguousDomain(0, 100).excludeValue(50)
        d = d.withMinAtLeast(50)
        assertEquals(51, d.min)
    }

    @Test
    fun `bitset rep refuses excludeValue that would empty the domain`() {
        var d = ContiguousDomain(0, 4).excludeValue(2)
        d = d.excludeValue(0)
        d = d.excludeValue(4)
        d = d.excludeValue(3)
        assertEquals(1, d.size)
        assertFails { d.excludeValue(1) }
    }
}
