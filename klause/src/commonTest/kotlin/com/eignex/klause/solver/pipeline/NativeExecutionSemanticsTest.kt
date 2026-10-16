package com.eignex.klause.solver.pipeline

import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.formats.smtlib.realSource
import com.eignex.klause.ir.BoundOverride
import com.eignex.klause.ir.BoundsOverride
import com.eignex.klause.ir.NonlinearIntegerProblem
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SemanticOverrides
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.ir.TranscendentalProblem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertSame

class NativeExecutionSemanticsTest {
    @Test
    fun `polynomial execution should retain effective real defaults after cancellation`() {
        val source = SmtLib.parse(
            "(declare-const x Real) (assert (= (* x x) 1))",
            semanticOverrides = SemanticOverrides(undeclaredReals = BoundsOverride(
                upper = BoundOverride.Value(SemanticRational.ONE),
            )),
        )
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            source.source, ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK,
        ))

        val completed = SourceSetup.execute(SourceExecutionRequest(invocation,
            polynomial = PolynomialTheoryParams(cancellation = Cancellation { true })))
        val result = assertIs<SourceExecutionResult.Polynomial>(completed).result.result

        assertIs<OpenTheoryResult.Unknown>(result)
        assertSame(source.semantics, result.semantics)
    }

    @Test
    fun `nonlinear integer execution should retain effective integer defaults after cancellation`() {
        val source = assertIs<NonlinearIntegerProblem>((SmtLib.parse(
            "(declare-const x Int) (assert (= (* x x) 1))",
            semanticOverrides = SemanticOverrides(undeclaredIntegers = BoundsOverride(
                upper = BoundOverride.Value(1L),
            )),
        )).source.originalSource)

        val result = OpenTheoryPipeline.execute(source.pipelineRequest(), NonlinearIntegerTheoryParams(
            polynomial = PolynomialTheoryParams(cancellation = Cancellation { true }),
        ), FiniteEngine.BACKTRACK).result

        assertIs<OpenTheoryResult.Unknown>(result)
        assertSame(source.semantics, result.semantics)
    }

    @Test
    fun `transcendental execution should retain effective real defaults after cancellation`() {
        val source = assertIs<TranscendentalProblem>((SmtLib.parse(
            "(declare-const x Real) (assert (= (exp 0) 1)) (assert (> x 0))",
            semanticOverrides = SemanticOverrides(undeclaredReals = BoundsOverride(
                upper = BoundOverride.Value(SemanticRational.ONE),
            )),
        )).source.originalSource)

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
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (assert (= (* x x) 1))",
        ).realSource()

        val execution = OpenTheoryPipeline.minimize(source.pipelineRequest(),
            LinearObjective(realCoefficients = doubleArrayOf(1.0)), engine = FiniteEngine.BACKTRACK)

        assertSame(source.semantics, execution.result.semantics)
    }
}
