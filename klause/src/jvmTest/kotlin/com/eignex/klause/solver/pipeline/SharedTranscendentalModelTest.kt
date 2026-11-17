package com.eignex.klause.solver.pipeline

import com.eignex.klause.arithmetic.real.ExactRealExpression
import com.eignex.klause.arithmetic.real.ExactRealValue
import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.ExactRealEquality
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceBoolId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.TranscendentalExpression
import com.eignex.klause.ir.TranscendentalFormula
import com.eignex.klause.ir.TranscendentalFunction
import com.eignex.klause.ir.TranscendentalProblem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.theory.Encoder
import com.eignex.klause.theory.EncoderProvider
import com.eignex.klause.theory.EncoderSupport
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.WorkMeter
import com.eignex.util.numbers.BigFraction
import org.junit.BeforeClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SharedTranscendentalModelTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun initializeRuntime() = FiniteTranscendentalWorkerTest.initializeRuntime()
    }

    @Test
    fun `zero function allowance should stop a finite peer without publishing a witness`() {
        val source = TranscendentalProblem(TranscendentalFormula.Constant(true), mapOf("x" to 0),
            emptyMap(), emptyMap()).withFactors(Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray()))

        val execution = OpenTheoryPipeline.execute(source.pipelineRequest(),
            TranscendentalTheoryParams(maximumWork = 0), FiniteEngine.MIXED)

        assertIs<OpenTheoryResult.Unknown>(execution.result)
        assertEquals(0L, execution.workSpent)
    }

    @Test
    fun `original source rejection should withhold a composed function witness`() {
        val source = TranscendentalProblem(TranscendentalFormula.Constant(true), mapOf("x" to 0),
            emptyMap(), emptyMap()).withFactors(Problem(0, 1, arrayOf(IntDomain(0, 0)), emptyArray()))
        val attachment = SourceAttachment(source, source.factorModel!!.settings).withWitnessValidator { _, _ ->
            SourceEvidenceCheck.Rejected("additional source relation")
        }
        val invocation = SourceSetup.prepare(SourceSetupRequest(attachment,
            ExecutionOperation.SATISFY, FiniteEngine.FIXED))

        val execution = assertIs<SourceExecutionResult.Transcendental>(
            SourceSetup.execute(SourceExecutionRequest(invocation)))

        assertIs<OpenTheoryResult.Unknown>(execution.result.result)
    }

    @Test
    fun `symbolic real factors should retain native globals through ordinary source setup`() {
        val factors = Problem(0, 2, arrayOf(IntDomain(0, 1), IntDomain(0, 1)), arrayOf(
            AllDifferent(intArrayOf(0, 1), 0, 2),
            ExactRealEquality(SourceRealId(0), ExactRealValue(ExactRealExpression.Pi))),
            numRealVars = 1, realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(factors),
            ExecutionOperation.SATISFY, FiniteEngine.FIXED))

        val execution = assertIs<SourceExecutionResult.Transcendental>(
            SourceSetup.execute(SourceExecutionRequest(invocation)))

        val point = assertIs<OpenTheoryResult.Sat>(execution.result.result).assignment
        assertTrue(point.intValue(0) != point.intValue(1))
        assertEquals("pi", point.realValue(0))
    }

    @Test
    fun `finite propagation should refute invalid globals before accepting a function witness`() {
        val factors = Problem(0, 2, arrayOf(IntDomain(0, 0), IntDomain(0, 0)),
            arrayOf(AllDifferent(intArrayOf(0, 1), 0, 1)))
        val source = TranscendentalProblem(TranscendentalFormula.Constant(true),
            mapOf("x" to 0, "y" to 1), emptyMap(), emptyMap()).withFactors(factors)

        val execution = OpenTheoryPipeline.execute(source.pipelineRequest(), engine = FiniteEngine.FIXED)

        assertIs<OpenTheoryResult.Unsat>(execution.result)
    }

    @Test
    fun `private Boolean numeric definitions should not appear in source witnesses`() {
        val factors = Problem(1, 0, emptyArray(), arrayOf(ExactSearchRow.of(
            intArrayOf(Term.ofLit(Lit.make(0, true)), Term.ofRealVar(0)),
            listOf(BigFraction.ofLong(2), BigFraction.MINUS_ONE), LinearOp.EQ, BigFraction.ZERO)),
            numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(2.0))
        val source = TranscendentalProblem(TranscendentalFormula.BooleanVariable(SourceBoolId(0)),
            emptyMap(), mapOf("y" to 0), mapOf("b" to 0)).withFactors(factors)

        val execution = OpenTheoryPipeline.execute(source.pipelineRequest(), engine = FiniteEngine.FIXED)

        val point = assertIs<TranscendentalAssignment>(assertIs<OpenTheoryResult.Sat>(execution.result).assignment)
        assertTrue(point.boolValue(0))
        assertEquals("2", point.realValue(0))
        assertEquals(1, point.sample.numRealVars)
    }

    @Test
    fun `open integer globals should have an explicit unavailable finite peer`() {
        val model = Problem(0, 2, arrayOf(IntDomain(0, 1), IntDomain(0, 1)),
            arrayOf(AllDifferent(intArrayOf(0, 1), 0, 2)), openIntHi = booleanArrayOf(true, false))
        val source = TranscendentalProblem(TranscendentalFormula.Constant(true), mapOf("x" to 0, "z" to 1),
            emptyMap(), emptyMap()).withFactors(model)

        val plan = source.prepareExecutionPlan()

        assertEquals(PreparedPlanDecline.OPEN_FINITE_INTEGER, plan.decline)
        assertFalse(plan.capabilities.admission(FiniteEngine.MIXED).runnable)
        assertTrue(model.intBounds.isOpenUpper(0))
    }

    @Test
    fun `an unsupported factor should prevent installing its arithmetic prefix`() {
        val row = ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE),
            LinearOp.GE, BigFraction.ZERO)
        val other = ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE),
            LinearOp.LE, BigFraction.ONE)
        val model = Problem(0, 0, emptyArray(), arrayOf(row, other), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))
        var installed = 0
        val providers = FactorProjectionProviders(encoder = EncoderProvider { factor, _ ->
            if (factor === other) Encoder(EncoderSupport.UNSUPPORTED)
            else Encoder(EncoderSupport.EQUIVALENT) { _, encoding ->
                installed++
                encoding.polynomial(PolynomialRealFormula.Constant(true))
                null
            }
        })
        val source = functions(TranscendentalFormula.Constant(true)).withFactors(model)

        val plan = source.prepareExecutionPlan(providers)

        assertEquals(0, installed)
        val decline = plan.capabilities.admission(ExecutionStrategy.COMPLETE).declines.single()
        assertEquals(1, decline.factorIndex)
        assertEquals(PreparedPlanDecline.POLYNOMIAL_ENCODING, decline.preparation)
    }

    @Test
    fun `preparation interruption should retain its resource cause`() {
        val model = Problem(0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))
        val source = functions(TranscendentalFormula.Constant(true)).withFactors(model)
        var charged = 0L
        val stop = object : ExecutionControl() {
            override fun isCancelled(): Boolean = charged >= 1
            override val accounting: WorkMeter get() = WorkMeter { charged += it }
        }

        val plan = source.prepareExecutionPlan(cancellation = stop)

        assertEquals(PreparedPlanDecline.RESOURCE, plan.decline)
        assertFalse(plan.capabilities.admission(FiniteEngine.MIXED).runnable)
        assertTrue(charged > 0)
    }

    @Test
    fun `unresolved completion at the only finite point should keep unknown`() {
        val x = TranscendentalExpression.Variable(SourceRealId(0))
        val source = functions(TranscendentalFormula.Atom(
            TranscendentalExpression.Function(TranscendentalFunction.EXP, x), PolynomialRelation.EQ, x))
            .withFactors(Problem(0, 0, emptyArray(), emptyArray(), numRealVars = 1,
                realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
                realUpper = doubleArrayOf(Double.POSITIVE_INFINITY)))

        val result = OpenTheoryPipeline.execute(source.pipelineRequest(),
            TranscendentalTheoryParams(maximumWork = 2000, maximumDepth = 0), FiniteEngine.FIXED)

        assertIs<OpenTheoryResult.Unknown>(result.result)
    }

    @Test
    fun `function composition should decline operations without installed procedures`() {
        val source = functions(TranscendentalFormula.Constant(true)).withFactors(Problem(
            0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(0.0)))

        val plan = source.prepareExecutionPlan()

        assertEquals(setOf(ExecutionOperation.SATISFY), plan.capabilities.supportedOperations)
        assertFalse(plan.capabilities.admission(ExecutionStrategy.COMPLETE, ExecutionOperation.EXACT_COUNT).runnable)
    }

    @Test
    fun `separate invocation pins should not retain a refuted finite choice`() {
        val formula = TranscendentalFormula.Atom(TranscendentalExpression.Variable(SourceRealId(0)),
            PolynomialRelation.EQ, constant(1))
        val source = TranscendentalProblem(formula, mapOf("x" to 0), emptyMap(), emptyMap())
            .withFactors(Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray()))
        val request = source.pipelineRequest()
        val first = TranscendentalTheoryParams(strategy = PolynomialStrategyParams(
            assumptions = Assumptions.None.withInt(0, 0)))
        val second = first.copy(strategy = first.strategy.copy(assumptions = Assumptions.None.withInt(0, 1)))

        val refuted = OpenTheoryPipeline.execute(request, first, FiniteEngine.FIXED)
        val accepted = OpenTheoryPipeline.execute(request, second, FiniteEngine.FIXED)

        assertIs<OpenTheoryResult.Unsat>(refuted.result)
        assertEquals("1", assertIs<OpenTheoryResult.Sat>(accepted.result).assignment.intValue(0))
    }

    private fun functions(formula: TranscendentalFormula) =
        TranscendentalProblem(formula, emptyMap(), mapOf("y" to 0), emptyMap())

    private fun constant(value: Long): TranscendentalExpression.Constant {
        val exact = BigFraction.ofLong(value)
        return TranscendentalExpression.Constant(PolynomialExpression.Constant.of(exact.num, exact.den))
    }
}
