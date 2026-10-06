package com.eignex.klause.localsearch

import com.eignex.klause.solver.Sample
import com.eignex.klause.util.EmptyIntArray

/**
 * Turns an assignment local search reached at zero violation into a solution of the model, or says why it
 * cannot.
 *
 * Local search scores a model through its invariants, and on some models that scoring is not exact: a
 * continuous column it moves in floating point, an incremental sum past the 64-bit range, a row whose
 * coefficients outrun the invariant's width. On those models zero violation is a proposal rather than a
 * proof, and this is what decides it. It is consulted only at a candidate, never per move.
 */
fun interface CandidateCompletion {
    /** Decide [candidate]: a [Completion.Witness] is a solution of the model, anything else is not. */
    fun complete(candidate: Sample): Completion
}

/** What a [CandidateCompletion] made of one candidate. Refutes the candidate alone, never the model. */
sealed interface Completion {
    /** [sample] is a solution of the model; it carries the candidate's discrete values and any completed reals. */
    class Witness(val sample: Sample) : Completion

    /** The candidate is no solution. [factors] names the model rows that show it, when the check knows them;
     *  local search raises their weights so the next descent steers away from the same failure. */
    class Refuted(val factors: IntArray = EmptyIntArray) : Completion

    /** The check could neither confirm nor refute the candidate within its budget. */
    data object Undecided : Completion
}
