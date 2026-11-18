package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.ir.FloatSemantics
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.lp.engine.FloatDeductionCertificate
import com.eignex.klause.lp.engine.LpBoundSlack
import com.eignex.klause.lp.engine.RevisedSimplex
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.relaxation.RootDomains
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.validation.verifyCapturedRealTolerance
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.bigIntOf
import org.junit.BeforeClass
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class RealProductLpProjectionToleranceTest(
    private val reading: String,
    private val policy: SourceTolerance,
    private val negative: Boolean,
    private val lowerSide: Boolean,
    private val fixed: Boolean,
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} negative={2} lower={3} fixed={4}")
        fun variants(): List<Array<Any>> = listOf(
            arrayOf<Any>("absolute", SourceTolerance(SemanticRational.ONE)),
            arrayOf<Any>("relative", SourceTolerance(relative = SemanticRational.of(BIG_ONE, bigIntOf(4)))),
            arrayOf<Any>("exact", SourceTolerance()),
        ).flatMap { reading ->
            listOf(false, true).flatMap { negative ->
                listOf(false, true).flatMap { lower -> listOf(false, true).map { reading + negative + lower + it } }
            }
        }

        @JvmStatic
        @BeforeClass
        fun initializeRelaxation() {
            val source = Problem(
                0, 1, arrayOf(IntDomain(1, 3)), listOf(RealProduct(0, 0, 1, 1.0, 2.0)),
                numRealVars = 2, realLower = doubleArrayOf(1.0, -32.0), realUpper = doubleArrayOf(2.0, 32.0),
                semantics = SourceSemantics(SourceFormat.IR, floats = FloatSemantics.Continuous,
                    feasibilityTolerance = SourceTolerance(SemanticRational.ONE)).resolve(),
            )
            val relaxation = CpToLpRelaxation(source,
                LinearObjective(realCoefficients = doubleArrayOf(0.0, -1.0))).build(RootDomains(source))
            RevisedSimplex(relaxation.model).use { it.solve() }
            verifyCapturedRealTolerance(source, Sample(booleanArrayOf(), longArrayOf(3), doubleArrayOf(3.0, 10.0)),
                SourceTolerance(SemanticRational.ONE), ExecutionControl.Never)
        }
    }

    @Test
    fun `envelope bound should cover a source accepted product`() {
        val sign = if (negative) -1L else 1L
        val direction = sign * if (lowerSide) -1L else 1L
        val absolute = BigFraction.of(policy.absolute.numerator, policy.absolute.denominator).toDouble()
        val relative = BigFraction.of(policy.relative.numerator, policy.relative.denominator).toDouble()
        val endpoint = if (lowerSide) 1.0 else 2.0
        val operand = when {
            policy.isExact -> endpoint
            relative > 0.0 -> if (lowerSide) 0.75 else 2.5
            else -> if (lowerSide) 0.0 else 3.0
        }
        val integer = 3L * sign
        val product = when {
            policy.isExact -> integer * operand
            relative > 0.0 -> sign * if (lowerSide) 1.5 else 12.5
            else -> integer * operand + direction
        }
        val problem = Problem(
            0, 1, arrayOf(when {
                fixed -> IntDomain(integer, integer)
                negative -> IntDomain(-3, -1)
                else -> IntDomain(1, 3)
            }),
            listOf(RealProduct(0, 0, 1, 1.0, 2.0)),
            numRealVars = 2, realLower = doubleArrayOf(1.0, -32.0), realUpper = doubleArrayOf(2.0, 32.0),
            semantics = SourceSemantics(
                SourceFormat.IR, floats = FloatSemantics.Continuous, feasibilityTolerance = policy,
            ).resolve(),
        )
        val witness = Sample(booleanArrayOf(), longArrayOf(integer), reals = doubleArrayOf(operand, product))
        assertTrue(
            verifyCapturedRealTolerance(problem, witness, policy, ExecutionControl.Never) is Verification.Accepted,
        )
        val integerCost = direction * endpoint.toLong()
        val realCost = -direction.toDouble()
        val objective = LinearObjective(intCoefficients = longArrayOf(integerCost),
            realCoefficients = doubleArrayOf(0.0, realCost))
        val relaxation = CpToLpRelaxation(problem, objective).build(RootDomains(problem))
        val bound = RevisedSimplex(relaxation.model).use { solver ->
            val result = assertNotNull(solver.solve())
            assertNotNull(FloatDeductionCertificate.of(
                relaxation.model, result.duals, slack = LpBoundSlack(absolute, relative),
            )).lowerBound
        }

        val value = integerCost * integer + realCost * product
        assertTrue(bound <= value, "$reading bound $bound excludes source objective $value")
        if (policy.isExact) assertTrue(bound > value - 1e-4, "exact bound $bound optimum $value")
    }
}
