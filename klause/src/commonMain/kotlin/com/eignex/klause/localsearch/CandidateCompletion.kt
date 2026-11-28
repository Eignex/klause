package com.eignex.klause.localsearch

import com.eignex.klause.solver.Sample
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.arrays.emptyIntArray

/**
 * Turns a proposed local-search assignment into a solution of the model, or says why it cannot.
 *
 * Where invariant scoring is inexact (floating-point continuous columns, sums past the 64-bit range), zero
 * violation is only a proposal and this decides it. Optimization may also complete candidate moves to score
 * their completed objectives. A candidate stays private until the completion accepts it.
 */
fun interface CandidateCompletion {
    /** Decide [candidate] under the search's [cancellation]: a [Completion.Witness] is a solution of the model,
     *  anything else is not. */
    fun complete(candidate: Sample, cancellation: ExecutionControl): Completion
}

/** What a [CandidateCompletion] made of one candidate; a refutation covers the candidate, never the model. */
sealed interface Completion {
    /** What deciding the candidate cost, in local-search moves charged to the search budget. */
    val work: Long

    /**
     * [sample] carries the candidate's discrete values and any completed reals, and meets the completion's
     * configured witness guarantee. Reals accepted under a source tolerance policy are approximate; a witness
     * claims neither optimality nor exact coordinates.
     */
    class Witness(val sample: Sample, override val work: Long = 0L) : Completion

    /** The candidate is no solution. [factors] names the model rows that show it, when the check knows them;
     *  local search raises their weights so the next descent steers away from the same failure. */
    class Refuted(val factors: IntArray = emptyIntArray(), override val work: Long = 0L) : Completion

    /** The check could neither confirm nor refute the candidate within its budget. */
    class Undecided(override val work: Long = 0L) : Completion
}
