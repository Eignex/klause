package com.eignex.klause.simplex.basis

import com.eignex.koblas.engine.BuiltinEngines
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IndexedVectorTest {
    @Test
    fun `norm agrees with packed reference across scaling and tails`() {
        for (count in listOf(0, 1, 63, 64, 65, 66, 67)) {
            for (scale in listOf(0.0, 1.0, 1e200, 1e-200)) {
                val vector = IndexedVector(count * 2 + 3)
                val packed = DoubleArray(count) { (it % 11 - 5) * scale }
                for (k in 0 until count) vector.store((count - k) * 2, packed[k])
                val expected = BuiltinEngines.scalar.nrm2(packed, 0, count)

                val actual = vector.nrm2()

                assertTrue(abs(expected - actual) <= 1e-12 * abs(expected), "count=$count scale=$scale")
            }
        }
    }

    @Test
    fun `norm excludes cleared support after reuse`() {
        val vector = IndexedVector(5)
        vector.store(4, 1e200)
        vector.clear()
        vector.store(1, 3.0)
        vector.store(3, 4.0)

        val actual = vector.nrm2()

        assertEquals(5.0, actual)
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
