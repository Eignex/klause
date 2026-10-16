package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.QuadraticCoefficient
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.ir.QuadraticObjectiveTerm
import com.eignex.klause.ir.QuadraticVariable
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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
}
