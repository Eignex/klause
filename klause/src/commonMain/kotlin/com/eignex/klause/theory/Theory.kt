package com.eignex.klause.theory

import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.ProblemSpec
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.search.ClauseSearchComponent
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchComponentSet
import com.eignex.klause.solver.search.SearchExplanation
import com.eignex.klause.solver.search.SearchResult

/** A complete decision procedure for one open-model theory fragment. */
interface Theory<out A> {
    /** Source model whose Boolean skeleton the shared engine drives. */
    val model: ProblemSpec

    /** Decides one complete Boolean assignment. */
    fun check(bools: BooleanArray, context: TheoryContext): TheoryCheck<A>

    /** Decides the model through the shared Boolean-theory engine. */
    fun solve(params: TheoryParams = TheoryParams()): TheoryResult<A> = TheoryEngine(model, this).solve(params)
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

/** Shared Boolean skeleton engine for complete open-model theories. */
class TheoryEngine<A>(private val model: ProblemSpec, private val theory: Theory<A>) {
    /** Decide the source theory through the shared search session. */
    fun solve(params: TheoryParams = TheoryParams()): TheoryResult<A> {
        val component = TheorySearchComponent(theory)
        val components = SearchComponentSet(
            listOf(
                ClauseSearchComponent(model.factors.filterIsInstance<com.eignex.klause.factor.bool.Clause>()),
                component,
            ),
        )
        val session = components.session(
            params.maxLeaves,
            Cancellation { params.cancellation() || model.cancellation() },
        )
        when (session.initialize()) {
            ComponentResult.Consistent -> Unit
            is ComponentResult.Conflict -> return TheoryResult.Unsat()
            ComponentResult.Indeterminate -> return TheoryResult.Unknown(TerminationReason.Unsupported)
        }
        return when (val result = session.solve(model.numBoolVars)) {
            is SearchResult.Satisfied -> TheoryResult.Sat(checkNotNull(result.model.valueOf(component)))

            SearchResult.Exhausted -> TheoryResult.Unsat()

            SearchResult.Indeterminate -> TheoryResult.Unknown(
                if (session.cancelled()) TerminationReason.Cancelled else TerminationReason.BudgetExhausted,
            )
        }
    }
}

/** The uniform outcome of a complete open-model theory. */
sealed interface TheoryResult<out A> {
    /** Statistics collected by the theory. */
    val stats: SolveStats

    /** A satisfying assignment for the source model. */
    data class Sat<A>(
        /** The complete assignment. */
        val assignment: A,
        override val stats: SolveStats = SolveStats.EMPTY,
    ) : TheoryResult<A>

    /** A proof that the source model is infeasible. */
    data class Unsat(override val stats: SolveStats = SolveStats.EMPTY) : TheoryResult<Nothing>

    /** An explicit budget or cancellation interrupted exact search. */
    data class Unknown(
        /** The explicit interruption cause. */
        val reason: TerminationReason,
        override val stats: SolveStats = SolveStats.EMPTY,
    ) : TheoryResult<Nothing>
}
