package com.eignex.klause.factor.arithmetic

import com.eignex.klause.ir.VarRemap
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RealFiniteDomainTest {
    @Test
    fun `membership should capture a canonical set independently of caller buffers`() {
        val values = doubleArrayOf(7.0, -0.0, -2.5, 0.125, 0.0, 7.0)
        val factor = RealFiniteDomain(2, values)
        values.fill(9.0)
        factor.values.fill(8.0)

        assertContentEquals(doubleArrayOf(-2.5, 0.0, 0.125, 7.0), factor.values)
        assertContentEquals(intArrayOf(2), factor.variables.reals)
        assertTrue(factor.intVars.isEmpty())
        assertTrue(factor.boolVars.isEmpty())
        assertEquals(RealFiniteDomain(2, doubleArrayOf(0.125, 7.0, -2.5, 0.0)).structuralKey(), factor.structuralKey())
    }

    @Test
    fun `remapping should preserve values in the real namespace`() {
        val factor = RealFiniteDomain(0, doubleArrayOf(-1.0, 0.25, 3.0))
        val mapping = VarRemap(intArrayOf(9), intArrayOf(8), intArrayOf(4))

        val remapped = factor.remap(mapping) as RealFiniteDomain

        assertEquals(4, remapped.variable)
        assertContentEquals(factor.values, remapped.values)
        assertEquals(remapped.structuralKey().hashCode(), factor.remapStructuralHash(mapping))
        assertNotEquals(factor.structuralKey(), RealFiniteDomain(0, doubleArrayOf(-1.0, 0.5, 3.0)).structuralKey())
    }

    @Test
    fun `empty and singleton sets should retain their source coordinate`() {
        for (values in listOf(doubleArrayOf(), doubleArrayOf(-3.0))) {
            val factor = RealFiniteDomain(0, values)

            assertEquals(values.size, factor.size)
            assertContentEquals(intArrayOf(0), factor.variables.reals)
        }
    }

    @Test
    fun `nonfinite values and removed coordinates should reject representation`() {
        for (value in listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { RealFiniteDomain(0, doubleArrayOf(value)) }
        }
        assertFailsWith<IllegalArgumentException> { RealFiniteDomain(-1, doubleArrayOf(1.0)) }
        assertFailsWith<IllegalArgumentException> {
            RealFiniteDomain(0, doubleArrayOf(1.0)).remap(VarRemap(intArrayOf(), intArrayOf(), intArrayOf(-1)))
        }
    }
}
