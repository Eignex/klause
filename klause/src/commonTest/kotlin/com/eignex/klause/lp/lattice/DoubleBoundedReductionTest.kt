package com.eignex.klause.lp.lattice

import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DoubleBoundedReductionTest {

    @Test
    fun `the transformed rows preserve every source activity through recovery`() {
        val original = listOf(
            row(longArrayOf(2, 3), -100, 100),
            row(longArrayOf(1, -1), -100, 100),
        )
        val reduced = assertNotNull(doubleBoundedReduction(original, 2))
        val y = arrayOf(bigIntOf(-3), bigIntOf(4))
        val x = reduced.recover(y)

        for (index in original.indices) {
            val sourceActivity = activity(original[index].coefficients, x)
            val transformedActivity = activity(reduced.rows[index].coefficients, y)
            assertEquals(sourceActivity, transformedActivity)
            assertTrue(sourceActivity >= original[index].lower && sourceActivity <= original[index].upper)
        }
    }

    @Test
    fun `a cancelled reduction does not return a partial transform`() {
        assertNull(doubleBoundedReduction(listOf(row(longArrayOf(1, 1), 0, 2)), 2, Cancellation { true }))
    }

    private fun row(coefficients: LongArray, lower: Long, upper: Long): DoubleBoundedRow = DoubleBoundedRow(
        sparseIntRow(coefficients.indices.associateWith { bigIntOf(coefficients[it]) }),
        bigIntOf(lower),
        bigIntOf(upper),
    )

    private fun activity(row: SparseIntRow, values: Array<BigInt>): BigInt {
        var sum = BIG_ZERO
        for (index in row.index.indices) sum += row.value[index] * values[row.index[index]]
        return sum
    }
}
