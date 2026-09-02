package com.eignex.klause.localsearch

import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.IncumbentSource
import com.eignex.klause.solver.incumbent.IncumbentSubscription

/**
 * Cross-engine solution flow: pulls the verified incumbent any arm has published and hands it back
 * only when it is *fresh* (a version this caller has not imported) and strictly better than the caller's
 * current best. Inert without a [source] or when [enabled] is false — the run carries assumption pins a
 * foreign full assignment could violate. The caller adopts the returned sample however it sees fit: a
 * restart anchor for [LocalSearchSolver], the incumbent for [com.eignex.klause.meta.alns.Alns]. Sharing
 * this one implementation keeps the two outer loops from each carrying their own copy of the
 * freshness-gate + pin-guard + adopt-if-better logic.
 *
 * The objective is re-derived through [evaluate] rather than taken from the exchange, because the caller
 * scores against its own objective view (an LS gradient view need not agree numerically with the publisher's).
 *
 * Single-threaded and stateful (it remembers the last version imported); drive it from one loop.
 */
internal class PooledSolutionImporter(
    source: IncumbentSource<Sample, Double>?,
    private val enabled: Boolean,
    private val evaluate: (Sample) -> Double,
) {
    private val incumbents = source?.let { IncumbentSubscription(it) }

    /**
     * The published assignment worth adopting given the caller's [currentBest] objective, or null when the
     * exchange offers nothing new and strictly better. Reads the exchange at most once per call, so a caller
     * polling once per restart/iteration triggers exactly one read per poll.
     */
    fun poll(currentBest: Double): Adoption? {
        if (!enabled) return null
        val fresh = incumbents?.poll() ?: return null
        val objective = evaluate(fresh.assignment)
        return if (objective < currentBest) Adoption(fresh.assignment, objective) else null
    }

    /** A published assignment [sample] with its [objective], strictly better than the caller's current best. */
    data class Adoption(val sample: Sample, val objective: Double)
}
