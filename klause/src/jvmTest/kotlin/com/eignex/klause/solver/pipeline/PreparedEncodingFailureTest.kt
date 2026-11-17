package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceBoolId
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.TranscendentalFormula
import com.eignex.klause.ir.TranscendentalProblem
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.theory.BuiltInEncoderProvider
import com.eignex.klause.theory.Encoder
import com.eignex.klause.theory.EncoderDecline
import com.eignex.klause.theory.EncoderProvider
import com.eignex.klause.theory.EncoderSupport
import com.eignex.klause.theory.EncodingTarget
import com.eignex.util.numbers.BigFraction
import org.junit.BeforeClass
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

@RunWith(Parameterized::class)
class PreparedEncodingFailureTest(private val route: String, private val failure: String) {
    companion object {
        @JvmStatic
        @BeforeClass
        fun initializeRuntime() = FiniteTranscendentalWorkerTest.initializeRuntime()

        @JvmStatic
        @Parameterized.Parameters(name = "{0} {1}")
        fun cases(): List<Array<String>> = listOf("linear", "polynomial", "open-integer", "function")
            .flatMap { route ->
                listOf("unsupported", "relaxation", "conditions", "installation", "empty", "resource")
                    .map { arrayOf(route, it) }
            }
    }

    @Test
    fun `arithmetic admission should retain the canonical factor and encoding failure`() {
        val row = ExactLinear.of(intArrayOf(Term.ofRealVar(1)), listOf(BigFraction.ONE),
            LinearOp.GE, BigFraction.ZERO)
        val prefix: Factor = if (route == "linear") {
            ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE),
                LinearOp.GE, BigFraction.ZERO)
        } else ContinuousProduct(0, 0, 1)
        val source = Problem(1, 1, arrayOf(IntDomain(-1, 1)), arrayOf(prefix, row),
            numRealVars = 2, realLower = doubleArrayOf(-1.0, -1.0), realUpper = doubleArrayOf(1.0, 1.0),
            openIntHi = booleanArrayOf(route == "open-integer"))
        val providers = FactorProjectionProviders(encoder = EncoderProvider { factor, target ->
            if (factor !== row) BuiltInEncoderProvider.select(factor, target) else when (failure) {
                "unsupported" -> Encoder(EncoderSupport.UNSUPPORTED)
                "relaxation" -> Encoder(EncoderSupport.RELAXATION_ONLY)
                "conditions" -> Encoder(EncoderSupport.CONDITIONED, listOf(SourceIntId(0)),
                    mapOf(SourceBoolId(0) to true))
                "installation" -> Encoder(EncoderSupport.EQUIVALENT) { _, _ -> EncoderDecline.UNSUPPORTED }
                "empty" -> Encoder(EncoderSupport.EQUIVALENT) { _, _ -> null }
                "resource" -> Encoder(EncoderSupport.EQUIVALENT) { _, _ -> EncoderDecline.RESOURCE }
                else -> error(failure)
            }
        })
        val plan = if (route == "function") {
            TranscendentalProblem(TranscendentalFormula.Constant(true), mapOf("i" to 0),
                mapOf("x" to 1, "y" to 2), mapOf("b" to 0)).withFactors(source).prepareExecutionPlan(providers)
        } else source.prepareExecutionPlan(providers)

        for (engine in FiniteEngine.entries) {
            val admission = plan.capabilities.admission(engine)
            assertFalse(admission.runnable, "$route $failure $engine")
            val decline = admission.declines.first { it.encoding != null }
            val encoding = assertNotNull(decline.encoding)
            assertEquals(1, decline.factorIndex)
            assertEquals(1, encoding.factorIndex)
            assertEquals(if (route == "linear") EncodingTarget.LINEAR else EncodingTarget.POLYNOMIAL, encoding.target)
            assertEquals(when (failure) {
                "conditions" -> EncoderDecline.CONDITIONS
                "resource" -> EncoderDecline.RESOURCE
                else -> EncoderDecline.UNSUPPORTED
            }, encoding.cause)
            assertEquals(if (failure == "conditions") listOf(SourceIntId(0)) else emptyList(),
                encoding.missingIntegers)
            assertEquals(if (failure == "conditions") mapOf(SourceBoolId(0) to true) else emptyMap(),
                encoding.missingBooleans)
        }
    }
}
