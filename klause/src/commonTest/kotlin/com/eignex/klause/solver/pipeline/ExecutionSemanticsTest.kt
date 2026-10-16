package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.ir.BoundOverride
import com.eignex.klause.ir.BoundsOverride
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.QuadraticCoefficient
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.ir.QuadraticObjectiveTerm
import com.eignex.klause.ir.QuadraticVariable
import com.eignex.klause.ir.SemanticOverrides
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.lp.optimization.atScope
import com.eignex.klause.lp.optimization.numericContext
import com.eignex.klause.lp.optimization.withLinearAuthority
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.evidence.sourceProofContext
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.LinearOptimizationResult
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class ExecutionSemanticsTest {
    @Test
    fun `cancelled source optimization should retain its interpretation across entry points`() {
        val source = Problem(0, 0, emptyArray(), emptyArray())
        val polynomial = PolynomialTheoryParams(cancellation = Cancellation { true })
        val affine = LinearObjective()
        val quadratic = QuadraticObjectiveSpec(emptyList())

        for (strategy in listOf(BacktrackSolver.source(source), LocalSearchSolver.source(source))) {
            val results = listOf(
                strategy.optimize(affine, polynomial).semantics,
                strategy.optimize(quadratic, polynomial).semantics,
                strategy.optimizeOpen(affine, polynomial).semantics,
            )

            for (semantics in results) assertSame(source.semantics, semantics)
        }
    }

    @Test
    fun `interrupted open entry points should retain the original source interpretation`() {
        val source = Problem(
            0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
        )
        val objective = LinearObjective(realCoefficients = doubleArrayOf(1.0))
        val request = OpenTheoryRequest(source, objective)
        val params = TheoryParams(cancellation = Cancellation { true })

        val results = listOf(
            OpenTheoryPipeline.execute(request, params),
            OpenTheoryPipeline.execute(request, params, FiniteEngine.BACKTRACK),
            OpenTheoryPipeline.executePortfolio(request, params),
            OpenTheoryPipeline.searchWithoutTheory(source, params, objective),
            OpenTheoryPipeline.searchWithoutTheory(source, params, objective, FiniteEngine.LOCAL_SEARCH),
            OpenTheoryPipeline.searchWithoutTheory(request.preparedPlan, params, objective, FiniteEngine.LOCAL_SEARCH),
        )

        for (result in results) assertSame(source.semantics, result.semantics)
    }

    @Test
    fun `qualified open optimization should preserve its accepted publication and native verdict`() {
        val source = Problem(
            0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0),
        )
        val request = OpenTheoryRequest(source, LinearObjective(realCoefficients = doubleArrayOf(1.0)))
        val observed = mutableListOf<SourceIncumbentPublication>()

        val execution = assertIs<OpenTheoryExecution.Optimize>(OpenTheoryPipeline.execute(
            request, TheoryParams(openWorkLimit = 1000), FiniteEngine.BACKTRACK, 1, observed::add,
        ))
        val qualified = assertIs<OpenTheoryExecution.Optimize>(execution.withSourceSemantics(source.semantics))

        assertSame(source.semantics, execution.semantics)
        assertSame(observed.last(), assertNotNull(execution.acceptedIncumbent))
        assertSame(execution.result, qualified.result)
        assertSame(execution.acceptedIncumbent, qualified.acceptedIncumbent)
    }

    @Test
    fun `qualified quadratic optimization should preserve its accepted publication and exact verdict`() {
        val source = Problem(
            0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0),
        )
        val x = QuadraticVariable.Real(SourceRealId(0))
        val objective = QuadraticObjectiveSpec(listOf(QuadraticObjectiveTerm(QuadraticCoefficient.ONE, x, x)))
        val observed = mutableListOf<QuadraticIncumbentPublication>()

        val execution = OpenTheoryPipeline.minimize(
            source.pipelineRequest(), objective, PolynomialTheoryParams(maximumWork = 10000),
            FiniteEngine.BACKTRACK, ObjectiveSense.MINIMIZE, 1, observed::add,
        )
        val qualified = execution.withSourceSemantics(source.semantics)

        assertSame(source.semantics, execution.semantics)
        assertSame(observed.last(), assertNotNull(execution.acceptedIncumbent))
        assertSame(execution.result, qualified.result)
        assertSame(execution.acceptedIncumbent, qualified.acceptedIncumbent)
    }

    @Test
    fun `qualified execution envelopes should reject a foreign source interpretation`() {
        val semantics = SourceSemantics(SourceFormat.IR).resolve()
        val foreign = SourceSemantics(SourceFormat.SCHEMA).resolve()
        val open = OpenTheoryExecution.Optimize(OpenTheoryOptimum.Infeasible(SolveStats.EMPTY))
            .withSourceSemantics(semantics)
        val quadratic = QuadraticOptimizationExecution(
            QuadraticOptimizationResult.Unknown(null, TerminationReason.Cancelled, ObjectiveSense.MINIMIZE,
                SolveStats.EMPTY), PolynomialTheoryStats(), 0, 0,
        ).withSourceSemantics(semantics)

        assertFailsWith<IllegalArgumentException> { open.withSourceSemantics(foreign) }
        assertFailsWith<IllegalArgumentException> { quadratic.withSourceSemantics(foreign) }
        assertSame(semantics, open.semantics)
        assertSame(semantics, quadratic.semantics)
    }

    @Test
    fun `a cancelled direct theory solve should retain effective declaration defaults`() {
        val source = SmtLib.parse(
            "(declare-const x Int) (declare-const y Int) (assert (> x y))",
            semanticOverrides = SemanticOverrides(undeclaredIntegers = BoundsOverride(
                lower = BoundOverride.Value(2L),
            )),
        ).model
        val engine = OpenTheoryEngine(source, source.componentPlan().theoryPipeline)

        val result = engine.solve(TheoryParams(cancellation = Cancellation { true }))

        assertIs<OpenTheoryResult.Unknown>(result)
        assertSame(source.semantics, result.semantics)
    }

    @Test
    fun `a cancelled direct minimizer should retain its effective declaration defaults`() {
        val source = SmtLib.parse(
            "(declare-const x Int) (declare-const y Int) (assert (> x y))",
            semanticOverrides = SemanticOverrides(undeclaredIntegers = BoundsOverride(
                lower = BoundOverride.Value(2L),
            )),
        ).model
        val minimizer = OpenTheoryMinimizer(source, LinearObjective(intCoefficients = longArrayOf(1, 0)))

        val result = minimizer.minimize(TheoryParams(cancellation = Cancellation { true }))

        assertIs<OpenTheoryOptimum.Bounded>(result)
        assertSame(source.semantics, result.semantics)
    }

    @Test
    fun `source context decoration should preserve its captured interpretation`() {
        val source = SmtLib.parse(
            "(declare-const x Int) (declare-const y Int) (assert (> x y))",
            semanticOverrides = SemanticOverrides(undeclaredIntegers = BoundsOverride(
                lower = BoundOverride.Value(2L),
            )),
        ).model
        val scope = SourceScope(SourceRevision("context-source", "klause-ir-exact-v1"),
            objective = SourceObjective("x", ObjectiveSense.MINIMIZE))
        val context = assertNotNull(source.sourceProofContext(scope,
            LinearObjective(intCoefficients = longArrayOf(1, 0))))

        val contexts = listOf(context, context.withLinearAuthority(), context.atScope(scope.copy()),
            context.numericContext())

        for (captured in contexts) assertSame(source.semantics, captured.semantics)
    }

    @Test
    fun `linear source execution should retain policy without inventing a checked claim`() {
        val source = SmtLib.parse(
            "(declare-const x Int) (declare-const y Int) (assert (> x y))",
            semanticOverrides = SemanticOverrides(undeclaredIntegers = BoundsOverride(
                lower = BoundOverride.Value(2L),
            )),
        ).model
        val scope = SourceScope(SourceRevision("linear-source", "klause-ir-exact-v1"),
            objective = SourceObjective("x", ObjectiveSense.MINIMIZE))
        val context = assertNotNull(source.sourceProofContext(scope,
            LinearObjective(intCoefficients = longArrayOf(1, 0)))).withLinearAuthority()

        val result = OpenTheoryPipeline.optimizeLinear(context, TheoryParams(cancellation = Cancellation { true }))

        assertSame(source.semantics, result.semantics)
        assertSame(scope, result.scope)
        assertNull(result.terminal)
        assertNull(result.bound)
        assertNull(result.incumbent)
    }

    @Test
    fun `a linear context without a source owner should keep its result unbound`() {
        val coordinate = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
        val context = SourceProofContext(
            SourceScope(SourceRevision("unbound-context", "klause-ir-exact-v1"),
                objective = SourceObjective("x", ObjectiveSense.MINIMIZE)),
            setOf(coordinate), emptyMap(), mapOf(coordinate to BigFraction.ONE),
        )

        val result = OpenTheoryPipeline.optimizeLinear(context, TheoryParams(cancellation = Cancellation { true }))

        assertNull(context.semantics)
        assertNull(result.semantics)
        assertNull(result.terminal)
    }

    @Test
    fun `qualifying an open optimum should preserve its point and checked linear result`() {
        val semantics = SourceSemantics(SourceFormat.IR).resolve()
        val point = Sample(booleanArrayOf(), longArrayOf(2))
        val assignment = OpenTheoryAssignment.Difference(point)
        val scope = SourceScope(SourceRevision("retained-context", "klause-ir-exact-v1"))
        val linear = LinearOptimizationResult(scope, null, null, point, BigFraction.ofLong(2),
            TerminationReason.BudgetExhausted, SolveStats.EMPTY, semantics)
        val native = OpenTheoryOptimum.Bounded(assignment, BigFraction.ofLong(2), TerminationReason.BudgetExhausted,
            SolveStats.EMPTY, linear, semantics = null)

        val result = assertIs<OpenTheoryOptimum.Bounded>(native.withSourceSemantics(semantics))

        assertSame(semantics, result.semantics)
        assertSame(assignment, result.incumbent)
        assertSame(linear, result.linearResult)
        assertSame(point, result.linearResult?.incumbent)
        assertNull(point.semantics)
    }

    @Test
    fun `an open optimum should reject a foreign checked linear interpretation`() {
        val semantics = SourceSemantics(SourceFormat.IR).resolve()
        val linear = LinearOptimizationResult(
            SourceScope(SourceRevision("foreign-context", "klause-ir-exact-v1")), null, null, null, null,
            TerminationReason.Cancelled, SolveStats.EMPTY, semantics,
        )
        val result = OpenTheoryOptimum.Bounded(null, null, TerminationReason.Cancelled, SolveStats.EMPTY,
            linear, semantics = null)

        assertFailsWith<IllegalArgumentException> {
            result.withSourceSemantics(SourceSemantics(SourceFormat.SMTLIB).resolve())
        }

        assertSame(linear, result.linearResult)
    }

    @Test
    fun `numerical admission should retain source policy before a context is available`() {
        val source = SmtLib.parse(
            "(declare-const x Int) (declare-const y Int) (assert (> x y))",
            semanticOverrides = SemanticOverrides(undeclaredIntegers = BoundsOverride(
                lower = BoundOverride.Value(2L),
            )),
        ).model

        val result = NumericalLinearRequest(source, LinearObjective(intCoefficients = longArrayOf(1, 0)))
            .solve(TheoryParams(cancellation = Cancellation { true }))

        assertSame(source.semantics, result.semantics)
        assertNull(result.terminal)
        assertNull(result.bound)
    }

}
