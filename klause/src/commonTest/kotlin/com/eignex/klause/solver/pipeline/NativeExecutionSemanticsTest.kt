package com.eignex.klause.solver.pipeline

import com.eignex.klause.formats.smtlib.SmtLibAdmission
import com.eignex.klause.formats.smtlib.SmtLibInput
import com.eignex.klause.ir.BoundOverride
import com.eignex.klause.ir.BoundsOverride
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SemanticOverrides
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertSame

class NativeExecutionSemanticsTest {
    @Test
    fun `polynomial execution should retain effective real defaults after cancellation`() {
        val source = assertIs<SmtLibInput.Polynomial>(SmtLibAdmission.parse(
            "(declare-const x Real) (assert (= (* x x) 1))",
            semanticOverrides = SemanticOverrides(undeclaredReals = BoundsOverride(
                upper = BoundOverride.Value(SemanticRational.ONE),
            )),
        )).problem

        val result = OpenTheoryPipeline.execute(source.pipelineRequest(),
            PolynomialTheoryParams(cancellation = Cancellation { true }), FiniteEngine.BACKTRACK).result

        assertIs<OpenTheoryResult.Unknown>(result)
        assertSame(source.semantics, result.semantics)
    }

    @Test
    fun `nonlinear integer execution should retain effective integer defaults after cancellation`() {
        val source = assertIs<SmtLibInput.NonlinearInteger>(SmtLibAdmission.parse(
            "(declare-const x Int) (assert (= (* x x) 1))",
            semanticOverrides = SemanticOverrides(undeclaredIntegers = BoundsOverride(
                upper = BoundOverride.Value(1L),
            )),
        )).problem

        val result = OpenTheoryPipeline.execute(source.pipelineRequest(), NonlinearIntegerTheoryParams(
            polynomial = PolynomialTheoryParams(cancellation = Cancellation { true }),
        ), FiniteEngine.BACKTRACK).result

        assertIs<OpenTheoryResult.Unknown>(result)
        assertSame(source.semantics, result.semantics)
    }

    @Test
    fun `transcendental execution should retain effective real defaults after cancellation`() {
        val source = assertIs<SmtLibInput.Transcendental>(SmtLibAdmission.parse(
            "(declare-const x Real) (assert (= (exp 0) 1)) (assert (> x 0))",
            semanticOverrides = SemanticOverrides(undeclaredReals = BoundsOverride(
                upper = BoundOverride.Value(SemanticRational.ONE),
            )),
        )).problem

        val result = OpenTheoryPipeline.execute(source.pipelineRequest(),
            TranscendentalTheoryParams(cancellation = Cancellation { true }), FiniteEngine.BACKTRACK).result

        assertIs<OpenTheoryResult.Unknown>(result)
        assertSame(source.semantics, result.semantics)
    }

    @Test
    fun `private polynomial views should preserve the original model interpretation`() {
        val semantics = SourceSemantics(SourceFormat.SCHEMA).resolve()
        val source = Problem(
            0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0), semantics = semantics,
        )

        val request = source.pipelineRequest()

        assertSame(semantics, request.problem.semantics)
        assertSame(source, request.preparedPlan.originalSource)
    }

    @Test
    fun `finite polynomial optimization should retain its source policy on objective decline`() {
        val source = assertIs<SmtLibInput.Polynomial>(SmtLibAdmission.parse(
            "(declare-const x Real) (assert (= (* x x) 1))",
        )).problem

        val execution = OpenTheoryPipeline.minimize(source.pipelineRequest(),
            LinearObjective(realCoefficients = doubleArrayOf(1.0)), engine = FiniteEngine.BACKTRACK)

        assertSame(source.semantics, execution.result.semantics)
    }
}
