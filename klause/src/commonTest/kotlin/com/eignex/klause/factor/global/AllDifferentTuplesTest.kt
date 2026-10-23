package com.eignex.klause.factor.global

import com.eignex.klause.ir.VarRemap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class AllDifferentTuplesTest {
    @Test
    fun `structural identity should preserve row permutations and coordinate order`() {
        val factor = AllDifferentTuples(listOf(intArrayOf(0, 0), intArrayOf(1, 2)))
        val reordered = AllDifferentTuples(listOf(intArrayOf(1, 2), intArrayOf(0, 0)))
        val reversed = AllDifferentTuples(listOf(intArrayOf(0, 0), intArrayOf(2, 1)))

        assertEquals(factor.structuralKey(), reordered.structuralKey())
        assertNotEquals(factor.structuralKey(), reversed.structuralKey())
    }

    @Test
    fun `remapping should preserve every repeated role including structural colors`() {
        val factor = AllDifferentTuples(listOf(intArrayOf(0, 0), intArrayOf(1, 2)))
        val mapping = VarRemap(intArrayOf(), intArrayOf(2, -1, 0))
        val expected = AllDifferentTuples(listOf(intArrayOf(2, 2), intArrayOf(-1, 0)))

        assertEquals(expected.structuralKey(), factor.remap(mapping).structuralKey())
        assertEquals(expected.structuralKey().hashCode(), factor.remapStructuralHash(mapping))
    }
}
