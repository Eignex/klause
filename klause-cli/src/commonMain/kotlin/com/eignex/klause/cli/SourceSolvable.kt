package com.eignex.klause.cli

import com.eignex.klause.ir.BoolFoldDefinition
import com.eignex.klause.ir.DefinitionHint
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.QuadraticObjectiveSpec
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
            if (definedVars.isEmpty() && boolFolds.isEmpty()) {
                SourceAttachment(problem, definitionHints, searchHints)
            } else {
                SourceAttachment(problem, definedVars, boolFolds, definitionHints, searchHints)
            },
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
        invocation,
        render,
        renderOpen,
        lpStats,
        elapsed,
    )
}

internal fun attachedSourceSolvable(
    source: SourceAttachment,
    common: CommonOptions,
    render: (OpenTheoryAssignment) -> String,
    admissionElapsedMs: Long,
    quadraticObjective: QuadraticObjectiveSpec? = null,
    sense: ObjectiveSense = source.embeddedObjectiveSense ?: ObjectiveSense.MINIMIZE,
): Solvable {
    val start = TimeSource.Monotonic.markNow()
    val stop = common.routingPreparationCancellation()
    val request = if (quadraticObjective != null) {
        SourceSetupRequest(
            source,
            common.resolvedEngine(),
            quadraticObjective,
            sense,
            cancellation = stop,
            boundCancellation = common.routingCancellation(),
        )
    } else {
        val operation = when {
            source.embeddedObjectiveSense != null -> ExecutionOperation.OPTIMIZE
            common.allSolutions || (common.solutionCap ?: 1L) > 1L -> ExecutionOperation.ENUMERATE
            else -> ExecutionOperation.SATISFY
        }
        SourceSetupRequest(
            source,
            operation,
            common.resolvedEngine(),
            maximize = source.embeddedObjectiveSense == ObjectiveSense.MAXIMIZE,
            cancellation = stop,
            boundCancellation = common.routingCancellation(),
        )
    }
    val invocation = SourceSetup.prepare(request)
    val elapsed = start.elapsedNow().inWholeMilliseconds
    common.logRoute(invocation.route, elapsed)
    return preparedSourceSolvable(
        invocation,
        { error("native source formulas retain their exact original coordinate renderer") },
        render,
        routingElapsedMs = admissionElapsedMs + elapsed,
    )
}

internal fun linearSourceSolvable(
    source: SourceAttachment,
    common: CommonOptions,
    render: (com.eignex.klause.solver.validation.LinearSourceWitness) -> String,
    admissionElapsedMs: Long = 0L,
): Solvable {
    require(source.originalSource is com.eignex.klause.ir.LinearSourceProblem)
    val start = TimeSource.Monotonic.markNow()
    val operation = when {
        source.embeddedObjectiveSense != null -> ExecutionOperation.OPTIMIZE
        common.allSolutions || (common.solutionCap ?: 1L) > 1L -> ExecutionOperation.ENUMERATE
        else -> ExecutionOperation.SATISFY
    }
    val request = SourceSetupRequest(
        source, operation, common.resolvedEngine(),
        maximize = source.embeddedObjectiveSense == ObjectiveSense.MAXIMIZE,
        cancellation = common.routingPreparationCancellation(),
        boundCancellation = common.routingCancellation(),
    )
    var lpStats = LpStats()
    val invocation = SourceSetup.prepare(request, onLpStats = { lpStats = it })
    val elapsed = start.elapsedNow().inWholeMilliseconds
    common.logRoute(invocation.route, elapsed)
    return Solvable(
        finite = null,
        render = { error("linear source publication requires its checked original coordinates") },
        objectiveValue = null,
        pipeline = SolvablePipeline.LinearSource(invocation, render),
        routingLpStats = lpStats,
        routingElapsedMs = admissionElapsedMs + elapsed,
        sourceInvocation = invocation,
    )
}

internal fun preparedSourceSolvable(
    invocation: PreparedSourceInvocation,
    render: (Sample) -> String,
    renderOpen: (OpenTheoryAssignment) -> String,
    routingLpStats: LpStats = LpStats(),
    routingElapsedMs: Long = 0,
): Solvable {
    if (invocation.route is SourceProblemRoute.NativeFormula) {
        return Solvable(
            finite = null,
            render = { error("native source formulas retain their exact original coordinate renderer") },
            objectiveValue = null,
            pipeline = SolvablePipeline.NativeSource(invocation, renderOpen),
            routingLpStats = routingLpStats,
            routingElapsedMs = routingElapsedMs,
            sourceInvocation = invocation,
        )
    }
    val objective = invocation.sourceObjective
    val maximize = invocation.maximizeSourceObjective
    return when (SourceSetup.executionKind(invocation)) {
        SourceExecutionKind.FINITE -> {
            val realObjective = objective?.takeIf { it.realCoefficients.any { coefficient -> coefficient != 0.0 } }
            Solvable(
                finite = invocation.finiteShape(),
                render = render,
                objectiveValue = objective?.takeIf { realObjective == null }
                    ?.let { linear -> { sample -> linear.evaluateLong(sample) } },
                continuousObjectiveValue = realObjective?.let { linear ->
                    { sample -> linear.evaluateExact(sample).toDouble() }
                },
                routingLpStats = routingLpStats,
                routingElapsedMs = routingElapsedMs,
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

        SourceExecutionKind.NONLINEAR_INTEGER, SourceExecutionKind.FUNCTIONS, SourceExecutionKind.NUMERICAL ->
            error("native formula execution requires its captured native source route")

        SourceExecutionKind.REFUTED -> refutedSolvable(
            routingLpStats,
            routingElapsedMs,
        ).withSourceInvocation(invocation)
    }
}
