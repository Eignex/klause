package com.eignex.klause.solver.pipeline

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.formats.smtlib.realSource
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRealProblem
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PolynomialTheoryRequestTest {
    @Test
    fun `constant polynomial truth should have a runnable empty ALNS projection`() {
        val source = PolynomialRealProblem(
            PolynomialRealFormula.Constant(true),
            1,
            0,
            1,
            0,
            mapOf("x" to 0),
            emptyMap(),
        )
        val params = PolynomialTheoryParams(strategy = PolynomialStrategyParams(alnsIterations = 2))

        val execution = OpenTheoryPipeline.execute(source.pipelineRequest(), params, FiniteEngine.ALNS)

        val witness = assertIs<OpenTheoryResult.Sat>(execution.result).assignment
        assertNotNull(witness.algebraicRealValue(0))
    }

    @Test
    fun `constant polynomial refutation should stay unknown in a heuristic strategy`() {
        val source = PolynomialRealProblem(
            PolynomialRealFormula.Constant(false),
            1,
            0,
            1,
            0,
            mapOf("x" to 0),
            emptyMap(),
        )

        val params = PolynomialTheoryParams(
            strategy = PolynomialStrategyParams(
                localSearch = LocalSearchParams(maxFlips = 2),
            ),
        )

        val execution = OpenTheoryPipeline.execute(source.pipelineRequest(), params, FiniteEngine.ALNS)

        assertIs<OpenTheoryResult.Unknown>(execution.result)
    }

    @Test
    fun `mixed polynomial portfolio should find a witness after complete search is capped`() {
        val input = SmtLibPolynomial.parse(
            "(declare-const b Bool) (declare-const x Real) (assert b) (assert (= (* x x) 2))",
        ).realSource()
        val params = PolynomialTheoryParams(
            strategy = PolynomialStrategyParams(
                backtrack = BacktrackParams(maxInstructions = 0),
                localSearch = LocalSearchParams(randomSeed = 17, maxFlips = 10),
            ),
        )

        val execution = OpenTheoryPipeline.execute(input.pipelineRequest(), params, FiniteEngine.MIXED)

        val witness = assertIs<OpenTheoryResult.Sat>(execution.result)
        assertNotNull(witness.assignment.algebraicRealValue(0))
        assertTrue(witness.stats.ls.moves.sum > 0)
    }

    @Test
    fun `native exhaustion should stop every polynomial strategy before entry`() {
        val input = SmtLibPolynomial.parse("(declare-const x Real) (assert (= (* x x) 2))").realSource()

        for (engine in FiniteEngine.entries) {
            val execution = OpenTheoryPipeline.execute(
                input.pipelineRequest(),
                PolynomialTheoryParams(maximumWork = 0),
                engine,
            )

            assertEquals(TerminationReason.BudgetExhausted, assertIs<OpenTheoryResult.Unknown>(execution.result).reason)
            assertEquals(0L, execution.stats.workSpent)
        }
    }

    @Test
    fun `source assumptions should publish original coordinates without numeric ite auxiliaries`() {
        val input = SmtLibPolynomial.parse(
                """
                (declare-const b Bool)
                (declare-const x Real)
                (declare-const unused Real)
                (assert (= (* x x) 1))
                (assert (= (ite b x (- x)) 1))
                (check-sat-assuming (b))
                """.trimIndent(),
            ).realSource()

        val execution = OpenTheoryPipeline.execute(input.pipelineRequest())

        val result = assertIs<OpenTheoryResult.Sat>(execution.result)
        val witness = assertIs<OpenTheoryAssignment.ExactNra>(result.assignment)
        assertEquals(1, witness.assignment.boolCount)
        assertEquals(2, witness.assignment.realCount)
        assertEquals(true, witness.boolValue(0))
        val arithmetic = AlgebraicArithmetic()
        val actual = assertNotNull(witness.algebraicRealValue(0))
        assertEquals(0, arithmetic.compare(actual, arithmetic.rational(BigFraction.ONE)))
        assertNotNull(witness.algebraicRealValue(1))
    }

    @Test
    fun `helper free variables should keep their definition scope under caller lets`() {
        for ((definition, call) in listOf(
            "(define-fun f ((y Real)) Real (* x y))" to "(= (f 2) 14)",
            "(define-fun f () Real x)" to "(= f 7)",
        )) {
            val input = SmtLibPolynomial.parse(
                    "(declare-const x Real) $definition (assert (= x 1)) " +
                        "(assert (let ((x 7)) $call)) (check-sat)",
                ).realSource()

            val execution = OpenTheoryPipeline.execute(input.pipelineRequest())

            assertIs<OpenTheoryResult.Unsat>(execution.result)
        }
    }

    @Test
    fun `helper arguments should read caller bindings before entering definition scope`() {
        val input = SmtLibPolynomial.parse(
                "(declare-const x Real) (define-fun f ((y Real)) Real (* x y)) (assert (= x 1)) " +
                    "(assert (let ((x 7)) (= (f x) 7))) (check-sat)",
            ).realSource()

        val execution = OpenTheoryPipeline.execute(input.pipelineRequest())

        val witness = assertIs<OpenTheoryResult.Sat>(execution.result).assignment
        val arithmetic = AlgebraicArithmetic()
        val actual = assertNotNull(witness.algebraicRealValue(0))
        assertEquals(0, arithmetic.compare(actual, arithmetic.rational(BigFraction.ONE)))
    }

    @Test
    fun `complete route should refute a strict irrational boundary`() {
        val input = SmtLibPolynomial.parse(
                "(declare-const x Real) (assert (= (* x x) 2)) (assert (> x 0)) (assert (< (* x x) 2))",
            ).realSource()

        val execution = OpenTheoryPipeline.execute(input.pipelineRequest())

        assertIs<OpenTheoryResult.Unsat>(execution.result)
    }

    @Test
    fun `expired wall token should stay distinct from external cancellation`() {
        val input = SmtLibPolynomial.parse("(declare-const x Real) (assert (= (* x x) 2))").realSource()

        val execution = OpenTheoryPipeline.execute(
            input.pipelineRequest(),
            PolynomialTheoryParams(timeout = Cancellation { true }),
        )

        assertEquals(TerminationReason.Timeout, assertIs<OpenTheoryResult.Unknown>(execution.result).reason)
        assertEquals(true, execution.result.stats.run.timedOut)
    }

    @Test
    fun `external cancellation should preserve the cancelled reason`() {
        val input = SmtLibPolynomial.parse("(declare-const x Real) (assert (= (* x x) 2))").realSource()

        val execution = OpenTheoryPipeline.execute(
            input.pipelineRequest(),
            PolynomialTheoryParams(cancellation = Cancellation { true }),
        )

        assertEquals(TerminationReason.Cancelled, assertIs<OpenTheoryResult.Unknown>(execution.result).reason)
    }

    @Test
    fun `representation resource decline should keep its native cause`() {
        val input = SmtLibPolynomial.parse("(declare-const x Real) (assert (= (* x x) 2))").realSource()

        val execution = OpenTheoryPipeline.execute(
            input.pipelineRequest(),
            PolynomialTheoryParams(maximumDenseCoefficients = 1),
        )

        assertEquals(TerminationReason.BudgetExhausted, assertIs<OpenTheoryResult.Unknown>(execution.result).reason)
        assertEquals(PolynomialTheoryResource.DenseCoefficients, execution.resource)
    }
}
