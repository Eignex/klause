package com.eignex.klause.lp.engine

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
    val attempt = advanceFloat(quantum, allowance, cancellation)
    val result = attempt?.second
    if (result == null || cancellation()) {
        return LpRootGuidance(null, null,
            if (lastFloatTermination == null && !floatPaused) LpRootGuidancePhase.PREPARATION
            else LpRootGuidancePhase.FLOAT, floatPaused, lastFloatTermination)
    }
    val point = result.primal.takeIf { it.size == state.model.n && it.all(Double::isFinite) }
    val checked = certifyLpBoundOnly(
        requireNotNull(state.ownerWorkingModel()), result.duals, certificateLimits, cancellation, observer, policy,
    )
    if (cancellation()) return LpRootGuidance(null, null, LpRootGuidancePhase.CERTIFICATE, false,
        lastFloatTermination, checked.metrics)
    return LpRootGuidance(point, checked.bound,
        if (checked.bound == null) LpRootGuidancePhase.CERTIFICATE else LpRootGuidancePhase.COMPLETE,
        false, lastFloatTermination, checked.metrics, result.luMaxFill, result.luMaxDensity)
}
