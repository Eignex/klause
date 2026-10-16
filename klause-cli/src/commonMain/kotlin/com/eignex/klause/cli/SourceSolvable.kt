package com.eignex.klause.cli

import com.eignex.klause.ir.BoolFoldDefinition
import com.eignex.klause.ir.DefinitionHint
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SearchHints
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.OpenTheoryAssignment
import com.eignex.klause.solver.pipeline.PreparedSourceInvocation
import com.eignex.klause.solver.pipeline.SourceAttachment
import com.eignex.klause.solver.pipeline.SourceExecutionKind
import com.eignex.klause.solver.pipeline.SourceProblemRoute
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.SourceSetupRequest
import com.eignex.klause.solver.pipeline.executionKind
import com.eignex.klause.solver.pipeline.maximizeSourceObjective
import com.eignex.klause.solver.pipeline.sourceObjective
import com.eignex.klause.solver.result.LpStats
import kotlin.time.TimeSource

internal fun sourceSolvable(
    problem: Problem,
    minimizedObjective: LinearObjective?,
    maximize: Boolean,
    common: CommonOptions,
    render: (Sample) -> String,
    renderOpen: (OpenTheoryAssignment) -> String,
    definedVars: IntArray = IntArray(0),
    boolFolds: List<BoolFoldDefinition> = emptyList(),
    definitionHints: List<DefinitionHint> = emptyList(),
    searchHints: SearchHints? = null,
): Solvable {
    val operation = when {
        minimizedObjective != null -> ExecutionOperation.OPTIMIZE
        common.allSolutions || (common.solutionCap ?: 1L) > 1L -> ExecutionOperation.ENUMERATE
        else -> ExecutionOperation.SATISFY
    }
    val start = TimeSource.Monotonic.markNow()
    var lpStats = LpStats()
    val invocation = SourceSetup.prepare(
        SourceSetupRequest(
            SourceAttachment(problem, definitionHints, searchHints),
            operation,
            common.resolvedEngine(),
            objective = if (maximize) minimizedObjective?.negated() else minimizedObjective,
            maximize = maximize,
            cancellation = common.routingPreparationCancellation(),
            boundCancellation = common.routingCancellation(),
        ),
        onLpStats = { lpStats = it },
    )
    val elapsed = start.elapsedNow().inWholeMilliseconds
    common.logRoute(invocation.route, elapsed)
    return preparedSourceSolvable(
        invocation, render, renderOpen, lpStats, elapsed, definedVars, boolFolds, searchHints,
    )
}

internal fun preparedSourceSolvable(
    invocation: PreparedSourceInvocation,
    render: (Sample) -> String,
    renderOpen: (OpenTheoryAssignment) -> String,
    routingLpStats: LpStats = LpStats(),
    routingElapsedMs: Long = 0,
    definedVars: IntArray = IntArray(0),
    boolFolds: List<BoolFoldDefinition> = emptyList(),
    searchHints: SearchHints? = null,
): Solvable {
    val objective = invocation.sourceObjective
    val maximize = invocation.maximizeSourceObjective
    return when (SourceSetup.executionKind(invocation)) {
        SourceExecutionKind.FINITE -> {
            val route = invocation.route as SourceProblemRoute.Finite
            linearSolvable(
                route.problem,
                if (maximize) objective?.negated() else objective,
                maximize,
                render,
                definedVars = definedVars,
                boolFolds = boolFolds,
                searchHints = searchHints,
                routingLpStats = routingLpStats,
                routingElapsedMs = routingElapsedMs,
                preparedRoute = route,
            )
        }

        SourceExecutionKind.LINEAR -> openTheorySolvable(
            (invocation.route as SourceProblemRoute.OpenTheory).request,
            renderOpen,
            routingLpStats,
            routingElapsedMs,
        ).withSourceInvocation(invocation)

        SourceExecutionKind.HEURISTIC -> {
            val model = when (val route = invocation.route) {
                is SourceProblemRoute.OpenTheory -> route.request.model
                is SourceProblemRoute.UnsupportedOpen -> route.problem
                else -> error("heuristic execution requires its source route")
            }
            openLocalSearchSolvable(
                model,
                renderOpen,
                routingLpStats,
                routingElapsedMs,
                if (maximize) objective?.negated() else objective,
                maximize,
            ).withSourceInvocation(invocation)
        }

        SourceExecutionKind.POLYNOMIAL -> polynomialSolvable(
            invocation.route as SourceProblemRoute.PolynomialTheory,
            renderOpen,
            routingElapsedMs,
        ).withSourceInvocation(invocation)

        SourceExecutionKind.REFUTED -> refutedSolvable(
            routingLpStats,
            routingElapsedMs,
        ).withSourceInvocation(invocation)
    }
}
