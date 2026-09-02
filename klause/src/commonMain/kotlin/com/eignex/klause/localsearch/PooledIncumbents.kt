package com.eignex.klause.localsearch

import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.IncumbentExchange

/**
 * One local-search-class engine's two-way participation in the shared verified-incumbent
 * [IncumbentExchange]: [publish] offers each improvement the engine finds, [poll] hands one back when
 * it is *fresh* (a version this caller has not imported) and strictly better than the caller's own
 * best. The caller adopts what it gets however it sees fit: a restart anchor for [LocalSearchSolver],
 * the destroy incumbent for [com.eignex.klause.meta.alns.Alns]. Sharing this one implementation keeps
 * the two outer loops from each carrying their own copy of the freshness gate, the pin guard, and the
 * offer plumbing.
 *
 * Publication goes through [IncumbentExchange.offer] and nowhere else, so what becomes an incumbent is
 * decided by the exchange's verifier and its strict-improvement test — never by the engine's local
 * "better than *my* best". An offer the exchange refutes or finds non-improving changes nothing here:
 * the engine keeps its own best and its own result stream either way.
 *
 * Importing is gated by [importEnabled] — off when the run carries assumption pins, which a foreign
 * full assignment may violate. Publication is not gated: reaching zero violation means every factor of
 * the problem holds, pinned or not, so the assignment is one the exchange may take.
 *
 * The imported objective is re-derived through [evaluate] rather than taken from the exchange, because
 * the caller scores against its own objective view (an LS gradient view need not agree numerically with
 * the publisher's).
 *
 * Single-threaded and stateful (it remembers the last version imported); drive it from one loop.
 */
internal class PooledIncumbents(
    private val exchange: IncumbentExchange<Sample, Double>?,
    private val importEnabled: Boolean,
    private val evaluate: (Sample) -> Double,
) {
    private val incumbents = exchange?.subscribe()

    /** Offer [sample], scored [objective] by the caller's own view, as a candidate incumbent. Inert
     *  without an exchange. */
    fun publish(sample: Sample, objective: Double) {
        exchange?.offer(sample, objective)
    }

    /**
     * The published assignment worth adopting given the caller's [currentBest] objective, or null when the
     * exchange offers nothing new and strictly better. Reads the exchange at most once per call, so a caller
     * polling once per restart/iteration triggers exactly one read per poll.
     */
    fun poll(currentBest: Double): Adoption? {
        if (!importEnabled) return null
        val fresh = incumbents?.poll() ?: return null
        val objective = evaluate(fresh.assignment)
        return if (objective < currentBest) Adoption(fresh.assignment, objective) else null
    }

    /** A published assignment [sample] with its [objective], strictly better than the caller's current best. */
    data class Adoption(val sample: Sample, val objective: Double)
}
