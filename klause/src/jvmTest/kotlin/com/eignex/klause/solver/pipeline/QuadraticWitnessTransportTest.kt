package com.eignex.klause.solver.pipeline

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialLimits
import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.quadraticEngineInvocation
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.toQuadraticObjectiveSpec
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import com.eignex.klause.util.Cancellation
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame

@RunWith(Parameterized::class)
class QuadraticWitnessTransportTest(
    private val engine: FiniteEngine,
    private val sense: ObjectiveSense,
    private val affine: Boolean,
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} {1} affine={2}")
        fun variants(): List<Array<Any>> = FiniteEngine.entries.flatMap { engine ->
            ObjectiveSense.entries.flatMap { sense ->
                listOf(true, false).map { arrayOf<Any>(engine, sense, it) }
            }
        }
    }

    @Test
    fun `actual quadratic producers retain the original objective point and exact value`() {
        val source = Problem(0, 0, emptyArray(), arrayOf(ContinuousProduct(0, 0, 1)), numRealVars = 2,
            realLower = doubleArrayOf(-1.0, Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(1.0, Double.POSITIVE_INFINITY))
        val linear = LinearObjective(constant = Long.MAX_VALUE, realCoefficients = doubleArrayOf(0.0, 1.0))
        val quadratic = linear.toQuadraticObjectiveSpec()
        val request = source.pipelineRequest()
        val params = PolynomialTheoryParams(strategy = PolynomialStrategyParams(
            localSearch = LocalSearchParams(maxFlips = 1, randomSeed = 17), alnsIterations = 1,
        ))

        val execution = if (affine) OpenTheoryPipeline.optimize(request, linear, params, engine, sense)
            else OpenTheoryPipeline.minimize(request, quadratic, params, engine, sense)

        val result = assertIs<QuadraticOptimizationResult.WithIncumbent>(execution.result)
        val point = result.incumbent
        val publication = assertNotNull(point.sample.witnessCertificate?.quadraticPublication)
        assertSame(point.sample, publication.incumbent.sample)
        assertEquals(sense, publication.invocation.sense)
        if (affine) assertSame(linear, publication.invocation.affineObjective)
        else assertSame(quadratic, publication.invocation.objective)
        val expectedValue = BigFraction.ofLong(Long.MAX_VALUE) +
            if (sense == ObjectiveSense.MINIMIZE) BigFraction.ZERO else BigFraction.ONE
        assertEquals(expectedValue, publication.incumbent.value.rational)
        val plan = if (affine) request.preparedPlan.withObjective(linear, sense == ObjectiveSense.MAXIMIZE)
            else request.preparedPlan
        val expected = assertNotNull(plan.quadraticEngineInvocation(
            publication.invocation.objective, sense, quadraticAssumptions(params, engine),
        ))
        assertIs<SourceEvidenceCheck.Checked>(publication.verify(expected, point, AlgebraicArithmetic()) {
            checkQuadraticSourceWitness(plan, it, Cancellation.Never, PolynomialLimits())
        })
    }
}
