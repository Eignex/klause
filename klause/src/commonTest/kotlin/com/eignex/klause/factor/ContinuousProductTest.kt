package com.eignex.klause.factor

import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.ir.VarRemap
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ContinuousProductTest {
    @Test
    fun `squares and result aliases have unique real membership`() {
        for ((factor, expected) in listOf(
            ContinuousProduct(0, 0, 1) to intArrayOf(0, 1),
            ContinuousProduct(0, 1, 0) to intArrayOf(0, 1),
            ContinuousProduct(0, 0, 0) to intArrayOf(0),
        )) {
            assertContentEquals(expected, factor.variables.reals)
            assertTrue(factor.intVars.isEmpty())
            assertTrue(factor.boolVars.isEmpty())
        }
    }

    @Test
    fun `commutative operands share identity while result roles remain distinct`() {
        assertEquals(ContinuousProduct(0, 1, 2).structuralKey(), ContinuousProduct(1, 0, 2).structuralKey())
        assertNotEquals(ContinuousProduct(0, 1, 2).structuralKey(), ContinuousProduct(0, 2, 1).structuralKey())
        assertNotEquals(ContinuousProduct(0, 0, 2).structuralKey(), ContinuousProduct(0, 1, 2).structuralKey())
    }

    @Test
    fun `integer and mixed products cannot collide with continuous identity`() {
        val key = ContinuousProduct(0, 1, 2).structuralKey()

        assertNotEquals(key, Product(0, 1, 2).structuralKey())
        assertNotEquals(key, RealProduct(0, 1, 2, 0.0, 1.0).structuralKey())
    }

    @Test
    fun `remapping uses only real ids and preserves shared chain columns`() {
        val mapping = VarRemap(intArrayOf(9, 8, 7), intArrayOf(6, 5, 4), intArrayOf(2, 0, 1))
        val square = ContinuousProduct(0, 0, 1).remap(mapping) as ContinuousProduct
        val chain = ContinuousProduct(1, 0, 2).remap(mapping) as ContinuousProduct

        assertEquals(2, square.a)
        assertEquals(square.a, square.b)
        assertEquals(square.result, chain.a)
        assertEquals(ContinuousProduct(0, 2, 1).structuralKey(), chain.structuralKey())
    }

    @Test
    fun `noninjective remapping keeps operand multiplicity and canonical hash`() {
        val product = ContinuousProduct(0, 1, 2)
        val mapping = VarRemap(intArrayOf(), intArrayOf(), intArrayOf(3, 3, 3))
        val remapped = product.remap(mapping)

        assertEquals(ContinuousProduct(3, 3, 3).structuralKey(), remapped.structuralKey())
        assertContentEquals(intArrayOf(3), remapped.variables.reals)
        assertEquals(remapped.structuralKey().hashCode(), product.remapStructuralHash(mapping))
    }

    @Test
    fun `absent real table preserves continuous identity`() {
        val product = ContinuousProduct(0, 1, 2)

        assertEquals(product.structuralKey(), product.remap(VarRemap(intArrayOf(), intArrayOf())).structuralKey())
    }

    @Test
    fun `deleted real operands cannot create sentinel factors`() {
        assertFailsWith<IllegalArgumentException> {
            ContinuousProduct(0, 0, 1).remap(VarRemap(intArrayOf(), intArrayOf(), intArrayOf(-1, 0)))
        }
    }
}
