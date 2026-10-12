package com.eignex.klause.solver.pipeline

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.RationalPolynomial
import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.PolynomialRealProblem
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.QuadraticCoefficient
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.ir.QuadraticObjectiveTerm
import com.eignex.klause.ir.QuadraticVariable
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import com.eignex.klause.solver.result.SearchEvent
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class QuadraticOptimizationExecutionTest {
    @Test
    fun `continuous rational optimum is published with a matching exact bound`() {
        val source = Problem(0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY), realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val x = QuadraticVariable.Real(SourceRealId(0))
        val objective = QuadraticObjectiveSpec(listOf(
            QuadraticObjectiveTerm(QuadraticCoefficient.ONE, x, x),
            QuadraticObjectiveTerm(QuadraticCoefficient.ofLong(-1), x),
        ))

        val execution = OpenTheoryPipeline.minimize(
            source.pipelineRequest(), objective, engine = FiniteEngine.BACKTRACK,
        )

        val optimum = assertIs<QuadraticOptimizationResult.Optimal>(execution.result)
        assertEquals(BigFraction.ofLong(-1) * BigFraction.ofLong(4).reciprocal(), optimum.incumbent.value.rational)
        assertEquals(0, AlgebraicArithmetic().compare(optimum.incumbent.value, optimum.bound.value))
    }

    @Test
    fun `strict source boundary publishes an unattained infimum with a feasible incumbent`() {
        val parsed = SmtLibPolynomial.parse("(declare-const x Real) (assert (> x 0)) (check-sat)")
        val source = PolynomialRealProblem(parsed.formula, parsed.realCount, parsed.boolCount,
            parsed.sourceRealCount, parsed.sourceBoolCount, parsed.realVarNames, parsed.boolVarNames)
        val x = QuadraticVariable.Real(SourceRealId(0))
        val objective = QuadraticObjectiveSpec(listOf(QuadraticObjectiveTerm(QuadraticCoefficient.ONE, x, x)))

        val execution = OpenTheoryPipeline.minimize(
            source.pipelineRequest(), objective, engine = FiniteEngine.BACKTRACK,
        )

        val infimum = assertIs<QuadraticOptimizationResult.UnattainedLimit>(execution.result)
        assertEquals(BigFraction.ZERO, infimum.bound.value.rational)
        assertTrue(infimum.bound.strict)
        assertTrue(AlgebraicArithmetic().sign(infimum.incumbent.value) > 0)
    }

    @Test
    fun `maximization keeps source objective direction and exact values`() {
        val source = Problem(0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY), realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val x = QuadraticVariable.Real(SourceRealId(0))
        val objective = QuadraticObjectiveSpec(listOf(QuadraticObjectiveTerm(QuadraticCoefficient.ofLong(-1), x, x)))

        val execution = OpenTheoryPipeline.minimize(
            source.pipelineRequest(), objective, engine = FiniteEngine.BACKTRACK, sense = ObjectiveSense.MAXIMIZE,
        )

        val optimum = assertIs<QuadraticOptimizationResult.Optimal>(execution.result)
        assertEquals(ObjectiveSense.MAXIMIZE, optimum.sense)
        assertEquals(BigFraction.ZERO, optimum.incumbent.value.rational)
    }

    @Test
    fun `zero native allowance cannot publish an optimization certificate`() {
        val source = Problem(0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))
        val x = QuadraticVariable.Real(SourceRealId(0))
        val objective = QuadraticObjectiveSpec(listOf(QuadraticObjectiveTerm(QuadraticCoefficient.ONE, x, x)))

        val execution = OpenTheoryPipeline.minimize(
            source.pipelineRequest(), objective, PolynomialTheoryParams(maximumWork = 0), FiniteEngine.MIXED,
        )

        assertEquals(
            TerminationReason.BudgetExhausted, assertIs<QuadraticOptimizationResult.Unknown>(execution.result).reason,
        )
        assertEquals(0L, execution.nativeWork)
    }

    @Test
    fun `local optimization keeps a source incumbent without promoting its neighborhood proof`() {
        val source = Problem(0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(-2.0), realUpper = doubleArrayOf(2.0))
        val x = QuadraticVariable.Real(SourceRealId(0))
        val objective = QuadraticObjectiveSpec(listOf(QuadraticObjectiveTerm(QuadraticCoefficient.ofLong(-1), x, x)))
        val params = PolynomialTheoryParams(strategy = PolynomialStrategyParams(
            localSearch = LocalSearchParams(maxFlips = 2, randomSeed = 17),
        ))

        val execution = OpenTheoryPipeline.minimize(
            source.pipelineRequest(), objective, params, FiniteEngine.LOCAL_SEARCH,
        )

        assertIs<QuadraticOptimizationResult.BestFound>(execution.result)
        assertTrue(execution.result.stats.ls.moves.sum > 0)
    }

    @Test
    fun `algebraic optimum retains its exact source root after a later cancelled invocation`() {
        val parsed = SmtLibPolynomial.parse(
            "(declare-const x Real) (assert (and (= (* x x) 2) (> x 0))) (check-sat)",
        )
        val source = PolynomialRealProblem(
            parsed.formula, parsed.realCount, parsed.boolCount, parsed.sourceRealCount,
            parsed.sourceBoolCount, parsed.realVarNames, parsed.boolVarNames,
        ).pipelineRequest()
        val x = QuadraticVariable.Real(SourceRealId(0))
        val objective = QuadraticObjectiveSpec(listOf(QuadraticObjectiveTerm(QuadraticCoefficient.ONE, x)))

        val execution = OpenTheoryPipeline.minimize(source, objective, engine = FiniteEngine.BACKTRACK)
        val cancelled = OpenTheoryPipeline.minimize(
            source, objective, PolynomialTheoryParams(cancellation = Cancellation { true }),
        )

        val optimum = assertIs<QuadraticOptimizationResult.Optimal>(execution.result)
        assertEquals(0, AlgebraicArithmetic().signAt(RationalPolynomial.of(-2, 0, 1), optimum.incumbent.value))
        assertTrue(AlgebraicArithmetic().sign(optimum.incumbent.value) > 0)
        assertEquals(
            TerminationReason.Cancelled, assertIs<QuadraticOptimizationResult.Unknown>(cancelled.result).reason,
        )
    }

    @Test
    fun `an exhausted heuristic retains a global box bound without a feasibility claim`() {
        val source = Problem(
            0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(-2.0), realUpper = doubleArrayOf(2.0),
        )
        val x = QuadraticVariable.Real(SourceRealId(0))
        val objective = QuadraticObjectiveSpec(listOf(
            QuadraticObjectiveTerm(QuadraticCoefficient.ofLong(-1), x, x),
        ))
        val params = PolynomialTheoryParams(strategy = PolynomialStrategyParams(
            localSearch = LocalSearchParams(maxFlips = 0),
        ))

        val execution = OpenTheoryPipeline.minimize(
            source.pipelineRequest(), objective, params, FiniteEngine.LOCAL_SEARCH,
        )

        val unknown = assertIs<QuadraticOptimizationResult.Unknown>(execution.result)
        assertEquals(BigFraction.ofLong(-4), unknown.bound?.value?.rational)
        assertEquals(0.0, unknown.stats.ls.moves.sum)
    }

    @Test
    fun `private worker cancellation retains a checked incumbent without an optimum certificate`() {
        val source = Problem(
            0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(-1.0), realUpper = doubleArrayOf(1.0),
        )
        val x = QuadraticVariable.Real(SourceRealId(0))
        val objective = QuadraticObjectiveSpec(listOf(QuadraticObjectiveTerm(QuadraticCoefficient.ONE, x, x)))
        var cancelled = false
        val params = PolynomialTheoryParams(strategy = PolynomialStrategyParams(
            backtrack = BacktrackParams(
                cancellation = Cancellation { cancelled },
                onEvent = { if (it is SearchEvent.Incumbent) cancelled = true },
            ),
        ))

        val execution = OpenTheoryPipeline.minimize(
            source.pipelineRequest(), objective, params, FiniteEngine.BACKTRACK,
        )

        val result = assertIs<QuadraticOptimizationResult.BestFound>(execution.result)
        assertEquals(TerminationReason.Cancelled, result.reason)
        assertEquals(BigFraction.ZERO, result.incumbent.value.rational)
    }

    @Test
    fun `a cancellation exception retains an incumbent checked before interruption`() {
        val source = Problem(
            0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(-1.0), realUpper = doubleArrayOf(1.0),
        )
        val x = QuadraticVariable.Real(SourceRealId(0))
        val objective = QuadraticObjectiveSpec(listOf(QuadraticObjectiveTerm(QuadraticCoefficient.ONE, x, x)))
        val params = PolynomialTheoryParams(strategy = PolynomialStrategyParams(
            backtrack = BacktrackParams(onEvent = {
                if (it is SearchEvent.Incumbent) throw CancellationException()
            }),
        ))

        val execution = OpenTheoryPipeline.minimize(
            source.pipelineRequest(), objective, params, FiniteEngine.BACKTRACK,
        )

        val result = assertIs<QuadraticOptimizationResult.BestFound>(execution.result)
        assertEquals(TerminationReason.Cancelled, result.reason)
        assertEquals(BigFraction.ZERO, result.incumbent.value.rational)
    }
}
