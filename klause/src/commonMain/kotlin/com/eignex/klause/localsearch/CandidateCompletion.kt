package com.eignex.klause.localsearch

import com.eignex.klause.solver.Sample
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.EmptyIntArray

/**
 * Turns a proposed local-search assignment into a solution of the model, or says why it
 * cannot.
 *
 * Local search scores a model through its invariants, and on some models that scoring is not exact: a
 * continuous column it moves in floating point, an incremental sum past the 64-bit range, a row whose
 * coefficients outrun the invariant's width. On those models zero violation is a proposal rather than a
 * proof, and this is what decides it. Optimization may also complete a bounded set of candidate moves to score
 * their completed objectives. Each call runs under the search's cancellation and work allowance; a candidate stays
 * private until the completion accepts it.
 */
fun interface CandidateCompletion {
    /** Decide [candidate] under the search's [cancellation]: a [Completion.Witness] is a solution of the model,
     *  anything else is not. */
    fun complete(candidate: Sample, cancellation: Cancellation): Completion
}

/**
 * What a [CandidateCompletion] made of one candidate. Refutes the candidate alone, never the model. [work] is what
 * deciding it cost, in local-search moves, which the search charges against its own budget.
 */
sealed interface Completion {
    /** What deciding the candidate cost, in local-search moves. */
    val work: Long

    /**
     * [sample] carries the candidate's discrete values and any completed reals, and meets the completion's
     * configured witness guarantee. Reals accepted under a source tolerance policy are approximate; a witness
     * claims neither optimality nor exact coordinates.
     */
    class Witness(val sample: Sample, override val work: Long = 0L) : Completion

    /** The candidate is no solution. [factors] names the model rows that show it, when the check knows them;
     *  local search raises their weights so the next descent steers away from the same failure. */
    class Refuted(val factors: IntArray = EmptyIntArray, override val work: Long = 0L) : Completion

    /** The check could neither confirm nor refute the candidate within its budget. */
    class Undecided(override val work: Long = 0L) : Completion
}
