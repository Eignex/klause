package com.eignex.klause.solver.result

import com.eignex.kumulant.stat.summary.SumResult

/**
 * Local-search telemetry: moves applied, stalls (plateau restarts), and the incumbent fingerprint
 * (time-to-best, objective, residual violation). Zero for complete backends. See [SolveStats].
 *
 * The LS engine's restart count folds into [SearchStats.restarts] (the shared restart field), not here.
 */
data class LocalSearchStats(
    /** Moves applied (bool flips / int sets / compounds + restart work) — the LS analogue of nodes. */
    val moves: SumResult = ZERO_COUNT,
    /** Descents that hit a local optimum / plateau and restarted — the thrash indicator against [moves]. */
    val stalls: SumResult = ZERO_COUNT,
    /** Wall ms to the best incumbent, or -1 when none was established. */
    val timeToBestMs: Long = -1L,
    /** Objective at the best incumbent, or NaN when none was feasible. */
    val incumbentObjective: Double = Double.NaN,
    /** Total constraint violation at the best incumbent: 0 once feasible, else the lowest residual. NaN unset. */
    val incumbentViolation: Double = Double.NaN,
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
    )
}
