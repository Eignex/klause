package com.eignex.klause.solver.incumbent

/**
 * An **untrusted** proposal for a new incumbent: an [assignment] with the [objective] value its producer
 * claims for it. Assignment and objective payloads must be immutable through verification and
 * publication; custom verifiers capture borrowed producer state before accepting it. Nothing reads a
 * candidate as a solution until a [CandidateVerifier] has accepted it.
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
 * Versions of one [IncumbentExchange] are strictly increasing, so consumers decide freshness by version
 * rather than by assignment identity.
 */
class VerifiedIncumbent<out A, out V>(
    /** The verified [Candidate.assignment]. */
    val assignment: A,
    /** Its verified [Candidate.objective]. */
    val objective: V,
    /** Position in the installing exchange's strictly increasing sequence, counted from 1. */
    val version: Long,
)

/** The read half of an incumbent exchange: whatever incumbent stands, or null before the first. */
fun interface IncumbentSource<out A, out V> {
    /** The incumbent standing at this instant, or null before the first one is installed. */
    fun current(): VerifiedIncumbent<A, V>?
}
