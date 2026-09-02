package com.eignex.klause.solver.incumbent

/**
 * An **untrusted** proposal for a new incumbent: an [assignment] with the [objective] value its producer
 * claims for it. A candidate carries no guarantee — it may come from a heuristic engine, a peer arm
 * running a different objective representation, or an engine whose feasibility test is weaker than the
 * consumer's. Nothing reads a candidate as a solution until a [CandidateVerifier] has accepted it.
 *
 * Generic over the assignment type `A` and the objective type `V` so the same protocol serves a finite-domain
 * [com.eignex.klause.solver.Sample] scored by `Double` and an exact open-model witness scored by a rational
 * — neither is reduced to the other.
 */
data class Candidate<out A, out V>(val assignment: A, val objective: V)

/**
 * The outcome of checking one [Candidate]. Three-valued on purpose: a verifier that cannot decide must say
 * so rather than pick a side, because [Rejected] and [Indeterminate] justify different actions and neither
 * says anything about the *model* — a rejected candidate is one bad proposal, never a proof of infeasibility.
 */
sealed interface Verification<out A, out V> {
    /** The [candidate] holds: it may be published as a [VerifiedIncumbent]. */
    data class Accepted<out A, out V>(val candidate: Candidate<A, V>) : Verification<A, V>

    /** The candidate is refuted — [reason] says how. Refutes the proposal alone, not the model. */
    data class Rejected(val reason: String) : Verification<Nothing, Nothing>

    /** The verifier could neither confirm nor refute the candidate; [reason] says why (budget, missing
     *  information). */
    data class Indeterminate(val reason: String) : Verification<Nothing, Nothing>
}

/** Decides whether a [Candidate] may become an incumbent. Total: every candidate gets one [Verification]. */
fun interface CandidateVerifier<A, V> {
    /** Check [candidate] and say whether it may become an incumbent. */
    fun verify(candidate: Candidate<A, V>): Verification<A, V>

    /** The stock verifiers. */
    companion object {
        /** The verifier for a producer already trusted to emit only feasible assignments: accepts everything. */
        fun <A, V> trusting(): CandidateVerifier<A, V> = CandidateVerifier { Verification.Accepted(it) }
    }
}

/**
 * An accepted candidate installed as the incumbent, stamped with the [version] it was installed at.
 * Versions of one [IncumbentExchange] are strictly increasing, so a consumer decides freshness by comparing
 * versions rather than by assignment identity — which cannot distinguish a re-published assignment from an
 * unchanged one, and cannot order two arms' publications at all.
 */
class VerifiedIncumbent<out A, out V>(
    /** The verified [Candidate.assignment]. */
    val assignment: A,
    /** Its verified [Candidate.objective]. */
    val objective: V,
    /** Position in the installing exchange's strictly increasing sequence, counted from 1. */
    val version: Long,
)

/** The read half of an incumbent exchange: whatever incumbent stands right now, or null before the first. */
fun interface IncumbentSource<out A, out V> {
    /** The incumbent standing at this instant, or null before the first one is installed. */
    fun current(): VerifiedIncumbent<A, V>?
}
