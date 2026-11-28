package com.eignex.klause.lp.lattice

import com.eignex.util.numbers.BIG_ZERO
import com.eignex.util.numbers.BigInt
import com.eignex.util.numbers.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SparseIntMatrixTest {

    private fun row(vararg values: Int): SparseIntRow =
        sparseIntRow(values.withIndex().filter { it.value != 0 }.associate { it.index to bigIntOf(it.value) })

    // The first row needs a swap, the second repeats it, and columns 2 and 3 carry no pivot.
    private val rankDeficient = listOf(
        row(0, 2, 0, 1, 0, 0),
        row(0, 4, 0, 2, 0, 0),
        row(3, 0, 1, 0, 0, 0),
        row(0, 0, 0, 0, 5, -1),
    )

    @Test
    fun `sparse elimination should find the rank and pivot columns of a rank deficient matrix`() {
        val e = bareissEchelon(rankDeficient, COLS)

        assertEquals(listOf(0, 1, 4), e.pivots.toList())
        assertEquals(e.pivots.size, e.rows.size)
    }

    @Test
    fun `structural bounds should contain a witness of the original system`() {
        // The right-hand sides come from a witness, so the system provably has that solution.
        val witness = listOf(1, -2, 3, 4, 0, -5).map { bigIntOf(it) }
        val rhs = Array(rankDeficient.size) { i ->
            val row = rankDeficient[i]
            var acc: BigInt = BIG_ZERO
            for (k in row.index.indices) acc += row.value[k] * witness[row.index[k]]
            acc
        }

        val mixed = mixedEchelonHermite(rankDeficient, emptyList(), COLS, rhs)
        val reduced = Array<BigInt?>(mixed.equalities.size) { mixed.equalityRhs[it] }
        val y = triangularBounds(mixed.equalities, COLS, reduced, reduced)
        val bounds = mixed.originalBounds(y.lo, y.hi)

        for (i in 0 until COLS) {
            val lo = bounds.lo[i]
            val hi = bounds.hi[i]
            assertTrue(lo == null || witness[i] >= lo, "x$i = ${witness[i]} fell below $lo")
            assertTrue(hi == null || witness[i] <= hi, "x$i = ${witness[i]} rose above $hi")
        }
    }

    private companion object {
        const val COLS = 6
    }
}
