package com.eignex.klause.solver

/**
 * Why a solver / optimiser returned without a definitive verdict. Lets callers tell
 * "ran out of decisions" apart from "wall-clock timeout" apart from "the embedding
 * application cancelled the call" — all of which previously collapsed onto a `null`
 * return or a bare `Unknown`. Backends that don't distinguish (e.g. LogicNG only knows
 * "timeout") pick the closest fit.
 */
enum class TerminationReason {
    /** Solver-specific budget (maxFlips, maxDecisions, maxAttempts) hit. */
    BudgetExhausted,

    /** Wall-clock `timeoutMillis` elapsed. */
    Timeout,

    /** Cooperative [Cancellation] token tripped. */
    Cancelled,

    /**
     * Complete-backend search space fully explored — no further work to do — but the
     * verdict can't be expressed as Sat / Unsat / Optimal / Infeasible because some
     * stronger context (external bound sharing in a parallel portfolio, an opaque
     * pruning predicate) made the absolute proof unsound from this worker's vantage
     * point. The caller (typically a portfolio reducer) combines this with peer
     * results to decide the global verdict.
     */
    SearchExhausted,
}

/**
 * Result of [Solver.sample]. Replaces the previous `Sample?` with an explicit
 * three-way distinction so a `null`-style return can't conflate "no feasible
 * assignment exists" (provable Unsat) with "budget exhausted before any was found."
 *
 *  - [Found] — a satisfying assignment.
 *  - [Infeasible] — only returned by complete backends that proved no satisfying
 *    assignment exists.
 *  - [Unknown] — incomplete result (budget / timeout / cancellation). Local-search
 *    backends return this when they couldn't reach feasibility within the budget;
 *    complete backends return it when they were cut off mid-search.
 */
sealed interface SampleResult {
    /** Underlying assignment if [Found], else `null`. Convenience for callers that
     *  don't care about the termination reason; prefer pattern-matching on the
     *  sealed type when the distinction matters. */
    val assignment: Sample?

    /** A feasible solution was found. */
    data class Found(
        /** The feasible assignment found. */
        val sample: Sample,
    ) : SampleResult {
        override val assignment: Sample get() = sample
    }

    /** Proven infeasible. See [SolveResult.Unsat.core] for [core] semantics. */
    data class Infeasible(val core: UnsatCore? = null) : SampleResult {
        override val assignment: Sample? = null
    }

    /** Search ended without a definitive answer. */
    data class Unknown(
        /** Why sampling ended without a definitive answer. */
        val reason: TerminationReason,
    ) : SampleResult {
        override val assignment: Sample? = null
    }
}

/**
 * Result of [Optimizer.minimize]. Replaces `Sample?` with an explicit verdict so
 * "best-effort feasible at objective o" can't be confused with "proven optimal."
 * Only complete backends ([BacktrackSolver], [BruteForceSolver], LogicNG, SMT/Z3) can
 * ever return [Optimal] or [Infeasible]; the local-search backend returns [BestFound]
 * or [Unknown].
 *
 *  - [Optimal] — sample and objective; search exhausted (or the bound is tight enough
 *    to prove optimality without exhausting).
 *  - [BestFound] — feasible but not proven optimal. Carries the [TerminationReason]
 *    that stopped the search before optimality could be proven.
 *  - [Infeasible] — proven no feasible assignment exists.
 *  - [Unknown] — neither feasible found nor infeasibility proven (typically budget
 *    exhausted before any feasible reached).
 */
sealed interface MinimizeResult {
    /** Underlying assignment if Optimal / BestFound, else `null`. */
    val assignment: Sample?

    /** Objective value at the [assignment] if any, else `null`. */
    val objectiveValue: Double?

    /** Common shape for verdicts that carry an assignment. */
    sealed interface WithSample : MinimizeResult {
        /** The solution assignment. */
        val sample: Sample

        /** Objective value of [sample]. */
        val objective: Double
        override val assignment: Sample get() = sample
        override val objectiveValue: Double get() = objective
    }

    /** A proven-optimal solution. */
    data class Optimal(override val sample: Sample, override val objective: Double) : WithSample

    /** Best solution found before the search stopped. */
    data class BestFound(
        override val sample: Sample,
        override val objective: Double,
        /** Why the search stopped before proving optimality. */
        val reason: TerminationReason,
    ) :
        WithSample

    /** Proven infeasible. See [SolveResult.Unsat.core] for [core] semantics. */
    data class Infeasible(val core: UnsatCore? = null) : MinimizeResult {
        override val assignment: Sample? = null
        override val objectiveValue: Double? = null
    }

    /** Optimisation ended without a definitive answer. */
    data class Unknown(
        /** Why optimisation ended without a definitive answer. */
        val reason: TerminationReason,
    ) : MinimizeResult {
        override val assignment: Sample? = null
        override val objectiveValue: Double? = null
    }
}
