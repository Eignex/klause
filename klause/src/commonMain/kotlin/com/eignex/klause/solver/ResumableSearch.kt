package com.eignex.klause.solver

import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction
import kotlin.coroutines.cancellation.CancellationException

/**
 * A pause/resume handle over an optimisation. Each [runSlice] advances the retained search state
 * (incumbent, heuristics, engine state) and returns at a checkpoint the next slice continues from.
 * Single-threaded and stateful: drive it from one thread, one [runSlice] at a time.
 *
 * Obtain one from a [ResumableOptimizer]. [close] releases per-search resources and is idempotent.
 * A closed pending handle rejects slices; a terminal verdict remains readable after close.
 */
interface ResumableSearch : AutoCloseable {
    /**
     * Advance the search for up to [sliceMillis] more milliseconds, until [global] fires, or until it
     * completes. Each strictly improving incumbent is passed to [onIncumbent] as it lands.
     *
     * Returns the terminal verdict if the search completed during this slice, otherwise `null`. A search
     * exhausted under an external bound reports [MinimizeResult.BestFound] or [MinimizeResult.Unknown] with
     * [TerminationReason.SearchExhausted]. After a verdict, [isDone] is `true` and further calls return it.
     */
    fun runSlice(
        global: ExecutionControl,
        sliceMillis: Long,
        onIncumbent: (MinimizeResult.WithSample) -> Unit,
    ): MinimizeResult? = runSlice(global, sliceMillis, sliceNodes = -1L, onIncumbent)

    /**
     * As [runSlice], but ending the slice after [sliceNodes] search nodes when [sliceNodes] is non-negative, or
     * after [sliceMillis] if that comes first. A node budget makes the pause point reproducible; pass
     * [Long.MAX_VALUE] for [sliceMillis] to rely on it alone. ExecutionControl is cooperative, so atomic engine
     * work can overrun a slice.
     */
    fun runSlice(
        global: ExecutionControl,
        sliceMillis: Long,
        sliceNodes: Long,
        onIncumbent: (MinimizeResult.WithSample) -> Unit,
    ): MinimizeResult?

    /** True once [runSlice] has returned a terminal verdict; further calls are no-ops. */
    val isDone: Boolean

    /**
     * Counters accumulated so far, whether or not the search has finished. Cumulative for this handle,
     * so fold each handle once rather than per slice. Readable after [close].
     */
    val stats: SolveStats

    /**
     * Work spent so far, in the units [runSlice]'s `sliceNodes` budgets: one per search node, plus LP work at the
     * rate the search charges it per node, and propagation work at its measured rate. Cumulative for this handle.
     */
    val work: Long get() = stats.search.nodes.sum.toLong()

    /** Work performed while opening this handle, included in [work]. */
    val initialWork: Long get() = 0L

    /** When true, another slice can continue private initialization before searching. */
    val preparationPending: Boolean get() = false

    override fun close() {}
}

internal interface ReseedGuard {
    val canReseed: Boolean
    val canContinuePreparation: Boolean get() = false
    val incumbentTrialPending: Boolean get() = false
}

// A complete minimization that may prune against an optimality-gap cutoff below its incumbent. gapFloor is the least
// proved bound a gap prune supplied (positive infinity otherwise): unseen points and shared facts hold only below it.
// exhaustionBound is what exhaustion proved, negative infinity until exhausted, and NaN
// for a wrapped search that reports none, whose exhaustion proves the cutoff it read.
internal interface GapBoundedSearch {
    val appliedOptimalityGap: Boolean
    val gapFloor: Double
    val exhaustionBound: Double
}

internal interface SliceWorkAccounting {
    val repaidWork: Long?
}

/**
 * An [Optimizer] whose search state can be paused and resumed across slices, such as
 * [com.eignex.klause.backtrack.BacktrackSolver] and [com.eignex.klause.localsearch.LocalSearchSolver].
 */
interface ResumableOptimizer<P : SolverParams> : Optimizer<P> {
    /** Open a fresh [ResumableSearch] minimising [objective] under [params]. The invocation token
     *  bounds construction. Tokens bound with [SolverParams.withLifetimeCancellation] persist through
     *  every [ResumableSearch.runSlice], composing with its `global` token and slice allowance. */
    fun resumable(objective: LinearObjective, params: P): ResumableSearch
}

/**
 * A pause/resume handle over a satisfaction search: the [ResumableSearch] of a model with no objective. The
 * learned clauses, trail and heuristics persist across [runSlice] calls, so an arm scheduled in segments continues
 * its search rather than starting it over. Single-threaded and stateful; obtain one from a [ResumableSolver].
 * Closing is idempotent: a closed pending handle rejects slices, while a terminal verdict remains readable.
 */
interface ResumableSolve : AutoCloseable {
    /**
     * Advance the search until it reaches a verdict, [global] fires, or the slice ends: after [sliceNodes]
     * work units when non-negative, or after [sliceMillis] of wall time, whichever comes first. Returns the
     * verdict once the search has one, else null with the search paused for the next call. After a verdict,
     * [isDone] is true and further calls return that verdict without doing work.
     */
    fun runSlice(global: ExecutionControl, sliceMillis: Long, sliceNodes: Long): SolveResult?

    /** True once [runSlice] has returned a verdict. */
    val isDone: Boolean

    /** Counters accumulated so far, whether or not the search has finished; cumulative for this handle.
     *  Remains readable after [close], including any counters finalized while closing. */
    val stats: SolveStats

    /** Work spent so far, in the units [runSlice]'s `sliceNodes` budgets; cumulative for this handle. */
    val work: Long get() = stats.search.nodes.sum.toLong()

    /** Work performed while opening this handle, included in [work]. */
    val initialWork: Long get() = 0L

    override fun close() {}
}

internal interface InstructionSlicedSolve : ResumableSolve {
    val auxiliaryWork: Long get() = 0L

    fun runInstructionSlice(global: ExecutionControl, sliceMillis: Long, sliceInstructions: Long): SolveResult?

    fun runScheduledSlice(
        global: ExecutionControl, sliceMillis: Long, sliceInstructions: Long, sliceWork: Long,
        instructionsPerWork: Double,
    ): SolveResult? = runInstructionSlice(global, sliceMillis, sliceInstructions)
}

internal interface IncumbentBootstrapSearch : ResumableSearch {
    val bootstrapPending: Boolean
}

internal interface InstructionSlicedSearch : ResumableSearch {
    /** Cumulative scheduling instructions; LNS allowances can differ from observed inner-engine work. */
    val chargedInstructions: Long get() = stats.ls.moves.sum.toLong()
    val auxiliaryWork: Long get() = 0L

    fun runScheduledSlice(
        global: ExecutionControl,
        sliceMillis: Long,
        sliceInstructions: Long,
        sliceWork: Long,
        instructionsPerWork: Double,
        onIncumbent: (MinimizeResult.WithSample) -> Unit,
    ): MinimizeResult? = runInstructionSlice(global, sliceMillis, sliceInstructions, onIncumbent)

    fun runInstructionSlice(
        global: ExecutionControl,
        sliceMillis: Long,
        sliceInstructions: Long,
        onIncumbent: (MinimizeResult.WithSample) -> Unit,
    ): MinimizeResult?
}

/** A [Solver] that can hand out a [ResumableSolve], such as [com.eignex.klause.backtrack.BacktrackSolver]
 *  and [com.eignex.klause.localsearch.LocalSearchSolver]. */
interface ResumableSolver<P : SolverParams> : Solver<P> {
    /** Open a fresh [ResumableSolve] under [params]; their cancellation bounds construction and is superseded
     *  per slice. */
    fun resumableSolve(params: P): ResumableSolve
}

// One persistent search for the LNS repair loop: learned clauses and the LP warm start carry across fragments.
internal interface RepairSearch : AutoCloseable {
    val stats: SolveStats get() = SolveStats.EMPTY
    val work: Long get() = stats.search.nodes.sum.toLong()

    fun begin(assumptions: Assumptions, decisionBudget: Long, cutoff: Double, cancellation: ExecutionControl)

    fun beginExact(assumptions: Assumptions, decisionBudget: Long, cutoff: BigFraction,
        cancellation: ExecutionControl): Unit =
        throw UnsupportedOperationException("exact repair cutoffs are unavailable")

    fun runSlice(cancellation: ExecutionControl, millis: Long, work: Long): RepairSearchStep

    // The cutoff must be non-increasing across calls: the reused session keeps permanent objective-bound
    // clauses, which stay sound only while every stale bound is looser than the current one.
    fun repair(assumptions: Assumptions, decisionBudget: Long, cutoff: Double,
        cancellation: ExecutionControl): Sample? = repairStep(assumptions, decisionBudget, cutoff, cancellation).sample

    fun repairStep(
        assumptions: Assumptions, decisionBudget: Long, cutoff: Double, cancellation: ExecutionControl,
    ): RepairSearchStep {
        begin(assumptions, decisionBudget, cutoff, cancellation)
        while (true) {
            val step = runSlice(ExecutionControl.Never, Long.MAX_VALUE, -1L)
            if (step.reason != null) return step
        }
    }

    override fun close() {}
}

internal data class RepairSearchStep(val sample: Sample?, val reason: TerminationReason?)

internal class SearchInitializationCancelled(val stats: SolveStats, val work: Long) :
    CancellationException("search initialization cancelled")
