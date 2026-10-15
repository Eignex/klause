package com.eignex.klause.factor.table

import com.eignex.klause.ir.FactorReduction
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.VarRemap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ElementTest {
    @Test
    fun `affine substitution declines an unrepresentable index offset`() {
        val factor = Element(0, 1, longArrayOf(4, 9), false, Int.MAX_VALUE)

        assertNull(factor.substituteAffine(0, 1, -1, 2))
    }

    @Test
    fun `constant reduction retains a range beyond integer rewrite bounds`() {
        val factor = Element(0, 1, longArrayOf(4, 4), false, Int.MAX_VALUE)
        val domains = arrayOf(IntDomain(Int.MAX_VALUE.toLong(), Int.MAX_VALUE.toLong() + 1), IntDomain(0, 9))

        assertIs<FactorReduction.Unchanged>(factor.structuralReduce(domains))
    }

    @Test
    fun `remapping preserves ordered repeated and aliased roles`() {
        val factor = Element(0, 1, longArrayOf(2, 1, 0, 2), true, -2)

        val remapped = assertIs<Element>(factor.remap(VarRemap(intArrayOf(), intArrayOf(1, 0, 0))))

        assertEquals(1, remapped.idx)
        assertEquals(0, remapped.result)
        assertEquals(listOf(0L, 0L, 1L, 0L), remapped.arr.toList())
        assertEquals(-2, remapped.indexOffset)
    }
}
