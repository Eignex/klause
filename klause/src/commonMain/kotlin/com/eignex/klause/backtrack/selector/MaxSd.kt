package com.eignex.klause.backtrack.selector

import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.search.VarRef
import kotlin.random.Random

// The maxSD heuristic of Pesant, Quimper and Zanarini, "Counting-Based Search: Branching Heuristics for
// Constraint Satisfaction Problems", JAIR 43, 2012 (https://doi.org/10.1613/jair.3463). Extensional support
// counts order small domains; they describe live relation rows, not global solutions. Otherwise [fallback],
// or else impact probing with the order reversed.
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
        return try {
            session.withWorkLimit(probeWorkLimit) {
                probeAndOrder(
                    session, varRef, rng, maxProbes, ascending = false, observations = observations,
                    scoreVisit = if (probeWorkLimit == null) null else session::visitValueScore,
                )
            }
        } finally {
            observations.probeWork += session.work - start
            if (probeWorkLimit?.let { session.work - start >= it } == true) {
                observations.cappedOrders++
                if (!session.cancelRequested) session.probeCancelled = priorCancelled
            }
        }
    }
}
