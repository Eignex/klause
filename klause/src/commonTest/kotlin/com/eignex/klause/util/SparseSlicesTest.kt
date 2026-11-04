package com.eignex.klause.util

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SparseSlicesTest {
    @Test
    fun `scatter should preserve first touch order and cancellation until compacted clear`() {
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
    fun `invalid scatter and overlapping gather should leave all state intact`() {
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
    fun `checked scatter should evaluate zero alpha and latch underflow across calls`() {
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
        assertEquals(
            8 or SparseSlices.ARITHMETIC_NONFINITE or SparseSlices.ARITHMETIC_NONZERO_PRODUCT_UNDERFLOW,
            status[0],
        )
        assertContentEquals(intArrayOf(0, 1), touched)
    }

    @Test
    fun `checked reduction should preserve input order and repeated contributions`() {
        val status = intArrayOf(8)

        val result = SparseSlices.reduceDotChecked(
            1e16, false, intArrayOf(-1, 0, 0, 0), 1,
            doubleArrayOf(9.0, 1.0, -1e16, 2.0), 1, 3, doubleArrayOf(1.0), status, 0,
        )

        assertEquals(2.0, result)
        assertEquals(8, status[0])
    }

    @Test
    fun `checked reduction should not fuse multiplication with subtraction`() {
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
    fun `checked reduction should report underflow and empty nonfinite initial values`() {
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
    fun `clear should validate before mutation and reset signed zeros for reuse`() {
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
    fun `pivot candidates should retain relative positions and ignore inactive nonfinite values`() {
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

    @Test
    fun `scatter should accumulate repeated indices across calls`() {
        val accumulator = DoubleArray(6)
        val marks = IntArray(6)
        val touched = IntArray(6)

        var count = SparseSlices.scatterAxpy(
            2.0, intArrayOf(4, 1), 0, doubleArrayOf(3.0, -2.0), 0, 2, accumulator, marks, 7, touched, 0, 0,
        )
        count = SparseSlices.scatterAxpy(
            -1.0, intArrayOf(1, 5), 0, doubleArrayOf(4.0, 8.0), 0, 2, accumulator, marks, 7, touched, 0, count,
        )

        assertEquals(3, count)
        assertContentEquals(intArrayOf(4, 1, 5), touched.copyOf(count))
        assertContentEquals(doubleArrayOf(0.0, -8.0, 0.0, 0.0, 6.0, -8.0), accumulator)
        assertContentEquals(intArrayOf(0, 7, 0, 0, 7, 7), marks)
    }

    @Test
    fun `gather should retain a cancelled entry unless compacting`() {
        val accumulator = DoubleArray(4)
        val marks = IntArray(4)
        val touched = IntArray(4)
        val indices = intArrayOf(2)
        var count = SparseSlices.scatterAxpy(
            1.0, indices, 0, doubleArrayOf(3.0), 0, 1, accumulator, marks, 4, touched, 0, 0,
        )
        count = SparseSlices.scatterAxpy(
            -1.0, indices, 0, doubleArrayOf(3.0), 0, 1, accumulator, marks, 4, touched, 0, count,
        )
        val outIndices = IntArray(4) { -1 }
        val outValues = DoubleArray(4) { 9.0 }

        val retained = SparseSlices.gatherTouched(touched, 0, count, accumulator, outIndices, 0, outValues, 0)
        val compacted = SparseSlices.gatherTouched(
            touched, 0, count, accumulator, outIndices, 0, outValues, 0, compactExactZeros = true,
        )

        assertEquals(1, retained)
        assertEquals(2, outIndices[0])
        assertEquals(0.0, outValues[0])
        assertEquals(0, compacted)
        assertEquals(4, marks[2])
    }

    @Test
    fun `scatter should apply a zero multiplier to nonfinite values`() {
        val accumulator = DoubleArray(3)
        val marks = IntArray(3)
        val touched = IntArray(3)

        val count = SparseSlices.scatterAxpy(
            0.0, intArrayOf(0, 2), 0, doubleArrayOf(5.0, Double.POSITIVE_INFINITY), 0, 2,
            accumulator, marks, 3, touched, 0, 0,
        )

        assertEquals(2, count)
        assertEquals(0.0, accumulator[0])
        assertTrue(accumulator[2].isNaN())
    }

    @Test
    fun `first touch should add onto positive zero`() {
        val accumulator = doubleArrayOf(9.0)

        SparseSlices.scatterAxpy(
            1.0, intArrayOf(0), 0, doubleArrayOf(-0.0), 0, 1, accumulator, IntArray(1), 12, IntArray(1), 0, 0,
        )

        assertEquals(0.0.toBits(), accumulator[0].toBits())
    }

    @Test
    fun `a zero epoch should be rejected before any mutation`() {
        val accumulator = doubleArrayOf(5.0)
        val marks = IntArray(1)
        val touched = IntArray(1)

        assertFailsWith<IllegalArgumentException> {
            SparseSlices.scatterAxpy(
                1.0, intArrayOf(0), 0, doubleArrayOf(2.0), 0, 1, accumulator, marks, 0, touched, 0, 0,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SparseSlices.scatterAxpyChecked(
                1.0, intArrayOf(0), 0, doubleArrayOf(2.0), 0, 1,
                accumulator, marks, 0, touched, 0, 0, IntArray(1), 0,
            )
        }

        assertEquals(5.0, accumulator[0])
        assertEquals(0, marks[0])
    }

    @Test
    fun `checked scatter should leave a finite exact product unflagged`() {
        val status = IntArray(1)

        SparseSlices.scatterAxpyChecked(
            0.0, intArrayOf(0), 0, doubleArrayOf(4.0), 0, 1,
            DoubleArray(1), IntArray(1), 5, IntArray(1), 0, 0, status, 0,
        )

        assertEquals(0, status[0])
    }

    @Test
    fun `gather with marks should clear every touched entry`() {
        val touched = intArrayOf(9, 3, 1, 4, 9)
        val accumulator = doubleArrayOf(8.0, -0.0, 7.0, Double.NaN, Double.NEGATIVE_INFINITY)
        val marks = intArrayOf(0, 6, 0, 6, 6)
        val outIndices = IntArray(5) { -1 }
        val outValues = DoubleArray(5)

        val written = SparseSlices.gatherClearTouched(
            touched, 1, 3, accumulator, marks, outIndices, 1, outValues, 1, compactExactZeros = true,
        )

        assertEquals(2, written)
        assertContentEquals(intArrayOf(-1, 3, 4, -1, -1), outIndices)
        assertTrue(outValues[1].isNaN())
        assertEquals(Double.NEGATIVE_INFINITY, outValues[2])
        assertContentEquals(doubleArrayOf(8.0, 0.0, 7.0, 0.0, 0.0), accumulator)
        assertContentEquals(IntArray(5), marks)
    }

    @Test
    fun `gather without marks should leave the accumulator loaded`() {
        val accumulator = doubleArrayOf(-4.0, 9.0, 3.0)
        val outIndices = IntArray(2)
        val outValues = DoubleArray(2)

        SparseSlices.gatherTouched(intArrayOf(2, 0), 0, 2, accumulator, outIndices, 0, outValues, 0)

        assertContentEquals(intArrayOf(2, 0), outIndices)
        assertContentEquals(doubleArrayOf(3.0, -4.0), outValues)
        assertContentEquals(doubleArrayOf(-4.0, 9.0, 3.0), accumulator)
    }

    @Test
    fun `clear should reset only the touched window`() {
        val touched = intArrayOf(7, 3, 0, 7)
        val values = doubleArrayOf(1.0, 2.0, 3.0, 4.0)
        val marks = intArrayOf(5, 5, 5, 5)

        SparseSlices.clearTouched(touched, 1, 2, values, marks)

        assertContentEquals(doubleArrayOf(0.0, 2.0, 3.0, 0.0), values)
        assertContentEquals(intArrayOf(0, 5, 5, 0), marks)
        assertContentEquals(intArrayOf(7, 3, 0, 7), touched)
    }

    @Test
    fun `active maximum should ignore inactive rows`() {
        val maximum = SparseSlices.activeColumnMaxAbs(
            intArrayOf(4, 1, 3),
            0,
            doubleArrayOf(Double.POSITIVE_INFINITY, -7.0, 5.0),
            0,
            3,
            BooleanArray(5) { it != 4 },
        )

        assertEquals(7.0, maximum)
    }

    @Test
    fun `active maximum should report nonfinite and empty support`() {
        val rows = intArrayOf(0, 2)

        val nonfinite = SparseSlices.activeColumnMaxAbs(
            rows,
            0,
            doubleArrayOf(3.0, Double.NaN),
            0,
            2,
            BooleanArray(3) { true },
        )
        val empty = SparseSlices.activeColumnMaxAbs(rows, 0, doubleArrayOf(3.0, 4.0), 0, 2, BooleanArray(3))

        assertTrue(nonfinite.isNaN())
        assertEquals(0.0, empty)
    }

    @Test
    fun `pivot candidates should skip zero and nonfinite entries in input order`() {
        val out = IntArray(4) { -1 }

        val written = SparseSlices.pivotCandidatePositions(
            intArrayOf(0, 1, 2, 3), 0, doubleArrayOf(0.5, 0.0, -9.0, Double.NaN), 0, 4,
            BooleanArray(4) { it != 1 }, columnMaximum = 1.0, absoluteTolerance = 0.25,
            relativeThreshold = 0.4, outPositions = out, outOffset = 0,
        )

        assertEquals(2, written)
        assertContentEquals(intArrayOf(0, 2, -1, -1), out)
    }

    @Test
    fun `pivot candidates should reject nonfinite tolerances before writing`() {
        val out = IntArray(1) { -1 }
        val tolerances = listOf(
            Triple(Double.NaN, 0.0, 1.0),
            Triple(1.0, -1.0, 1.0),
            Triple(Double.MAX_VALUE, 0.0, 2.0),
        )

        for ((columnMaximum, absoluteTolerance, relativeThreshold) in tolerances) {
            assertFailsWith<IllegalArgumentException> {
                SparseSlices.pivotCandidatePositions(
                    intArrayOf(0), 0, doubleArrayOf(1.0), 0, 1, BooleanArray(1) { true },
                    columnMaximum, absoluteTolerance, relativeThreshold, out, 0,
                )
            }
        }

        assertEquals(-1, out[0])
    }

    @Test
    fun `empty slices at array ends should do nothing`() {
        val accumulator = doubleArrayOf(1.0)
        val marks = intArrayOf(2)
        val touched = intArrayOf(0)

        val scattered = SparseSlices.scatterAxpy(
            1.0, IntArray(0), 0, DoubleArray(0), 0, 0, accumulator, marks, 2, touched, 1, 0,
        )
        val gathered = SparseSlices.gatherClearTouched(
            touched, 1, 0, accumulator, marks, IntArray(0), 0, DoubleArray(0), 0, compactExactZeros = true,
        )
        SparseSlices.clearTouched(touched, 1, 0, accumulator, marks)

        assertEquals(0, scattered)
        assertEquals(0, gathered)
        assertContentEquals(doubleArrayOf(1.0), accumulator)
        assertContentEquals(intArrayOf(2), marks)
    }
}
