package com.eignex.klause.theory

import com.eignex.klause.solver.ProblemSpec
import com.eignex.klause.solver.search.SearchExplanation

/** A complete decision procedure for one open-model theory fragment. */
interface Theory<out A> {
    /** Source model whose Boolean skeleton the shared engine drives. */
    val model: ProblemSpec

    /** Decides one complete Boolean assignment. */
    fun check(bools: BooleanArray, context: TheoryContext): TheoryCheck<A>
}

/** Per-run limits available to a theory's local search. */
interface TheoryContext {
    /** Returns false when the shared complete-check budget is exhausted. */
    fun consumeCheck(): Boolean

    /** True when the caller must stop cooperatively. */
    fun cancelled(): Boolean

    /** Tightest shared lower bound for an integer column, without exposing a CP domain. */
    fun intLowerBound(variable: Int): Long? = null

    /** Tightest shared upper bound for an integer column, without exposing a CP domain. */
    fun intUpperBound(variable: Int): Long? = null
}

/** A theory's answer for one Boolean assignment. */
sealed interface TheoryCheck<out A> {
    /**
     * This Boolean assignment has a complete witness.
     *
     * @param A Theory assignment type.
     * @property assignment Complete theory-owned assignment.
     */
    data class Sat<A>(val assignment: A) : TheoryCheck<A>

    /**
     * This Boolean assignment is infeasible in the theory fragment.
     *
     * [explanation] is a clause over source Boolean literals that is false under the checked
     * assignment. It lets a complete theory share a conflict certificate with the common search
     * engine; absent certificates preserve the existing chronological fallback.
     */
    data class Infeasible(val explanation: SearchExplanation? = null) : TheoryCheck<Nothing>

    /** Exact checking stopped before determining the assignment. */
    data object Cancelled : TheoryCheck<Nothing>
}
