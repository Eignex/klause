package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.TranscendentalFormula
import com.eignex.klause.ir.TranscendentalProblem
import com.eignex.klause.localsearch.NoInvariant
import com.eignex.klause.localsearch.ScoringProjection
import com.eignex.klause.localsearch.ScoringProjectionProvider
import com.eignex.klause.lp.RelaxationProjectionProvider
import com.eignex.klause.propagation.BuiltInPropagationProjectionProvider
import com.eignex.klause.propagation.NoPropagator
import com.eignex.klause.propagation.PropagationProjection
import com.eignex.klause.propagation.PropagationProjectionProvider
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.AssignmentCheckCapability
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.PropagationCapability
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.ScoringCapability
import com.eignex.klause.theory.BuiltInEncoderProvider
import com.eignex.klause.theory.Encoder
import com.eignex.klause.theory.EncoderProvider
import com.eignex.klause.theory.EncoderSupport
import com.eignex.klause.theory.EncodingAffineExpression
import com.eignex.klause.theory.EncodingLinearTerm
import com.eignex.klause.theory.EncodingTarget
import com.eignex.klause.theory.LinearEncodingFormula
import com.eignex.util.numbers.BigFraction
import org.junit.BeforeClass
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class PreparedCustomMechanismsTest(
    private val route: String,
    private val mechanisms: String,
    private val engine: FiniteEngine,
) {
    companion object {
        @JvmStatic
        @BeforeClass
        fun initializeRuntime() {
            FiniteTranscendentalWorkerTest.initializeRuntime()
            val source = Problem(1, 2, Array(2) { IntDomain(0, 1) },
                arrayOf(Clause(intArrayOf(0)), AllDifferent(intArrayOf(0, 1), 0, 2)))
            val providers = FactorProjectionProviders(propagation = PropagationProjectionProvider { null })
            val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source),
                ExecutionOperation.SATISFY, FiniteEngine.FIXED, providers = providers))
            SourceSetup.execute(SourceExecutionRequest(invocation, ExecutionOptions()))
        }

        @JvmStatic
        @Parameterized.Parameters(name = "{0} {1} {2}")
        fun cases(): List<Array<Any>> = listOf("finite", "polynomial", "function").flatMap { route ->
            listOf("native", "no-scoring", "no-relaxation", "no-encoder", "no-propagation", "unsupported-propagation",
                "inert-propagation", "custom-check", "missing-check", "encoder-only", "encoder-auxiliary")
                .flatMap { mechanisms ->
                    FiniteEngine.entries.map { arrayOf(route, mechanisms, it) }
                }
        }
    }

    @Test
    fun `installed custom mechanisms should agree with execution admission`() {
        val clause = Clause(intArrayOf(0))
        val relation = if (mechanisms in setOf("custom-check", "missing-check", "encoder-only", "encoder-auxiliary")) {
            object : Factor by clause {
                override val linearForm: LinearForm? = null
            }
        } else clause
        val native = arrayOf(relation, AllDifferent(intArrayOf(0, 1), 0, 2))
        val factors = if (route == "polynomial") native + ContinuousProduct(0, 0, 0) else native
        val source = Problem(1, 2, Array(2) { IntDomain(0, 1) }, factors,
            numRealVars = if (route == "polynomial") 1 else 0,
            realLower = if (route == "polynomial") doubleArrayOf(0.0) else doubleArrayOf(),
            realUpper = if (route == "polynomial") doubleArrayOf(0.0) else doubleArrayOf())
        val providers = when (mechanisms) {
            "encoder-only", "encoder-auxiliary" -> FactorProjectionProviders(encoder = EncoderProvider {
                    factor,
                    target,
                ->
                if (factor === relation) {
                    val selected = BuiltInEncoderProvider.select(clause, target)
                    Encoder(selected.support) { _, encoding ->
                        if (mechanisms == "encoder-auxiliary" && target == EncodingTarget.LINEAR) {
                            val helper = encoding.realAuxiliary()
                            encoding.linearFormula(LinearEncodingFormula.Comparison(EncodingAffineExpression(listOf(
                                EncodingLinearTerm.auxiliary(helper, BigFraction.ONE),
                                EncodingLinearTerm.source(Term.ofLit(0), BigFraction.MINUS_ONE))),
                                LinearOp.EQ, BigFraction.ZERO))
                            encoding.linearFormula(LinearEncodingFormula.Comparison(EncodingAffineExpression(listOf(
                                EncodingLinearTerm.auxiliary(helper, BigFraction.ONE))), LinearOp.EQ, BigFraction.ONE))
                            null
                        } else selected.install(clause, encoding)
                    }
                } else BuiltInEncoderProvider.select(factor, target)
            })
            "no-scoring" -> FactorProjectionProviders(scoring = ScoringProjectionProvider { null })
            "no-relaxation" -> FactorProjectionProviders(relaxation = RelaxationProjectionProvider { null })
            "no-encoder" -> FactorProjectionProviders(encoder = EncoderProvider { _, _ ->
                Encoder(EncoderSupport.UNSUPPORTED)
            })
            "no-propagation" -> FactorProjectionProviders(propagation = PropagationProjectionProvider { null })
            "unsupported-propagation" -> FactorProjectionProviders(propagation = PropagationProjectionProvider {
                PropagationProjection(PropagationCapability.UNSUPPORTED, AssignmentCheckCapability.UNSUPPORTED) {
                    NoPropagator
                }
            })
            "inert-propagation", "missing-check" -> FactorProjectionProviders(
                propagation = PropagationProjectionProvider {
                    PropagationProjection(PropagationCapability.INERT, AssignmentCheckCapability.SOURCE_CERTIFICATION,
                        "The original source must certify the candidate.") { NoPropagator }
                })
            "custom-check" -> FactorProjectionProviders(propagation = PropagationProjectionProvider { factor ->
                if (factor === relation) PropagationProjection(PropagationCapability.SOUND_FILTERING,
                    AssignmentCheckCapability.EXACT) {
                    object : Propagator {
                        override fun propagate(state: PropagationState, factorId: Int): Boolean =
                            state.boolValues[0] != false
                    }
                } else BuiltInPropagationProjectionProvider.select(factor)
            }, scoring = ScoringProjectionProvider { factor ->
                if (factor === relation) ScoringProjection(ScoringCapability.INERT,
                    AssignmentCheckCapability.UNSUPPORTED, "The custom complete propagator checks candidates.") {
                        _,
                        _,
                    ->
                    NoInvariant
                } else FactorProjectionProviders.Default.scoring.select(factor)
            })
            else -> FactorProjectionProviders.Default
        }
        val attachment = if (route == "function") {
            SourceAttachment(TranscendentalProblem(TranscendentalFormula.Constant(true), mapOf("x" to 0, "y" to 1),
                emptyMap(), mapOf("b" to 0)).withFactors(source), source.settings)
        } else SourceAttachment(source)
        val invocation = SourceSetup.prepare(SourceSetupRequest(attachment, ExecutionOperation.SATISFY,
            engine, providers = providers))
        val expected = mechanisms != "missing-check" && !(mechanisms == "no-encoder" && route == "polynomial") &&
            !(mechanisms == "no-scoring" && engine in setOf(FiniteEngine.LOCAL_SEARCH, FiniteEngine.ALNS))
        assertEquals(expected, invocation.preparedPlan!!.capabilities.admission(engine).runnable,
            "$route $mechanisms $engine")

        var finiteSample: Sample? = null
        val controls = if (SourceSetup.executionKind(invocation) == SourceExecutionKind.FINITE) ExecutionOptions()
        else ExecutionOptions(ExecutionOption.maxFlips(16L), ExecutionOption.maxInstructions(100000L),
            ExecutionOption.alnsIterations(4))
        val execution = SourceSetup.execute(SourceExecutionRequest(invocation, controls),
            SourceExecutionCallbacks(finite = FiniteSolveCallbacks({ finiteSample = it }, {})))

        if (!expected) {
            when (execution) {
                is SourceExecutionResult.Declined -> Unit
                is SourceExecutionResult.Polynomial -> assertIs<OpenTheoryResult.Unknown>(execution.result.result)
                is SourceExecutionResult.Transcendental -> assertIs<OpenTheoryResult.Unknown>(execution.result.result)
                is SourceExecutionResult.Linear -> assertIs<OpenTheoryResult.Unknown>(
                    assertIs<OpenTheoryExecution.Satisfy>(execution.result).result)
                is SourceExecutionResult.Finite -> assertEquals(FiniteSolveVerdict.UNKNOWN,
                    assertIs<FiniteSolveOutcome.Completed>(execution.result.outcome).verdict)
                else -> error("$route $mechanisms $engine: $execution")
            }
            return
        }
        val point = when (execution) {
            is SourceExecutionResult.Finite -> {
                val outcome = assertIs<FiniteSolveOutcome.Completed>(execution.result.outcome)
                assertEquals(FiniteSolveVerdict.SAT, outcome.verdict)
                OpenTheoryAssignment.Sampled(requireNotNull(outcome.bestSample ?: finiteSample))
            }
            is SourceExecutionResult.Polynomial -> assertIs<OpenTheoryResult.Sat>(execution.result.result,
                "$route $mechanisms $engine").assignment
            is SourceExecutionResult.Transcendental -> assertIs<OpenTheoryResult.Sat>(execution.result.result,
                "$route $mechanisms $engine").assignment
            is SourceExecutionResult.Linear -> assertIs<OpenTheoryResult.Sat>(
                assertIs<OpenTheoryExecution.Satisfy>(execution.result).result,
                "$route $mechanisms $engine").assignment
            else -> error("$route $mechanisms $engine: $execution")
        }
        assertTrue(point.boolValue(0))
        assertTrue(point.intValue(0) != point.intValue(1))
    }
}
