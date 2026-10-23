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
import com.eignex.klause.solver.pipeline.LinearTraversal
import com.eignex.klause.solver.pipeline.OpenTheoryAssignment
import com.eignex.klause.solver.pipeline.PreparedSourceInvocation
import com.eignex.klause.solver.pipeline.SourceAttachment
import com.eignex.klause.solver.pipeline.SourceExecutionKind
import com.eignex.klause.solver.pipeline.SourceProblemRoute
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.SourceSetupRequest
import com.eignex.klause.solver.pipeline.executionKind
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
    val invocation = common.routingPreparationCancellation().use { stop ->
        SourceSetup.prepare(
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
                cancellation = stop,
                boundCancellation = common.routingCancellation(),
            ),
            onLpStats = { lpStats = it },
        )
    }
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
    objective: LinearObjective? = null,
    renderFinite: (Sample) -> String = { error("source requires its exact coordinate renderer") },
): Solvable {
    val start = TimeSource.Monotonic.markNow()
    var lpStats = LpStats()
    val invocation = common.routingPreparationCancellation().use { stop ->
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
                objective != null || source.embeddedObjectiveSense != null -> ExecutionOperation.OPTIMIZE
                common.allSolutions || (common.solutionCap ?: 1L) > 1L -> ExecutionOperation.ENUMERATE
                else -> ExecutionOperation.SATISFY
            }
            SourceSetupRequest(
                source,
                operation,
                common.resolvedEngine(),
                objective = objective,
                maximize = sense == ObjectiveSense.MAXIMIZE,
                cancellation = stop,
                boundCancellation = common.routingCancellation(),
            )
        }
        SourceSetup.prepare(request) { lpStats = it }
    }
    val elapsed = start.elapsedNow().inWholeMilliseconds
    common.logRoute(invocation.route, elapsed)
    if (invocation.route is SourceProblemRoute.Finite) {
        return Solvable(
            finite = invocation.finiteShape(),
            render = renderFinite,
            objectiveValue = objective?.let { linear -> { sample -> linear.evaluateLong(sample) } },
            continuousObjectiveValue = objective?.takeIf { linear ->
                linear.realCoefficients.any { it != 0.0 }
            }?.let { linear -> { sample -> linear.evaluate(sample) } },
            routingLpStats = lpStats,
            routingElapsedMs = admissionElapsedMs + elapsed,
        )
    }
    return preparedSourceSolvable(
        invocation,
        renderFinite,
        render,
        routingLpStats = lpStats,
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
    var lpStats = LpStats()
    val invocation = common.routingPreparationCancellation().use { stop ->
        val request = SourceSetupRequest(
            source, operation, common.resolvedEngine(),
            linearTraversal = common.takeLinearTraversal(),
            maximize = source.embeddedObjectiveSense == ObjectiveSense.MAXIMIZE,
            cancellation = stop,
            boundCancellation = common.routingCancellation(),
        )
        SourceSetup.prepare(request, onLpStats = { lpStats = it })
    }
    val elapsed = start.elapsedNow().inWholeMilliseconds
    common.logRoute(invocation.route, elapsed)
    return Solvable(
        finite = null,
        render = { error("linear source publication requires its checked original coordinates") },
        objectiveValue = null,
        rendering = SourceRendering.LinearSource(invocation, render),
        routingLpStats = lpStats,
        routingElapsedMs = admissionElapsedMs + elapsed,
        sourceInvocation = invocation,
    )
}

private fun CommonOptions.takeLinearTraversal(): LinearTraversal {
    val entries = engineParams.filter { it.startsWith("linear-traversal=") }
    engineParams.removeAll(entries)
    val choices = entries.map { entry ->
        when (entry.substringAfter('=')) {
            "automatic" -> LinearTraversal.AUTOMATIC
            "numerical" -> LinearTraversal.NUMERICAL
            else -> usageError("linear-traversal expects automatic or numerical")
        }
    }.distinct()
    if (choices.size > 1) usageError("linear-traversal cannot be set to conflicting values")
    return choices.singleOrNull() ?: LinearTraversal.AUTOMATIC
}

internal fun preparedSourceSolvable(
    invocation: PreparedSourceInvocation,
    render: (Sample) -> String,
    renderOpen: (OpenTheoryAssignment) -> String,
    routingLpStats: LpStats = LpStats(),
    routingElapsedMs: Long = 0,
): Solvable {
    if (SourceSetup.executionKind(invocation) != SourceExecutionKind.FINITE) {
        return Solvable(
            finite = null,
            render = { error("source arithmetic retains its exact coordinate renderer") },
            objectiveValue = null,
            rendering = SourceRendering.ExactSource(invocation, renderOpen),
            routingLpStats = routingLpStats,
            routingElapsedMs = routingElapsedMs,
            sourceInvocation = invocation,
        )
    }
    val objective = invocation.sourceObjective
    val realObjective = objective?.takeIf { it.realCoefficients.any { coefficient -> coefficient != 0.0 } }
    return Solvable(
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
