package com.eignex.klause.theory

import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.search.SearchExplanation

/**
 * A batch checking service for one source-model fragment under a complete Boolean assignment.
 *
 * [check] establishes feasibility or infeasibility only for that fragment and the supplied conditions.
 * Participation does not promise a complete decision procedure: an incomplete service or an interrupted
 * check returns [TheoryCheck.Cancelled] when it cannot decide. [TheorySearchComponent] bridges this
 * batch contract into the incremental [com.eignex.klause.solver.search.SearchComponent] lifecycle.
 */
interface Theory<out A> {
    /** Source model. */
    val model: Problem

    /** Check the fragment under [bools] and the semantic bounds and limits in [context]. */
    fun check(bools: BooleanArray, context: TheoryContext): TheoryCheck<A>
}

/** Per-invocation limits and semantic bounds available to a batch fragment check. */
interface TheoryContext {
    /** Consumes one complete-check allowance. */
    fun consumeCheck(): Boolean

    /** Whether the caller must stop cooperatively. */
    fun cancelled(): Boolean

    /** Tightest shared lower bound for an integer column, without exposing a CP domain. */
    fun intLowerBound(variable: Int): Long? = null

    /** Tightest shared upper bound for an integer column, without exposing a CP domain. */
    fun intUpperBound(variable: Int): Long? = null
}

/** A fragment-scoped answer for one complete Boolean assignment and its active conditions. */
sealed interface TheoryCheck<out A> {
    /** Satisfying fragment result with its authoritative assignment. */
    data class Sat<A>(
        /** Complete assignment for the checked fragment. */
        val assignment: A,
    ) : TheoryCheck<A>

    /**
     * This Boolean assignment is infeasible in the theory fragment.
     *
     * [explanation] is a clause over source Boolean literals that is false under the checked
     * assignment. It lets a checking service share a conflict certificate with the common search
     * engine; absent certificates preserve the existing chronological fallback.
     */
    data class Infeasible(val explanation: SearchExplanation? = null) : TheoryCheck<Nothing>

    /**
     * The fragment remains undecided because of cancellation, limits or an incomplete service.
     * The compatibility name does not restrict this result to external cancellation. It supplies
     * neither a witness nor a refutation and must not be cached as a decided answer.
     */
    data object Cancelled : TheoryCheck<Nothing>
}
