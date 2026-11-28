package com.eignex.klause.localsearch.strategy

import com.eignex.klause.localsearch.ObjectiveGuidance
import com.eignex.klause.localsearch.RestartPolicy
import com.eignex.klause.localsearch.acceptance.AcceptanceRule
import com.eignex.klause.localsearch.movesource.ConfiguredSource
import com.eignex.klause.localsearch.schedule.Schedule
import com.eignex.klause.localsearch.scoring.MoveScoring

/**
 * A named local-search recipe: a [SourceDrivenStrategy], whose schedule carries the restart cadence, plus the
 * solver-level knobs the strategy does not own.
 */
class LocalSearchRecipe(
    /** External name (CLI, telemetry). */
    val label: String,
    /** Drives the feasibility phase, and the optimize phase when [optimizeStrategy] is null. */
    val strategy: SourceDrivenStrategy,
    /** Optimize-phase strategy; `null` reuses [strategy]. */
    val optimizeStrategy: SourceDrivenStrategy? = null,
    /** Per-arm switch for the per-move invariant network; off carves a diversity niche for cyclic
     *  definitional encodings whose reified indicators are otherwise search-excluded. */
    val perMoveInvariants: Boolean = true,
    /** Per-arm switch for implicit-solving feasible init on every restart, paired with a CBLS whose
     *  `implicitStructuredCap > 0`. */
    val seedImplicitOnRestart: Boolean = false,
    /** Objective guidance installed when the worker initializes its live search. */
    val objectiveGuidance: ObjectiveGuidance = ObjectiveGuidance.Functional,
) {
    /**
     * How the optimize-phase strategy descends. On a COP the portfolio overlays an `objective ≤ incumbent` ratchet
     * on a [FeasibleDescent.RatchetAsConstraint] recipe, so every recipe optimizes.
     */
    val feasibleDescent: FeasibleDescent
        get() = (optimizeStrategy ?: strategy).feasibleDescent

    // One axis edit rewrites both strategies consistently.
    private inline fun mapStrategies(transform: (SourceDrivenStrategy) -> SourceDrivenStrategy): LocalSearchRecipe =
        LocalSearchRecipe(
            label,
            transform(strategy),
            optimizeStrategy?.let(transform),
            perMoveInvariants,
            seedImplicitOnRestart,
            objectiveGuidance,
        )

    /** A copy selecting [guidance], with a distinct telemetry label. */
    fun withObjectiveGuidance(guidance: ObjectiveGuidance): LocalSearchRecipe = LocalSearchRecipe(
        "$label/${guidance.name.lowercase()}", strategy, optimizeStrategy, perMoveInvariants,
        seedImplicitOnRestart, guidance,
    )

    /** A copy whose configured move sources are [transform]ed. */
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

    /** A copy whose restart cadence is replaced. */
    fun withRestart(restart: RestartPolicy): LocalSearchRecipe =
        mapStrategies { it.copy(schedule = it.schedule.copy(restart = restart)) }

    /** A copy whose schedule-axis temperature is replaced. */
    fun withTemperature(temperature: Schedule): LocalSearchRecipe =
        mapStrategies { it.copy(schedule = it.schedule.copy(temperature = temperature)) }
}
