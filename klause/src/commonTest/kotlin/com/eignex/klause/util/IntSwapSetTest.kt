package com.eignex.klause.util

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IntSwapSetTest {
    @Test
    fun `add and contains`() {
        val s = IntSwapSet(8)
        assertTrue(s.add(3))
        assertTrue(s.add(5))
        assertFalse(s.add(3))
        assertTrue(s.contains(3))
        assertTrue(s.contains(5))
        assertFalse(s.contains(0))
        assertEquals(2, s.size)
    }

    @Test
    fun `remove maintains contents`() {
        val s = IntSwapSet(8)
        listOf(1, 4, 7, 2).forEach { s.add(it) }
        assertTrue(s.remove(4))
        assertFalse(s.contains(4))
        assertFalse(s.remove(4))
        assertEquals(setOf(1, 7, 2), s.toIntArray().toSet())
    }

    @Test
    fun `random draws from members`() {
        val s = IntSwapSet(16)
        listOf(2, 5, 9).forEach { s.add(it) }
        val rng = Random(42)
        repeat(50) { assertTrue(s.random(rng) in setOf(2, 5, 9)) }
    }
}
