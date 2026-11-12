package com.eignex.klause.solver.result

import com.eignex.klause.portfolio.PortfolioEvent
import com.eignex.klause.solver.search.SearchComponentStop
import com.eignex.klause.solver.search.SearchStopCause

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
    /** Retained component-terminal provenance; it supplies no completeness or retry authority. */
    data class BacktrackComponentStop(
        /** First component-terminal observation, retained across slice rearming. */
        val stop: SearchComponentStop,
        /** Whether a later traversal call consumed the cached terminal. */
        val replayed: Boolean,
    ) : SearchEvent

    /** Native optimization returned incomplete; this observation supplies no exhaustion proof. */
    data class BacktrackIncomplete(
        /** Return path responsible for withholding a complete verdict. */
        val path: Path,
        /** Native stop visible at return, or null when none was recorded. */
        val stopCause: SearchStopCause?,
    ) : SearchEvent {
        /** Native optimization paths that preserve unresolved coverage. */
        enum class Path {
            /** At least one objective leaf remained unresolved. */
            UnresolvedLeaf,

            /** A required component could not decide the residual. */
            Component,
        }
    }

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
        /** Whether composition selected this eligible recipe for materialization. */
        val selected: Boolean = false,
        /** The role a selected recipe declares in its roster; null when it declares none. */
        val role: String? = null,
    ) : SearchEvent

    /** Bounded companion comparisons in exact working-objective units; values confer no source authority. */
    data class CompanionComparison(
        /** Query sequence within this worker, zero for center preparation outside a query. */
        val condition: Long,
        /** External completion, center admission, query opening or checked discovery. */
        val phase: String,
        /** Version captured by the external read, zero for other phases. */
        val seedVersion: Long,
        /** Current pool version at observation, zero when empty. */
        val poolVersion: Long,
        /** Exact canonical pool objective, or null when empty. */
        val poolObjective: String?,
        /** Offered objective before completion; advisory coordinates can be approximate. */
        val rawObjective: String?,
        /** Completed exact objective, or null when completion is unavailable. */
        val completedObjective: String?,
        /** Captured query cutoff, or null when absent. */
        val cutoff: String?,
        /** Source-checked local discovery objective, or null for other phases. */
        val candidateObjective: String?,
        /** Observation-site disposition; a discovery comparison is not pool admission. */
        val disposition: String,
    ) : SearchEvent

    /** An opt-in logical work-schedule event or physical phase/lifecycle observation. */
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
