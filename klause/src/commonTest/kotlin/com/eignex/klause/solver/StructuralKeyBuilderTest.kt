package com.eignex.klause.solver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The key builder's capacity handling. Sizing is a memory concern (issue #1415: a key that splices a
 * whole transition table doubled its way up and was then copied again), so what must be proven is that
 * it changes nothing about the key itself — these payloads back factor dedup and symmetry refinement.
 */
class StructuralKeyBuilderTest {

    private fun key(expectedWords: Int): StructuralKey = StructuralKeyBuilder(expectedWords).apply {
        int(7)
        longs(LongArray(40) { it.toLong() })
        sortedInts(intArrayOf(5, 1, 3))
    }.build(FactorKind.TABLE)

    @Test
    fun `a pre-sized builder yields the same key as an unsized one`() {
        val unsized = key(0)
        val exact = key(45)
        val oversized = key(4096)

        assertEquals(unsized, exact, "sizing must not change the payload")
        assertEquals(unsized, oversized, "over-sizing must not leave slack in the payload")
        assertEquals(unsized.hashCode(), exact.hashCode())
    }

    private fun words(expectedWords: Int): LongArray =
        StructuralKeyBuilder(expectedWords).apply { longs(LongArray(40) { it.toLong() }) }.buildWords()

    @Test
    fun `the payload holds exactly the words appended whatever the capacity`() {
        // A length word plus 40 elements — an over-sized builder must not leave trailing capacity in it.
        assertEquals(41, words(0).size)
        assertEquals(41, words(41).size)
        assertEquals(41, words(4096).size)
        assertTrue(words(0).contentEquals(words(4096)), "capacity must not change the words")
    }

    @Test
    fun `an empty builder still produces a usable key`() {
        assertEquals(
            StructuralKeyBuilder().build(FactorKind.TABLE),
            StructuralKeyBuilder(1024).build(FactorKind.TABLE),
        )
    }
}
