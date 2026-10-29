package com.eignex.klause.solver.result

import com.eignex.klause.portfolio.PortfolioEvent

/**
 * Coarse-grained live search events, reported through the caller-supplied `onEvent` hook
 * on [com.eignex.klause.backtrack.BacktrackParams] and
 * [com.eignex.klause.localsearch.LocalSearchParams] (default `null` — no
 * observation, no cost beyond one null check at each event site).
 *
 * Events fire only at points that are already rare relative to the engine's hot loop —
 * restarts, learned-database sweeps, incumbent improvements — never per-flip or
 * per-propagation. Listeners run inline on the search thread: keep them cheap and
 * non-blocking (write a line, bump a counter), and don't touch engine state from them.
 *
 * This is the live-progress counterpart to the [SolveStats] counters carried on results:
 * stats summarize a finished solve, events narrate one in flight (e.g. the CLI's `-v`).
 */
sealed interface SearchEvent {
    /** Recipe eligibility and requirements; these descriptions confer no publication authority. */
    data class WorkerAdmission(
        /** Configured recipe or companion identity. */
        val recipe: String,
        /** Whether the automatic gate admitted the recipe. */
        val admitted: Boolean,
        /** Fragment, objective, authority, mapping, retained-state and construction requirements. */
        val requirements: String,
        /** Stable reasons for declining the automatic admission. */
        val declines: List<String>,
    ) : SearchEvent

    /** A logical event from opt-in single-lane portfolio diagnostics. */
    data class PortfolioDiagnostic(
        /** Logical schedule event. */
        val event: PortfolioEvent,
    ) : SearchEvent
    /** The backtrack engine completed a restart. [index] counts restarts from 1
     *  within the solve; [steps] is the decisions spent in the run that just ended.
     *  (Local-search restarts are deliberately not reported — they fire on a flip
     *  cadence and would be noise, not signal.) */
    data class Restart(val index: Long, val steps: Long) : SearchEvent

    /** A learned-clause database sweep ran (backtrack engine, when the database is over its
     *  cap): [kept] clauses retained, [dropped] forgotten. */
    data class LearnedDbSweep(val kept: Int, val dropped: Int) : SearchEvent

    /** A new best incumbent was committed during optimization, with its [objective]. */
    data class Incumbent(val objective: Double) : SearchEvent
}
