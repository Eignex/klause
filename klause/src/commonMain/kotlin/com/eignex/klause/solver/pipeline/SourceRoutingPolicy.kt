package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.compareTo
import com.eignex.util.numbers.plus
import com.eignex.util.numbers.times
import com.eignex.util.numbers.unaryMinus

internal fun Problem.requiresExactObjectiveRoute(objective: LinearObjective?, stop: ExecutionControl): Boolean {
    if (objective == null) return false
    for (coefficient in objective.realCoefficientValues) {
        stop.charge(1)
        if (stop()) return false
        if (coefficient != 0.0) return false
    }
    for (weight in objective.boolWeightValues) {
        stop.charge(1)
        if (stop()) return false
        if (weight != 0L) return false
    }
    var lower = bigIntOf(objective.constant)
    var upper = lower
    for (variable in objective.intCoefficientValues.indices) {
        stop.charge(1)
        if (stop()) return false
        val coefficient = objective.intCoefficientValues[variable]
        if (coefficient == 0L) continue
        val lo = intBounds.lowerAsBigInteger(variable) ?: return true
        val hi = intBounds.upperAsBigInteger(variable) ?: return true
        val exactCoefficient = bigIntOf(coefficient)
        lower += exactCoefficient * if (coefficient > 0) lo else hi
        upper += exactCoefficient * if (coefficient > 0) hi else lo
    }
    val limit = bigIntOf(1L shl 53)
    return lower < -limit || upper > limit
}

// A complete arm decides the original source, so it keeps the source over an automatic grid. A heuristic route
// offers no complete arm.
internal fun SourceSetup.preservesOriginalCompleteArm(selection: SourceRouteSelection): Boolean {
    val kind = admittedKind(selection, ExecutionStrategy.COMPLETE) ?: return false
    return selection.operation == ExecutionOperation.SATISFY ||
        selection.operation == ExecutionOperation.OPTIMIZE ||
        (selection.operation == ExecutionOperation.ENUMERATE && kind == SourceExecutionKind.FINITE)
}

// A local-search or ALNS engine searches the original source with the numerical leaf completion its tolerance
// admits, as the default portfolio's incomplete arms do, so its satisfaction and optimization keep the source over
// an automatic grid. Its enumeration lists no complete solution set and keeps the grid, whose accepted points are
// published without a completeness marker.
internal fun SourceSetup.preservesOriginalIncompleteArm(selection: SourceRouteSelection): Boolean {
    val arm = when (selection.request.engine) {
        FiniteEngine.LOCAL_SEARCH -> ExecutionStrategy.LOCAL_SEARCH
        FiniteEngine.ALNS -> ExecutionStrategy.ALNS
        else -> return false
    }
    admittedKind(selection, arm) ?: return false
    return selection.operation == ExecutionOperation.SATISFY || selection.operation == ExecutionOperation.OPTIMIZE
}

private fun SourceSetup.admittedKind(selection: SourceRouteSelection, arm: ExecutionStrategy): SourceExecutionKind? {
    val admission = selection.admission ?: return null
    if (!admission.runnable || arm !in admission.arms) return null
    return executionKind(selection.route, selection.source).takeUnless { it == SourceExecutionKind.HEURISTIC }
}
