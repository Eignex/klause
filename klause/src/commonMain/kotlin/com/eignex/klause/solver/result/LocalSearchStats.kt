package com.eignex.klause.solver.result

import com.eignex.kumulant.stat.summary.SumResult

/**
 * Local-search telemetry: moves applied, stalls (plateau restarts), and the incumbent fingerprint
 * (time-to-best, objective, residual violation). Zero for complete backends. See [SolveStats].
 *
 * The LS engine's restart count folds into [SearchStats.restarts] (the shared restart field), not here.
 */
data class LocalSearchStats(
    /** Work steps (bool flips / int sets / compounds + one unit per restart) — the LS analogue of nodes. */
    val moves: SumResult = ZERO_COUNT,
    /** Descents that hit a local optimum / plateau and restarted — the thrash indicator against [moves]. */
    val stalls: SumResult = ZERO_COUNT,
    /** Wall ms to the best incumbent, or -1 when none was established. */
    val timeToBestMs: Long = -1L,
    /** Objective at the best incumbent, or NaN when none was feasible. */
    val incumbentObjective: Double = Double.NaN,
    /** Total constraint violation at the best incumbent: 0 once feasible, else the lowest residual. NaN unset. */
    val incumbentViolation: Double = Double.NaN,
    /** Candidates a completion decided, over continuous columns; 0 on a model without them. */
    val completions: SumResult = ZERO_COUNT,
    /** Of [completions], those it refuted — the floating-point search reached a point no exact completion holds. */
    val completionsRefuted: SumResult = ZERO_COUNT,
    /** Of [completions], those it could not decide within its budget. */
    val completionsUndecided: SumResult = ZERO_COUNT,
    /** Optional best committed assignment's graded violations; not a witness or a completion verdict. */
    val bestResidual: LocalSearchResidual? = null,
    /** Active elapsed nanoseconds by phase, excluding suspended idle time. Empty when disabled. */
    val phaseNanos: Map<String, Long> = emptyMap(),
    /** Retained ILS restarts with an admissible searched board input changed after preparation and repair. */
    val boardRestartChanges: SumResult = ZERO_COUNT,
    /** Retained ILS board restarts explicitly declined because prepared searched inputs did not change. */
    val boardRestartDeclines: SumResult = ZERO_COUNT,
    /** Searches whose geometric proposals bind to maintained cones of their canonical objective. */
    val geometricProposalRuns: SumResult = ZERO_COUNT,
    /** Searches declining geometric proposals and retaining canonical execution. */
    val geometricProposalDeclines: SumResult = ZERO_COUNT,
    /** Sink-emitted geometric candidates before driver sampling; neither commits nor distinct points. */
    val geometricProposalCandidates: SumResult = ZERO_COUNT,
    /** Optional objective-view preparation and completed scoring counts, populated with phase reporting. */
    val objectiveScoring: Map<String, Long> = emptyMap(),
) {
    /** Combine two workers: moves/stalls add, earliest time-to-best wins, incumbent from the lower violation. */
    fun mergedWith(o: LocalSearchStats): LocalSearchStats = LocalSearchStats(
        moves = SumResult(moves.sum + o.moves.sum),
        stalls = SumResult(stalls.sum + o.stalls.sum),
        // Earliest time-to-best across workers; -1 sentinels defer to any real reading.
        timeToBestMs = when {
            timeToBestMs < 0L -> o.timeToBestMs
            o.timeToBestMs < 0L -> timeToBestMs
            else -> minOf(timeToBestMs, o.timeToBestMs)
        },
        incumbentObjective = pickByViolation(
            incumbentViolation,
            incumbentObjective,
            o.incumbentViolation,
            o.incumbentObjective,
        ),
        incumbentViolation = naNDeferring(incumbentViolation, o.incumbentViolation, ::minOf),
        completions = SumResult(completions.sum + o.completions.sum),
        completionsRefuted = SumResult(completionsRefuted.sum + o.completionsRefuted.sum),
        completionsUndecided = SumResult(completionsUndecided.sum + o.completionsUndecided.sum),
        bestResidual = when {
            bestResidual == null -> o.bestResidual
            o.bestResidual == null -> bestResidual
            o.bestResidual.cost < bestResidual.cost -> o.bestResidual
            else -> bestResidual
        },
        phaseNanos = (phaseNanos.keys + o.phaseNanos.keys).associateWith { phase ->
            val first = phaseNanos[phase] ?: 0L
            first + minOf(o.phaseNanos[phase] ?: 0L, Long.MAX_VALUE - first)
        },
        boardRestartChanges = SumResult(boardRestartChanges.sum + o.boardRestartChanges.sum),
        boardRestartDeclines = SumResult(boardRestartDeclines.sum + o.boardRestartDeclines.sum),
        geometricProposalRuns = SumResult(geometricProposalRuns.sum + o.geometricProposalRuns.sum),
        geometricProposalDeclines = SumResult(geometricProposalDeclines.sum + o.geometricProposalDeclines.sum),
        geometricProposalCandidates = SumResult(geometricProposalCandidates.sum + o.geometricProposalCandidates.sum),
        objectiveScoring = (objectiveScoring.keys + o.objectiveScoring.keys).associateWith { outcome ->
            val first = objectiveScoring[outcome] ?: 0L
            first + minOf(o.objectiveScoring[outcome] ?: 0L, Long.MAX_VALUE - first)
        },
    )
}

/**
 * Mutable [LocalSearchStats] accumulator. Moves/stalls are plain counters (they reach the millions, so
 * per-event stat updates would be pure overhead — the LS loop sets them in bulk at exit). Also holds
 * the LS restart count, which the sink folds into [SearchStats.restarts]. See [SolveStatsSink].
 */
internal class LocalSearchStatsSink {
    private var moves: Long = 0L
    private var stalls: Long = 0L
    var restarts: Long = 0L
        private set
    private var timeToBestMs: Long = -1L
    private var incumbentObjective: Double = Double.NaN
    private var incumbentViolation: Double = Double.NaN
    private var completions: Long = 0L
    private var completionsRefuted: Long = 0L
    private var completionsUndecided: Long = 0L
    private var boardRestartChanges = 0L
    private var boardRestartDeclines = 0L
    private var geometricProposalRuns = 0L
    private var geometricProposalDeclines = 0L
    private var geometricProposalCandidates = 0L
    var bestResidual: LocalSearchResidual? = null
    var phaseNanos: (() -> Map<String, Long>)? = null
    var objectiveScoring: (() -> Map<String, Long>)? = null

    /** Count one decided candidate: [refuted] or [undecided] when it was not accepted. */
    fun recordCompletion(refuted: Boolean, undecided: Boolean) {
        completions++
        if (refuted) completionsRefuted++
        if (undecided) completionsUndecided++
    }

    /** Record the LS move / restart / stall totals in one call at loop exit. */
    fun recordWork(moves: Long, restarts: Long, stalls: Long) {
        this.moves = moves
        this.restarts = restarts
        this.stalls = stalls
    }

    fun recordBoardRestarts(changes: Long, declines: Long) {
        boardRestartChanges = changes
        boardRestartDeclines = declines
    }

    fun recordGeometricProposals(qualified: Boolean, candidates: Long) {
        geometricProposalRuns = if (qualified) 1L else 0L
        geometricProposalDeclines = if (qualified) 0L else 1L
        geometricProposalCandidates = candidates
    }

    /** Record the incumbent fingerprint: objective (NaN if never feasible), residual violation, and the
     *  wall ms at which it was found (-1 if no incumbent). */
    fun recordIncumbent(objective: Double, violation: Double, foundAtMs: Long) {
        incumbentObjective = objective
        incumbentViolation = violation
        timeToBestMs = foundAtMs
    }

    fun snapshot(): LocalSearchStats = LocalSearchStats(
        moves = SumResult(moves.toDouble()),
        stalls = SumResult(stalls.toDouble()),
        timeToBestMs = timeToBestMs,
        incumbentObjective = incumbentObjective,
        incumbentViolation = incumbentViolation,
        completions = SumResult(completions.toDouble()),
        completionsRefuted = SumResult(completionsRefuted.toDouble()),
        completionsUndecided = SumResult(completionsUndecided.toDouble()),
        bestResidual = bestResidual,
        phaseNanos = phaseNanos?.invoke() ?: emptyMap(),
        boardRestartChanges = SumResult(boardRestartChanges.toDouble()),
        boardRestartDeclines = SumResult(boardRestartDeclines.toDouble()),
        geometricProposalRuns = SumResult(geometricProposalRuns.toDouble()),
        geometricProposalDeclines = SumResult(geometricProposalDeclines.toDouble()),
        geometricProposalCandidates = SumResult(geometricProposalCandidates.toDouble()),
        objectiveScoring = objectiveScoring?.invoke() ?: emptyMap(),
    )
}
