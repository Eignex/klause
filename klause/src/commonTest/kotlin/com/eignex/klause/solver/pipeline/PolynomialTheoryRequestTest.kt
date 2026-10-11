package com.eignex.klause.solver.pipeline

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

class PolynomialTheoryRequestTest {
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
        assertEquals(BigFraction.ONE, assertNotNull(witness.algebraicRealValue(0)).rational)
        assertNotNull(witness.algebraicRealValue(1))
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
    fun `a local search request receives no polynomial proof authority`() {
        val input = assertIs<SmtLibInput.Polynomial>(
            SmtLibAdmission.parse("(declare-const x Real) (assert (= (* x x) 2))"),
        )

        val execution = OpenTheoryPipeline.execute(input.problem.pipelineRequest(), engine = FiniteEngine.LOCAL_SEARCH)

        assertEquals(TerminationReason.Unsupported, assertIs<OpenTheoryResult.Unknown>(execution.result).reason)
        assertEquals(PolynomialTheoryStats(), execution.stats)
        assertNull(execution.resource)
    }
}
