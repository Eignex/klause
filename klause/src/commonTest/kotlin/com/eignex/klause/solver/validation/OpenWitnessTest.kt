package com.eignex.klause.solver.validation

import com.eignex.klause.factor.arithmetic.Power
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.arithmetic.RealArrayMinMax
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.propagation.BuiltInPropagationProjectionProvider
import com.eignex.klause.propagation.PropagationProblem
import com.eignex.klause.propagation.PropagationProjection
import com.eignex.klause.propagation.PropagationProjectionProvider
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.AssignmentCheckCapability
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.PropagationCapability
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertIs

class OpenWitnessTest {
    @Test
    fun `real min max points should be checked before discrete propagation`() {
        for (maximum in listOf(false, true)) {
            val source = Problem(0, 0, emptyArray(), arrayOf(RealArrayMinMax(2, intArrayOf(0, 1), maximum)),
                numRealVars = 3, realLower = doubleArrayOf(0.0, 1.0, 0.0),
                realUpper = doubleArrayOf(2.0, 3.0, 3.0))
            val point = Sample(booleanArrayOf(), longArrayOf(),
                reals = doubleArrayOf(0.0, 1.0, if (maximum) 1.0 else 0.0),
                exactReals = listOf(BigFraction.ZERO, BigFraction.ONE,
                    if (maximum) BigFraction.ONE else BigFraction.ZERO))

            val checked = verifyOpenWitness(source, point)

            assertIs<Verification.Accepted<*, *>>(checked)
        }
    }

    @Test
    fun `invalid real min max points should be rejected before discrete propagation`() {
        val source = Problem(0, 0, emptyArray(), arrayOf(RealArrayMinMax(2, intArrayOf(0, 1), true)),
            numRealVars = 3, realLower = doubleArrayOf(0.0, 1.0, 0.0), realUpper = doubleArrayOf(2.0, 3.0, 3.0))
        val point = Sample(booleanArrayOf(), longArrayOf(), reals = doubleArrayOf(0.0, 1.0, 0.0),
            exactReals = listOf(BigFraction.ZERO, BigFraction.ONE, BigFraction.ZERO))

        val checked = verifyOpenWitness(source, point)

        assertIs<Verification.Rejected>(checked)
    }

    @Test
    fun `valid real relations should not hide a violated discrete product`() {
        val source = Problem(0, 3, Array(3) { IntDomain(0, 9) },
            arrayOf(RealArrayMinMax(2, intArrayOf(0, 1), true), Product(0, 1, 2)),
            numRealVars = 3, realLower = doubleArrayOf(0.0, 1.0, 0.0), realUpper = doubleArrayOf(2.0, 3.0, 3.0))
        val point = Sample(booleanArrayOf(), longArrayOf(2, 3, 5), reals = doubleArrayOf(0.0, 1.0, 1.0),
            exactReals = listOf(BigFraction.ZERO, BigFraction.ONE, BigFraction.ONE))

        val checked = verifyOpenWitness(source, point)

        assertIs<Verification.Rejected>(checked)
    }

    @Test
    fun `cross domain relations should be checked before discrete projection`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 3)), arrayOf(RealProduct(0, 0, 1, 0.0, 2.0)),
            numRealVars = 2, realLower = doubleArrayOf(0.0, 0.0), realUpper = doubleArrayOf(2.0, 6.0))
        val point = Sample(booleanArrayOf(), longArrayOf(2), reals = doubleArrayOf(1.0, 3.0),
            exactReals = listOf(BigFraction.ONE, BigFraction.ofLong(3)))

        val checked = verifyOpenWitness(source, point)

        assertIs<Verification.Rejected>(checked)
    }

    @Test
    fun `a real relation without a complete checker should remain indeterminate`() {
        val relation = Power(Power.Coordinate.Real(SourceRealId(0)), 2, Power.Coordinate.Real(SourceRealId(1)))
        val source = Problem(0, 0, emptyArray(), arrayOf(relation), numRealVars = 2,
            realLower = doubleArrayOf(0.0, 0.0), realUpper = doubleArrayOf(2.0, 4.0))
        val point = Sample(booleanArrayOf(), longArrayOf(), reals = doubleArrayOf(1.0, 1.0),
            exactReals = listOf(BigFraction.ONE, BigFraction.ONE))

        val checked = verifyOpenWitness(source, point)

        assertIs<Verification.Indeterminate>(checked)
    }

    @Test
    fun `discrete point checking should retain captured provider factories`() {
        val source = Problem(0, 3, Array(3) { IntDomain(0, 9) },
            arrayOf(RealArrayMinMax(2, intArrayOf(0, 1), true), Product(0, 1, 2)),
            numRealVars = 3, realLower = doubleArrayOf(0.0, 1.0, 0.0), realUpper = doubleArrayOf(2.0, 3.0, 3.0))
        var selecting = true
        val providers = FactorProjectionProviders(propagation = PropagationProjectionProvider { factor ->
            check(selecting)
            if (factor is Product) {
                PropagationProjection(PropagationCapability.SOUND_FILTERING, AssignmentCheckCapability.EXACT) { view ->
                    BuiltInPropagationProjectionProvider.create(view, null)
                }
            } else BuiltInPropagationProjectionProvider.select(factor)
        })
        val captured = providers.prepare(source)
        selecting = false
        val point = Sample(booleanArrayOf(), longArrayOf(2, 3, 6), reals = doubleArrayOf(0.0, 1.0, 1.0),
            exactReals = listOf(BigFraction.ZERO, BigFraction.ONE, BigFraction.ONE))

        val checked = verifyOpenWitness(source, point, Cancellation.Never, PropagationProblem(source, captured))

        assertIs<Verification.Accepted<*, *>>(checked)
    }

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
