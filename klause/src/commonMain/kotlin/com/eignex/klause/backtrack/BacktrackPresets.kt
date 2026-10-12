package com.eignex.klause.backtrack

import com.eignex.klause.backtrack.selector.IndomainMin
import com.eignex.klause.backtrack.selector.LastConflict
import com.eignex.klause.backtrack.selector.SolutionGuided
import com.eignex.klause.backtrack.selector.Vsids
import com.eignex.klause.solver.result.SearchEvent
import com.eignex.klause.util.Cancellation

/**
 * Curated [BacktrackParams] presets — named, ready-to-use configurations that bundle the
 * engine's tuning knobs into a strategy, so callers (the portfolio, the bench, the CLI) don't
 * each reassemble the same combination by hand.
 */
object BacktrackPresets {

    /**
     * CDCL preset with VSIDS branching, phase saving and target phasing, LBD-adaptive restarts,
     * a capped three-tier learned-clause database, and binary-resolution clause minimization.
     * Pure-Boolean inprocessing is skipped where it does not apply; the preset supports both
     * satisfaction and optimization.
     *
     * [inprocess] enables clause subsumption, self-subsumption and vivification every
     * [inprocessingCadence] restarts (default: four), amortizing their probing cost.
     * [maxLearnedClauses] caps the database; reductions run at restarts once it exceeds the cap.
     * [randomSeed] seeds the RNG, or uses a fresh seed when null.
     * [cancellation] and [onEvent] supply cancellation and observation hooks.
     */
    fun satOptimized(
        randomSeed: Long? = null,
        maxLearnedClauses: Int = 20_000,
        inprocess: Boolean = true,
        inprocessingCadence: Int = 4,
        cancellation: Cancellation = Cancellation.Never,
        onEvent: ((SearchEvent) -> Unit)? = null,
    ): BacktrackParams = BacktrackParams(
        randomSeed = randomSeed,
        cancellation = cancellation,
        onEvent = onEvent,
    ).copy(
        phaseSaving = true,
        targetPhasing = true,
        adaptiveRestart = true,
        maxLearnedClauses = maxLearnedClauses,
        tieredLearnedDb = true,
        vivification = inprocess,
        subsumption = inprocess,
        inprocessingCadence = inprocessingCadence,
    )

    /**
     * Free-search preset with last-conflict probing over VSIDS, solution-guided value ordering,
     * phase saving and Luby restarts. Used by the free-search entry point and
     * [com.eignex.klause.portfolio.PortfolioBuilder]'s backtrack pool.
     *
     * [randomSeed] seeds the RNG, or uses a fresh seed when null.
     * [lubyRestartBase] sets the restart unit (default: 256).
     * [cancellation] and [onEvent] supply cancellation and observation hooks.
     */
    fun conflictDriven(
        randomSeed: Long? = null,
        lubyRestartBase: Long = 256L,
        cancellation: Cancellation = Cancellation.Never,
        onEvent: ((SearchEvent) -> Unit)? = null,
    ): BacktrackParams = BacktrackParams(
        randomSeed = randomSeed,
        cancellation = cancellation,
        onEvent = onEvent,
    ).copy(
        variableSelector = LastConflict(Vsids()),
        valueSelector = SolutionGuided(IndomainMin),
        phaseSaving = true,
        lubyRestartBase = lubyRestartBase,
    )
}
