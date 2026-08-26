package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.LinearOp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Bounds the equality structure implies for columns nothing else bounds. The rows below leave every
 * column open individually; only the combination of them pins anything down.
 */
class StructuralIntBoundsTest {

    private fun row(vararg terms: Pair<Int, Long>, op: LinearOp, bound: Long) = Linear(
        LongArray(terms.size) { terms[it].second },
        IntArray(terms.size) { terms[it].first },
        op,
        bound,
    )

    @Test
    fun `equalities that determine a column bound it on both sides`() {
        // x + y = 10 and x - y = 4 leave x and y unbounded row by row, and together fix x = 7, y = 3.
        val rows = listOf(
            row(0 to 1L, 1 to 1L, op = LinearOp.EQ, bound = 10L),
            row(0 to 1L, 1 to -1L, op = LinearOp.EQ, bound = 4L),
        )

        val bounds = assertNotNull(structuralIntBounds(2, rows))

        assertEquals("7", bounds.lo[0].toString())
        assertEquals("7", bounds.hi[0].toString())
        assertEquals("3", bounds.lo[1].toString())
        assertEquals("3", bounds.hi[1].toString())
    }

    @Test
    fun `a system with no equality implies nothing`() {
        val rows = listOf(row(0 to 1L, op = LinearOp.LE, bound = 4L))

        assertNull(structuralIntBounds(1, rows))
    }

    @Test
    fun `an underdetermined equality leaves its columns open`() {
        // x + y = 10 alone admits every integer x, so neither column takes a bound.
        val rows = listOf(row(0 to 1L, 1 to 1L, op = LinearOp.EQ, bound = 10L))

        val bounds = assertNotNull(structuralIntBounds(2, rows))

        for (v in 0..1) {
            assertNull(bounds.lo[v], "column $v keeps no lower bound")
            assertNull(bounds.hi[v], "column $v keeps no upper bound")
        }
    }
}
