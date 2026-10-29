package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.ExactContinuationMetrics
import kotlin.time.TimeSource

internal enum class LpClaim(val phase: LpSolvePhase) {
    BOUND(LpSolvePhase.CLAIM_BOUND),
    CONTRADICTION(LpSolvePhase.CLAIM_CONTRADICTION),
    FEASIBLE_COMPLETION(LpSolvePhase.CLAIM_FEASIBLE_COMPLETION),
    ATTAINED_OPTIMUM(LpSolvePhase.CLAIM_ATTAINED_OPTIMUM);

    fun consumed(observer: LpCertificationObserver?) {
        observer?.observePhase(LpPhaseMetrics(LpSolvePhase.CLAIM_CONSUMPTION, name, 0L))
    }

    fun satisfiedBy(result: CertifiedLpResult): Boolean = when (this) {
        BOUND -> result.bound != null
        CONTRADICTION -> result.verdict == LpVerdict.INFEASIBLE
        FEASIBLE_COMPLETION -> result.witness != null
        ATTAINED_OPTIMUM -> result.verdict == LpVerdict.ATTAINED_OPTIMUM
    }

    fun needsMore(bound: CertifiedLpBound?, witness: ExactLpWitness?): Boolean = when (this) {
        BOUND -> bound == null
        CONTRADICTION -> witness == null
        FEASIBLE_COMPLETION -> witness == null
        ATTAINED_OPTIMUM -> witness == null || bound?.value != witness.objective
    }
}

// Inclusive request costs are a view of the existing ledger, not additional work charges.
internal class LpClaimObservation(
    private val claim: LpClaim,
    private val delegate: LpCertificationObserver,
) : LpCertificationObserver by delegate {
    private val started = TimeSource.Monotonic.markNow()
    private var work = 0L
    private var refusal: String? = null

    private fun charge(value: Long) {
        work += minOf(value.coerceAtLeast(0L), Long.MAX_VALUE - work)
    }

    override fun observe(certifier: LpCertifier, success: Boolean, cost: LpCertifierCost) {
        if (cost is LpCertifierCost.Metered) charge(cost.work)
        delegate.observe(certifier, success, cost)
    }

    override fun observeBasisVerification(metrics: ExactBasisMetrics) {
        charge(metrics.work)
        metrics.decline?.let { refusal = it.name }
        delegate.observeBasisVerification(metrics)
    }

    override fun observeContinuation(metrics: ExactContinuationMetrics) {
        charge(metrics.work)
        metrics.decline?.let { refusal = it.name }
        delegate.observeContinuation(metrics)
    }

    fun refuse(reason: String) {
        refusal = reason
    }

    fun finish(result: CertifiedLpResult, reused: Boolean = false) {
        val outcome = when {
            reused -> "REUSED"
            claim.satisfiedBy(result) -> "SATISFIED"
            result.verdict == LpVerdict.INFEASIBLE -> "CONTRADICTION"
            result.floatOptimum != null -> "TOLERANCE_ACCEPTED"
            else -> refusal ?: result.reconstruction?.let {
                if (it.dualRejection != null) "MISSING_BOUND_SIDE" else it.decline?.name
            } ?: result.refinement?.decline?.name ?: "NO_REQUIRED_PACKAGE"
        }
        finish(outcome)
    }

    fun finish(outcome: String) {
        delegate.observePhase(LpPhaseMetrics(claim.phase, outcome, started.elapsedNow().inWholeNanoseconds, work))
    }
}

internal inline fun <T : Any> observeLpClaim(
    claim: LpClaim,
    observer: LpCertificationObserver?,
    check: (LpCertificationObserver?) -> T?,
): T? {
    val observation = observer?.takeUnless { it is LpClaimObservation }?.let { LpClaimObservation(claim, it) }
    val result = check(observation ?: observer)
    observation?.finish(if (result != null) "SATISFIED" else "NO_REQUIRED_PACKAGE")
    return result
}
