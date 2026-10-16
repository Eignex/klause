package com.eignex.klause.solver.validation

import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.FloatSemantics
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.propagation.bake
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertIs

class ProjectedSourceWitnessTest {
    @Test
    fun `an additional callback cannot establish a violated original product`() {
        val source = Problem(0, 0, emptyArray<IntDomain>(), arrayOf(ContinuousProduct(0, 0, 1)), numRealVars = 2,
            realLower = doubleArrayOf(0.0, 0.0), realUpper = doubleArrayOf(1.0, 1.0))
        val checker = source.withFactors(emptyArray()).bake()
        val point = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(0.5, 0.0),
            exactReals = listOf(BigFraction.of(bigIntOf(1), bigIntOf(2)), BigFraction.ZERO))

        val result = verifyProjectedSourceWitness(source, checker, point, Cancellation.Never) { true }

        assertIs<Verification.Rejected>(result)
    }

    @Test
    fun `captured linear tolerance should admit a covered row independently of an extra callback`() {
        val source = Problem(0, 0, emptyArray<IntDomain>(), arrayOf(Linear(intArrayOf(), doubleArrayOf(),
            intArrayOf(0), doubleArrayOf(1.0), LinearOp.EQ, 0.25)), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0),
            semantics = SourceSemantics(SourceFormat.IR, floats = FloatSemantics.Grid(2, 1000),
                feasibilityTolerance = SourceTolerance(SemanticRational.of(bigIntOf(3), bigIntOf(10)))).resolve())
        val checker = source.withFactors(emptyArray()).bake()
        val point = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(0.0), exactReals = listOf(BigFraction.ZERO))
        for (filter in listOf<((Sample) -> Boolean)?>(null, { true })) {
            val result = verifyProjectedSourceWitness(source, checker, point, Cancellation.Never, filter)

            assertIs<Verification.Accepted<*, *>>(result)
        }
    }

    @Test
    fun `an uncovered tolerant nonlinear relation should remain indeterminate despite an extra callback`() {
        val source = Problem(0, 0, emptyArray<IntDomain>(), arrayOf(ContinuousProduct(0, 0, 1)), numRealVars = 2,
            realLower = doubleArrayOf(0.0, 0.0), realUpper = doubleArrayOf(1.0, 1.0),
            semantics = SourceSemantics(SourceFormat.IR,
                feasibilityTolerance = SourceTolerance(SemanticRational.of(bigIntOf(3), bigIntOf(10)))).resolve())
        val checker = source.withFactors(emptyArray()).bake()
        val point = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(0.5, 0.0),
            exactReals = listOf(BigFraction.of(bigIntOf(1), bigIntOf(2)), BigFraction.ZERO))

        val result = verifyProjectedSourceWitness(source, checker, point, Cancellation.Never) { true }

        assertIs<Verification.Indeterminate>(result)
    }
}
