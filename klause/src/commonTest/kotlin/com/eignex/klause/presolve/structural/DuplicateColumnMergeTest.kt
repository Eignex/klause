package com.eignex.klause.presolve.structural

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DuplicateColumnMergeTest {
    @Test
    fun `every aggregate value should split within extreme and ordinary domains`() {
        val m = Long.MAX_VALUE
        val cases = listOf(
            IntDomain(-m, -m + 100) to IntDomain(m - 100, m),
            IntDomain(Long.MIN_VALUE, Long.MIN_VALUE + 100) to IntDomain(m - 100, m),
            IntDomain(m - 100, m) to IntDomain(-m, -m + 100),
            IntDomain(m - 100, m) to IntDomain(Long.MIN_VALUE, Long.MIN_VALUE + 100),
            IntDomain(-2, 0) to IntDomain(Long.MIN_VALUE + 2, Long.MIN_VALUE + 10),
            IntDomain(0, 2) to IntDomain(m - 10, m - 2),
            IntDomain(-7, 3) to IntDomain(-2, 8),
        )
        for ((keep, drop) in cases) {
            val merge = DuplicateColumnMerge(listOf(ColumnMerge(0, 1, keep, drop)))
            for (z in (keep.min + drop.min)..(keep.max + drop.max)) {
                val result = merge.reconstruct(Sample(BooleanArray(0), longArrayOf(z, 0))).ints

                assertTrue(result[0] in keep.min..keep.max)
                assertTrue(result[1] in drop.min..drop.max)
                assertEquals(bigIntOf(z), bigIntOf(result[0]) + bigIntOf(result[1]))
            }
        }
    }

    @Test
    fun `reverse batches should recover each extreme source column`() {
        val keep = IntDomain(Long.MIN_VALUE, Long.MIN_VALUE + 20)
        val first = IntDomain(Long.MAX_VALUE - 20, Long.MAX_VALUE)
        val second = IntDomain(-5, 5)
        val aggregate = IntDomain(keep.min + first.min, keep.max + first.max)
        val merges = DuplicateColumnMerges(listOf(
            listOf(ColumnMerge(0, 1, keep, first)),
            listOf(ColumnMerge(0, 2, aggregate, second)),
        ))
        for (z in (aggregate.min + second.min)..(aggregate.max + second.max)) {
            val result = merges.reconstruct(Sample(BooleanArray(0), longArrayOf(z, 0, 0))).ints

            assertTrue(result[0] in keep.min..keep.max)
            assertTrue(result[1] in first.min..first.max)
            assertTrue(result[2] in second.min..second.max)
            assertEquals(bigIntOf(z), bigIntOf(result[0]) + bigIntOf(result[1]) + bigIntOf(result[2]))
        }
    }

    @Test
    fun `an aggregate outside the feasible sum should reject reconstruction`() {
        val merge = DuplicateColumnMerge(listOf(ColumnMerge(0, 1, IntDomain(-3, 2), IntDomain(1, 4))))

        assertFailsWith<IllegalStateException> {
            merge.reconstruct(Sample(BooleanArray(0), longArrayOf(-3, 0)))
        }
    }
}
