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
 * proof, and this is what decides it. Optimization can also admit a bounded set of finite moves for
 * continuous completion before scoring their completed objectives. Each attempt retains the containing
 * cancellation and work allowance; candidates are private until the completion accepts them.
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
     * [sample] meets the completion's configured source witness guarantee, with the candidate's discrete values
     * and any completed reals. Floating coordinates accepted by an explicit source tolerance policy remain
     * approximate; this result supplies no optimality or exact-coordinate authority.
     */
    class Witness(val sample: Sample, override val work: Long = 0L) : Completion

    /** The candidate is no solution. [factors] names the model rows that show it, when the check knows them;
     *  local search raises their weights so the next descent steers away from the same failure. */
    class Refuted(val factors: IntArray = EmptyIntArray, override val work: Long = 0L) : Completion

    /** The check could neither confirm nor refute the candidate within its budget. */
    class Undecided(override val work: Long = 0L) : Completion
}
