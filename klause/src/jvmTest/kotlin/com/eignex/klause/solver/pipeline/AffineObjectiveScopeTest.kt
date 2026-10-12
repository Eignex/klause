package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.QuadraticCoefficient
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.ir.QuadraticObjectiveTerm
import com.eignex.klause.ir.QuadraticVariable
import com.eignex.klause.ir.SourceBoolId
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

@RunWith(Parameterized::class)
class AffineObjectiveScopeTest(private val engine: FiniteEngine, private val affine: Boolean) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} affine={1}")
        fun variants(): List<Array<Any>> = FiniteEngine.entries.flatMap { engine ->
            listOf(true, false).map { arrayOf<Any>(engine, it) }
        }
    }

    @Test
    fun `changed assumptions and affine objectives retain finite global and source namespace semantics`() {
        val source = Problem(1, 2, arrayOf(IntDomain(0, 1), IntDomain(0, 1)),
            arrayOf(AllDifferent(intArrayOf(0, 1), 0, 2), ContinuousProduct(0, 0, 1)), numRealVars = 2,
            realLower = doubleArrayOf(1.0, 1.0), realUpper = doubleArrayOf(1.0, 1.0))
        val request = source.pipelineRequest()
        val firstScope = PolynomialTheoryParams(strategy = PolynomialStrategyParams(
            assumptions = Assumptions.None.withBool(0, true).withInt(0, 1).withInt(1, 0),
            localSearch = LocalSearchParams(maxFlips = 1, randomSeed = 17), alnsIterations = 1,
        ))
        val nextScope = firstScope.copy(strategy = firstScope.strategy.copy(
            assumptions = Assumptions.None.withBool(0, false).withInt(0, 0).withInt(1, 1),
        ))
        val linear = LinearObjective(longArrayOf(-3), longArrayOf(7, 0), 11, doubleArrayOf(0.5, 0.0))
        val quadratic = QuadraticObjectiveSpec(listOf(
            QuadraticObjectiveTerm(QuadraticCoefficient.ofLong(-3), QuadraticVariable.Bool(SourceBoolId(0))),
            QuadraticObjectiveTerm(QuadraticCoefficient.ofLong(7), QuadraticVariable.Int(SourceIntId(0))),
            QuadraticObjectiveTerm(QuadraticCoefficient.of(BigFraction.ONE.num, BigFraction.ofLong(2).num),
                QuadraticVariable.Real(SourceRealId(0))),
        ), QuadraticCoefficient.ofLong(11))

        val first = if (affine) OpenTheoryPipeline.optimize(request, linear, firstScope, engine).result
            else OpenTheoryPipeline.minimize(request, quadratic, firstScope, engine).result
        val next = if (affine) {
            OpenTheoryPipeline.optimize(request, linear.copy(constant = 13), nextScope, engine).result
        } else OpenTheoryPipeline.minimize(request, QuadraticObjectiveSpec(quadratic.terms,
                QuadraticCoefficient.ofLong(13)), nextScope, engine).result

        val firstPoint = assertIs<QuadraticOptimizationResult.WithIncumbent>(first).incumbent
        val nextPoint = assertIs<QuadraticOptimizationResult.WithIncumbent>(next).incumbent
        assertEquals(true, firstPoint.sample.boolValue(0))
        assertEquals(1L, firstPoint.sample.intValue(0))
        assertNotEquals(firstPoint.sample.intValue(0), firstPoint.sample.intValue(1))
        assertEquals(BigFraction.ofLong(31) * BigFraction.ofLong(2).reciprocal(), firstPoint.value.rational)
        assertEquals(false, nextPoint.sample.boolValue(0))
        assertEquals(0L, nextPoint.sample.intValue(0))
        assertNotEquals(nextPoint.sample.intValue(0), nextPoint.sample.intValue(1))
        assertEquals(BigFraction.ofLong(27) * BigFraction.ofLong(2).reciprocal(), nextPoint.value.rational)
    }
}
