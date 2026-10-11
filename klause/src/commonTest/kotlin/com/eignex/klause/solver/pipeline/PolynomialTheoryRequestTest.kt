package com.eignex.klause.solver.pipeline

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.formats.smtlib.SmtLibAdmission
import com.eignex.klause.formats.smtlib.SmtLibInput
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolynomialTheoryRequestTest {
    @Test
    fun `a mixed polynomial portfolio finds a witness after complete search is capped`() {
        val input = assertIs<SmtLibInput.Polynomial>(
            SmtLibAdmission.parse("(declare-const x Real) (assert (= (* x x) 2))"),
        )
        val params = PolynomialTheoryParams(strategy = PolynomialStrategyParams(
            backtrack = BacktrackParams(maxInstructions = 0),
            localSearch = LocalSearchParams(maxFlips = 3),
        ))

        val execution = OpenTheoryPipeline.execute(input.problem.pipelineRequest(), params, FiniteEngine.MIXED)

        val witness = assertIs<OpenTheoryResult.Sat>(execution.result)
        assertNotNull(witness.assignment.algebraicRealValue(0))
        assertTrue(witness.stats.ls.moves.sum > 0)
    }

    @Test
    fun `native exhaustion stops every polynomial strategy before entry`() {
        val input = assertIs<SmtLibInput.Polynomial>(
            SmtLibAdmission.parse("(declare-const x Real) (assert (= (* x x) 2))"),
        )

        for (engine in FiniteEngine.entries) {
            val execution = OpenTheoryPipeline.execute(
                input.problem.pipelineRequest(), PolynomialTheoryParams(maximumWork = 0), engine,
            )

            assertEquals(TerminationReason.BudgetExhausted, assertIs<OpenTheoryResult.Unknown>(execution.result).reason)
            assertEquals(0L, execution.stats.workSpent)
        }
    }

    @Test
    fun `source assumptions publish original coordinates without numeric ite auxiliaries`() {
        val input = assertIs<SmtLibInput.Polynomial>(
            SmtLibAdmission.parse(
                """
                (declare-const b Bool)
                (declare-const x Real)
                (declare-const unused Real)
                (assert (= (* x x) 1))
                (assert (= (ite b x (- x)) 1))
                (check-sat-assuming (b))
                """.trimIndent(),
            ),
        )

        val execution = OpenTheoryPipeline.execute(input.problem.pipelineRequest())

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
    fun `helper free variables retain their definition scope under caller lets`() {
        for ((definition, call) in listOf(
            "(define-fun f ((y Real)) Real (* x y))" to "(= (f 2) 14)",
            "(define-fun f () Real x)" to "(= f 7)",
        )) {
            val input = assertIs<SmtLibInput.Polynomial>(
                SmtLibAdmission.parse(
                    "(declare-const x Real) $definition (assert (= x 1)) " +
                        "(assert (let ((x 7)) $call)) (check-sat)",
                ),
            )

            val execution = OpenTheoryPipeline.execute(input.problem.pipelineRequest())

            assertIs<OpenTheoryResult.Unsat>(execution.result)
        }
    }

    @Test
    fun `helper arguments read caller bindings before entering definition scope`() {
        val input = assertIs<SmtLibInput.Polynomial>(
            SmtLibAdmission.parse(
                "(declare-const x Real) (define-fun f ((y Real)) Real (* x y)) (assert (= x 1)) " +
                    "(assert (let ((x 7)) (= (f x) 7))) (check-sat)",
            ),
        )

        val execution = OpenTheoryPipeline.execute(input.problem.pipelineRequest())

        val witness = assertIs<OpenTheoryResult.Sat>(execution.result).assignment
        val arithmetic = AlgebraicArithmetic()
        val actual = assertNotNull(witness.algebraicRealValue(0))
        assertEquals(0, arithmetic.compare(actual, arithmetic.rational(BigFraction.ONE)))
    }

    @Test
    fun `a strict irrational boundary is refuted by the complete route`() {
        val input = assertIs<SmtLibInput.Polynomial>(
            SmtLibAdmission.parse(
                "(declare-const x Real) (assert (= (* x x) 2)) (assert (> x 0)) (assert (< (* x x) 2))",
            ),
        )

        val execution = OpenTheoryPipeline.execute(input.problem.pipelineRequest())

        assertIs<OpenTheoryResult.Unsat>(execution.result)
    }

    @Test
    fun `zero native work returns unknown budget exhaustion`() {
        val input = assertIs<SmtLibInput.Polynomial>(
            SmtLibAdmission.parse("(declare-const x Real) (assert (= (* x x) 2))"),
        )

        val execution = OpenTheoryPipeline.execute(
            input.problem.pipelineRequest(),
            PolynomialTheoryParams(maximumWork = 0),
        )

        assertEquals(TerminationReason.BudgetExhausted, assertIs<OpenTheoryResult.Unknown>(execution.result).reason)
        assertEquals(0L, execution.stats.workSpent)
    }

    @Test
    fun `expired wall tokens remain distinct from external cancellation`() {
        val input = assertIs<SmtLibInput.Polynomial>(
            SmtLibAdmission.parse("(declare-const x Real) (assert (= (* x x) 2))"),
        )

        val execution = OpenTheoryPipeline.execute(
            input.problem.pipelineRequest(),
            PolynomialTheoryParams(timeout = Cancellation { true }),
        )

        assertEquals(TerminationReason.Timeout, assertIs<OpenTheoryResult.Unknown>(execution.result).reason)
        assertEquals(true, execution.result.stats.run.timedOut)
    }

    @Test
    fun `external cancellation preserves the cancelled reason`() {
        val input = assertIs<SmtLibInput.Polynomial>(
            SmtLibAdmission.parse("(declare-const x Real) (assert (= (* x x) 2))"),
        )

        val execution = OpenTheoryPipeline.execute(
            input.problem.pipelineRequest(),
            PolynomialTheoryParams(cancellation = Cancellation { true }),
        )

        assertEquals(TerminationReason.Cancelled, assertIs<OpenTheoryResult.Unknown>(execution.result).reason)
    }

    @Test
    fun `representation resource declines retain their native cause`() {
        val input = assertIs<SmtLibInput.Polynomial>(
            SmtLibAdmission.parse("(declare-const x Real) (assert (= (* x x) 2))"),
        )

        val execution = OpenTheoryPipeline.execute(
            input.problem.pipelineRequest(),
            PolynomialTheoryParams(maximumDenseCoefficients = 1),
        )

        assertEquals(TerminationReason.BudgetExhausted, assertIs<OpenTheoryResult.Unknown>(execution.result).reason)
        assertEquals(PolynomialTheoryResource.DenseCoefficients, execution.resource)
    }

    @Test
    fun `a local search candidate receives an exact polynomial witness`() {
        val input = assertIs<SmtLibInput.Polynomial>(
            SmtLibAdmission.parse("(declare-const x Real) (assert (= (* x x) 2))"),
        )

        val execution = OpenTheoryPipeline.execute(input.problem.pipelineRequest(), engine = FiniteEngine.LOCAL_SEARCH)

        val witness = assertIs<OpenTheoryResult.Sat>(execution.result).assignment
        assertNotNull(witness.algebraicRealValue(0))
        assertTrue(execution.stats.workSpent > 0)
        assertNull(execution.resource)
    }
}
