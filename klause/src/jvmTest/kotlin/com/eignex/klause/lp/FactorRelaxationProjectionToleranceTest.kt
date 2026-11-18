package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.Power
import com.eignex.klause.factor.arithmetic.RealAbs
import com.eignex.klause.factor.arithmetic.RealArrayMinMax
import com.eignex.klause.factor.table.RealElement
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FloatSemantics
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.lp.engine.FloatDeductionCertificate
import com.eignex.klause.lp.engine.LpBoundSlack
import com.eignex.klause.lp.engine.RevisedSimplex
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.relaxation.RelaxationDomains
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
class FactorRelaxationProjectionToleranceTest(
    private val reading: String,
    private val factors: Array<Factor>,
    private val lower: DoubleArray,
    private val upper: DoubleArray,
    private val values: DoubleArray,
    private val costs: DoubleArray,
    private val relative: SemanticRational,
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun variants(): List<Array<Any>> {
            val quarter = SemanticRational.of(BIG_ONE, bigIntOf(4))
            val half = SemanticRational.of(BIG_ONE, bigIntOf(2))
            val guard = intArrayOf(Lit.make(0, true))
            val atLeastOne = Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0),
                LinearOp.GE, 1.0)
            val atMostMinusOne = Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0),
                LinearOp.LE, -1.0)
            val atMostOne = Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0),
                LinearOp.LE, 1.0)
            val secondAtLeastOne = Linear(intArrayOf(), doubleArrayOf(), intArrayOf(1), doubleArrayOf(1.0),
                LinearOp.GE, 1.0)
            return listOf(
                arrayOf("linear source row", arrayOf<Factor>(
                    Linear(intArrayOf(), doubleArrayOf(), intArrayOf(2, 0), doubleArrayOf(1.0, -1.0),
                        LinearOp.EQ, 0.0), atLeastOne),
                    doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(1.0, 0.0, 4.0),
                    doubleArrayOf(0.75, 0.0, 0.46875), doubleArrayOf(0.0, 0.0, 1.0), quarter),
                arrayOf("mixed source row", arrayOf<Factor>(
                    Linear(intArrayOf(0), doubleArrayOf(-1.0), intArrayOf(2), doubleArrayOf(1.0),
                        LinearOp.EQ, 0.0),
                    Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1)),
                    doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(0.0, 0.0, 4.0),
                    doubleArrayOf(0.0, 0.0, 0.625), doubleArrayOf(0.0, 0.0, 1.0), quarter),
                arrayOf("absolute value", arrayOf<Factor>(RealAbs(0, 2), atLeastOne),
                    doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(1.0, 0.0, 4.0),
                    doubleArrayOf(0.75, 0.0, 0.46875), doubleArrayOf(0.0, 0.0, 1.0), quarter),
                arrayOf("negative absolute value", arrayOf<Factor>(RealAbs(0, 2), atMostMinusOne),
                    doubleArrayOf(-1.0, 0.0, 0.0), doubleArrayOf(0.0, 0.0, 4.0),
                    doubleArrayOf(-0.75, 0.0, 0.46875), doubleArrayOf(0.0, 0.0, 1.0), quarter),
                arrayOf("maximum", arrayOf<Factor>(RealArrayMinMax(2, intArrayOf(0, 1), true), atLeastOne),
                    doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(1.0, 0.0, 4.0),
                    doubleArrayOf(0.75, 0.0, 0.46875), doubleArrayOf(0.0, 0.0, 1.0), quarter),
                arrayOf("minimum", arrayOf<Factor>(RealArrayMinMax(2, intArrayOf(0, 1), false), atMostOne),
                    doubleArrayOf(0.0, 2.0, 0.0), doubleArrayOf(2.0, 2.0, 4.0),
                    doubleArrayOf(1.25, 2.0, 1.75), doubleArrayOf(0.0, 0.0, -1.0), quarter),
                arrayOf("variable selection", arrayOf<Factor>(RealElement(0, 2, intArrayOf(0)), atLeastOne),
                    doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(1.0, 0.0, 4.0),
                    doubleArrayOf(0.75, 0.0, 0.46875), doubleArrayOf(0.0, 0.0, 1.0), quarter),
                arrayOf("constant selection", arrayOf<Factor>(RealElement(0, 2, doubleArrayOf(1.0))),
                    doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(0.0, 0.0, 4.0),
                    doubleArrayOf(0.0, 0.0, 1.3125), doubleArrayOf(0.0, 0.0, -1.0), quarter),
                arrayOf("choice product", arrayOf<Factor>(
                    ContinuousProduct(0, 1, 2, 0, doubleArrayOf(1.0), guard), secondAtLeastOne),
                    doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(1.0, 1.0, 4.0),
                    doubleArrayOf(1.0, 0.75, 0.46875), doubleArrayOf(0.0, 0.0, 1.0), quarter),
                arrayOf("choice operand", arrayOf<Factor>(ContinuousProduct(0, 1, 2, 0, doubleArrayOf(1.0), guard)),
                    doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(2.0, 0.0, 0.0),
                    doubleArrayOf(1.25, 0.0, 0.0), doubleArrayOf(-1.0, 0.0, 0.0), quarter),
                arrayOf("choice result alias", arrayOf<Factor>(
                    ContinuousProduct(0, 1, 1, 0, doubleArrayOf(2.0), guard)),
                    doubleArrayOf(2.0, 0.0, 0.0), doubleArrayOf(2.0, 2.0, 0.0),
                    doubleArrayOf(2.0, 1.5, 0.0), doubleArrayOf(0.0, -1.0, 0.0), half),
                arrayOf("zero power", arrayOf<Factor>(Power(Power.Coordinate.Real(SourceRealId(0)), 0,
                    Power.Coordinate.Real(SourceRealId(2)))),
                    doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(0.0, 0.0, 4.0),
                    doubleArrayOf(0.0, 0.0, 1.3125), doubleArrayOf(0.0, 0.0, -1.0), quarter),
                arrayOf("first power", arrayOf<Factor>(Power(Power.Coordinate.Real(SourceRealId(0)), 1,
                    Power.Coordinate.Real(SourceRealId(2))), atLeastOne),
                    doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(1.0, 0.0, 4.0),
                    doubleArrayOf(0.75, 0.0, 0.46875), doubleArrayOf(0.0, 0.0, 1.0), quarter),
            )
        }

        @JvmStatic
        @BeforeClass
        fun initializeRelaxation() {
            val policy = SourceTolerance(relative = SemanticRational.of(BIG_ONE, bigIntOf(4)))
            val source = Problem(
                1, 1, arrayOf(IntDomain(1, 1)), listOf(
                    RealAbs(0, 2),
                    Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.GE, 1.0),
                ), numRealVars = 3, realLower = doubleArrayOf(0.0, 0.0, 0.0),
                realUpper = doubleArrayOf(1.0, 0.0, 4.0),
                semantics = SourceSemantics(SourceFormat.IR, floats = FloatSemantics.Continuous,
                    feasibilityTolerance = policy).resolve(),
            )
            val relaxation = CpToLpRelaxation(source,
                LinearObjective(realCoefficients = doubleArrayOf(0.0, 0.0, 1.0))).build(RootDomains(source))
            RevisedSimplex(relaxation.model).use { it.solve() }
            verifyCapturedRealTolerance(source,
                Sample(booleanArrayOf(true), longArrayOf(1), doubleArrayOf(0.75, 0.0, 0.46875)),
                policy, ExecutionControl.Never)
        }
    }

    @Test
    fun `projected bound should cover a source accepted relative tolerance point`() {
        val policy = SourceTolerance(relative = relative)
        val problem = Problem(
            1, 1, arrayOf(if (factors.any { it is RealElement }) IntDomain(1, 1) else IntDomain(0, 1)), factors,
            numRealVars = 3, realLower = lower, realUpper = upper,
            semantics = SourceSemantics(SourceFormat.IR, floats = FloatSemantics.Continuous,
                feasibilityTolerance = policy).resolve(),
        )
        val witness = Sample(booleanArrayOf(true), longArrayOf(1), values)
        assertTrue(
            verifyCapturedRealTolerance(problem, witness, policy, ExecutionControl.Never) is Verification.Accepted,
            reading,
        )
        val domains = object : RelaxationDomains by RootDomains(problem) {
            override fun boolValue(varId: Int): Boolean = true
        }
        val relaxation = CpToLpRelaxation(problem, LinearObjective(realCoefficients = costs)).build(domains)

        val bound = RevisedSimplex(relaxation.model).use { solver ->
            val result = assertNotNull(solver.solve())
            assertNotNull(FloatDeductionCertificate.of(relaxation.model, result.duals,
                slack = LpBoundSlack(0.0, BigFraction.of(relative.numerator, relative.denominator).toDouble())))
                .lowerBound
        }

        val value = costs.indices.sumOf { costs[it] * values[it] }
        assertTrue(bound <= value, "$reading bound $bound excludes source objective $value")
    }
}
