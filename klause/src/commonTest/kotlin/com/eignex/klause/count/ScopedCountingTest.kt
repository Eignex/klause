package com.eignex.klause.count

import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
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

    @Test
    fun `intersecting qualified bounds should retain policy without promoting confidence`() {
        val semantics = SourceSemantics(SourceFormat.DIMACS).resolve()
        val proven = Count(6L, 5L, 7L, exact = false, confidence = 1.0, semantics = semantics)
        val approximate = Count(4L, 3L, 5L, exact = false, confidence = 0.8, semantics = semantics)

        val result = combineCounts(proven) { approximate }

        assertSame(semantics, result.semantics)
        assertEquals(0.8, result.confidence)
        assertFalse(result.exact)
    }

    @Test
    fun `an unbound estimate should not acquire policy from a qualified bound`() {
        val semantics = SourceSemantics(SourceFormat.DIMACS).resolve()
        val proven = Count(1L, 0L, 2L, exact = false, confidence = 1.0, semantics = semantics)

        val result = combineCounts(proven) { Count(1L, 0L, 2L, exact = false, confidence = 0.8) }

        assertNull(result.semantics)
    }

    @Test
    fun `count bounds from different interpretations should not combine`() {
        val proven = Count(1L, 0L, 2L, exact = false, confidence = 1.0,
            semantics = SourceSemantics(SourceFormat.DIMACS).resolve())
        val approximate = Count(1L, 0L, 2L, exact = false, confidence = 0.8,
            semantics = SourceSemantics(SourceFormat.WCNF).resolve())

        assertFailsWith<IllegalArgumentException> { combineCounts(proven) { approximate } }
    }
}
