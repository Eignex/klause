package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.BoolFoldDefinition
import com.eignex.klause.ir.DefinitionHint
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.unhintedDefinitionSweep
import com.eignex.klause.presolve.SourceMapping
import com.eignex.klause.presolve.retainedCoordinates
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.encodingPreparationScope
import com.eignex.klause.solver.objective.IncrementalObjective
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation

internal class PreparedSourceHints(val sweep: DefinitionalSweep?, val objective: IncrementalObjective?)

internal fun PreparedSourceInvocation.heuristicHints(
    problem: Problem,
    objective: LinearObjective?,
    mapping: SourceMapping?,
    cancellation: Cancellation,
    strategies: Set<ExecutionStrategy>,
    capabilities: ExecutionCapabilities,
): PreparedSourceHints? {
    val plan = requireNotNull(enginePlan)
    val route = engineRoute as? SourceProblemRoute.Finite ?: return null
    val projection = requireNotNull(mapping) { "hint projection requires its source mapping" }
    require(projection.source === route.problem) { "hint projection belongs to a different source model" }
    require(
        problem === projection.target || (
            problem is BakedProblem &&
                problem.numBoolVars == projection.target.numBoolVars &&
                problem.numIntVars == projection.target.numIntVars &&
                problem.numRealVars == projection.target.numRealVars &&
                problem.numFactors == projection.target.numFactors &&
                problem.factors.indices.all { problem.factors[it] === projection.target.factors[it] }
            ),
    ) {
        "hint projection belongs to a different execution model"
    }
    if (strategies.none { it != ExecutionStrategy.COMPLETE && capabilities.admission(it, operation).runnable }) {
        return null
    }
    val definitions = source.definitionHints
    val stop = (cancellation or this.cancellation).encodingPreparationScope()
    if (stop()) return null
    if (definitions.isEmpty() && source.definitionReferences == null) {
        val sweep = unhintedDefinitionSweep(problem, stop) ?: return null
        val functional = objective?.let { sweep.functionalObjective(it, stop) }
        return if (stop()) null else PreparedSourceHints(sweep, functional)
    }
    val projected = projection.retainedCoordinates(stop) ?: return null
    val planned = plan.sourceCoordinates(stop) ?: return null
    val coordinates = planned.followedBy(projected, stop) ?: return null
    // Affine elimination keeps a reconstructed column's partner in its place, so a hint over that column
    // still holds on the reduced model once the reconstruction is substituted into it.
    val reconstructions = projection.affineReconstructions(stop) ?: return null
    val aliased = if (reconstructions.isEmpty()) {
        coordinates
    } else {
        planned.followedBy(projected.withAliases(reconstructions), stop) ?: return null
    }
    val hints = ArrayList<DefinitionHint>()
    for (hint in definitions) {
        if (stop()) return null
        stop.charge(1)
        val mapped = hint.remap(coordinates) ?: (hint.remap(planned) as? DefinitionHint.Linear)
            ?.takeIf { reconstructions.isNotEmpty() }?.reconstructed(reconstructions)?.remap(projected)
            ?: hint.remap(aliased)
        mapped?.let(hints::add)
    }
    val references = source.definitionReferences?.let { it.remapped(coordinates, stop) ?: return null }
    if (references == null) {
        val sweep = DefinitionalSweep.fromSource(problem, hints, stop)
        if (stop()) return null
        val functional = objective?.let { sweep?.functionalObjective(it, stop) }
        return if (stop()) null else PreparedSourceHints(sweep, functional)
    }
    val moveFolds = references.foldsForMoves(objective, stop) ?: return null
    val inferred = DefinitionalSweep.infer(problem, references.integers, moveFolds, stop)
    if (stop()) return null
    val sweep = DefinitionalSweep.withSource(problem, inferred, hints, stop)
    if (stop()) return null
    val gradient = if (objective != null && references.folds.isNotEmpty()) {
        val allFolds = DefinitionalSweep.infer(problem, references.integers, references.folds, stop)
        if (stop()) return null
        DefinitionalSweep.withSource(problem, allFolds, hints, stop)
    } else sweep
    if (stop()) return null
    val captureBooleanCosts = sweep == null && ExecutionStrategy.LOCAL_SEARCH in strategies &&
        problem.semantics.effective.format == SourceFormat.WCNF &&
        problem.numBoolVars <= 1_000_000 && problem.numIntVars == 0 && problem.numRealVars == 0
    val functional = objective?.let { gradient?.functionalObjective(it, stop, captureBooleanCosts) }
    return if (stop()) null else PreparedSourceHints(sweep, functional)
}

private fun SourceDefinitionReferences.foldsForMoves(
    objective: LinearObjective?,
    cancellation: Cancellation,
): List<BoolFoldDefinition>? {
    if (objective == null) return emptyList()
    val weights = objective.boolWeights
    val retained = ArrayList<BoolFoldDefinition>()
    for (fold in folds) {
        cancellation.charge(1)
        if (cancellation()) return null
        var priced = true
        for (literal in fold.lits) {
            cancellation.charge(1)
            if (cancellation()) return null
            val id = Lit.variable(literal)
            if (id !in weights.indices || weights[id] == 0L) priced = false
        }
        // Unit folds preserve literal moves; wider unpriced folds remain searched for feasibility repair.
        if (fold.lits.size == 1 || priced) retained += fold
    }
    return if (cancellation()) null else retained
}
