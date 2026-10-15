package com.eignex.klause.ir

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StructuralKeyTest {

    private fun key(expectedWords: Int): StructuralKey = StructuralKeyBuilder(expectedWords).apply {
        int(7)
        longs(LongArray(40) { it.toLong() })
        sortedInts(intArrayOf(5, 1, 3))
    }.build(FactorKind.TABLE)

    @Test
    fun `pre-sized builder should produce the same key as an unsized one`() {
        val unsized = key(0)
        val exact = key(45)
        val oversized = key(4096)

        assertEquals(unsized, exact)
        assertEquals(unsized, oversized)
        assertEquals(unsized.hashCode(), exact.hashCode())
    }

    private fun words(expectedWords: Int): LongArray =
        StructuralKeyBuilder(expectedWords).apply { longs(LongArray(40) { it.toLong() }) }.buildWords()

    @Test
    fun `buildWords should hold exactly the appended words regardless of capacity`() {
        // one length word plus 40 elements
        assertEquals(41, words(0).size)
        assertEquals(41, words(41).size)
        assertEquals(41, words(4096).size)
        assertTrue(words(0).contentEquals(words(4096)))
    }
}
