package com.eignex.klause.backtrack.selector

import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.search.VarRef
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.random.Random

/**
 * Counting-based value selection — the maxSD (maximum solution density) heuristic of Pesant, Quimper
 * and Zanarini, "Counting-Based Search: Branching Heuristics for Constraint Satisfaction Problems", JAIR 43,
 * 2012 (https://doi.org/10.1613/jair.3463). Completed extensional support counters order small integer
 * domains by the product of per-factor support fractions. These counts describe live relation rows,
 * not independent global solutions, and only order alternatives.
 *
 * Unavailable counters and larger domains probe each candidate and score the log-product of its
 * remaining domain sizes. This fallback uses [Impact]'s probing machinery with the order reversed.
 * A supplied [fallback] replaces those probes when a recipe requires cheap declines.
 */
internal class MaxSd(
    private val maxProbes: Int = 32,
    private val fallback: ValueSelector? = null,
    private val maxSupportVisits: Int = 65_536,
    private val probeWorkLimit: Long? = null,
) : ValueSelector {

    override fun fresh() = MaxSd(maxProbes, fallback?.fresh(), maxSupportVisits, probeWorkLimit)
    override fun values(session: PropagationSession, varRef: VarRef, rng: Random): Sequence<Long> {
        if (varRef is VarRef.IntVar) {
            val domain = session.intDomain(varRef.varId).spanOrNull(maxProbes.toLong())
            if (domain != null) {
                val values = LongArray(domain.size) { domain.valueAt(it) }
                val densities = session.extensionalDensities(varRef.varId, values, maxSupportVisits)
                if (densities != null) {
                    session.valueOrdering.supportOrders++
                    return values.indices.sortedByDescending { densities[it] }.asSequence().map { values[it] }
                }
            }
        }
        return fallback?.values(session, varRef, rng)
            ?: probe(session, varRef, rng)
    }

    private fun probe(session: PropagationSession, varRef: VarRef, rng: Random): Sequence<Long> {
        val observations = session.valueOrdering
        observations.probeOrders++
        val start = session.work
        val priorCancelled = session.probeCancelled
        var capped = false
        val token = probeWorkLimit?.let { limit ->
            object : Cancellation {
                private val meter = WorkMeter { }
                override fun workMeter(): WorkMeter = meter
                override fun isCancelled(): Boolean {
                    if (session.work - start >= limit) capped = true
                    return capped
                }
            }
        } ?: Cancellation.Never
        return try {
            session.withCancellation(token) {
                probeAndOrder(session, varRef, rng, maxProbes, ascending = false, observations = observations)
            }
        } finally {
            observations.probeWork += session.work - start
            if (capped) {
                observations.cappedOrders++
                if (!session.cancelRequested) session.probeCancelled = priorCancelled
            }
        }
    }
}
