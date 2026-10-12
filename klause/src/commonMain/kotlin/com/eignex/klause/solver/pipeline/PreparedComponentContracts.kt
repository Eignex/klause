package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.AssignmentCheckCapability
import com.eignex.klause.solver.PropagationCapability
import com.eignex.klause.solver.ScoringCapability
import com.eignex.klause.solver.executionCapabilities

internal val witnessAuthority = setOf(ExecutionClaimAuthority.WITNESS)
internal val completeAuthority = ExecutionClaimAuthority.entries.toSet()
internal val sourceOperations = setOf(ExecutionOperation.SATISFY, ExecutionOperation.OPTIMIZE)
internal val finiteOperations = ExecutionOperation.entries.toSet()

internal fun Problem.unplacedArithmeticContract(): ComponentExecutionContract {
    val missing = ComponentStrategySupport(ProcedureCompleteness.UNAVAILABLE, emptySet(), listOf(ExecutionDecline(
        ExecutionDeclineCause.SOURCE_COMPLETION, "unplaced-arithmetic",
        preparation = PreparedPlanDecline.UNOWNED_SOURCE_COMPONENT)))
    val checkedDiscrete = numRealVars == 0 && factors.all { factor ->
        factor.executionCapabilities().assignmentCheck in setOf(
            AssignmentCheckCapability.EXACT, AssignmentCheckCapability.REDUNDANT)
    }
    val local = if (checkedDiscrete) ComponentStrategySupport(
        ProcedureCompleteness.WITNESS_SEARCH, witnessAuthority) else missing
    return ComponentExecutionContract(missing, local, missing)
}

internal fun unavailableContract(
    cause: ExecutionDeclineCause,
    semantics: String,
    preparation: PreparedPlanDecline? = null,
): ComponentExecutionContract {
    val support = ComponentStrategySupport(ProcedureCompleteness.UNAVAILABLE, emptySet(),
        listOf(ExecutionDecline(cause, semantics, preparation = preparation)))
    return ComponentExecutionContract(support, support)
}

internal fun checkedCompletionContract(
    completeness: ProcedureCompleteness,
    authorities: Set<ExecutionClaimAuthority> = completeAuthority,
): ComponentExecutionContract = ComponentExecutionContract(
    ComponentStrategySupport(completeness, authorities),
    ComponentStrategySupport(ProcedureCompleteness.WITNESS_SEARCH, witnessAuthority),
)

internal fun Problem.finiteRealCompletionSupported(): Boolean = realLower.none { it.isNaN() } &&
    realUpper.none { it.isNaN() } && factors.all { factor ->
        factor.variables.reals.isEmpty() || factor is RealProduct ||
            ((factor.linearForm is LinearForm.Conjunction || factor.linearForm is LinearForm.Disjunction) &&
                factor.linearForm!!.rows.none { it.strict })
    }

internal fun Problem.finiteComponentContract(
    factors: List<Int>,
    integers: List<Int>,
    ownership: ComponentPlan?,
    sourceCompletion: Boolean,
    exactNeighborhood: Boolean,
): ComponentExecutionContract {
    fun refusal(cause: ExecutionDeclineCause, index: Int? = null): ComponentStrategySupport =
        ComponentStrategySupport(ProcedureCompleteness.UNAVAILABLE, emptySet(),
            listOf(ExecutionDecline(cause, "finite", index)))
    val incompleteProjection = integers.any { !intBounds.hasLower(it) || !intBounds.hasUpper(it) } ||
        factors.any { index -> this.factors[index].intVars.any { column ->
            !intBounds.hasLower(column) || !intBounds.hasUpper(column) ||
                ownership?.intOwner(column) == IntVariableOwner.THEORY
        } }
    val unsupportedPropagation = factors.firstOrNull {
        this.factors[it].executionCapabilities().propagation == PropagationCapability.UNSUPPORTED
    }
    val missingCheck = factors.firstOrNull { index ->
        val capabilities = this.factors[index].executionCapabilities()
        capabilities.propagationCheck == AssignmentCheckCapability.UNSUPPORTED ||
            capabilities.assignmentCheck == AssignmentCheckCapability.UNSUPPORTED ||
            (capabilities.assignmentCheck == AssignmentCheckCapability.SOURCE_CERTIFICATION && !sourceCompletion)
    }
    val complete = when {
        incompleteProjection -> refusal(ExecutionDeclineCause.FINITE_PROJECTION)
        unsupportedPropagation != null -> refusal(ExecutionDeclineCause.FINITE_PROJECTION, unsupportedPropagation)
        missingCheck != null -> refusal(ExecutionDeclineCause.SOURCE_COMPLETION, missingCheck)
        else -> ComponentStrategySupport(ProcedureCompleteness.DECISION, completeAuthority)
    }
    val missingScore = factors.firstOrNull {
        this.factors[it].executionCapabilities().scoring == ScoringCapability.UNSUPPORTED
    }
    val unsupportedTimeline = factors.firstOrNull { index ->
        val factor = this.factors[index]
        factor is Cumulative && factor.intVars.any { column ->
            !intBounds.hasLower(column) || intBounds.lower(column) !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
        }
    }
    val local = when {
        exactNeighborhood -> if (complete.runnable) {
            ComponentStrategySupport(ProcedureCompleteness.WITNESS_SEARCH, witnessAuthority)
        } else complete
        missingCheck != null -> refusal(ExecutionDeclineCause.SOURCE_COMPLETION, missingCheck)
        missingScore != null -> refusal(ExecutionDeclineCause.HEURISTIC_PROJECTION, missingScore)
        unsupportedTimeline != null -> refusal(ExecutionDeclineCause.HEURISTIC_PROJECTION, unsupportedTimeline)
        else -> ComponentStrategySupport(ProcedureCompleteness.WITNESS_SEARCH, witnessAuthority)
    }
    val adaptive = if (complete.runnable) local else complete
    return ComponentExecutionContract(complete, local, adaptive)
}
