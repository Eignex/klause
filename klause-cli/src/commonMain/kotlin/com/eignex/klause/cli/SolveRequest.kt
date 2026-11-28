package com.eignex.klause.cli

import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SearchHints
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.IncrementalObjective
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.FiniteSolveShape
import com.eignex.klause.solver.pipeline.OpenTheoryAssignment
import com.eignex.klause.solver.pipeline.PreparedSourceInvocation
import com.eignex.klause.solver.pipeline.SourceProblemRoute
import com.eignex.klause.solver.pipeline.maximizeSourceObjective
import com.eignex.klause.solver.pipeline.pipelineRoute
import com.eignex.klause.solver.result.LpStats
import com.eignex.klause.solver.result.TerminationReason

// A parsed instance lowered to what the solve driver needs, independent of the mode.
internal class Solvable(
    private val finite: FiniteSolveShape?,
    val render: (Sample) -> String,
    // Sign-corrected, in source-model units.
    val objectiveValue: ((Sample) -> Long)?,
    val continuousObjectiveValue: ((Sample) -> Double)? = null,
    val rendering: SourceRendering = SourceRendering.FiniteCp,
    val routingLpStats: LpStats = LpStats(),
    val routingElapsedMs: Long = 0L,
    // Checks a source-space sample under tolerance semantics; null keeps exact semantics.
    val toleranceCheck: ((Sample) -> Boolean)? = null,
    val numericalCompletion: Boolean = false,
    val sourceInvocation: PreparedSourceInvocation? = finite?.sourceInvocation,
) {
    fun withToleranceCheck(check: (Sample) -> Boolean): Solvable = Solvable(
        finite,
        render,
        objectiveValue,
        continuousObjectiveValue,
        rendering,
        routingLpStats,
        routingElapsedMs,
        check,
        numericalCompletion,
        sourceInvocation,
    )

    fun withNumericalCompletion(): Solvable = Solvable(
        finite, render, objectiveValue,
        continuousObjectiveValue, rendering, routingLpStats, routingElapsedMs, toleranceCheck, true, sourceInvocation,
    )

    constructor(
        problem: Problem?,
        optimize: Boolean,
        // The objectives already negate for maximization.
        maximize: Boolean,
        lsObjective: IncrementalObjective?,
        linearObjective: LinearObjective?,
        // Set when the objective is one variable; enables the enumerate-over-objective fallback.
        objVarId: Int?,
        definitionalSweep: DefinitionalSweep?,
        render: (Sample) -> String,
        // Discrete terms only, exact for integral formats whose coefficients can outrun a Double.
        objectiveValue: ((Sample) -> Long)?,
        // Set only when the objective weights a continuous column; the total then has no exact integral form.
        continuousObjectiveValue: ((Sample) -> Double)? = null,
        searchHints: SearchHints? = null,
        rendering: SourceRendering = SourceRendering.FiniteCp,
        routingLpStats: LpStats = LpStats(),
        routingElapsedMs: Long = 0L,
        preparedRoute: SourceProblemRoute.Finite? = null,
    ) : this(
        finite = problem?.finiteSolveShape(
            optimize,
            maximize,
            lsObjective,
            linearObjective,
            objVarId,
            definitionalSweep,
            searchHints,
            preparedRoute,
        ),
        render = render,
        objectiveValue = objectiveValue,
        continuousObjectiveValue = continuousObjectiveValue,
        rendering = rendering,
        routingLpStats = routingLpStats,
        routingElapsedMs = routingElapsedMs,
    )

    val problem: Problem? get() = finite?.problem
    val optimize: Boolean get() = finite?.optimize
        ?: (sourceInvocation?.operation == ExecutionOperation.OPTIMIZE)
    val maximize: Boolean get() = finite?.maximize ?: sourceInvocation?.maximizeSourceObjective ?: false
    val lsObjective: IncrementalObjective? get() = finite?.localSearchObjective
    val linearObjective: LinearObjective? get() = finite?.linearObjective
    val objVarId: Int? get() = finite?.objectiveIntVar
    val definitionalSweep: DefinitionalSweep? get() = finite?.definitionalSweep
    val searchHints: SearchHints? get() = finite?.searchHints
    val finiteShape: FiniteSolveShape get() = requireNotNull(finite) { "open model was not materialized" }
    val finiteProblem: Problem get() = requireNotNull(problem) { "open model was not materialized" }
}

private fun Problem.finiteSolveShape(
    optimize: Boolean,
    maximize: Boolean,
    localSearchObjective: IncrementalObjective?,
    linearObjective: LinearObjective?,
    objectiveIntVar: Int?,
    definitionalSweep: DefinitionalSweep?,
    searchHints: SearchHints?,
    preparedRoute: SourceProblemRoute.Finite?,
): FiniteSolveShape {
    require(preparedRoute == null || preparedRoute.problem === this) { "finite route has a different execution model" }
    val route = preparedRoute ?: pipelineRoute(linearObjective, maximize)
    require(route is SourceProblemRoute.Finite) { "finite solve requested for ${sourceRouteName(route)}" }
    return FiniteSolveShape(
        route.problem,
        optimize,
        maximize,
        localSearchObjective,
        linearObjective,
        objectiveIntVar,
        definitionalSweep,
        searchHints,
        route.componentPlan,
        route.preparedPlan,
    )
}

private fun sourceRouteName(route: SourceProblemRoute): String = when (route) {
    is SourceProblemRoute.NativeFormula -> "a native formula"
    is SourceProblemRoute.PolynomialTheory -> "a polynomial model"
    is SourceProblemRoute.Finite -> "a finite model"
    is SourceProblemRoute.OpenTheory -> "an open theory model"
    is SourceProblemRoute.UnsupportedOpen -> "an unsupported open model"
    SourceProblemRoute.Refuted -> "a model routing refuted"
}

internal sealed interface SourceRendering {
    data class LinearSource(
        val invocation: PreparedSourceInvocation,
        val render: (com.eignex.klause.solver.validation.LinearSourceWitness) -> String,
    ) : SourceRendering

    data class ExactSource(val invocation: PreparedSourceInvocation, val render: (OpenTheoryAssignment) -> String) :
        SourceRendering

    data object FiniteCp : SourceRendering

    data class Interrupted(val reason: TerminationReason) : SourceRendering
}
