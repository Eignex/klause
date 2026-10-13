package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.ContinuationDecline
import com.eignex.klause.simplex.exact.ContinuationPhase

internal class LpRejectionPairing {
    private val counts = LongArray(LpPointRejection.entries.size + 1)
    var refinement: LpRefinementMetrics? = null
    var stopObserved: Boolean = false

    fun observe(result: LpContinuationVerification) {
        if (result.metrics.phase != ContinuationPhase.VERIFY ||
            result.metrics.decline != ContinuationDecline.CANDIDATE
        ) return
        val index = result.pointRejection?.ordinal ?: LpPointRejection.entries.size
        counts[index]++
    }

    fun report(observer: LpCertificationObserver, completed: Boolean) {
        val metrics = refinement
        val outcome = when {
            !completed -> "PAIRING_UNAVAILABLE_ABORTED"
            stopObserved || metrics?.decline == LpRefinementDecline.CANCELLED -> "STOP_OBSERVED"
            metrics == null -> "REFINEMENT_NOT_REPORTED"
            metrics.strictWitnesses > 0 -> "STRICT_NUMERICAL_WITNESS"
            metrics.strictConflicts > 0 -> "STRICT_NUMERICAL_CONFLICT"
            metrics.strictAttempts > 0 -> "STRICT_ATTEMPT_${metrics.decline?.name ?: "NO_PACKAGE"}"
            else -> "NO_STRICT_ATTEMPT_${metrics.decline?.name ?: "COMPLETED"}"
        }
        for (index in counts.indices) {
            val count = counts[index]
            if (count == 0L) continue
            val reason = LpPointRejection.entries.getOrNull(index)?.name ?: "POINT_REASON_UNAVAILABLE"
            observer.observeContinuationRejection("VERIFY_CANDIDATE_FIRST_${reason}_$outcome", count)
        }
    }
}
