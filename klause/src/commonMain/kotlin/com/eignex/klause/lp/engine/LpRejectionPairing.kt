package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.ContinuationDecline
import com.eignex.klause.simplex.exact.ContinuationPhase

internal enum class LpContinuationSite { EARLY, LATE }
internal enum class LpRefinementBudget { UNREACHED, AVAILABLE, WORK_EMPTY, ALLOCATION_EMPTY, BOTH_EMPTY }
internal enum class LpRefinementStage {
    ADMISSION, PRIOR, ENTRY_CHARGE, RUN, SOURCE_BASIS, SOURCE_AUTHORITY, KNOWN_POINT,
    STRICT_ELIGIBILITY, STRICT_MARGIN, CHILD_ADMISSION, COMPLETION,
}

internal class LpRefinementPath(val preferBasis: Boolean) {
    var beforePrior = LpRefinementBudget.UNREACHED
    var afterPrior = LpRefinementBudget.UNREACHED
    var runEntry = LpRefinementBudget.UNREACHED
    var stage = LpRefinementStage.ADMISSION
    var decline: LpRefinementDecline? = null

    fun label(): String = "PREFERRED_${preferBasis}_BEFORE_${beforePrior}_AFTER_${afterPrior}_" +
        "RUN_${runEntry}_AT_${stage}_DECLINE_${decline?.name ?: "NONE"}"
}

internal class LpRejectionPairing {
    private val counts = LongArray(LpPointRejection.entries.size + 1)
    private val routes = LongArray(counts.size * LpContinuationSite.entries.size * 2)
    private val refinementPaths = arrayOfNulls<LpRefinementPath>(2)
    private var refinementCalls = 0
    var refinement: LpRefinementMetrics? = null
    var stopObserved: Boolean = false

    fun newRefinement(preferBasis: Boolean): LpRefinementPath? {
        val index = refinementCalls++
        if (index >= refinementPaths.size) return null
        return LpRefinementPath(preferBasis).also { refinementPaths[index] = it }
    }

    fun observe(result: LpContinuationVerification, site: LpContinuationSite, floatPresent: Boolean) {
        if (result.metrics.phase != ContinuationPhase.VERIFY ||
            result.metrics.decline != ContinuationDecline.CANDIDATE
        ) return
        val index = result.pointRejection?.ordinal ?: LpPointRejection.entries.size
        counts[index]++
        routes[(index * LpContinuationSite.entries.size + site.ordinal) * 2 + if (floatPresent) 1 else 0]++
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
            for (site in LpContinuationSite.entries) {
                for (present in 0..1) {
                    val routed = routes[(index * LpContinuationSite.entries.size + site.ordinal) * 2 + present]
                    if (routed == 0L) continue
                    val float = if (present == 1) "FLOAT_PRESENT" else "FLOAT_MISSING"
                    val context = "${reason}_${site}_${float}"
                    observer.observeContinuationRejection("VERIFY_CANDIDATE_ROUTE_${context}_$outcome", routed)
                    if (refinementCalls == 0) {
                        observer.observeContinuationRejection("VERIFY_CANDIDATE_ENTRY_${context}_NONE_$outcome", routed)
                    }
                    for ((call, path) in refinementPaths.withIndex()) {
                        if (path == null) continue
                        observer.observeContinuationRejection(
                            "VERIFY_CANDIDATE_ENTRY_${context}_${call + 1}_${path.label()}_$outcome", routed,
                        )
                    }
                    if (refinementCalls > refinementPaths.size) {
                        observer.observeContinuationRejection(
                            "VERIFY_CANDIDATE_ENTRY_${context}_LIMIT_UNAVAILABLE_$outcome", routed,
                        )
                    }
                }
            }
        }
    }
}
