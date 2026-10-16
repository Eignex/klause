package com.eignex.klause.solver.pipeline

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.formats.smtlib.smtLibSemantics
import com.eignex.klause.ir.NonlinearIntegerProblem
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRealProblem
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.ir.QuadraticCoefficient
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.ir.QuadraticObjectiveTerm
import com.eignex.klause.ir.QuadraticVariable
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.ir.TranscendentalExpression
import com.eignex.klause.ir.TranscendentalFormula
import com.eignex.klause.ir.TranscendentalProblem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration

class NativeSourceExecutionTest {
    @Test
    fun `native polynomial execution should retain original source and captured preparation settings`() {
        val parsed = SmtLibPolynomial.parse(
            "(declare-const x Real) (assert (= (* x x) 1)) (assert (> x 0))",
        )
        val source = PolynomialRealProblem(
            parsed.formula,
            parsed.realCount,
            parsed.boolCount,
            parsed.sourceRealCount,
            parsed.sourceBoolCount,
            parsed.realVarNames,
            parsed.boolVarNames,
            smtLibSemantics.resolve(),
        )
        val settings = ProblemSettings(lpMaxTableauCells = 8192, lpCeilingTableauCells = 16384)
        val attachment = SourceAttachment(source, settings)
        val invocation = SourceSetup.prepare(
            SourceSetupRequest(
                attachment,
                ExecutionOperation.SATISFY,
                FiniteEngine.BACKTRACK,
            ),
        )

        val result = assertIs<SourceExecutionResult.Polynomial>(
            SourceSetup.execute(
                SourceExecutionRequest(
                    invocation,
                    finiteRequest = { error("native formula constructed an unrelated finite consumer") },
                ),
            ),
        )

        val point = assertIs<OpenTheoryResult.Sat>(result.result.result).assignment
        val arithmetic = AlgebraicArithmetic()
        assertEquals(
            0,
            arithmetic.compare(assertNotNull(point.algebraicRealValue(0)), arithmetic.rational(BigFraction.ONE)),
        )
        assertSame(source, result.invocation.source.originalSource)
        assertSame(source.semantics, result.semantics)
        assertSame(settings, attachment.settings)
        assertSame(settings, assertNotNull(assertNotNull(invocation.preparedPlan).model).settings)
        assertTrue(result.result.stats.workSpent > 0)
    }

    @Test
    fun `native mixed formulas should preserve interleaved integer and real output identities`() {
        val x = PolynomialExpression.Variable(SourceRealId(0))
        val n = PolynomialExpression.Variable(SourceRealId(1))
        val source = NonlinearIntegerProblem(
            PolynomialRealFormula.And(
                listOf(
                    PolynomialRealFormula.Atom(n, PolynomialRelation.EQ, PolynomialExpression.Constant.of(bigIntOf(2))),
                    PolynomialRealFormula.Atom(PolynomialExpression.Power(x, 2), PolynomialRelation.EQ, n),
                ),
            ),
            2,
            0,
            mapOf("n" to 1),
            mapOf("x" to 0),
            emptyMap(),
        )
        val invocation = SourceSetup.prepare(
            SourceSetupRequest(
                SourceAttachment(source, ProblemSettings()),
                ExecutionOperation.SATISFY,
                FiniteEngine.BACKTRACK,
            ),
        )

        val result = assertIs<SourceExecutionResult.Polynomial>(
            SourceSetup.execute(
                SourceExecutionRequest(
                    invocation,
                    NonlinearIntegerTheoryParams(),
                ),
            ),
        )

        val point = assertIs<OpenTheoryResult.Sat>(result.result.result).assignment
        assertEquals("2", point.intValue(0))
        assertNotNull(point.algebraicRealValue(0))
        assertSame(source, assertNotNull(invocation.preparedPlan).originalSource)
        assertEquals(SourceExecutionKind.NONLINEAR_INTEGER, SourceSetup.executionKind(invocation))
        assertFailsWith<IllegalArgumentException> { invocation.finiteShape() }
    }

    @Test
    fun `native polynomial maximization should retain exact coefficients and accepted publications`() {
        val parsed = SmtLibPolynomial.parse("(declare-const x Real) (assert (and (>= x 1) (<= x 2)))")
        val source = PolynomialRealProblem(
            parsed.formula,
            parsed.realCount,
            parsed.boolCount,
            parsed.sourceRealCount,
            parsed.sourceBoolCount,
            parsed.realVarNames,
            parsed.boolVarNames,
            smtLibSemantics.resolve(),
        )
        val x = QuadraticVariable.Real(SourceRealId(0))
        val objective = QuadraticObjectiveSpec(
            listOf(
                QuadraticObjectiveTerm(QuadraticCoefficient.ofLong(3), x, x),
            ),
            QuadraticCoefficient.ofLong(7),
        )
        val invocation = SourceSetup.prepare(
            SourceSetupRequest(
                SourceAttachment(source, ProblemSettings()),
                FiniteEngine.BACKTRACK,
                objective,
                ObjectiveSense.MAXIMIZE,
            ),
        )
        val values = mutableListOf<BigFraction>()

        val result = assertIs<SourceExecutionResult.Quadratic>(
            SourceSetup.execute(
                SourceExecutionRequest(invocation),
                SourceExecutionCallbacks(onQuadraticIncumbent = {
                    values += assertNotNull(it.incumbent.value.rational)
                }),
            ),
        )

        val optimum = assertIs<QuadraticOptimizationResult.Optimal>(result.result.result)
        assertEquals(ObjectiveSense.MAXIMIZE, optimum.sense)
        assertEquals(BigFraction.ofLong(19), optimum.incumbent.value.rational)
        assertEquals(BigFraction.ofLong(19), values.last())
        assertSame(objective, invocation.sourceQuadraticObjective)
        assertSame(source, invocation.source.originalSource)
        assertNotNull(result.result.acceptedIncumbent)
    }

    @Test
    fun `native integer quadratic optimization should retain original integer objective coordinates`() {
        val source = NonlinearIntegerProblem(
            PolynomialRealFormula.Atom(
                PolynomialExpression.Variable(SourceRealId(0)),
                PolynomialRelation.EQ,
                PolynomialExpression.Constant.of(bigIntOf(2)),
            ),
            1,
            0,
            mapOf("n" to 0),
            emptyMap(),
            emptyMap(),
        )
        val n = QuadraticVariable.Int(SourceIntId(0))
        val objective = QuadraticObjectiveSpec(
            listOf(QuadraticObjectiveTerm(QuadraticCoefficient.ONE, n, n)),
            QuadraticCoefficient.ofLong(7),
        )
        val invocation = SourceSetup.prepare(
            SourceSetupRequest(
                SourceAttachment(source, ProblemSettings()),
                FiniteEngine.BACKTRACK,
                objective,
                ObjectiveSense.MAXIMIZE,
            ),
        )

        val result = assertIs<SourceExecutionResult.Quadratic>(
            SourceSetup.execute(
                SourceExecutionRequest(
                    invocation,
                    NonlinearIntegerTheoryParams(),
                ),
            ),
        )

        val optimum = assertIs<QuadraticOptimizationResult.Optimal>(result.result.result)
        assertEquals(BigFraction.ofLong(11), optimum.incumbent.value.rational)
        assertEquals("2", optimum.incumbent.sample.exactIntValue(0).toString())
        assertEquals(ObjectiveSense.MAXIMIZE, optimum.sense)
        assertSame(source, invocation.source.originalSource)
    }

    @Test
    fun `factor quadratic optimization should use the same source binding and native objective contract`() {
        val source = Problem(
            0,
            0,
            emptyArray(),
            emptyArray(),
            numRealVars = 1,
            realLower = doubleArrayOf(1.0),
            realUpper = doubleArrayOf(2.0),
        )
        val x = QuadraticVariable.Real(SourceRealId(0))
        val objective = QuadraticObjectiveSpec(listOf(QuadraticObjectiveTerm(QuadraticCoefficient.ONE, x, x)))
        val invocation = SourceSetup.prepare(
            SourceSetupRequest(
                SourceAttachment(source),
                FiniteEngine.BACKTRACK,
                objective,
                ObjectiveSense.MAXIMIZE,
            ),
        )

        val result = assertIs<SourceExecutionResult.Quadratic>(SourceSetup.execute(SourceExecutionRequest(invocation)))

        val optimum = assertIs<QuadraticOptimizationResult.Optimal>(result.result.result)
        assertEquals(BigFraction.ofLong(4), optimum.incumbent.value.rational)
        assertEquals(ObjectiveSense.MAXIMIZE, optimum.sense)
        assertSame(source, invocation.source.originalSource)
    }

    @Test
    fun `a stopped native source should not resume with fresh function controls`() {
        val source = TranscendentalProblem(
            TranscendentalFormula.Constant(true),
            emptyMap(),
            emptyMap(),
            emptyMap(),
        )
        var stopped = false
        val invocation = SourceSetup.prepare(
            SourceSetupRequest(
                SourceAttachment(source, ProblemSettings()),
                ExecutionOperation.SATISFY,
                FiniteEngine.BACKTRACK,
                cancellation = Cancellation { stopped },
            ),
        )
        stopped = true

        val first = assertIs<SourceExecutionResult.Transcendental>(
            SourceSetup.execute(
                SourceExecutionRequest(invocation, TranscendentalTheoryParams()),
            ),
        )
        stopped = false
        val second = assertIs<SourceExecutionResult.Transcendental>(
            SourceSetup.execute(
                SourceExecutionRequest(invocation, TranscendentalTheoryParams()),
            ),
        )

        assertEquals(TerminationReason.Cancelled, assertIs<OpenTheoryResult.Unknown>(first.result.result).reason)
        assertEquals(TerminationReason.Cancelled, assertIs<OpenTheoryResult.Unknown>(second.result.result).reason)
        assertEquals(0L, second.result.workSpent)
    }

    @Test
    fun `a stopped native preparation should retain its source deadline without exporting a plan`() {
        val source = TranscendentalProblem(
            TranscendentalFormula.Constant(true),
            emptyMap(),
            emptyMap(),
            emptyMap(),
        )
        val invocation = SourceSetup.prepare(
            SourceSetupRequest(
                SourceAttachment(source, ProblemSettings()),
                ExecutionOperation.SATISFY,
                FiniteEngine.BACKTRACK,
                cancellation = Cancellation.after(Duration.ZERO),
            ),
        )

        val result = assertIs<SourceExecutionResult.Declined>(SourceSetup.execute(SourceExecutionRequest(invocation)))

        assertNull(invocation.preparedPlan)
        assertEquals(TerminationReason.Timeout, result.reason)
        assertSame(source, result.invocation.source.originalSource)
    }

    @Test
    fun `zero native integer allowances should prevent arithmetic execution`() {
        val source = NonlinearIntegerProblem(
            PolynomialRealFormula.Constant(true),
            1,
            0,
            mapOf("n" to 0),
            emptyMap(),
            emptyMap(),
        )
        val invocation = SourceSetup.prepare(
            SourceSetupRequest(
                SourceAttachment(source, ProblemSettings()),
                ExecutionOperation.SATISFY,
                FiniteEngine.MIXED,
            ),
        )
        val controls = NonlinearIntegerTheoryParams(
            polynomial = PolynomialTheoryParams(maximumWork = 0),
            maximumCandidates = 0,
            maximumMoves = 0,
            nodeBudget = NodeBudget(0),
        )

        val result = assertIs<SourceExecutionResult.Polynomial>(
            SourceSetup.execute(
                SourceExecutionRequest(invocation, controls, cores = 2),
            ),
        )
        val native = OpenTheoryPipeline.execute(source.pipelineRequest(), controls, FiniteEngine.MIXED, cores = 2)

        assertEquals(
            assertIs<OpenTheoryResult.Unknown>(native.result).reason,
            assertIs<OpenTheoryResult.Unknown>(result.result.result).reason,
        )
        assertEquals(native.resource, result.result.resource)
        assertEquals(0L, native.stats.workSpent)
        assertEquals(0L, result.result.stats.workSpent)
    }

    @Test
    fun `zero native function work should retain the function resource result`() {
        val source = TranscendentalProblem(
            TranscendentalFormula.Constant(true),
            emptyMap(),
            emptyMap(),
            emptyMap(),
        )
        val invocation = SourceSetup.prepare(
            SourceSetupRequest(
                SourceAttachment(source, ProblemSettings()),
                ExecutionOperation.SATISFY,
                FiniteEngine.BACKTRACK,
            ),
        )

        val result = assertIs<SourceExecutionResult.Transcendental>(
            SourceSetup.execute(
                SourceExecutionRequest(
                    invocation,
                    TranscendentalTheoryParams(maximumWork = 0, maximumBits = 32, maximumDepth = 0),
                ),
            ),
        )

        assertEquals(TerminationReason.BudgetExhausted, assertIs<OpenTheoryResult.Unknown>(result.result.result).reason)
        assertEquals(0L, result.result.workSpent)
        assertSame(source.semantics, result.semantics)
    }

    @Test
    fun `an unsupported embedded function objective should retain its original operation and sense`() {
        val source = TranscendentalProblem(
            TranscendentalFormula.Constant(true),
            emptyMap(),
            emptyMap(),
            emptyMap(),
            objective = TranscendentalExpression.Constant(PolynomialExpression.Constant.of(bigIntOf(7))),
            objectiveSense = ObjectiveSense.MAXIMIZE,
        )
        val invocation = SourceSetup.prepare(
            SourceSetupRequest(
                SourceAttachment(source, ProblemSettings()),
                ExecutionOperation.OPTIMIZE,
                FiniteEngine.BACKTRACK,
                maximize = true,
            ),
        )

        val result = assertIs<SourceExecutionResult.Transcendental>(
            SourceSetup.execute(SourceExecutionRequest(invocation)),
        )

        assertEquals(TerminationReason.Unsupported, assertIs<OpenTheoryResult.Unknown>(result.result.result).reason)
        assertEquals(ExecutionOperation.OPTIMIZE, result.invocation.operation)
        assertEquals(true, result.invocation.maximizeSourceObjective)
        assertEquals(0L, result.result.workSpent)
    }

    @Test
    fun `native controls for a foreign source family should reject before worker execution`() {
        val parsed = SmtLibPolynomial.parse("(declare-const x Real) (assert (= (* x x) 1))")
        val source = PolynomialRealProblem(
            parsed.formula,
            parsed.realCount,
            parsed.boolCount,
            parsed.sourceRealCount,
            parsed.sourceBoolCount,
            parsed.realVarNames,
            parsed.boolVarNames,
            smtLibSemantics.resolve(),
        )
        val invocation = SourceSetup.prepare(
            SourceSetupRequest(
                SourceAttachment(source, ProblemSettings()),
                ExecutionOperation.SATISFY,
                FiniteEngine.BACKTRACK,
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            SourceSetup.execute(SourceExecutionRequest(invocation, TranscendentalTheoryParams()))
        }
    }

    @Test
    fun `native enumeration should reject before constructing an unrelated finite consumer`() {
        val parsed = SmtLibPolynomial.parse("(declare-const x Real) (assert (= (* x x) 1))")
        val source = PolynomialRealProblem(
            parsed.formula,
            parsed.realCount,
            parsed.boolCount,
            parsed.sourceRealCount,
            parsed.sourceBoolCount,
            parsed.realVarNames,
            parsed.boolVarNames,
            smtLibSemantics.resolve(),
        )
        val invocation = SourceSetup.prepare(
            SourceSetupRequest(
                SourceAttachment(source, ProblemSettings()),
                ExecutionOperation.ENUMERATE,
                FiniteEngine.BACKTRACK,
            ),
        )

        assertFailsWith<PipelineConfigException> {
            SourceSetup.execute(
                SourceExecutionRequest(
                    invocation,
                    finiteRequest = { error("native enumeration constructed a finite consumer") },
                ),
            )
        }
    }
}
