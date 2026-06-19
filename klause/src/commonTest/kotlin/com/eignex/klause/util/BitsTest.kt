package com.eignex.klause.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BitsTest {

    @Test
    fun `set and get round-trip across word boundaries`() {
        val b = Bits(130)
        b.set(0)
        b.set(63)
        b.set(64)
        b.set(129)
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

    @Test
    fun `companion set has and clear round trip across words`() {
        val bits = LongArray(3)
        Bits.set(bits, 0)
        Bits.set(bits, 63)
        Bits.set(bits, 64)
        Bits.set(bits, 129)
        assertTrue(Bits.has(bits, 0))
        assertTrue(Bits.has(bits, 63))
        assertTrue(Bits.has(bits, 64))
        assertTrue(Bits.has(bits, 129))
        assertFalse(Bits.has(bits, 1))
        assertFalse(Bits.has(bits, 128))
        Bits.clear(bits, 64)
        assertFalse(Bits.has(bits, 64))
        assertTrue(Bits.has(bits, 63))
    }

    @Test
    fun `fillRange sets a half open range across word boundaries`() {
        val bits = LongArray(3)
        Bits.fillRange(bits, 61, 67)
        for (i in 0..60) assertFalse(Bits.has(bits, i), "bit $i")
        for (i in 61..66) assertTrue(Bits.has(bits, i), "bit $i")
        for (i in 67..140) assertFalse(Bits.has(bits, i), "bit $i")
    }

    @Test
    fun `clearBelow clears only bits below exclusive boundary`() {
        val bits = LongArray(3)
        Bits.fillRange(bits, 0, 140)
        Bits.clearBelow(bits, 65)
        for (i in 0..64) assertFalse(Bits.has(bits, i), "bit $i")
        for (i in 65..139) assertTrue(Bits.has(bits, i), "bit $i")
    }

    @Test
    fun `clearAbove clears only bits above inclusive boundary`() {
        val bits = LongArray(3)
        Bits.fillRange(bits, 0, 140)
        Bits.clearAbove(bits, 65)
        for (i in 0..65) assertTrue(Bits.has(bits, i), "bit $i")
        for (i in 66..139) assertFalse(Bits.has(bits, i), "bit $i")
    }

    @Test
    fun `firstSet and lastSet return sentinel for empty and extremes for populated`() {
        val empty = LongArray(2)
        assertEquals(-1, Bits.firstSet(empty))
        assertEquals(-1, Bits.lastSet(empty))

        val bits = LongArray(3)
        Bits.set(bits, 5)
        Bits.set(bits, 73)
        Bits.set(bits, 130)
        assertEquals(5, Bits.firstSet(bits))
        assertEquals(130, Bits.lastSet(bits))
    }
}
