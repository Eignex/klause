package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.lowering.LinearSourceView
import com.eignex.klause.lp.optimization.atScope
import com.eignex.klause.presolve.SourceMapping
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.ExecutionControl
import kotlin.uuid.Uuid

internal class LinearToleranceAuthority(
    private val nominal: Problem,
    private val model: Problem,
    private val objective: LinearObjective,
) {
    private val identity = Uuid.random().toString()
    val scope = SourceScope(SourceRevision(identity, "absolute-tolerance-envelope-v1"),
        objective = SourceObjective("$identity/objective", ObjectiveSense.MINIMIZE))

    fun compatible(mapping: SourceMapping?, working: Problem, costs: LinearObjective?): Boolean =
        costs != null && costs.constant == objective.constant &&
            costs.boolWeightValues.contentEquals(objective.boolWeightValues) &&
            costs.intCoefficientValues.contentEquals(objective.intCoefficientValues) &&
            costs.realCoefficientValues.contentEquals(objective.realCoefficientValues) &&
            working.numBoolVars == nominal.numBoolVars && working.numIntVars == nominal.numIntVars &&
            working.numRealVars == nominal.numRealVars && mapping != null && mapping.source === nominal &&
            mapping.stages.all { !it.opaqueReconstruction && it.rebuild.steps.isEmpty() }

    fun capture(working: Problem, objective: LinearObjective, stop: ExecutionControl): SourceProofContext? {
        if (working.numBoolVars != model.numBoolVars || working.numIntVars != model.numIntVars ||
            working.numRealVars != model.numRealVars) return null
        // The accepted-set model supplies its own rows and root sides; propagated nominal sides are not premises.
        return model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE, stop, numericalIndicators = true)
            ?.atScope(scope)
    }
}

internal fun LinearSourceView.sharesToleranceCoordinates(other: LinearSourceView, stop: ExecutionControl): Boolean {
    stop.charge(1L + columns.size + other.columns.size + model.numIntVars + model.numRealVars)
    if (stop()) return false
    if (!exact || !other.exact || !columns.contentEquals(other.columns) || objectiveScale != other.objectiveScale ||
        model.numBoolVars != other.model.numBoolVars || model.numIntVars != other.model.numIntVars ||
        model.numRealVars != other.model.numRealVars) return false
    val first = objective
    val second = other.objective
    if (first == null || second == null) return false
    if (first != second) return false
    for (id in 0 until model.numIntVars) {
        stop.charge(1L)
        if (stop()) return false
        val left = model.intBounds
        val right = other.model.intBounds
        if (left.hasLower(id) != right.hasLower(id) || left.hasUpper(id) != right.hasUpper(id) ||
            (left.hasLower(id) && left.lower(id) != right.lower(id)) ||
            (left.hasUpper(id) && left.upper(id) != right.upper(id))) return false
    }
    return !stop()
}
