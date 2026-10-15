package com.eignex.klause.count

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScopedCountingTest {
    @Test
    fun `probabilistic interval intersected at one value should not prove an exact count`() {
        val proven = Count(6L, 5L, 7L, exact = false, confidence = 1.0)
        val approximate = Count(4L, 3L, 5L, exact = false, confidence = 0.8)

        val result = combineCounts(proven) { approximate }

        assertEquals(5L, result.lower)
        assertEquals(5L, result.upper)
        assertFalse(result.exact)
        assertEquals(0.8, result.confidence)
    }

    @Test
    fun `exhausted fallback cell should prove the hybrid count exactly`() {
        val proven = Count(6L, 5L, 7L, exact = false, confidence = 1.0)
        val approximate = Count(6L, 6L, 6L, exact = true, confidence = 1.0)

        val result = combineCounts(proven) { approximate }

        assertTrue(result.exact)
        assertEquals(approximate, result)
    }
}
