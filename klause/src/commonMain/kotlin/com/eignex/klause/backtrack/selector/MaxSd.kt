package com.eignex.klause.backtrack.selector

import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.search.VarRef
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
 */
internal class MaxSd(private val maxProbes: Int = 32) : ValueSelector {

    override fun fresh() = MaxSd(maxProbes)
    override fun values(session: PropagationSession, varRef: VarRef, rng: Random): Sequence<Long> {
        if (varRef is VarRef.IntVar) {
            val domain = session.intDomain(varRef.varId).spanOrNull(maxProbes.toLong())
            if (domain != null) {
                val values = LongArray(domain.size) { domain.valueAt(it) }
                val densities = session.extensionalDensities(varRef.varId, values)
                if (densities != null) {
                    return values.indices.sortedByDescending { densities[it] }.asSequence().map { values[it] }
                }
            }
        }
        return probeAndOrder(session, varRef, rng, maxProbes, ascending = false)
    }
}
