package com.eignex.klause.factor.arithmetic.internals

import kotlin.test.Test
import kotlin.test.assertEquals

class LinearCoeffIndexTest {
    @Test
    fun `uniform coefficients should retain row membership across word boundaries`() {
        val variables = intArrayOf(100, 163, 164, 227, 228, 100)
        for (coefficient in listOf(1L, -2L, 0L, Long.MIN_VALUE, Long.MAX_VALUE)) {
            val index = LinearCoeffIndex(LongArray(variables.size) { coefficient }, variables)

            for (variable in variables) assertEquals(coefficient, index.coeffOf(variable))
            for (variable in listOf(Int.MIN_VALUE, 99, 101, 162, 165, 226, 229, Int.MAX_VALUE)) {
                assertEquals(0L, index.coeffOf(variable))
            }
        }
    }

    @Test
    fun `nonuniform coefficients should retain the last duplicate payload`() {
        val index = LinearCoeffIndex(longArrayOf(1, 5, -7, 11), intArrayOf(0, 64, 128, 64))

        assertEquals(1L, index.coeffOf(0))
        assertEquals(11L, index.coeffOf(64))
        assertEquals(-7L, index.coeffOf(128))
        assertEquals(0L, index.coeffOf(1))
    }

    @Test
    fun `sparse uniform coefficients should retain integer endpoint keys`() {
        for (variables in listOf(intArrayOf(Int.MIN_VALUE, Int.MAX_VALUE), intArrayOf(0, 1_000_000_000))) {
            val index = LinearCoeffIndex(longArrayOf(7, 7), variables)

            for (variable in variables) assertEquals(7L, index.coeffOf(variable))
            for (variable in listOf(-1, 1, Int.MIN_VALUE + 1, Int.MAX_VALUE - 1)) {
                assertEquals(0L, index.coeffOf(variable))
            }
        }
    }

    @Test
    fun `empty coefficient indexes should return zero`() {
        val index = LinearCoeffIndex(longArrayOf(), intArrayOf())

        for (variable in listOf(Int.MIN_VALUE, -1, 0, 1, Int.MAX_VALUE)) {
            assertEquals(0L, index.coeffOf(variable))
        }
    }
}
