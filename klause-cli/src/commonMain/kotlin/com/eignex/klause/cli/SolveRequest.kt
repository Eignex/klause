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

/**
 * A parsed instance, lowered to exactly what [SolveCore] needs — mode-neutral. Both the
 * FlatZinc objective-by-name + lsObjective/linear split and XCSP/SMT's single LinearObjective
 * collapse onto this shape.
 */
internal class Solvable(
    private val finite: FiniteSolveShape?,
    /** Render one solution to the mode's solution text. */
    val render: (Sample) -> String,
    /** Sign-corrected objective value in source-model units. */
    val objectiveValue: ((Sample) -> Long)?,
    /** Sign-corrected objective including continuous-column contributions. */
    val continuousObjectiveValue: ((Sample) -> Double)? = null,
    val rendering: SourceRendering = SourceRendering.FiniteCp,
    /** LP work performed while proving bounds for source routing. */
    val routingLpStats: LpStats = LpStats(),
    /** Wall time spent proving bounds for rendering selection. */
    val routingElapsedMs: Long = 0L,
    /** Tolerance semantics for continuous leaves: a check of a source-space sample; `null` keeps exact semantics. */
    val toleranceCheck: ((Sample) -> Boolean)? = null,
    val numericalCompletion: Boolean = false,
    val sourceInvocation: PreparedSourceInvocation? = finite?.sourceInvocation,
) {
    /** This instance solved under tolerance semantics with [check]. */
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
        /** Finite CP problem. */
        problem: Problem?,
        optimize: Boolean,
        /** True when the user goal is maximisation (the objectives below already negate for it). */
        maximize: Boolean,
        /** Per-move gradient view of the objective for the LS workers (null for satisfy / when
         *  none); rides into `LocalSearchParams.lsObjective`. */
        lsObjective: IncrementalObjective?,
        /** The canonical linear objective every optimising backend minimises (null for satisfy). */
        linearObjective: LinearObjective?,
        /** Single objective int var id, when the objective is one variable — enables the
         *  enumerate-over-objective fallback. Null for weighted-sum objectives / satisfy. */
        objVarId: Int?,
        /** FlatZinc definitional-sweep DAG; null for XCSP/SMT. */
        definitionalSweep: DefinitionalSweep?,
        render: (Sample) -> String,
        /** Sign-corrected objective value in original problem units, or null for satisfy. Carries the
         *  discrete terms only, so a model weighting a continuous column also sets [continuousObjectiveValue]
         *  — this one stays exact for the integral formats, whose coefficients can outrun a `Double`. */
        objectiveValue: ((Sample) -> Long)?,
        /** Sign-corrected objective value including the continuous columns' contribution, set only when the
         *  objective weights one: their values are real, so the total has no exact integral form. Null
         *  leaves [objectiveValue] the whole objective. */
        continuousObjectiveValue: ((Sample) -> Double)? = null,
        /** Model-supplied finite search intent. */
        searchHints: SearchHints? = null,
        rendering: SourceRendering = SourceRendering.FiniteCp,
        /** LP work performed while proving bounds for source routing. */
        routingLpStats: LpStats = LpStats(),
        /** Wall time spent proving bounds for rendering selection. */
        routingElapsedMs: Long = 0L,
        /** Existing finite source ownership selected by the frontend. */
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

    /** Finite CP problem. */
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
