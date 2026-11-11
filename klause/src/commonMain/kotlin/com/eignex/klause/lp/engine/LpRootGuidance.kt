package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigRationalConflict
import com.eignex.klause.util.Cancellation

internal enum class LpRootGuidancePhase { PREPARATION, FLOAT, CERTIFICATE, NUMERICAL, COMPLETE }

internal class LpNumericalBound(private val model: LpModel, val value: Double) {
    fun belongsTo(candidate: LpModel): Boolean = candidate === model
}

internal class LpNumericalConflict(private val model: LpModel, val margin: Double) {
    fun belongsTo(candidate: LpModel): Boolean = candidate === model
}

internal class LpRootGuidance(
    primal: DoubleArray?,
    val bound: CertifiedLpBound?,
    val phase: LpRootGuidancePhase,
    val paused: Boolean,
    val termination: LpFloatTermination?,
    val certificateMetrics: ReconstructionMetrics? = null,
    val maxLuFill: Double? = null,
    val maxLuDensity: Double? = null,
    val optimalCandidate: Boolean = false,
    val conflict: BigRationalConflict? = null,
    val conflictSupport: LpExactSupport? = null,
    val numericalBound: LpNumericalBound? = null,
    val numericalConflict: LpNumericalConflict? = null,
    val numericalWork: Long = 0L,
) {
    private val values = primal?.copyOf()
    val primal: DoubleArray? get() = values?.copyOf()
}

// The caller owns the scoped source session and maps heuristic coordinates back to its source.
internal fun LpScopedSolver.advanceRootGuidance(
    quantum: Long,
    allowance: LpFloatAllowance,
    certificateLimits: ReconstructionLimits,
    cancellation: Cancellation = Cancellation.Never,
    observer: LpCertificationObserver? = null,
    policy: LpCertificationPolicy = proofPolicy,
): LpRootGuidance {
    val token = cancellation or cancellationToken
    val attempt = advanceFloat(quantum, allowance, token, retainFundingStop = true)
    val result = attempt?.second
    if (attempt != null && result == null && !token() &&
        lastFloatTermination == LpFloatTermination.INFEASIBLE_CANDIDATE
    ) {
        val ray = attempt.first.infeasibleRay
        if (ray != null) {
            if (policy.numericalDeductions) {
                return numericalConflictGuidance(requireNotNull(state.ownerWorkingModel()), ray,
                    certificateLimits.maxWork, token, observer, lastFloatTermination)
            }
            val checked = certifyLpConflictOnly(requireNotNull(state.ownerWorkingModel()), ray,
                certificateLimits, token, observer, policy)
            val conflict = checked.conflict.takeUnless { token() }
            return LpRootGuidance(null, null,
                if (conflict != null) LpRootGuidancePhase.COMPLETE else LpRootGuidancePhase.CERTIFICATE,
                false, lastFloatTermination, checked.metrics, conflict = conflict,
                conflictSupport = checked.conflictSupport.takeIf { conflict != null })
        }
    }
    if (result == null || token()) {
        return LpRootGuidance(null, null,
            if (lastFloatTermination == null && !floatPaused) LpRootGuidancePhase.PREPARATION
            else LpRootGuidancePhase.FLOAT, floatPaused && !floatFundingStopped, lastFloatTermination)
    }
    if (policy.numericalDeductions) {
        return numericalBoundGuidance(requireNotNull(state.ownerWorkingModel()), result,
            certificateLimits.maxWork, token, observer, lastFloatTermination)
    }
    val point = result.primal.takeIf { it.size == state.model.n && it.all(Double::isFinite) }
    val checked = certifyLpBoundOnly(
        requireNotNull(state.ownerWorkingModel()), result.duals, certificateLimits, token, observer, policy,
        basis = result.basis,
    )
    if (token()) return LpRootGuidance(null, null, LpRootGuidancePhase.CERTIFICATE, false,
        lastFloatTermination, checked.metrics)
    val phase = when {
        !result.optimal -> LpRootGuidancePhase.FLOAT
        checked.bound == null -> LpRootGuidancePhase.CERTIFICATE
        else -> LpRootGuidancePhase.COMPLETE
    }
    return LpRootGuidance(point, checked.bound, phase, false, lastFloatTermination,
        checked.metrics, result.luMaxFill, result.luMaxDensity, result.optimal)
}

private fun numericalConflictGuidance(
    model: LpModel,
    ray: DoubleArray,
    workLimit: Long,
    cancellation: Cancellation,
    observer: LpCertificationObserver?,
    termination: LpFloatTermination?,
): LpRootGuidance {
    val timer = LpPhaseTimer(observer, LpSolvePhase.NUMERICAL_CONFLICT)
    val checked = floatingInfeasibilityMargin(model, ray, workLimit, cancellation)
    timer.finish(checked.stop?.name ?: if (checked.infeasible) "ACCEPTED" else "REFUSED", checked.work)
    val conflict = checked.margin?.takeIf { checked.infeasible && !cancellation() }
        ?.let { LpNumericalConflict(model, it) }
    return LpRootGuidance(null, null,
        if (conflict != null) LpRootGuidancePhase.COMPLETE else LpRootGuidancePhase.NUMERICAL,
        false, termination, numericalConflict = conflict, numericalWork = checked.work)
}

private fun numericalBoundGuidance(
    model: LpModel,
    result: FloatLpResult,
    workLimit: Long,
    cancellation: Cancellation,
    observer: LpCertificationObserver?,
    termination: LpFloatTermination?,
): LpRootGuidance {
    val point = result.primal.takeIf { it.size == model.n && it.all(Double::isFinite) }
    val timer = LpPhaseTimer(observer, LpSolvePhase.NUMERICAL_BOUND)
    val checked = floatingObjectiveLowerBound(model, result.duals, workLimit, cancellation)
    timer.finish(checked.stop?.name ?: if (checked.bound != null) "ACCEPTED" else "REFUSED", checked.work)
    val bound = checked.bound?.takeUnless { cancellation() }?.let { LpNumericalBound(model, it) }
    val phase = when {
        !result.optimal -> LpRootGuidancePhase.FLOAT
        bound == null -> LpRootGuidancePhase.NUMERICAL
        else -> LpRootGuidancePhase.COMPLETE
    }
    return LpRootGuidance(point.takeUnless { cancellation() }, null, phase, false, termination,
        maxLuFill = result.luMaxFill, maxLuDensity = result.luMaxDensity, optimalCandidate = result.optimal,
        numericalBound = bound, numericalWork = checked.work)
}
