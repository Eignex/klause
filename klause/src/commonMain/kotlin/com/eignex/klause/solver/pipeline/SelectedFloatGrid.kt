package com.eignex.klause.solver.pipeline

import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.projection.FloatGridDecline
import com.eignex.klause.solver.projection.FloatGridDefinition
import com.eignex.klause.solver.projection.FloatGridObjective
import com.eignex.klause.solver.projection.FloatGridPreparation
import com.eignex.klause.solver.projection.FloatGridRequest
import com.eignex.klause.solver.projection.FloatGridVariable
import com.eignex.klause.solver.projection.prepareFloatGrid
import com.eignex.klause.solver.projection.projectObjective
import com.eignex.klause.solver.validation.hasCapturedLinearTolerance
import com.eignex.klause.util.Cancellation

internal enum class FloatGridConsumerPurpose {
    FINITE_COMPATIBILITY, HEURISTIC_VIEW, AUTOMATIC_FINITE_COMPATIBILITY,
}

internal class SelectedFloatGridConsumer(
    val selection: SourceRouteSelection,
    val purpose: FloatGridConsumerPurpose,
    val declaredGridSemantics: Boolean,
) {
    val providers = selection.request.providers
    val operation = selection.operation
    val engine = selection.request.engine
}

internal class SelectedFloatGridBinding(
    val consumer: SelectedFloatGridConsumer,
    val projection: FloatGridPreparation.Prepared,
    val route: SourceProblemRoute.Finite,
    val objective: FloatGridObjective?,
    val nativeObjective: LinearObjective?,
    val sourceChecker: BakedProblem,
) {
    val metadata = SourceFloatProjection(this)
}

internal enum class SelectedGridDecline { SOURCE_REFUTED, CONSUMER, PROJECTION, OBJECTIVE, ENGINE_PLAN, RESOURCE }

internal sealed interface SelectedFloatGridPreparation {
    class Prepared(val invocation: PreparedSourceInvocation) : SelectedFloatGridPreparation
    class Declined(val cause: SelectedGridDecline, val projectionCause: FloatGridDecline? = null,
        val sourceFactor: Int? = null) : SelectedFloatGridPreparation
}

internal fun SourceSetup.prepareSelectedFloatGrid(
    selection: SourceRouteSelection,
    variables: List<FloatGridVariable>,
    purpose: FloatGridConsumerPurpose,
    truncatedRows: Set<Int> = emptySet(),
    declaredGridSemantics: Boolean = false,
): SelectedFloatGridPreparation = selectFloatGrid(selection, purpose, declaredGridSemantics) { consumer, stop ->
    prepareFloatGrid(FloatGridRequest(selection.source.problem, variables, consumer, truncatedRows), stop)
}

internal fun SourceSetup.prepareSelectedFloatGrid(
    selection: SourceRouteSelection,
    definition: FloatGridDefinition,
    purpose: FloatGridConsumerPurpose,
    declaredGridSemantics: Boolean = false,
): SelectedFloatGridPreparation = selectFloatGrid(selection, purpose, declaredGridSemantics) { consumer, stop ->
    require(definition.source === selection.source.factorProblem) { "grid definition belongs to a foreign source" }
    definition.bind(consumer, stop)
}

private fun selectFloatGrid(
    selection: SourceRouteSelection,
    purpose: FloatGridConsumerPurpose,
    declaredGridSemantics: Boolean,
    prepare: (SelectedFloatGridConsumer, Cancellation) -> FloatGridPreparation,
): SelectedFloatGridPreparation {
    val stop = selection.cancellation
    if (stop()) return SelectedFloatGridPreparation.Declined(SelectedGridDecline.RESOURCE)
    if (selection.source.factorProblem == null || selection.request.quadraticObjective != null) {
        return SelectedFloatGridPreparation.Declined(SelectedGridDecline.CONSUMER)
    }
    if (selection.route == SourceProblemRoute.Refuted) {
        return SelectedFloatGridPreparation.Declined(SelectedGridDecline.SOURCE_REFUTED)
    }
    if (purpose == FloatGridConsumerPurpose.AUTOMATIC_FINITE_COMPATIBILITY &&
        SourceSetup.preservesOriginalCompleteArm(selection)
    ) return SelectedFloatGridPreparation.Declined(SelectedGridDecline.CONSUMER)
    if (selection.operation !in setOf(ExecutionOperation.SATISFY, ExecutionOperation.OPTIMIZE,
            ExecutionOperation.ENUMERATE) ||
        (purpose == FloatGridConsumerPurpose.HEURISTIC_VIEW && !selection.request.engine.pureLocalSearch)
    ) return SelectedFloatGridPreparation.Declined(SelectedGridDecline.CONSUMER)
    val consumer = SelectedFloatGridConsumer(selection, purpose, declaredGridSemantics)
    val projection = when (val result = prepare(consumer, stop)) {
        is FloatGridPreparation.Prepared -> result
        is FloatGridPreparation.Declined -> return SelectedFloatGridPreparation.Declined(
            if (stop()) SelectedGridDecline.RESOURCE else SelectedGridDecline.PROJECTION,
            result.cause, result.sourceFactor,
        )
    }
    val originalObjective = selection.request.objective
    val objective = originalObjective?.let { projection.mapping.projectObjective(it, stop) }
    if (originalObjective != null && objective == null) {
        return SelectedFloatGridPreparation.Declined(
            if (stop()) SelectedGridDecline.RESOURCE else SelectedGridDecline.OBJECTIVE,
        )
    }
    val nativeObjective = normalizeGridObjective(objective?.native, selection.request.maximize)
    if (objective != null && nativeObjective == null) {
        return SelectedFloatGridPreparation.Declined(SelectedGridDecline.OBJECTIVE)
    }
    val plan = projection.problem.prepareExecutionPlan(consumer.providers, nativeObjective,
        preferFinite = true, cancellation = stop)
    if (stop() || plan.decline == PreparedPlanDecline.RESOURCE) {
        return SelectedFloatGridPreparation.Declined(SelectedGridDecline.RESOURCE)
    }
    val ownership = plan.execution.finiteLinear?.ownership
    if (ownership == null || !plan.capabilities.admission(consumer.engine, consumer.operation).runnable) {
        return SelectedFloatGridPreparation.Declined(SelectedGridDecline.ENGINE_PLAN)
    }
    if (stop()) return SelectedFloatGridPreparation.Declined(SelectedGridDecline.RESOURCE)
    val route = SourceProblemRoute.Finite(projection.problem, ownership, plan)
    val source = selection.source.problem
    val checkedIntegers = ArrayList<Int>()
    val tolerant = source.semantics.effective.feasibilityTolerance?.isExact == false
    for (id in source.factors.indices) {
        stop.charge(1)
        if (stop()) return SelectedFloatGridPreparation.Declined(SelectedGridDecline.RESOURCE)
        val factor = source.factors[id]
        if (factor.variables.reals.isEmpty() && !(tolerant && factor.hasCapturedLinearTolerance())) {
            checkedIntegers += id
        }
    }
    val integerView = source.withFactors(checkedIntegers.map { source.factors[it] }.toTypedArray(),
        source.impliedFactorMask?.let { mask -> BooleanArray(checkedIntegers.size) { mask[checkedIntegers[it]] } })
    val captured = selection.preparedPlan?.factorProjections?.takeIf { it.problem === source }
        ?: consumer.providers.prepare(source)
    val checker = integerView.bake(captured.projected(integerView, checkedIntegers.toIntArray()), stop)
    if (stop()) return SelectedFloatGridPreparation.Declined(SelectedGridDecline.RESOURCE)
    stop.charge(projection.problem.numIntVars.toLong() - selection.source.problem.numIntVars +
        projection.problem.numFactors)
    if (stop()) return SelectedFloatGridPreparation.Declined(SelectedGridDecline.RESOURCE)
    val binding = SelectedFloatGridBinding(consumer, projection, route, objective, nativeObjective, checker)
    if (stop()) return SelectedFloatGridPreparation.Declined(SelectedGridDecline.RESOURCE)
    return SelectedFloatGridPreparation.Prepared(PreparedSourceInvocation(selection, binding))
}

private fun normalizeGridObjective(objective: LinearObjective?, maximize: Boolean): LinearObjective? {
    if (!maximize || objective == null) return objective
    if (objective.constant == Long.MIN_VALUE || objective.boolWeightValues.any { it == Long.MIN_VALUE } ||
        objective.intCoefficientValues.any { it == Long.MIN_VALUE }
    ) return null
    return objective.negated()
}
