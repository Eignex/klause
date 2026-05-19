package com.eignex.klause.solver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BitsTest {

    @Test
    fun `set and get round-trip across word boundaries`() {
        val b = Bits(130)
        b.set(0); b.set(63); b.set(64); b.set(129)
        assertTrue(b.get(0))
        assertTrue(b.get(63))
        assertTrue(b.get(64))
        assertTrue(b.get(129))
        assertFalse(b.get(1))
        assertFalse(b.get(128))
        assertEquals(4, b.cardinality())
    }

    @Test
    fun `full vs empty cardinality`() {
        assertEquals(0, Bits.empty(100).cardinality())
        assertEquals(100, Bits.full(100).cardinality())
        // Word tail clears properly: full(65) has exactly 65 bits, not 128.
        assertEquals(65, Bits.full(65).cardinality())
    }

    @Test
    fun `or and andNot ops`() {
        val a = Bits.of(10, intArrayOf(0, 2, 4, 6, 8))
        val b = Bits.of(10, intArrayOf(2, 3, 4))
        val u = a.copy().also { it.orInPlace(b) }
        assertEquals(listOf(0, 2, 3, 4, 6, 8), u.toIntArray().toList())
        val d = a.copy().also { it.andNotInPlace(b) }
        assertEquals(listOf(0, 6, 8), d.toIntArray().toList())
    }

    @Test
    fun `containsAll subset semantics`() {
        val universe = Bits.full(10)
        val sub = Bits.of(10, intArrayOf(2, 5, 7))
        assertTrue(universe.containsAll(sub))
        assertFalse(sub.containsAll(universe))
        assertTrue(sub.containsAll(sub))
    }

    @Test
    fun `forEachSet iterates in ascending order`() {
        val b = Bits.of(200, intArrayOf(199, 0, 63, 64, 100))
        val seen = mutableListOf<Int>()
        b.forEachSet { seen.add(it) }
        assertEquals(listOf(0, 63, 64, 100, 199), seen)
    }
}
