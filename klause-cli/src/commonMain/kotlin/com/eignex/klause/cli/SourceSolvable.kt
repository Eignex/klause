package com.eignex.klause.cli

import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.pipeline.OpenTheoryAssignment
import com.eignex.klause.solver.pipeline.PreparedSourceInvocation
import com.eignex.klause.solver.pipeline.SourceExecutionKind
import com.eignex.klause.solver.pipeline.SourceProblemRoute
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.executionKind
import com.eignex.klause.solver.pipeline.maximizeSourceObjective
import com.eignex.klause.solver.pipeline.sourceObjective
import com.eignex.klause.solver.result.LpStats

internal fun preparedSourceSolvable(
    invocation: PreparedSourceInvocation,
    render: (Sample) -> String,
    renderOpen: (OpenTheoryAssignment) -> String,
    routingLpStats: LpStats = LpStats(),
    routingElapsedMs: Long = 0,
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
