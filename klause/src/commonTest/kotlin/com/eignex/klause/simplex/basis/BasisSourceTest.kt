package com.eignex.klause.simplex.basis

import com.eignex.koblas.SparseMatrix
import kotlin.test.Test
import kotlin.test.assertEquals

class BasisSourceTest {
    @Test
    fun `captured coefficients survive mutation of every caller buffer`() {
        val pointers = intArrayOf(0, 2, 4)
        val rows = intArrayOf(0, 1, 0, 1)
        val values = doubleArrayOf(3.0, 1.0, 2.0, 4.0)
        val source = BasisSource(SparseMatrix.wrap(2, 2, pointers, rows, values))

        pointers.fill(0)
        rows.fill(9)
        values.fill(Double.NaN)

        assertEquals(2, source.matrix.rows)
        assertEquals(2, source.matrix.cols)
        assertEquals(3.0, source.matrix[0, 0])
        assertEquals(1.0, source.matrix[1, 0])
        assertEquals(2.0, source.matrix[0, 1])
        assertEquals(4.0, source.matrix[1, 1])
    }
}
