package com.eignex.klause.solver.result

import com.eignex.klause.ir.EffectiveSourceSemantics
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceResultEvidence
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction

/**
 * Why a solver or optimizer returned without a definitive verdict. Backends that don't distinguish
 * these pick the closest fit.
 */
enum class TerminationReason {
    /** Solver-specific budget (maxFlips, maxDecisions, maxAttempts) hit. */
    BudgetExhausted,

    /** Wall-clock `timeoutMillis` elapsed. */
    Timeout,

    /** Cooperative [com.eignex.klause.util.ExecutionControl] token tripped. */
    Cancelled,

    /**
     * The backend does not implement a required feature or operation. Another backend may support it.
     */
    Unsupported,

    /** A required representation or exact check declined while executing an admitted model. */
    RepresentationDeclined,

    /**
     * Complete-backend search space fully explored, but external context (bound sharing in a parallel
     * portfolio, an opaque pruning predicate) makes an absolute verdict unsound from this worker. The
     * caller combines it with peer results to decide the global verdict.
     */
    SearchExhausted,
}

/**
 * Result of [com.eignex.klause.solver.Solver.sample].
 *
 *  - [Found] — a satisfying assignment.
 *  - [Infeasible] — the backend proved no satisfying assignment exists, including
 *    a root-propagation refutation under local search.
 *  - [Unknown] — incomplete result with its [TerminationReason], including resource
 *    stops, cancellation, unsupported features and representation declines.
 */
sealed interface SampleResult {
    /** Effective source interpretation, or null for an unbound result. */
    val semantics: EffectiveSourceSemantics?

    /** Underlying assignment if [Found], else `null`. */
    val assignment: Sample?

    /** A feasible solution was found. */
    data class Found(
        /** The feasible assignment found. */
        val sample: Sample,
        override val semantics: EffectiveSourceSemantics? = sample.semantics,
    ) : SampleResult {
        override val assignment: Sample get() = sample
    }

    /** Proven infeasible. See [com.eignex.klause.solver.SolveResult.Unsat.core] for [core] semantics. */
    data class Infeasible(
        val core: UnsatCore? = null,
        override val semantics: EffectiveSourceSemantics? = null,
    ) : SampleResult {
        override val assignment: Sample? = null
    }

    /** Search ended without a definitive answer. */
    data class Unknown(
        /** Why sampling ended without a definitive answer. */
        val reason: TerminationReason,
        override val semantics: EffectiveSourceSemantics? = null,
    ) : SampleResult {
        override val assignment: Sample? = null
    }
}

/**
 * Result of [com.eignex.klause.solver.Optimizer.minimize], distinguishing feasible incumbents
 * from proven optimality.
 * Only complete backends (such as `BacktrackSolver`, `BruteForceSolver` and the open-theory route)
 * return [Optimal] or [Unbounded]. Local search returns [Infeasible] for a root-propagation refutation,
 * otherwise [BestFound] or [Unknown].
 *
 *  - [Optimal] — sample and objective; search exhausted (or the bound is tight enough
 *    to prove optimality without exhausting).
 *  - [BestFound] — feasible but not proven optimal. Carries the [TerminationReason]
 *    that stopped the search before optimality could be proven.
 *  - [Unbounded] — a feasible sample and a ray along which the objective descends without limit.
 *  - [Infeasible] — proven no feasible assignment exists.
 *  - [Unknown] — neither feasible found nor infeasibility proven (typically budget
 *    exhausted before any feasible reached).
 */
sealed interface MinimizeResult {
    /** Effective source interpretation, independent of the verdict's proof authority. */
    val semantics: EffectiveSourceSemantics?

    /** Underlying assignment if Optimal / BestFound, else `null`. */
    val assignment: Sample?

    /** Solver-side counters, populated on terminal verdicts by backends that opt in; [SolveStats.EMPTY]
     *  otherwise, including on streamed intermediate incumbents. */
    val stats: SolveStats

    /** Optional original-source claims and derivations; null when capture was not requested. */
    val evidence: SourceResultEvidence?

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

    /**
     * A proven-optimal solution: exactly when [gapBound] is null, otherwise within the optimality gap of
     * [gapBound], a proved bound below [objective] on every feasible objective value.
     */
    data class Optimal(
        override val sample: Sample,
        override val objective: Double,
        override val stats: SolveStats = SolveStats.EMPTY,
        override val evidence: SourceResultEvidence? = null,
        override val semantics: EffectiveSourceSemantics? = sample.semantics,
        /** Proved objective bound that closes the optimality gap, or null for an exact optimum. */
        val gapBound: Double? = null,
    ) : WithSample

    /** Best solution found before the search stopped. */
    data class BestFound(
        override val sample: Sample,
        override val objective: Double,
        /** Why the search stopped before proving optimality. */
        val reason: TerminationReason,
        override val stats: SolveStats = SolveStats.EMPTY,
        override val evidence: SourceResultEvidence? = null,
        override val semantics: EffectiveSourceSemantics? = sample.semantics,
    ) : WithSample

    /**
     * The objective descends without limit, so there is no optimum.
     *
     * [sample] is a feasible assignment and [direction] a ray of the continuous variables, indexed by real
     * variable id, along which every assignment stays feasible and the objective strictly decreases. The
     * discrete values stay those of [sample]. [objective] is the value at [sample], not a bound.
     */
    data class Unbounded(
        override val sample: Sample,
        override val objective: Double,
        /** Exact recession ray of the continuous variables through [sample]. */
        val direction: List<BigFraction>,
        override val stats: SolveStats = SolveStats.EMPTY,
        override val evidence: SourceResultEvidence? = null,
        override val semantics: EffectiveSourceSemantics? = sample.semantics,
    ) : WithSample

    /** Proven infeasible. See [com.eignex.klause.solver.SolveResult.Unsat.core] for [core] semantics. */
    data class Infeasible(
        /** Optional jointly infeasible source factor subset. */
        val core: UnsatCore? = null,
        override val stats: SolveStats = SolveStats.EMPTY,
        override val evidence: SourceResultEvidence? = null,
        override val semantics: EffectiveSourceSemantics? = null,
    ) : MinimizeResult {
        override val assignment: Sample? = null
        override val objectiveValue: Double? = null
    }

    /** Optimisation ended without a definitive answer. */
    data class Unknown(
        /** Why optimisation ended without a definitive answer. */
        val reason: TerminationReason,
        override val stats: SolveStats = SolveStats.EMPTY,
        override val evidence: SourceResultEvidence? = null,
        override val semantics: EffectiveSourceSemantics? = null,
    ) : MinimizeResult {
        override val assignment: Sample? = null
        override val objectiveValue: Double? = null
    }
}
