package com.eignex.klause.ir

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RetainedFactorsTest {
    @Test
    fun `ordered deletion should retain the source positions of duplicate objects`() {
        val first = Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 2)
        val last = Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 0)
        val source = Problem(0, 1, arrayOf(IntDomain(0, 3)), arrayOf<Factor>(first, first, last))
        val target = source.withFactors(arrayOf(first, last))

        val mapping = requireNotNull(RetainedFactors.between(source, target, Cancellation.Never))

        assertEquals(listOf(0, 2), mapping.sourceIndices().toList())
    }

    @Test
    fun `changed declarations should decline retained factor mapping`() {
        val row = Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 2)
        val source = Problem(0, 1, arrayOf(IntDomain(0, 3)), arrayOf<Factor>(row, row))
        val target = source.withFactors(arrayOf(row)).withIntDomains(arrayOf(IntDomain(0, 2)))

        assertNull(RetainedFactors.between(source, target, Cancellation.Never))
    }

    @Test
    fun `replaced factors should decline retained factor mapping`() {
        val row = Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 2)
        val source = Problem(0, 1, arrayOf(IntDomain(0, 3)), arrayOf<Factor>(row, row))
        val target = source.withFactors(arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 2)))

        assertNull(RetainedFactors.between(source, target, Cancellation.Never))
    }

    @Test
    fun `cancelled factor matching should publish no mapping`() {
        val row = Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 2)
        val source = Problem(0, 1, arrayOf(IntDomain(0, 3)), arrayOf<Factor>(row, row))

        assertNull(RetainedFactors.between(source, source.withFactors(arrayOf(row)), Cancellation { true }))
    }
}
