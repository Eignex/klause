package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.ir.SourceBoolId
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.ir.TranscendentalExpression
import com.eignex.klause.ir.TranscendentalFormula
import com.eignex.klause.ir.TranscendentalFunction
import com.eignex.klause.ir.TranscendentalProblem
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class TranscendentalAssignmentTest {
    @Test
    fun `zero backtracking instructions prevent required Boolean traversal`() {
        val x = TranscendentalExpression.Variable(SourceRealId(0))
        val two = BigFraction.ofLong(2)
        val source = TranscendentalProblem(TranscendentalFormula.And(listOf(
            TranscendentalFormula.BooleanVariable(SourceBoolId(0)),
            TranscendentalFormula.Atom(TranscendentalExpression.Function(TranscendentalFunction.EXP, x),
                PolynomialRelation.EQ,
                TranscendentalExpression.Constant(PolynomialExpression.Constant.of(two.num, two.den))),
        )), emptyMap(), mapOf("x" to 0), mapOf("b" to 0))
        val params = TranscendentalTheoryParams(maximumWork = 20000, maximumDepth = 0,
            strategy = PolynomialStrategyParams(backtrack = BacktrackParams(maxInstructions = 0)))

        val execution = OpenTheoryPipeline.execute(source.pipelineRequest(), params, FiniteEngine.BACKTRACK)

        val result = assertIs<OpenTheoryResult.Unknown>(execution.result)
        assertEquals(0.0, result.stats.search.nodes.sum)
    }

    @Test
    fun `shared source callback preserves its exact witness after later cancellation`() {
        val x = TranscendentalExpression.Variable(SourceRealId(0))
        val two = BigFraction.ofLong(2)
        val source = TranscendentalProblem(
            TranscendentalFormula.Atom(TranscendentalExpression.Function(TranscendentalFunction.EXP, x),
                PolynomialRelation.EQ,
                TranscendentalExpression.Constant(PolynomialExpression.Constant.of(two.num, two.den))),
            emptyMap(), mapOf("x" to 0), emptyMap(),
        )
        val request: ExactTheorySatisfactionRequest = source.pipelineRequest()
        val params = PolynomialTheoryParams(maximumWork = 50000, strategy = PolynomialStrategyParams(
            localSearch = LocalSearchParams(maxFlips = 16, maxInstructions = 30000), alnsIterations = 8,
        ))

        val first = request.execute(params, FiniteEngine.MIXED, 1)
        val later = request.execute(params.copy(cancellation = Cancellation { true }), FiniteEngine.MIXED, 1)

        val witness = assertIs<OpenTheoryResult.Sat>(first.result).assignment
        assertEquals("log(2)", witness.realValue(0))
        assertEquals("transcendental", first.nativeWorkScope)
        assertIs<OpenTheoryResult.Unknown>(later.result)
        assertEquals("log(2)", witness.realValue(0))
    }

    @Test
    fun `replacing approximate coordinates discards symbolic authority`() {
        val one = BigFraction.ONE
        val source = TranscendentalProblem(TranscendentalFormula.Atom(
            TranscendentalExpression.Variable(SourceRealId(0)), PolynomialRelation.EQ,
            TranscendentalExpression.Constant(PolynomialExpression.Constant.of(one.num, one.den)),
        ), emptyMap(), mapOf("x" to 0), emptyMap())
        val result = assertIs<OpenTheoryResult.Sat>(OpenTheoryPipeline.execute(source.pipelineRequest()).result)
        val sample = assertIs<TranscendentalAssignment>(result.assignment).sample

        val replaced = sample.copy(reals = doubleArrayOf(2.0))

        assertNull(replaced.symbolicReals)
        assertEquals(one, sample.symbolicReals?.single()?.rational)
    }
}
