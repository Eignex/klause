@file:OptIn(ExperimentalAtomicApi::class)

package com.eignex.klause.solver.incumbent

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** The bound a minimisation consumer prunes on: the standing incumbent's objective, `+∞` before the first
 *  one is installed — the value a search may still improve on. */
fun IncumbentSource<*, Double>.bound(): Double = current()?.objective ?: Double.POSITIVE_INFINITY

/** What one [IncumbentExchange.offer] did with the candidate it was handed. */
sealed interface Publication<out A, out V> {
    /** *This* call installed [incumbent]. Exactly one caller ever sees a given version installed, so a
     *  publisher that reports an improvement on this outcome can neither duplicate nor report a stale one. */
    data class Installed<out A, out V>(val incumbent: VerifiedIncumbent<A, V>) : Publication<A, V>

    /** Verified, but not strictly better than the incumbent standing at the moment of the attempt. */
    data object NotImproving : Publication<Nothing, Nothing>

    /** The verifier refuted the candidate; [reason] is its explanation. Says nothing about the model. */
    data class Rejected(val reason: String) : Publication<Nothing, Nothing>

    /** The verifier could not decide ([reason] says why); the incumbent is untouched. */
    data class Indeterminate(val reason: String) : Publication<Nothing, Nothing>
}

/**
 * The single verified incumbent shared by every producer and consumer of one optimisation run: arms publish
 * candidates, the exchange verifies them, and a candidate that survives verification *and* strictly improves
 * the standing incumbent is installed under a fresh version.
 *
 * Lock-free — one compare-and-set swaps assignment, objective, and version together, so a reader can never
 * observe an objective that belongs to a different assignment, and a losing publisher is told it lost. That
 * makes it correct under the parallel executor's concurrent writers and free under the sequential one's
 * single writer, without either having to pick a locking discipline.
 *
 * The exchange holds the best only. A diversity pool would be a different type: nothing here retains a
 * superseded incumbent, and [offer] is the only way in.
 */
class IncumbentExchange<A, V>(
    /** Strict improvement over the standing incumbent: `improves(candidate, standing)`. Must be irreflexive
     *  and transitive, or the version sequence stops meaning "better". */
    private val improves: (V, V) -> Boolean,
    private val verifier: CandidateVerifier<A, V> = CandidateVerifier.trusting(),
) : IncumbentSource<A, V> {

    private val cell = AtomicReference<VerifiedIncumbent<A, V>?>(null)

    override fun current(): VerifiedIncumbent<A, V>? = cell.load()

    /** Verify [assignment]/[objective] and install it when it strictly improves the standing incumbent. */
    fun offer(assignment: A, objective: V): Publication<A, V> {
        val accepted = when (val verdict = verifier.verify(Candidate(assignment, objective))) {
            is Verification.Accepted -> verdict.candidate
            is Verification.Rejected -> return Publication.Rejected(verdict.reason)
            is Verification.Indeterminate -> return Publication.Indeterminate(verdict.reason)
        }
        while (true) {
            val standing = cell.load()
            if (standing != null && !improves(accepted.objective, standing.objective)) return Publication.NotImproving
            val next = VerifiedIncumbent(accepted.assignment, accepted.objective, (standing?.version ?: 0L) + 1L)
            if (cell.compareAndSet(standing, next)) return Publication.Installed(next)
        }
    }

    /** A fresh single-consumer view of this exchange; see [IncumbentSubscription]. */
    fun subscribe(): IncumbentSubscription<A, V> = IncumbentSubscription(this)

    /** The stock exchanges. */
    companion object {
        /**
         * The exchange for a minimisation scored by `Double`: lower is strictly better, and a non-finite
         * score is rejected rather than installed — it carries no information, and `NaN` compares false
         * against every bound, so an unguarded exchange would take it and never let it go.
         */
        fun <A> minimizing(): IncumbentExchange<A, Double> = IncumbentExchange(
            improves = { candidate, standing -> candidate < standing },
            verifier = { candidate ->
                if (candidate.objective.isFinite()) {
                    Verification.Accepted(candidate)
                } else {
                    Verification.Rejected("non-finite objective ${candidate.objective}")
                }
            },
        )
    }
}
