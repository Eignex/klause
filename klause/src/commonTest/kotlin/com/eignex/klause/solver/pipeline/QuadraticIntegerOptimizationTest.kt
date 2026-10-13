package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.ir.NonlinearIntegerProblem
import com.eignex.klause.ir.ObjectiveSense
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
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.parseBigInt
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.plus
import com.eignex.klause.util.times
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
    fun `fractional optimum branches at an arbitrary width integer threshold`() {
        val source = NonlinearIntegerProblem(
            PolynomialRealFormula.Constant(true), 1, 0, mapOf("i" to 0), emptyMap(), emptyMap(),
        )
        val wide = parseBigInt("100000000000000000000")
        val i = QuadraticVariable.Int(SourceIntId(0))
        val objective = QuadraticObjectiveSpec(listOf(
            QuadraticObjectiveTerm(QuadraticCoefficient.ONE, i, i),
            QuadraticObjectiveTerm(QuadraticCoefficient.of(parseBigInt("-200000000000000000001")), i),
        ), QuadraticCoefficient.of(wide * (wide + BIG_ONE)))

        val execution = OpenTheoryPipeline.minimize(
            source.pipelineRequest(), objective, engine = FiniteEngine.BACKTRACK,
        )

        val result = assertIs<QuadraticOptimizationResult.Optimal>(execution.result)
        assertEquals(BigFraction.ZERO, result.incumbent.value.rational)
        assertTrue(result.incumbent.sample.exactIntValue(0) in listOf(wide, wide + BIG_ONE))
        assertTrue(result.stats.search.nodes.sum > 1)
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
    fun `recession projection preserves coupled integer and real equality`() {
        val source = NonlinearIntegerProblem(
            PolynomialRealFormula.Atom(
                PolynomialExpression.Variable(SourceRealId(0)), PolynomialRelation.EQ,
                PolynomialExpression.Variable(SourceRealId(1)),
            ), 2, 0, mapOf("i" to 1), mapOf("x" to 0), emptyMap(),
        )
        val x = QuadraticVariable.Real(SourceRealId(0))
        val objective = QuadraticObjectiveSpec(listOf(
            QuadraticObjectiveTerm(QuadraticCoefficient.ofLong(-1), x, x),
        ))

        val execution = OpenTheoryPipeline.minimize(
            source.pipelineRequest(), objective, engine = FiniteEngine.BACKTRACK,
        )

        val result = assertIs<QuadraticOptimizationResult.Unbounded>(execution.result)
        val arithmetic = AlgebraicArithmetic()
        val integer = arithmetic.rational(BigFraction.of(result.incumbent.sample.exactIntValue(0), BIG_ONE))
        assertEquals(0, arithmetic.compare(integer, requireNotNull(result.incumbent.sample.algebraicReals).single()))
    }

    @Test
    fun `integer maximization preserves a nonzero source optimum and upper bound`() {
        val source = NonlinearIntegerProblem(
            PolynomialRealFormula.Constant(true), 1, 0, mapOf("i" to 0), emptyMap(), emptyMap(),
        )
        val i = QuadraticVariable.Int(SourceIntId(0))
        val objective = QuadraticObjectiveSpec(listOf(
            QuadraticObjectiveTerm(QuadraticCoefficient.ofLong(-1), i, i),
            QuadraticObjectiveTerm(QuadraticCoefficient.ofLong(1), i),
        ), QuadraticCoefficient.ofLong(5))

        val execution = OpenTheoryPipeline.minimize(
            source.pipelineRequest(), objective, engine = FiniteEngine.BACKTRACK, sense = ObjectiveSense.MAXIMIZE,
        )

        val result = assertIs<QuadraticOptimizationResult.Optimal>(execution.result)
        assertEquals(BigFraction.ofLong(5), result.incumbent.value.rational)
        assertEquals(BigFraction.ofLong(5), result.bound.value.rational)
    }

    @Test
    fun `local real-coordinate repair improves an open mixed incumbent without proving unboundedness`() {
        val source = NonlinearIntegerProblem(
            PolynomialRealFormula.Atom(PolynomialExpression.Variable(SourceRealId(0)), PolynomialRelation.EQ,
                PolynomialExpression.Constant.of(parseBigInt("0"))),
            2, 0, mapOf("i" to 0), mapOf("x" to 1), emptyMap(),
        )
        val x = QuadraticVariable.Real(SourceRealId(0))
        val objective = QuadraticObjectiveSpec(listOf(
            QuadraticObjectiveTerm(QuadraticCoefficient.ofLong(-1), x, x),
        ))
        val params = NonlinearIntegerTheoryParams(polynomial = PolynomialTheoryParams(
            strategy = PolynomialStrategyParams(localSearch = LocalSearchParams(maxFlips = 100000, randomSeed = 17)),
        ), maximumMoves = 2)

        val execution = OpenTheoryPipeline.minimize(
            source.pipelineRequest(), objective, params, FiniteEngine.LOCAL_SEARCH,
        )

        val result = assertIs<QuadraticOptimizationResult.BestFound>(execution.result)
        assertTrue(requireNotNull(result.incumbent.value.rational) <= BigFraction.ofLong(-4))
        assertEquals(2.0, result.stats.ls.moves.sum)
    }

    @Test
    fun `fractional real witnesses cannot satisfy an integer square equation`() {
        val coordinate = PolynomialExpression.Variable(SourceRealId(0))
        val source = NonlinearIntegerProblem(
            PolynomialRealFormula.And(listOf(
                PolynomialRealFormula.Atom(PolynomialExpression.Power(coordinate, 2), PolynomialRelation.EQ,
                    PolynomialExpression.Constant.of(BIG_ONE, parseBigInt("4"))),
                PolynomialRealFormula.Atom(coordinate, PolynomialRelation.GE,
                    PolynomialExpression.Constant.of(parseBigInt("0"))),
            )), 1, 0, mapOf("i" to 0), emptyMap(), emptyMap(),
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
    @Test
    fun `parallel integer traversal preserves the exact fractional relaxation optimum`() {
        val source = NonlinearIntegerProblem(
            PolynomialRealFormula.Constant(true), 1, 0, mapOf("i" to 0), emptyMap(), emptyMap())
        val i = QuadraticVariable.Int(SourceIntId(0))
        val objective = QuadraticObjectiveSpec(listOf(
            QuadraticObjectiveTerm(QuadraticCoefficient.ONE, i, i),
            QuadraticObjectiveTerm(QuadraticCoefficient.ofLong(-1), i),
        ))

        val execution = OpenTheoryPipeline.minimize(source.pipelineRequest(), objective, NonlinearIntegerTheoryParams(),
            FiniteEngine.MIXED, ObjectiveSense.MINIMIZE, 4)

        val optimum = assertIs<QuadraticOptimizationResult.Optimal>(execution.result)
        assertEquals(BigFraction.ZERO, optimum.incumbent.value.rational)
        assertEquals(BigFraction.ZERO, optimum.bound.value.rational)
        assertEquals(4, optimum.stats.portfolio.arms.size)
    }


    @Test
    fun `sliced integer traversal preserves exact completion and cumulative native work`() {
        val source = NonlinearIntegerProblem(
            PolynomialRealFormula.Constant(true), 1, 0, mapOf("i" to 0), emptyMap(), emptyMap(),
        )
        val i = QuadraticVariable.Int(SourceIntId(0))
        val objective = QuadraticObjectiveSpec(listOf(
            QuadraticObjectiveTerm(QuadraticCoefficient.ONE, i, i),
            QuadraticObjectiveTerm(QuadraticCoefficient.ofLong(-1), i),
        ))
        val request = source.pipelineRequest()
        val params = NonlinearIntegerTheoryParams()
        val whole = OpenTheoryPipeline.minimize(request, objective, params, FiniteEngine.BACKTRACK)
        val native = QuadraticNativeScope(params.polynomial)
        val adapter = QuadraticIntegerOptimization(source, objective, params, FiniteEngine.BACKTRACK,
            ObjectiveSense.MINIMIZE, native, request.preparedPlan)
        val handle = assertIs<QuadraticPortfolioSearch>(
            adapter.worker(0).newResumableSearch { Double.POSITIVE_INFINITY },
        )
        var slices = 0

        while (!handle.isDone && slices++ < 4096) handle.runSlice(Cancellation.Never, Long.MAX_VALUE, 1) { }
        val result = assertIs<QuadraticOptimizationResult.Optimal>(handle.completion)
        handle.close()
        adapter.close()

        assertTrue(slices > 1)
        assertEquals(BigFraction.ZERO, result.incumbent.value.rational)
        assertEquals(BigFraction.ZERO, result.bound.value.rational)
        assertEquals(whole.nativeWork, native.work)
        assertEquals(whole.result.stats.search.nodes.sum, result.stats.search.nodes.sum)
    }
}
