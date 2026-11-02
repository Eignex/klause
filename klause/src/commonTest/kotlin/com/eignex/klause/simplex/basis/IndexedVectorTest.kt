package com.eignex.klause.simplex.basis

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IndexedVectorTest {
    @Test
    fun `stored vector norms should preserve finite magnitudes across floating scales`() {
        val cases = listOf(
            doubleArrayOf(3.0, 4.0) to 5.0,
            doubleArrayOf(3e300, 4e300) to 5e300,
            doubleArrayOf(3e-300, 4e-300) to 5e-300,
            doubleArrayOf(Double.MIN_VALUE) to Double.MIN_VALUE,
        )
        for ((values, expected) in cases) {
            val vector = IndexedVector(values.size * 2 + 1)
            for (i in values.indices) vector.store(2 * i, values[i])

            val actual = vector.nrm2()

            assertEquals(expected, actual, abs(expected) * 1e-15)
        }
    }

    @Test
    fun `stored vector norms should retain positive zero and nonfinite classification`() {
        val cases = listOf(
            doubleArrayOf() to 0.0,
            doubleArrayOf(-0.0, 0.0) to 0.0,
            doubleArrayOf(Double.POSITIVE_INFINITY) to Double.POSITIVE_INFINITY,
            doubleArrayOf(Double.NEGATIVE_INFINITY) to Double.POSITIVE_INFINITY,
            doubleArrayOf(Double.NaN) to Double.NaN,
            doubleArrayOf(Double.POSITIVE_INFINITY, Double.NaN) to Double.NaN,
        )
        for ((values, expected) in cases) {
            val vector = IndexedVector(values.size + 1)
            for (i in values.indices) vector.store(i, values[i])

            val actual = vector.nrm2()

            assertEquals(expected.toBits(), actual.toBits())
        }
    }

    @Test
    fun `vector norms should ignore cleared support across sparse sizes`() {
        for (size in listOf(63, 64, 65)) {
            val vector = IndexedVector(size * 2 + 1)
            repeat(size) { vector.store(2 * it, 3.0) }
            assertEquals(3.0 * sqrt(size.toDouble()), vector.nrm2(), 1e-12)

            vector.clear()
            vector.store(1, 4.0)

            assertEquals(4.0, vector.nrm2())
        }
    }

    @Test
    fun `stored positions should stay unique when their values are zero`() {
        for (value in listOf(0.0, 2.0)) {
            val vector = IndexedVector(3)
            vector.store(1, value)

            assertFailsWith<IllegalArgumentException> { vector.store(1, 4.0) }

            assertEquals(1, vector.count)
            assertEquals(listOf(1 to value), buildList { vector.forEachStored { i, v -> add(i to v) } })
        }
    }

    @Test
    fun `clear should release every stored position for reuse`() {
        val vector = IndexedVector(3)
        vector.store(2, 0.0)
        vector.store(0, 2.0)

        vector.clear()
        vector.store(2, 3.0)
        vector.store(0, 4.0)

        assertContentEquals(doubleArrayOf(4.0, 0.0, 3.0), vector.toDoubleArray())
        assertEquals(2, vector.count)
    }

    @Test
    fun `dense input and gathered output should not alias the vector`() {
        val input = doubleArrayOf(0.0, 2.0, 0.0)
        val vector = IndexedVector(3)
        vector.scatter(input)
        val output = vector.gather(DoubleArray(3) { 7.0 })

        input[1] = 5.0
        output[1] = 8.0

        assertContentEquals(doubleArrayOf(0.0, 2.0, 0.0), vector.toDoubleArray())
        assertEquals(1, vector.count)
    }

    @Test
    fun `gather should preserve IEEE values and clear absent entries after reuse`() {
        val vector = IndexedVector(4)
        val out = DoubleArray(4) { 7.0 }
        for (value in listOf(-0.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            vector.clear()
            vector.store(2, value)

            vector.gather(out)

            assertEquals(value.toRawBits(), out[2].toRawBits())
            assertEquals(0.0.toRawBits(), out[0].toRawBits())
            assertEquals(0.0.toRawBits(), out[1].toRawBits())
            assertEquals(0.0.toRawBits(), out[3].toRawBits())
            vector.unit(1)
            vector.gather(out)
            assertContentEquals(doubleArrayOf(0.0, 1.0, 0.0, 0.0), out)
        }
    }
}
