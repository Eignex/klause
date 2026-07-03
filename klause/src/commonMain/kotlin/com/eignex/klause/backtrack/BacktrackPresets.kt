package com.eignex.klause.backtrack

import com.eignex.klause.backtrack.selector.IndomainMin
import com.eignex.klause.backtrack.selector.LastConflict
import com.eignex.klause.backtrack.selector.SolutionGuided
import com.eignex.klause.backtrack.selector.Vsids
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.result.SearchEvent

/**
 * Curated [BacktrackParams] presets — named, ready-to-use configurations that bundle the
 * engine's tuning knobs into a strategy, so callers (the portfolio, the bench, the CLI) don't
 * each reassemble the same combination by hand.
 */
object BacktrackPresets {

    /**
     * Competitive modern-CDCL configuration for SAT-style search:
     *  - VSIDS activity branching;
     *  - phase saving plus target phasing with periodic rephasing;
     *  - glucose-style adaptive restarts with trail-size blocking;
     *  - a capped three-tier learned-clause database with promotion on reuse;
     *  - binary-resolution learned-clause minimization (always on in the analyzer).
     *
     * Every component is sound on any [com.eignex.klause.solver.Problem] and the pure-Boolean
     * inprocessing simply no-ops where it doesn't apply, so the same preset drives both
     * satisfaction and the complete-search side of optimization — in a portfolio the shared
     * objective bound keeps a SAT-tuned worker useful on COP by racing to feasible incumbents.
     *
     * **Vivification is intentionally left off here** ([vivify] defaults to false). On the SAT
     * search-effort benchmark its per-restart probing — amplified by the frequent adaptive
     * restarts this preset enables — cut conflict *throughput* enough to time out instances the
     * leaner config solves (e.g. php8), while the conflict-quality win it was meant to provide
     * already comes from the three-tier DB + binary minimization + LBD restarts (the preset is
     * ~0.73-0.77× the baseline's conflicts either way). It stays available via [vivify] for
     * hard-UNSAT campaigns that restart infrequently, where the probing pays for itself.
     *
     *  - [randomSeed] optionally seeds the engine RNG; null uses a fresh seed per call.
     *  - [maxLearnedClauses] caps the learned database (the three-tier reduction runs at each
     *    restart once over the cap).
     *  - [vivify] opts the periodic clause-vivification inprocessing pass back in.
     *  - [cancellation] / [onEvent] thread the usual cooperative-cancellation and observation
     *    seams through unchanged.
     */
    fun satOptimized(
        randomSeed: Long? = null,
        maxLearnedClauses: Int = 20_000,
        vivify: Boolean = false,
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
        vivification = vivify,
    )

    /**
     * Conflict-driven free search: last-conflict probing over VSIDS activity, solution-guided
     * value ordering, phase saving, Luby restarts. The strongest single free-search composition
     * for the optimization corpus — it proves rcpsp-wet and shortest_path in seconds and takes
     * celar from 9344 to 2323 where a random free worker and the model's own search annotation
     * both stall.
     *
     * This is the configuration the single-threaded free-category measurement runs, and one leg
     * of the [com.eignex.klause.portfolio.PortfolioBuilder] backtrack pool, so the same
     * composition drives both the solo competition track and the parallel portfolio.
     *
     *  - [randomSeed] optionally seeds the engine RNG; null uses a fresh seed per call.
     *  - [lubyRestartBase] sets the Luby restart unit (anytime default 256).
     *  - [cancellation] / [onEvent] thread the usual cooperative-cancellation and observation
     *    seams through unchanged.
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
