package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.Power
import com.eignex.klause.factor.arithmetic.RealAbs
import com.eignex.klause.factor.arithmetic.RealFiniteDomain
import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.LinearIndicator
import com.eignex.klause.lp.finiteRelaxationSupported
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.factor.table.RealElement
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.hasCompleteGuardedLpCompletion
import com.eignex.klause.solver.AssignmentCheckCapability
import com.eignex.klause.solver.PreparedFactorProjections
import com.eignex.klause.solver.PropagationCapability
import com.eignex.klause.solver.ScoringCapability
import com.eignex.klause.solver.defaultExecutionCapabilities
import com.eignex.klause.solver.executionCapabilities
import com.eignex.klause.solver.selectedFactorProjections
import com.eignex.klause.theory.EncoderSupport
import com.eignex.klause.theory.EncodingTarget

internal val witnessAuthority = setOf(ExecutionClaimAuthority.WITNESS)
internal val completeAuthority = ExecutionClaimAuthority.entries.toSet()
internal val sourceOperations = setOf(ExecutionOperation.SATISFY, ExecutionOperation.OPTIMIZE)
internal val finiteOperations = ExecutionOperation.entries.toSet()

internal fun Problem.unplacedArithmeticContract(projections: PreparedFactorProjections? = null):
    ComponentExecutionContract {
    val missing = ComponentStrategySupport(ProcedureCompleteness.UNAVAILABLE, emptySet(), listOf(ExecutionDecline(
        ExecutionDeclineCause.SOURCE_COMPLETION, "unplaced-arithmetic",
        preparation = PreparedPlanDecline.UNOWNED_SOURCE_COMPONENT)))
    val checkedDiscrete = numRealVars == 0 && factors.indices.all { index ->
        (projections?.capabilities(index) ?: factors[index].executionCapabilities()).assignmentCheck in setOf(
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

internal fun Problem.finiteRealCompletionSupported(
    projections: PreparedFactorProjections = selectedFactorProjections(),
): Boolean = realLower.none { it.isNaN() } && realUpper.none { it.isNaN() } && factors.indices.all { index ->
    finiteRealFactorSupported(index, projections)
}

internal fun Problem.finiteRealFactorSupported(index: Int, projections: PreparedFactorProjections): Boolean {
    val factor = factors[index]
    val encoder = projections.encoder(index, EncodingTarget.LINEAR)
    if (projections.capabilities(index).propagation == PropagationCapability.UNSUPPORTED) return false
    if (factor.variables.reals.isEmpty()) return true
    if (factor is ExactLinear) return encoder.enforcesSource && factor.finiteRelaxationSupported()
    if (factor is LinearIndicator) return encoder.enforcesSource && factor.row.finiteRelaxationSupported()
    if (!encoder.enforcesSource && encoder.support != EncoderSupport.CONDITIONED) return false
    if (factor is RealProduct) return intBounds.hasLower(factor.intOperand) &&
        intBounds.hasUpper(factor.intOperand) &&
        intBounds.lower(factor.intOperand) >= -9007199254740992L &&
        intBounds.upper(factor.intOperand) <= 9007199254740992L
    return factor is RealAbs || factor is RealElement || factor is RealFiniteDomain ||
        (factor is ContinuousProduct &&
            (factor.hasCompleteGuardedLpCompletion() || factor.finiteChoices?.hasGuards == false)) ||
        (factor is Power && factor.base is Power.Coordinate.Real && factor.exponent <= 1) ||
        ((factor.linearForm is LinearForm.Conjunction || factor.linearForm is LinearForm.Disjunction) &&
            factor.linearForm!!.rows.none { it.strict })
}

internal fun Problem.finiteComponentContract(
    factors: List<Int>,
    integers: List<Int>,
    ownership: ComponentPlan?,
    sourceCompletion: Boolean,
    exactNeighborhood: Boolean,
    projections: PreparedFactorProjections? = null,
    enforcingFactors: Set<Int> = emptySet(),
): ComponentExecutionContract {
    projections?.requireSource(this)
    fun capabilities(index: Int) = projections?.capabilities(index) ?: this.factors[index].executionCapabilities()
    fun completeCheck(index: Int): Boolean {
        val factor = this.factors[index]
        val selected = capabilities(index)
        if (selected.propagationCheck == AssignmentCheckCapability.EXACT) return true
        if (sourceCompletion && (ownership?.factorEncoded(index) == true || index in enforcingFactors)) return true
        if (factor.variables.reals.isNotEmpty()) {
            return selected.propagationCheck == AssignmentCheckCapability.SOURCE_CERTIFICATION && sourceCompletion
        }
        return projections?.requiresOriginalCheck(index) == true &&
            factor.defaultExecutionCapabilities().propagationCheck == AssignmentCheckCapability.EXACT
    }
    fun scoreCheck(index: Int): Boolean = when (capabilities(index).assignmentCheck) {
        AssignmentCheckCapability.EXACT, AssignmentCheckCapability.REDUNDANT -> true
        AssignmentCheckCapability.SOURCE_CERTIFICATION -> if (this.factors[index].variables.reals.isNotEmpty()) {
            sourceCompletion
        } else completeCheck(index)
        AssignmentCheckCapability.UNSUPPORTED -> completeCheck(index)
    }

    fun refusal(cause: ExecutionDeclineCause, index: Int? = null): ComponentStrategySupport =
        ComponentStrategySupport(ProcedureCompleteness.UNAVAILABLE, emptySet(),
            listOf(ExecutionDecline(cause, "finite", index)))
    val incompleteProjection = integers.any { !intBounds.hasLower(it) || !intBounds.hasUpper(it) } ||
        factors.any { index -> this.factors[index].intVars.any { column ->
            !intBounds.hasLower(column) || !intBounds.hasUpper(column) ||
                ownership?.intOwner(column) == IntVariableOwner.THEORY
        } }
    val unsupportedPropagation = factors.firstOrNull {
        capabilities(it).propagation == PropagationCapability.UNSUPPORTED
    }
    val missingCheck = factors.firstOrNull { !completeCheck(it) }
    val missingScoreCheck = factors.firstOrNull { !scoreCheck(it) }
    val complete = when {
        incompleteProjection -> refusal(ExecutionDeclineCause.FINITE_PROJECTION)
        unsupportedPropagation != null -> refusal(ExecutionDeclineCause.FINITE_PROJECTION, unsupportedPropagation)
        missingCheck != null -> refusal(ExecutionDeclineCause.SOURCE_COMPLETION, missingCheck)
        else -> ComponentStrategySupport(ProcedureCompleteness.DECISION, completeAuthority)
    }
    val missingScore = factors.firstOrNull {
        capabilities(it).scoring == ScoringCapability.UNSUPPORTED
    }
    val unsupportedTimeline = factors.firstOrNull { index ->
        val factor = this.factors[index]
        factor is Cumulative && projections?.usesBuiltInScoring(index) != false && factor.intVars.any { column ->
            !intBounds.hasLower(column) || intBounds.lower(column) !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
        }
    }
    val local = when {
        exactNeighborhood -> if (complete.runnable) {
            ComponentStrategySupport(ProcedureCompleteness.WITNESS_SEARCH, witnessAuthority)
        } else complete
        unsupportedPropagation != null -> refusal(ExecutionDeclineCause.FINITE_PROJECTION, unsupportedPropagation)
        missingScoreCheck != null -> refusal(ExecutionDeclineCause.SOURCE_COMPLETION, missingScoreCheck)
        missingScore != null -> refusal(ExecutionDeclineCause.HEURISTIC_PROJECTION, missingScore)
        unsupportedTimeline != null -> refusal(ExecutionDeclineCause.HEURISTIC_PROJECTION, unsupportedTimeline)
        else -> ComponentStrategySupport(ProcedureCompleteness.WITNESS_SEARCH, witnessAuthority)
    }
    val adaptive = if (complete.runnable) local else complete
    return ComponentExecutionContract(complete, local, adaptive)
}
