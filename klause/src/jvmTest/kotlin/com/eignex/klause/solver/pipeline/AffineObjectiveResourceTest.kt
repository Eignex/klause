package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.QuadraticCoefficient
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.ir.QuadraticObjectiveTerm
import com.eignex.klause.ir.QuadraticVariable
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@RunWith(Parameterized::class)
class AffineObjectiveResourceTest(private val stop: String, private val affine: Boolean) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} affine={1}")
        fun variants(): List<Array<Any>> = listOf("work", "timeout", "cancelled", "dense").flatMap { stop ->
            listOf(true, false).map { arrayOf<Any>(stop, it) }
        }
    }

    @Test
    fun `affine representation preserves the incomplete reason and native allowance`() {
        val source = Problem(0, 0, emptyArray(), arrayOf(ContinuousProduct(0, 0, 1)), numRealVars = 2,
            realLower = doubleArrayOf(0.0, 2.0), realUpper = doubleArrayOf(2.0, 2.0))
        val params = when (stop) {
            "work" -> PolynomialTheoryParams(maximumWork = 0)
            "timeout" -> PolynomialTheoryParams(timeout = Cancellation { true })
            "cancelled" -> PolynomialTheoryParams(cancellation = Cancellation { true })
            else -> PolynomialTheoryParams(maximumDenseCoefficients = 1)
        }

        val execution = if (affine) OpenTheoryPipeline.optimize(
            source.pipelineRequest(), LinearObjective(realCoefficients = doubleArrayOf(1.0)), params,
            FiniteEngine.BACKTRACK,
        ) else OpenTheoryPipeline.minimize(source.pipelineRequest(), QuadraticObjectiveSpec(listOf(
            QuadraticObjectiveTerm(QuadraticCoefficient.ONE, QuadraticVariable.Real(SourceRealId(0))),
        )), params, FiniteEngine.BACKTRACK)

        val result = assertIs<QuadraticOptimizationResult.Unknown>(execution.result)
        val reason = when (stop) {
            "timeout" -> TerminationReason.Timeout
            "cancelled" -> TerminationReason.Cancelled
            else -> TerminationReason.BudgetExhausted
        }
        assertEquals(reason, result.reason)
        if (stop == "dense") assertEquals(PolynomialTheoryResource.DenseCoefficients, execution.resource)
        else assertEquals(0L, execution.nativeWork)
    }
}
