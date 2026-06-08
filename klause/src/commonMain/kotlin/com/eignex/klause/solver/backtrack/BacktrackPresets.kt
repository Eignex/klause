package com.eignex.klause.solver.backtrack

import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.SearchEvent

/**
 * Curated [BacktrackParams] presets — named, ready-to-use configurations that bundle the
 * engine's tuning knobs into a strategy, so callers (the portfolio, the bench, the CLI) don't
 * each reassemble the same combination by hand.
 */
object BacktrackPresets {

    /**
     * Competitive modern-CDCL configuration for SAT-style search, bundling the full
     * pure-Boolean stack:
     *  - VSIDS activity branching;
     *  - phase saving plus target phasing with periodic rephasing;
     *  - glucose-style adaptive restarts with trail-size blocking;
     *  - a capped three-tier learned-clause database with promotion on reuse;
     *  - binary-resolution learned-clause minimization (always on in the analyzer);
     *  - periodic clause vivification at restart boundaries.
     *
     * Every component is sound on any [com.eignex.klause.solver.Problem] and the pure-Boolean
     * inprocessing simply no-ops where it doesn't apply, so the same preset drives both
     * satisfaction and the complete-search side of optimization — in a portfolio the shared
     * objective bound keeps a SAT-tuned worker useful on COP by racing to feasible incumbents.
     *
     *  - [randomSeed] seeds the engine RNG.
     *  - [maxLearnedClauses] caps the learned database (the three-tier reduction runs at each
     *    restart once over the cap).
     *  - [cancellation] / [onEvent] thread the usual cooperative-cancellation and observation
     *    seams through unchanged.
     */
    fun satOptimized(
        randomSeed: Long = 0L,
        maxLearnedClauses: Int = 20_000,
        cancellation: Cancellation = Cancellation.Never,
        onEvent: ((SearchEvent) -> Unit)? = null,
    ): BacktrackParams = BacktrackParams(
        randomSeed = randomSeed,
        variableHeuristic = Vsids(),
        phaseSaving = true,
        targetPhasing = true,
        adaptiveRestart = true,
        maxLearnedClauses = maxLearnedClauses,
        tieredLearnedDb = true,
        vivification = true,
        cancellation = cancellation,
        onEvent = onEvent,
    )
}
