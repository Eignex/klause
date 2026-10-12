package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.ir.NonlinearIntegerProblem
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.ir.QuadraticCoefficient
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.ir.QuadraticObjectiveTerm
import com.eignex.klause.ir.QuadraticVariable
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.parseBigInt
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class QuadraticIntegerOptimizationTest {
    @Test
    fun `fractional relaxation splits open integers before proving the optimum`() {
        val source = NonlinearIntegerProblem(
            PolynomialRealFormula.Constant(true), 1, 0, mapOf("i" to 0), emptyMap(), emptyMap(),
        )
        val i = QuadraticVariable.Int(SourceIntId(0))
        val objective = QuadraticObjectiveSpec(listOf(
            QuadraticObjectiveTerm(QuadraticCoefficient.ONE, i, i),
            QuadraticObjectiveTerm(QuadraticCoefficient.ofLong(-1), i),
        ))

        val execution = OpenTheoryPipeline.minimize(
            source.pipelineRequest(), objective, engine = FiniteEngine.BACKTRACK,
        )

        val result = assertIs<QuadraticOptimizationResult.Optimal>(execution.result)
        assertEquals(BigFraction.ZERO, result.incumbent.value.rational)
        assertEquals(BigFraction.ZERO, result.bound.value.rational)
        assertTrue(result.stats.search.nodes.sum > 1)
    }

    @Test
    fun `open wide integer optimum preserves interleaved source identities`() {
        val source = NonlinearIntegerProblem(
            PolynomialRealFormula.Constant(true), 2, 0, mapOf("i" to 1), mapOf("x" to 0), emptyMap(),
        )
        val value = parseBigInt("100000000000000000000")
        val i = QuadraticVariable.Int(SourceIntId(0))
        val x = QuadraticVariable.Real(SourceRealId(0))
        val objective = QuadraticObjectiveSpec(listOf(
            QuadraticObjectiveTerm(QuadraticCoefficient.ONE, i, i),
            QuadraticObjectiveTerm(QuadraticCoefficient.of(parseBigInt("-200000000000000000000")), i),
            QuadraticObjectiveTerm(QuadraticCoefficient.ONE, x, x),
        ), QuadraticCoefficient.of(parseBigInt("10000000000000000000000000000000000000000")))

        val execution = OpenTheoryPipeline.minimize(
            source.pipelineRequest(), objective, engine = FiniteEngine.BACKTRACK,
        )

        val result = assertIs<QuadraticOptimizationResult.Optimal>(execution.result)
        assertEquals(value, result.incumbent.sample.exactIntValue(0))
        assertEquals(BigFraction.ZERO, result.incumbent.sample.algebraicReals?.single()?.rational)
        assertEquals(BigFraction.ZERO, result.incumbent.value.rational)
    }

    @Test
    fun `integral recession ray proves open integer unboundedness`() {
        val source = NonlinearIntegerProblem(
            PolynomialRealFormula.Constant(true), 1, 0, mapOf("i" to 0), emptyMap(), emptyMap(),
        )
        val i = QuadraticVariable.Int(SourceIntId(0))
        val objective = QuadraticObjectiveSpec(listOf(QuadraticObjectiveTerm(QuadraticCoefficient.ofLong(-1), i, i)))

        val execution = OpenTheoryPipeline.minimize(
            source.pipelineRequest(), objective, engine = FiniteEngine.BACKTRACK,
        )

        assertIs<QuadraticOptimizationResult.Unbounded>(execution.result)
        assertTrue(execution.numericalWork > 0)
    }

    @Test
    fun `fractional real witnesses cannot satisfy an integer square equation`() {
        val source = NonlinearIntegerProblem(
            PolynomialRealFormula.Atom(
                PolynomialExpression.Power(PolynomialExpression.Variable(SourceRealId(0)), 2), PolynomialRelation.EQ,
                PolynomialExpression.Constant.of(parseBigInt("2")),
            ), 1, 0, mapOf("i" to 0), emptyMap(), emptyMap(),
        )
        val i = QuadraticVariable.Int(SourceIntId(0))
        val objective = QuadraticObjectiveSpec(listOf(QuadraticObjectiveTerm(QuadraticCoefficient.ONE, i, i)))

        val execution = OpenTheoryPipeline.minimize(
            source.pipelineRequest(), objective, engine = FiniteEngine.BACKTRACK,
        )

        assertIs<QuadraticOptimizationResult.Infeasible>(execution.result)
    }

    @Test
    fun `zero native budget prevents open quadratic entry`() {
        val source = NonlinearIntegerProblem(
            PolynomialRealFormula.Constant(true), 1, 0, mapOf("i" to 0), emptyMap(), emptyMap(),
        )

        val execution = OpenTheoryPipeline.minimize(source.pipelineRequest(), QuadraticObjectiveSpec(emptyList()),
            NonlinearIntegerTheoryParams(polynomial = PolynomialTheoryParams(maximumWork = 0)))

        assertIs<QuadraticOptimizationResult.Unknown>(execution.result)
        assertEquals(0L, execution.nativeWork)
    }

    @Test
    fun `cancellation exception retains a checked open integer incumbent and relaxation bound`() {
        val source = NonlinearIntegerProblem(
            PolynomialRealFormula.Constant(true), 1, 0, mapOf("i" to 0), emptyMap(), emptyMap(),
        )
        val i = QuadraticVariable.Int(SourceIntId(0))
        val objective = QuadraticObjectiveSpec(listOf(
            QuadraticObjectiveTerm(QuadraticCoefficient.ONE, i, i),
            QuadraticObjectiveTerm(QuadraticCoefficient.ofLong(-1), i),
        ))
        val params = NonlinearIntegerTheoryParams(polynomial = PolynomialTheoryParams(
            strategy = PolynomialStrategyParams(
                backtrack = BacktrackParams(onEvent = { throw CancellationException("stop after incumbent") }),
            ),
        ))

        val execution = OpenTheoryPipeline.minimize(source.pipelineRequest(), objective, params, FiniteEngine.BACKTRACK)

        val result = assertIs<QuadraticOptimizationResult.BestFound>(execution.result)
        assertEquals(BigFraction.ZERO, result.incumbent.value.rational)
        assertEquals(BigFraction.ofLong(-1) * BigFraction.ofLong(4).reciprocal(), result.bound?.value?.rational)
        assertEquals(TerminationReason.Cancelled, result.reason)
    }
}
