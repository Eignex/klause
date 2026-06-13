package com.eignex.klause.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals

class FloatLinearConstraintTest {

    private fun flc(coeffs: DoubleArray, names: List<String>, op: IntCmpOp = IntCmpOp.LE, bound: Double = 1.0) =
        FloatLinearConstraint(coeffs, names, op, bound)

    @Test
    fun `equal content with distinct coeff arrays compares equal`() {
        val a = flc(doubleArrayOf(1.0, 2.0), listOf("x", "y"))
        val b = flc(doubleArrayOf(1.0, 2.0), listOf("x", "y"))
        assertEquals(a, b) // data-class default would be false (DoubleArray identity); custom equals fixes it
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `differs on any field`() {
        val base = flc(doubleArrayOf(1.0), listOf("x"))
        assertNotEquals(base, flc(doubleArrayOf(2.0), listOf("x")))
        assertNotEquals(base, flc(doubleArrayOf(1.0), listOf("y")))
        assertNotEquals(base, flc(doubleArrayOf(1.0), listOf("x"), op = IntCmpOp.GE))
        assertNotEquals(base, flc(doubleArrayOf(1.0), listOf("x"), bound = 2.0))
    }

    @Test
    fun `requires coeffs and varNames of equal length`() {
        assertFails { FloatLinearConstraint(doubleArrayOf(1.0, 2.0), listOf("x"), IntCmpOp.LE, 0.0) }
    }
}
