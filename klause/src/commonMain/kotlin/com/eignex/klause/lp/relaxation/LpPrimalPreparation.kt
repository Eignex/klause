package com.eignex.klause.lp.relaxation

import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.PreparedFactorProjections
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.LpStatsSink
import com.eignex.klause.util.Cancellation

@Suppress("LongParameterList") // Reuse controls share the fixed ordinary primal builder configuration.
internal fun buildPrimalSource(
    problem: Problem,
    objective: LinearObjective?,
    domains: RelaxationDomains,
    cancellation: Cancellation,
    onWork: (Long) -> Unit,
    selection: PreparedFactorProjections? = (problem as? BakedProblem)?.factorProjections,
    reuseCompleteTemplates: Boolean = true,
    reuseSourceTemplates: Boolean = true,
    reuseTemplateIndexes: Boolean = true,
    templateStats: LpStatsSink? = null,
    onDefinition: (String) -> Unit = {},
): LpRelaxation {
    selection?.requireSource(problem)
    val reuse = reuseCompleteTemplates && reuseSourceTemplates && problem.numRealVars == 0 &&
        selection?.builtInRelaxation == true
    if (cancellation()) throw LpAssemblyCancelled()
    if (reuse) {
        val borrowed = selection?.borrowLpPrimal(objective, domains, cancellation)
        if (cancellation()) throw LpAssemblyCancelled()
        if (borrowed != null) {
            onDefinition("REUSED")
            return borrowed
        }
    }
    var paidUnits = 0L
    val relaxation = CpToLpRelaxation(problem, objective, projections = selection,
        preparationCancellation = cancellation, reuseSourceTemplates = reuseSourceTemplates,
        reuseTemplateIndexes = reuseTemplateIndexes, templateStats = templateStats, retainImportedDefinitions = reuse)
        .buildWithWork(domains, cancellation) {
            paidUnits += it
            onWork(it)
        }
    if (reuse && selection?.rememberLpPrimal(relaxation, objective, domains, paidUnits, cancellation) == true) {
        onDefinition("PUBLISHED")
    }
    if (cancellation()) throw LpAssemblyCancelled()
    return relaxation
}
