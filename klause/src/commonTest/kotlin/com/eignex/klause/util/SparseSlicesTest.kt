package com.eignex.klause.util

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SparseSlicesTest {
    @Test
    fun `scatter preserves first touch order and cancellation until compacted clear`() {
        val values = doubleArrayOf(99.0, 99.0, 99.0)
        val marks = IntArray(3)
        val touched = IntArray(5) { -1 }
        val indices = intArrayOf(-1, 2, 0)
        val source = doubleArrayOf(9.0, 9.0, 3.0, 4.0)

        val count = SparseSlices.scatterAxpy(1.0, indices, 1, source, 2, 2, values, marks, 7, touched, 1, 0)
        val repeated = SparseSlices.scatterAxpy(-1.0, indices, 1, source, 2, 2, values, marks, 7, touched, 1, count)
        val outIndices = IntArray(4) { -1 }
        val outValues = DoubleArray(4) { 8.0 }
        val written = SparseSlices.gatherClearTouched(
            touched, 1, repeated, values, marks, outIndices, 1, outValues, 2, compactExactZeros = true,
        )

        assertEquals(2, count)
        assertEquals(2, repeated)
        assertEquals(0, written)
        assertContentEquals(intArrayOf(-1, 2, 0, -1, -1), touched)
        assertContentEquals(doubleArrayOf(0.0, 99.0, 0.0), values)
        assertContentEquals(IntArray(3), marks)
        assertContentEquals(DoubleArray(4) { 8.0 }, outValues)
    }

    @Test
    fun `invalid scatter and overlapping gather leave all state intact`() {
        val values = doubleArrayOf(5.0, 6.0)
        val marks = intArrayOf(1, 0)
        val touched = intArrayOf(0, -1)

        assertFailsWith<IndexOutOfBoundsException> {
            SparseSlices.scatterAxpy(
                1.0, intArrayOf(1, 2), 0, doubleArrayOf(3.0, 4.0), 0, 2, values, marks, 1, touched, 0, 1,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SparseSlices.gatherClearTouched(touched, 0, 1, values, marks, touched, 0, DoubleArray(1), 0)
        }

        assertContentEquals(doubleArrayOf(5.0, 6.0), values)
        assertContentEquals(intArrayOf(1, 0), marks)
        assertContentEquals(intArrayOf(0, -1), touched)
    }

    @Test
    fun `checked scatter evaluates zero alpha and latches underflow across calls`() {
        val values = DoubleArray(2)
        val marks = IntArray(2)
        val touched = IntArray(2)
        val status = intArrayOf(8)

        val count = SparseSlices.scatterAxpyChecked(
            0.0, intArrayOf(0), 0, doubleArrayOf(Double.POSITIVE_INFINITY), 0, 1,
            values, marks, 1, touched, 0, 0, status, 0,
        )
        SparseSlices.scatterAxpyChecked(
            0.5, intArrayOf(1), 0, doubleArrayOf(Double.MIN_VALUE), 0, 1,
            values, marks, 1, touched, 0, count, status, 0,
        )

        assertTrue(values[0].isNaN())
        assertEquals(0.0, values[1])
        assertEquals(8 or SparseSlices.ARITHMETIC_NONFINITE or SparseSlices.ARITHMETIC_NONZERO_PRODUCT_UNDERFLOW, status[0])
        assertContentEquals(intArrayOf(0, 1), touched)
    }

    @Test
    fun `checked reduction preserves input order and repeated contributions`() {
        val status = intArrayOf(8)

        val result = SparseSlices.reduceDotChecked(
            1e16, false, intArrayOf(-1, 0, 0, 0), 1,
            doubleArrayOf(9.0, 1.0, -1e16, 2.0), 1, 3, doubleArrayOf(1.0), status, 0,
        )

        assertEquals(2.0, result)
        assertEquals(8, status[0])
    }

    @Test
    fun `checked reduction does not fuse multiplication with subtraction`() {
        val status = IntArray(1)
        val operand = 1.0 + 2.220446049250313e-16
        val roundedProduct = operand * operand

        val result = SparseSlices.reduceDotChecked(
            roundedProduct, true, intArrayOf(0), 0, doubleArrayOf(operand), 0, 1,
            doubleArrayOf(operand), status, 0,
        )

        assertEquals(0.0, result)
        assertEquals(0, status[0])
    }

    @Test
    fun `checked reduction reports underflow and empty nonfinite initial values`() {
        val status = intArrayOf(8)
        SparseSlices.reduceDotChecked(
            0.0, false, intArrayOf(0), 0, doubleArrayOf(Double.MIN_VALUE), 0, 1,
            doubleArrayOf(0.5), status, 0,
        )

        val result = SparseSlices.reduceDotChecked(
            Double.POSITIVE_INFINITY, false, intArrayOf(0), 1, doubleArrayOf(0.0), 1, 0,
            doubleArrayOf(), status, 0,
        )

        assertEquals(Double.POSITIVE_INFINITY, result)
        assertEquals(11, status[0])
    }

    @Test
    fun `clear validates before mutation and resets signed zeros for reuse`() {
        val values = doubleArrayOf(-0.0, 3.0)
        val marks = intArrayOf(1, 1)
        assertFailsWith<IndexOutOfBoundsException> {
            SparseSlices.clearTouched(intArrayOf(0, 2), 0, 2, values, marks)
        }
        assertEquals((-0.0).toBits(), values[0].toBits())
        assertContentEquals(intArrayOf(1, 1), marks)

        SparseSlices.clearTouched(intArrayOf(1, 0), 0, 2, values, marks)
        SparseSlices.clearTouched(intArrayOf(1), 1, 0, values, marks)

        assertContentEquals(doubleArrayOf(0.0, 0.0), values)
        assertEquals(0.0.toBits(), values[0].toBits())
        assertContentEquals(IntArray(2), marks)
    }

    @Test
    fun `pivot candidates retain relative positions and ignore inactive nonfinite values`() {
        val rows = intArrayOf(-1, 2, 0, 1)
        val values = doubleArrayOf(9.0, 9.0, 4.0, Double.NaN, -2.0)
        val active = booleanArrayOf(false, true, true)
        val out = IntArray(5) { -1 }
        val maximum = SparseSlices.activeColumnMaxAbs(rows, 1, values, 2, 3, active)

        val count = SparseSlices.pivotCandidatePositions(rows, 1, values, 2, 3, active, maximum, 1.0, 0.5, out, 1)

        assertEquals(4.0, maximum)
        assertEquals(2, count)
        assertContentEquals(intArrayOf(-1, 0, 2, -1, -1), out)
    }
}
