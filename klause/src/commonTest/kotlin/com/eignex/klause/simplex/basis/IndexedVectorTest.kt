package com.eignex.klause.simplex.basis

import com.eignex.koblas.SparseMatrix
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IndexedVectorTest {
    @Test
    fun `stored positions remain unique even when their values are zero`() {
        for (value in listOf(0.0, 2.0)) {
            val vector = IndexedVector(3)
            vector.store(1, value)

            assertFailsWith<IllegalArgumentException> { vector.store(1, 4.0) }

            assertEquals(1, vector.count)
            assertEquals(listOf(1 to value), buildList { vector.forEachStored { i, v -> add(i to v) } })
        }
    }

    @Test
    fun `clearing releases every stored position for reuse`() {
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
    fun `dense input and gathered output do not alias the vector`() {
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
    fun `scattering a column replaces the previous support`() {
        val matrix = SparseMatrix.wrap(
            3,
            2,
            intArrayOf(0, 1, 3),
            intArrayOf(0, 1, 2),
            doubleArrayOf(1.0, 0.0, 4.0),
        )
        val vector = IndexedVector(3)
        vector.unit(0)

        vector.scatterColumn(matrix, 1)

        assertContentEquals(doubleArrayOf(0.0, 0.0, 4.0), vector.toDoubleArray())
        assertEquals(1, vector.count)
    }

    @Test
    fun `invalid replacement inputs preserve the previous vector`() {
        val vector = IndexedVector(1)
        vector.unit(0)

        assertFailsWith<IllegalArgumentException> { vector.unit(1) }
        assertFailsWith<IllegalArgumentException> { vector.scatter(doubleArrayOf()) }

        assertContentEquals(doubleArrayOf(1.0), vector.toDoubleArray())
    }

    @Test
    fun `an empty vector has empty support and zero density`() {
        val vector = IndexedVector(0)

        vector.scatter(doubleArrayOf())
        vector.clear()

        assertEquals(0, vector.count)
        assertEquals(0.0, vector.density)
        assertContentEquals(doubleArrayOf(), vector.toDoubleArray())
    }

    @Test
    fun `gather preserves stored IEEE values and clears absent entries after reuse`() {
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
