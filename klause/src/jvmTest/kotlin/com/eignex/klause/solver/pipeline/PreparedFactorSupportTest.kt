package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.ExactRealEquality
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.codec.factorCodecSamples
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.ir.TranscendentalExpression
import com.eignex.klause.ir.TranscendentalFormula
import com.eignex.klause.ir.TranscendentalFunction
import com.eignex.klause.ir.TranscendentalProblem
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.theory.EncodingTarget
import org.junit.BeforeClass
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class PreparedFactorSupportTest(
    private val name: String,
    private val factor: Factor,
    private val engine: FiniteEngine,
) {
    companion object {
        @JvmStatic
        @BeforeClass
        fun initializeRuntime() = FiniteTranscendentalWorkerTest.initializeRuntime()

        @JvmStatic
        @Parameterized.Parameters(name = "{0} {2}")
        fun factors(): List<Array<Any>> = factorCodecSamples().flatMapIndexed { index, (key, factor) ->
            FiniteEngine.entries.map { arrayOf("$key-$index", factor, it) }
        }
    }

    @Test
    fun `bounded factors should retain enforcing owners beside affine arithmetic`() {
        val source = source(arrayOf(factor, Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(3),
            doubleArrayOf(-1.0), LinearOp.EQ, 0.0)))

        val plan = source.prepareExecutionPlan()

        assertSatisfaction(plan, factor !is ExactRealEquality)
        assertOwner(plan)
    }

    @Test
    fun `bounded factors should retain enforcing owners beside polynomial arithmetic`() {
        val plan = source(arrayOf(factor, ContinuousProduct(2, 2, 3))).prepareExecutionPlan()

        assertSatisfaction(plan, factor !is ExactRealEquality)
        assertOwner(plan)
    }

    @Test
    fun `polynomial peers should admit only their installed source operations`() {
        val plan = source(arrayOf(factor, ContinuousProduct(2, 2, 3))).prepareExecutionPlan()
            .withObjective(LinearObjective(intCoefficients = longArrayOf(1)))

        for (operation in ExecutionOperation.entries) {
            val admission = plan.capabilities.admission(engine, operation)
            val expected = factor !is ExactRealEquality && operation in sourceOperations
            assertEquals(expected, admission.runnable, "$name $engine $operation")
        }
    }

    @Test
    fun `bounded factors should retain enforcing owners beside functions`() {
        val factorSource = source(arrayOf(factor))
        val function = TranscendentalProblem(TranscendentalFormula.Atom(
            TranscendentalExpression.Function(TranscendentalFunction.EXP,
                TranscendentalExpression.Variable(SourceRealId(8))),
            PolynomialRelation.EQ, TranscendentalExpression.Variable(SourceRealId(9))),
            (0 until 6).associate { "i$it" to it },
            (0 until 4).associate { "r$it" to (6 + it) },
            (0 until 6).associate { "b$it" to it }).withFactors(factorSource)

        val plan = function.prepareExecutionPlan()

        assertSatisfaction(plan, true)
        assertOwner(plan)
        for (operation in ExecutionOperation.entries - ExecutionOperation.SATISFY) {
            val admission = plan.capabilities.admission(engine, operation)
            assertFalse(admission.runnable, "$name $engine $operation")
            assertTrue(admission.declines.isNotEmpty())
        }
    }

    @Test
    fun `open integer polynomial traversal should require every source relation encoded`() {
        val source = source(arrayOf(factor, ContinuousProduct(2, 2, 3)), open = true)
        val selected = FactorProjectionProviders.Default.prepare(source)
        val nativeOnly = setOf("exactRealEquality", "xor", "gaussianXor", "reifiedCardinality", "allDifferentTuples",
            "globalCardinality", "nValue", "sort", "valuePrecede", "lexLess", "inverse",
            "symmetricAllDifferent", "circuit", "cumulative", "diffn", "table", "regular", "mdd", "symmetryHandling")
        val completeEncoding = name.substringBeforeLast("-") !in nativeOnly
        assertEquals(completeEncoding, selected.encoder(0, EncodingTarget.POLYNOMIAL).enforcesSource)

        val plan = source.prepareExecutionPlan()

        assertSatisfaction(plan, completeEncoding)
        assertOwner(plan)
        if (!completeEncoding) assertNotNull(plan.decline)
    }

    private fun source(factors: Array<Factor>, open: Boolean = false): Problem = Problem(
        6, 6, Array(6) { IntDomain(-10, 10) }, factors,
        openIntLo = BooleanArray(6) { open }, openIntHi = BooleanArray(6) { open },
        numRealVars = 4, realLower = DoubleArray(4) { -5.0 }, realUpper = DoubleArray(4) { 5.0 })

    private fun assertSatisfaction(plan: PreparedSourcePlan, expected: Boolean) {
        val admission = plan.capabilities.admission(engine)
        assertEquals(expected, admission.runnable, "$name $engine ${plan.kind} ${plan.decline}")
        if (!expected) {
            assertTrue(admission.declines.isNotEmpty())
            assertTrue(admission.claimAuthorities.isEmpty())
        }
    }

    private fun assertOwner(plan: PreparedSourcePlan) {
        assertEquals(1, plan.components.count { 0 in it.factorIndices }, name)
        val owner = plan.factorOwner(0)
        if (plan.capabilities.admission(ExecutionStrategy.COMPLETE).runnable) {
            assertTrue(0 in owner.participatingFactorIndices, name)
            if (factor.variables.reals.isEmpty() && plan.kind != PreparedExecutionKind.NONLINEAR_INTEGER) {
                assertTrue(owner.responsibility in setOf(ComponentResponsibility.FINITE,
                    ComponentResponsibility.BOOLEAN), name)
            }
        }
    }
}
