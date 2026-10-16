package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.DefinitionHint
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.presolve.SourceMapping
import com.eignex.klause.presolve.retainedCoordinates
import com.eignex.klause.solver.encodingPreparationScope
import com.eignex.klause.solver.objective.IncrementalObjective
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation

internal class PreparedSourceHints(
    val sweep: DefinitionalSweep?,
    val objective: IncrementalObjective?,
)

internal fun PreparedSourceInvocation.heuristicHints(
    problem: Problem,
    objective: LinearObjective?,
    mapping: SourceMapping?,
    cancellation: Cancellation,
    strategies: Set<ExecutionStrategy>,
    capabilities: ExecutionCapabilities,
): PreparedSourceHints? {
    val plan = requireNotNull(preparedPlan)
    val route = this.route as? SourceProblemRoute.Finite ?: return null
    val projection = requireNotNull(mapping) { "hint projection requires its source mapping" }
    require(projection.source === route.problem) { "hint projection belongs to a different source model" }
    require(problem === projection.target || (problem is BakedProblem &&
        problem.numBoolVars == projection.target.numBoolVars && problem.numIntVars == projection.target.numIntVars &&
        problem.numRealVars == projection.target.numRealVars && problem.numFactors == projection.target.numFactors &&
        problem.factors.indices.all { problem.factors[it] === projection.target.factors[it] })) {
        "hint projection belongs to a different execution model"
    }
    if (strategies.none { it != ExecutionStrategy.COMPLETE && capabilities.admission(it, operation).runnable }) {
        return null
    }
    val definitions = source.definitionHints
    if (definitions.isEmpty()) return null
    val stop = (cancellation or this.cancellation).encodingPreparationScope()
    if (stop()) return null
    val projected = projection.retainedCoordinates(stop) ?: return null
    val coordinates = plan.sourceCoordinates(stop)?.followedBy(projected, stop) ?: return null
    val hints = ArrayList<DefinitionHint>()
    for (hint in definitions) {
        if (stop()) return null
        stop.charge(1)
        hint.remap(coordinates)?.let(hints::add)
    }
    val sweep = DefinitionalSweep.fromSource(problem, hints, stop)
    if (stop()) return null
    val functional = objective?.let { sweep?.functionalObjective(it, stop) }
    return if (stop()) null else PreparedSourceHints(sweep, functional)
}
