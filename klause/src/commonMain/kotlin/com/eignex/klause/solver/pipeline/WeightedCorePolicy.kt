package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.Problem
import com.eignex.klause.meta.coreguided.CoreRefinementMode
import com.eignex.klause.portfolio.EngineMix
import com.eignex.klause.portfolio.Kind
import com.eignex.klause.portfolio.PortfolioScenario
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import kotlin.math.abs

private const val WEIGHTED_CORE_COLUMNS = 16_384
private const val WEIGHTED_CORE_FACTORS = 16_384
private const val WEIGHTED_CORE_SCALAR_MAGNITUDE = 9_007_199_254_740_991L

internal fun weightedCoreDefaultScenario(
    scenario: PortfolioScenario,
    problem: Problem,
    objective: LinearObjective?,
    options: ExecutionOptions,
    cancellation: Cancellation,
): PortfolioScenario {
    if (cancellation() || objective == null || scenario.cores != 1 || scenario.kind != Kind.COP ||
        scenario.engine != EngineMix.MIXED || scenario.explicitArms || options.explicitArms ||
        scenario.arms != PortfolioScenario.DEFAULT_ARMS || scenario.btPool != null || scenario.lsPool != null ||
        scenario.btEdit != null || scenario.annotationArm != null || scenario.diagnosticWorkLimit != null ||
        scenario.earlyCoreAdmission || scenario.coreRefinement != CoreRefinementMode.NONE
    ) return scenario
    val reader = options.reader()
    if (reader[ExecutionOption.coreEarly] != null || reader[ExecutionOption.coreRefinement] != null) return scenario
    if (problem.numBoolVars !in 1..WEIGHTED_CORE_COLUMNS || problem.factors.size > WEIGHTED_CORE_FACTORS ||
        problem.numIntVars != 0 || problem.numRealVars != 0 ||
        objective.boolWeightValues.size > problem.numBoolVars ||
        objective.intCoefficientValues.isNotEmpty() || objective.realCoefficientValues.isNotEmpty() ||
        objective.constant == Long.MIN_VALUE || abs(objective.constant) > WEIGHTED_CORE_SCALAR_MAGNITUDE
    ) return scenario
    var remaining = WEIGHTED_CORE_SCALAR_MAGNITUDE - abs(objective.constant)
    var first = 0L
    var varied = false
    for (coefficient in objective.boolWeightValues) {
        cancellation.charge(1)
        if (cancellation() || coefficient == Long.MIN_VALUE) return scenario
        val weight = abs(coefficient)
        if (weight > remaining) return scenario
        remaining -= weight
        if (weight == 0L) continue
        if (first == 0L) first = weight else if (weight != first) varied = true
    }
    return if (varied) scenario.copy(earlyCoreAdmission = true, coreRefinement = CoreRefinementMode.REASONS)
    else scenario
}
