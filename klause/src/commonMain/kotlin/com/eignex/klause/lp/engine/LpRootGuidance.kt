package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigRationalConflict
import com.eignex.klause.util.Cancellation

internal enum class LpRootGuidancePhase { PREPARATION, FLOAT, CERTIFICATE, COMPLETE }

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
    val attempt = advanceFloat(quantum, allowance, token)
    val result = attempt?.second
    if (attempt != null && result == null && !token() &&
        lastFloatTermination == LpFloatTermination.INFEASIBLE_CANDIDATE
    ) {
        val ray = attempt.first.infeasibleRay
        if (ray != null) {
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
            else LpRootGuidancePhase.FLOAT, floatPaused, lastFloatTermination)
    }
    val point = result.primal.takeIf { it.size == state.model.n && it.all(Double::isFinite) }
    val checked = certifyLpBoundOnly(
        requireNotNull(state.ownerWorkingModel()), result.duals, certificateLimits, token, observer, policy,
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
