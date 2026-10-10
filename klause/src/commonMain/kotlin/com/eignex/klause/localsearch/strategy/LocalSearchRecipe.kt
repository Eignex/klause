package com.eignex.klause.localsearch.strategy

import com.eignex.klause.localsearch.RestartPolicy
import com.eignex.klause.localsearch.acceptance.AcceptanceRule
import com.eignex.klause.localsearch.movesource.ConfiguredSource
import com.eignex.klause.localsearch.schedule.Schedule
import com.eignex.klause.localsearch.scoring.MoveScoring

/**
 * A named local-search recipe: a four-axis [SourceDrivenStrategy] (its restart cadence carried in the
 * schedule axis, [com.eignex.klause.localsearch.schedule.ScheduleBundle.restart]) plus the few
 * solver-level knobs the strategy itself doesn't own. The portfolio wraps one of these per arm; a
 * single recipe is just a portfolio of one.
 */
class LocalSearchRecipe(
    /** External name (CLI / campaign / telemetry). */
    val label: String,
    /** Drives the feasibility fight; its [SourceDrivenStrategy.feasibleDescent] also decides the optimize
     *  phase when there is no separate [optimizeStrategy]. Restart lives in its `schedule.restart`. */
    val strategy: SourceDrivenStrategy,
    /** Minimize-phase strategy; `null` reuses [strategy] for the optimize phase. A CBLS/SA arm sets this
     *  to a second instance carrying its optimize [SourceDrivenStrategy.feasibleDescent]. */
    val optimizeStrategy: SourceDrivenStrategy? = null,
    /** Per-arm switch for the per-move invariant network; off carves a diversity niche for cyclic
     *  definitional encodings whose reified indicators are otherwise search-excluded. */
    val perMoveInvariants: Boolean = true,
    /** Per-arm switch for implicit-solving feasible init on every restart (paired with a CBLS whose
     *  `implicitStructuredCap > 0` on permutation/assignment-shaped models). */
    val seedImplicitOnRestart: Boolean = false,
) {
    /** How this recipe conducts the optimize phase — the optimize strategy's declared
     *  [SourceDrivenStrategy.feasibleDescent] (the [optimizeStrategy] when present, else [strategy]).
     *  [FeasibleDescent.RatchetAsConstraint] is violation-native (probSAT / WalkSAT / feasibility-jump):
     *  on a COP the portfolio posts an `objective ≤ incumbent` ratchet so it optimizes anyway, and on a
     *  CSP it is a pure feasibility finder. So no recipe fails to optimize on a COP — a CSP is the only
     *  non-optimizing case. */
    val feasibleDescent: FeasibleDescent
        get() = (optimizeStrategy ?: strategy).feasibleDescent

    /** A copy with [transform] applied to the satisfy strategy and the optimize strategy (if any), so
     *  one axis edit rewrites both halves of the recipe consistently. */
    private inline fun mapStrategies(transform: (SourceDrivenStrategy) -> SourceDrivenStrategy): LocalSearchRecipe =
        LocalSearchRecipe(
            label,
            transform(strategy),
            optimizeStrategy?.let(transform),
            perMoveInvariants,
            seedImplicitOnRestart,
        )

    /** A copy whose sources axis is [transform]ed (the editable list of configured move sources). */
    fun withSources(transform: (List<ConfiguredSource>) -> List<ConfiguredSource>): LocalSearchRecipe =
        mapStrategies { it.copy(sources = transform(it.sources)) }

    /** A copy whose scoring axis is replaced. */
    fun withScoring(scoring: MoveScoring): LocalSearchRecipe = mapStrategies { it.copy(scoring = scoring) }

    /** A copy whose acceptance axis is replaced. */
    fun withAcceptance(acceptance: AcceptanceRule): LocalSearchRecipe = mapStrategies {
        it.copy(
            acceptance = acceptance,
        )
    }

    /** A copy whose restart cadence (the schedule axis's restart member) is replaced. */
    fun withRestart(restart: RestartPolicy): LocalSearchRecipe =
        mapStrategies { it.copy(schedule = it.schedule.copy(restart = restart)) }

    /** A copy whose schedule-axis temperature is replaced — used to attach a cooling schedule when an
     *  acceptance edit turns a recipe into simulated annealing but it carried no temperature. */
    fun withTemperature(temperature: Schedule): LocalSearchRecipe =
        mapStrategies { it.copy(schedule = it.schedule.copy(temperature = temperature)) }
}
