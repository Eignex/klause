package com.eignex.klause.lp.relaxation

import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.LpModel
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
    retainPrivateDefinition: Boolean = false,
    onDefinition: (String) -> Unit = {},
): LpRelaxation {
    selection?.requireSource(problem)
    val reuse = reuseCompleteTemplates && reuseSourceTemplates && problem.numRealVars == 0 &&
        selection?.builtInRelaxation == true
    cancellation.charge(1L)
    cancellation.checkLpAssembly()
    if (reuse) {
        val borrowed = selection?.borrowLpPrimal(objective, domains, cancellation)
        cancellation.checkLpAssembly()
        if (borrowed != null) {
            onDefinition("REUSED")
            cancellation.checkLpAssembly()
            return borrowed
        }
    }
    var paidUnits = 0L
    val relaxation = CpToLpRelaxation(problem, objective, projections = selection,
        preparationCancellation = cancellation, reuseSourceTemplates = reuseSourceTemplates,
        reuseTemplateIndexes = reuseTemplateIndexes, templateStats = templateStats, retainImportedDefinitions = reuse,
        primalDefinitionFactory = if (retainPrivateDefinition) NativePrimalDefinitionFactory else null)
        .buildWithWork(domains, cancellation) {
            paidUnits += it
            onWork(it)
        }
    if (reuse && selection?.rememberLpPrimal(relaxation, objective, domains, paidUnits, cancellation) == true) {
        onDefinition("PUBLISHED")
    }
    cancellation.checkLpAssembly()
    return relaxation
}

internal sealed interface LpPrimalDefinitionFactory {
    fun capture(
        problem: Problem,
        objective: LinearObjective?,
        selection: PreparedFactorProjections?,
        model: LpModel,
        cancellation: Cancellation,
    ): LpPrimalDefinition?
}

internal sealed interface LpPrimalDefinition {
    val paidWork: Long get() = CAPTURE_WORK
    val storageUnits: Long get() = STORAGE_UNITS

    fun matches(problem: Problem, selection: PreparedFactorProjections?, model: LpModel): Boolean
    fun matchesObjective(model: LpModel, objective: LinearObjective): Boolean

    companion object {
        const val CAPTURE_WORK = 28L
        const val STORAGE_UNITS = 72L
    }
}

private object NativePrimalDefinitionFactory : LpPrimalDefinitionFactory {
    override fun capture(
        problem: Problem,
        objective: LinearObjective?,
        selection: PreparedFactorProjections?,
        model: LpModel,
        cancellation: Cancellation,
    ): LpPrimalDefinition? {
        val construction = model.n.toLong() + model.m + model.csc.rowIdx.size
        val storage = LpPrimalDefinition.STORAGE_UNITS +
            (objective?.boolWeightValues?.size?.toLong() ?: 0L) +
            (objective?.intCoefficientValues?.size?.toLong() ?: 0L)
        if (problem.numRealVars != 0 || selection?.builtInRelaxation != true || !selection.nativePropagation ||
            model.n == 0 || model.doubleView != null || model.exactState != null ||
            construction <= LpPrimalDefinition.CAPTURE_WORK || storage > LpEmissionTemplates.MAX_UNITS
        ) return null
        // Reserve both immutable objects, coefficient references and their release before publication.
        cancellation.charge(LpPrimalDefinition.CAPTURE_WORK)
        cancellation.checkLpAssembly()
        return NativePrimalDefinition(problem, selection, model, objective, storage)
            .also { cancellation.checkLpAssembly() }
    }
}

// The default primal factory applies objectives only to column costs; source lifetime remains the caller's duty.
private class NativePrimalDefinition(
    private val problem: Problem,
    selection: PreparedFactorProjections,
    private val model: LpModel,
    objective: LinearObjective?,
    override val storageUnits: Long,
) : LpPrimalDefinition {
    private val scope = LpEmissionScope(selection.lpEmissionNamespace, problem)
    private val boolWeights = objective?.boolWeightValues
    private val intCoefficients = objective?.intCoefficientValues

    override fun matches(problem: Problem, selection: PreparedFactorProjections?, model: LpModel): Boolean =
        this.problem === problem && this.model === model && selection?.builtInRelaxation == true &&
            selection.nativePropagation && scope.matches(selection.lpEmissionNamespace, problem)

    override fun matchesObjective(model: LpModel, objective: LinearObjective): Boolean =
        this.model === model && boolWeights === objective.boolWeightValues &&
            intCoefficients === objective.intCoefficientValues && objective.realCoefficientValues.isEmpty()
}
