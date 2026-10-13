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
import com.eignex.klause.solver.InstructionSlicedSearch
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
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith

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

    @Test
    fun `alternative source equalities cannot become unconditional objective fixings`() {
        val parsed = SmtLibPolynomial.parse(
            "(declare-const x Real) (assert (or (= x 1) (= x 2))) (check-sat)",
        )
        val source = PolynomialRealProblem(
            parsed.formula, parsed.realCount, parsed.boolCount, parsed.sourceRealCount,
            parsed.sourceBoolCount, parsed.realVarNames, parsed.boolVarNames,
        ).pipelineRequest()
        val x = QuadraticVariable.Real(SourceRealId(0))
        val objective = QuadraticObjectiveSpec(listOf(QuadraticObjectiveTerm(QuadraticCoefficient.ONE, x, x)))

        val execution = OpenTheoryPipeline.minimize(source, objective, engine = FiniteEngine.BACKTRACK)

        val result = assertIs<QuadraticOptimizationResult.Optimal>(execution.result)
        assertEquals(BigFraction.ONE, result.incumbent.value.rational)
    }
    @Test
    fun `parallel mixed execution preserves a strict unattained source limit`() {
        val parsed = SmtLibPolynomial.parse("(declare-const x Real) (assert (> x 0))")
        val source = PolynomialRealProblem(parsed.formula, parsed.realCount, parsed.boolCount,
            parsed.sourceRealCount, parsed.sourceBoolCount, parsed.realVarNames, parsed.boolVarNames)
        val x = QuadraticVariable.Real(SourceRealId(0))
        val objective = QuadraticObjectiveSpec(listOf(QuadraticObjectiveTerm(QuadraticCoefficient.ONE, x, x)))

        val execution = OpenTheoryPipeline.minimize(source.pipelineRequest(), objective, PolynomialTheoryParams(),
            FiniteEngine.MIXED, ObjectiveSense.MINIMIZE, 4)

        val limit = assertIs<QuadraticOptimizationResult.UnattainedLimit>(execution.result)
        assertEquals(BigFraction.ZERO, limit.bound.value.rational)
        assertTrue(limit.bound.strict)
        assertTrue(AlgebraicArithmetic().sign(limit.incumbent.value) > 0)
        assertEquals(4, limit.stats.portfolio.arms.size)
    }

    @Test
    fun `parallel mixed maximization retains the original exact objective direction`() {
        val source = Problem(0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(-1.0), realUpper = doubleArrayOf(1.0))
        val x = QuadraticVariable.Real(SourceRealId(0))
        val objective = QuadraticObjectiveSpec(listOf(QuadraticObjectiveTerm(QuadraticCoefficient.ofLong(-1), x, x)))

        val execution = OpenTheoryPipeline.minimize(source.pipelineRequest(), objective, PolynomialTheoryParams(),
            FiniteEngine.MIXED, ObjectiveSense.MAXIMIZE, 4)

        val optimum = assertIs<QuadraticOptimizationResult.Optimal>(execution.result)
        assertEquals(ObjectiveSense.MAXIMIZE, optimum.sense)
        assertEquals(BigFraction.ZERO, optimum.incumbent.value.rational)
        assertEquals(BigFraction.ZERO, optimum.bound.value.rational)
        assertEquals(4, optimum.stats.portfolio.arms.size)
    }

    @Test
    fun `sliced quadratic traversal preserves exact completion and native accounting`() {
        val source = Problem(0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(-2.0), realUpper = doubleArrayOf(3.0))
        val x = QuadraticVariable.Real(SourceRealId(0))
        val objective = QuadraticObjectiveSpec(listOf(QuadraticObjectiveTerm(QuadraticCoefficient.ONE, x, x)))
        val request = source.pipelineRequest()
        val params = PolynomialTheoryParams()
        val whole = OpenTheoryPipeline.minimize(request, objective, params, FiniteEngine.BACKTRACK)
        val native = QuadraticNativeScope(params)
        val adapter = QuadraticWorkerAdapter(request, request.resolvedModel(), objective, ObjectiveSense.MINIMIZE,
            params, native, FiniteEngine.BACKTRACK)
        val worker = adapter.worker(0)
        val handle = assertIs<QuadraticPortfolioSearch>(worker.newResumableSearch { Double.POSITIVE_INFINITY })
        var slices = 0

        while (!handle.isDone && slices++ < 4096) handle.runSlice(Cancellation.Never, Long.MAX_VALUE, 1) { }
        val result = assertIs<QuadraticOptimizationResult.Optimal>(assertNotNull(handle.completion))
        handle.close()
        adapter.close()

        assertEquals(BigFraction.ZERO, result.incumbent.value.rational)
        assertEquals(whole.nativeWork, native.work)
        assertEquals(whole.polynomialStats, native.polynomialStats)
        assertTrue(handle.isDone)
    }

    @Test
    fun `closed pending quadratic traversal rejects more slices`() {
        val source = Problem(0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(-2.0), realUpper = doubleArrayOf(3.0))
        val x = QuadraticVariable.Real(SourceRealId(0))
        val objective = QuadraticObjectiveSpec(listOf(QuadraticObjectiveTerm(QuadraticCoefficient.ONE, x, x)))
        val request = source.pipelineRequest()
        val params = PolynomialTheoryParams()
        val adapter = QuadraticWorkerAdapter(request, request.resolvedModel(), objective, ObjectiveSense.MINIMIZE,
            params, QuadraticNativeScope(params), FiniteEngine.BACKTRACK)
        val handle = assertNotNull(adapter.worker(0).newResumableSearch { Double.POSITIVE_INFINITY })

        handle.close()

        assertFailsWith<IllegalStateException> { handle.runSlice(Cancellation.Never, Long.MAX_VALUE, 1) { } }
    }


    @Test
    fun `quadratic neighborhood preparation is charged once before its first repair`() {
        val source = Problem(0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(-2.0), realUpper = doubleArrayOf(3.0))
        val x = QuadraticVariable.Real(SourceRealId(0))
        val objective = QuadraticObjectiveSpec(listOf(QuadraticObjectiveTerm(QuadraticCoefficient.ONE, x, x)))
        val request = source.pipelineRequest()
        val params = PolynomialTheoryParams()
        val native = QuadraticNativeScope(params)
        val adapter = QuadraticWorkerAdapter(request, request.resolvedModel(), objective, ObjectiveSense.MINIMIZE,
            params, native, FiniteEngine.LOCAL_SEARCH)
        val handle = assertIs<InstructionSlicedSearch>(
            adapter.worker(1).newResumableSearch { Double.POSITIVE_INFINITY },
        )

        handle.runInstructionSlice(Cancellation.Never, Long.MAX_VALUE, 0) { }
        val preparation = handle.chargedInstructions
        val nativePreparation = native.work
        handle.runInstructionSlice(Cancellation.Never, Long.MAX_VALUE, 0) { }
        handle.close()
        adapter.close()

        assertTrue(preparation > 0)
        assertEquals(preparation, handle.chargedInstructions)
        assertEquals(nativePreparation, native.work)
        assertEquals(0.0, handle.stats.ls.moves.sum)
    }
}
