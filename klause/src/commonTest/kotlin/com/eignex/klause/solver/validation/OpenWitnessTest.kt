package com.eignex.klause.solver.validation

import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertIs

class OpenWitnessTest {
    @Test
    fun `verifyOpenWitness should reject incomplete discrete coordinates`() {
        val source = Problem(1, 1, arrayOf(IntDomain(0, 2)), emptyArray())
        val partial = listOf(Sample(booleanArrayOf(), longArrayOf(1)), Sample(booleanArrayOf(true), longArrayOf()))

        for (sample in partial) {
            assertIs<Verification.Rejected>(verifyOpenWitness(source, sample))
        }
    }

    @Test
    fun `verifyOpenWitness should reject a value in a domain hole`() {
        val domain = requireNotNull(IntDomain(0, 2).excludeValues(longArrayOf(1)))
        val source = Problem(0, 1, arrayOf(domain), emptyArray())
        val sample = Sample(booleanArrayOf(), longArrayOf(1))

        val checked = verifyOpenWitness(source, sample)

        assertIs<Verification.Rejected>(checked)
    }

    @Test
    fun `verifyOpenWitness should be indeterminate for wide coordinates`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 2)), emptyArray())
        val sample = Sample(booleanArrayOf(), longArrayOf(), exactInts = listOf(parseBigInt("9223372036854775808")))

        val checked = verifyOpenWitness(source, sample)

        assertIs<Verification.Indeterminate>(checked)
    }

    @Test
    fun `verifyOpenWitness should be independent of earlier cancelled or rejected checks`() {
        val source = Problem(0, 3, Array(3) { IntDomain(0, 9) }, arrayOf(Product(0, 1, 2)))
        val valid = Sample(booleanArrayOf(), longArrayOf(2, 3, 6))
        val invalid = Sample(booleanArrayOf(), longArrayOf(2, 3, 5))

        assertIs<Verification.Indeterminate>(verifyOpenWitness(source, valid, Cancellation { true }))
        assertIs<Verification.Rejected>(verifyOpenWitness(source, invalid))
        assertIs<Verification.Accepted<*, *>>(verifyOpenWitness(source, valid))
    }
}
