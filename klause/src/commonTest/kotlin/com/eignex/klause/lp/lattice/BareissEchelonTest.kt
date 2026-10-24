package com.eignex.klause.lp.lattice

import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BareissEchelonTest {

    @Test
    fun `fill limits should discard unfinished block deductions`() {
        val rows = sparseRows(longArrayOf(1, 1, 1, 0, 0), longArrayOf(1, 0, 0, 1, 1))
        for (limits in listOf(EchelonLimits(3, 20, 64), EchelonLimits(5, 6, 64))) {
            val result = bareissEchelon(rows, 5, vec(1, 2), limits = limits)

            assertTrue(result.dependentRows.isEmpty())
            assertTrue(result.rows.isEmpty())
            assertFalse(result.inconsistent)
        }
    }

    @Test
    fun `product bit limits should decline before multiplying wide pivot scales`() {
        val result = bareissEchelon(sparseRows(longArrayOf(15, 1, 0), longArrayOf(13, 0, 1)),
            3, vec(1, 2), limits = EchelonLimits(4, 20, 8))

        assertTrue(result.rows.isEmpty())
        assertTrue(result.dependentRows.isEmpty())
        assertFalse(result.inconsistent)
    }

    private fun echelon(vararg rows: LongArray): BareissEchelon =
        bareissEchelon(sparseRows(*rows), rows.maxOf { it.size })

    private fun vec(vararg v: Long) = Array(v.size) { bigIntOf(v[it]) }

    @Test
    fun `echelon should drop a dependent row from the rank`() {
        // Row 2 is twice row 1, so the rank is 1.
        val e = echelon(longArrayOf(1, 2, 3), longArrayOf(2, 4, 6))
        assertEquals(1, e.rows.size)
        assertEquals(listOf(0), e.pivots.toList())
    }

    @Test
    fun `echelon should skip a zero leading column without consuming a row`() {
        val e = echelon(longArrayOf(0, 3, 1), longArrayOf(0, 6, 5))
        assertEquals(listOf(1), e.pivots.toList().take(1))
        assertEquals(2, e.rows.size)
    }

    @Test
    fun `echelon should keep intermediates bounded on a dense integer system`() {
        val n = 6
        val base = longArrayOf(100003, 99991, 100019, 99989, 100043, 99961)
        val m = List(n) { i ->
            sparseIntRow((0 until n).associateWith { j -> bigIntOf(base[(i * 2 + j * 3) % n] + i + j) })
        }
        val e = bareissEchelon(m, n)
        var widest = 0
        for (row in e.rows) for (v in row.value) widest = maxOf(widest, v.abs().toString().length)
        assertTrue(widest < 60, "widest entry was $widest digits")
    }

    @Test
    fun `row swap should carry its right-hand side along`() {
        // Column 0 is zero in the first row, so elimination swaps the rows; the bounds must swap too.
        val e = bareissEchelon(sparseRows(longArrayOf(0, 1), longArrayOf(1, 0)), 2, vec(3, 5))
        assertEquals(bigIntOf(5), e.rhs[0])
        assertEquals(bigIntOf(3), e.rhs[1])
    }

    @Test
    fun `dependent row with a mismatched bound should be reported inconsistent`() {
        // x + y = 4 and 2x + 2y = 9 reduce to 0 = 1: the equalities alone have no solution.
        val e = bareissEchelon(sparseRows(longArrayOf(1, 1), longArrayOf(2, 2)), 2, vec(4, 9))
        assertTrue(e.inconsistent)
    }

    @Test
    fun `implied row should be reported by its input index`() {
        // x + y = 3, y + z = 4, x + 2y + z = 7: the third is the sum of the first two.
        val e = bareissEchelon(
            sparseRows(longArrayOf(1, 1, 0), longArrayOf(0, 1, 1), longArrayOf(1, 2, 1)),
            3,
            vec(3, 4, 7),
        )
        assertEquals(listOf(2), e.dependentRows.toList())
    }

    @Test
    fun `row reduced to a nonzero constant should not be reported dependent`() {
        val e = bareissEchelon(sparseRows(longArrayOf(1, 1), longArrayOf(2, 2)), 2, vec(4, 9))
        assertEquals(emptyList(), e.dependentRows.toList())
    }

    @Test
    fun `cancellation within a support merge should discard all partial deductions`() {
        val row = sparseIntRow((0 until 129).associateWith { bigIntOf(1) })
        var spent = 0L
        val token = object : Cancellation {
            override fun isCancelled(): Boolean = spent >= 65 * LATTICE_WORK_WEIGHT
            override fun workMeter(): WorkMeter = WorkMeter { spent += it }
        }

        val e = bareissEchelon(listOf(SparseIntRow.Zero, SparseIntRow.Zero, row, row), 129, vec(0, 1, 1, 1), token)

        assertTrue(e.rows.isEmpty())
        assertTrue(e.pivots.isEmpty())
        assertTrue(e.rhs.isEmpty())
        assertTrue(e.dependentRows.isEmpty())
        assertFalse(e.inconsistent)
        assertEquals(65 * LATTICE_WORK_WEIGHT, spent)
    }

    @Test
    fun `completed merges should charge every input entry once`() {
        for (width in listOf(1, 3, 129)) {
            val row = sparseIntRow((0 until width).associateWith { bigIntOf(1) })
            var spent = 0L
            val token = object : Cancellation {
                override fun isCancelled(): Boolean = false
                override fun workMeter(): WorkMeter = WorkMeter { spent += it }
            }

            val e = bareissEchelon(listOf(row, row), width, vec(1, 1), token)

            assertEquals(listOf(1), e.dependentRows.toList())
            assertEquals((1L + 2L * width) * LATTICE_WORK_WEIGHT, spent)
        }
    }
}
