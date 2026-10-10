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
        // Keep the incumbent from whichever worker got closer to feasibility (lower violation). NaN defers.
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
    var bestResidual: LocalSearchResidual? = null

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
    )
}
