package com.eignex.klause.portfolio

import com.eignex.klause.localsearch.AdaptivePerturbationRestart
import com.eignex.klause.localsearch.FixedCadenceRestart
import com.eignex.klause.localsearch.LubyRestart
import com.eignex.klause.localsearch.RestartPolicy
import com.eignex.klause.localsearch.TabuFilter
import com.eignex.klause.localsearch.acceptance.AcceptanceRule
import com.eignex.klause.localsearch.movesource.MoveSourceCatalog
import com.eignex.klause.localsearch.schedule.Geometric
import com.eignex.klause.localsearch.schedule.Reheating
import com.eignex.klause.localsearch.schedule.Schedule
import com.eignex.klause.localsearch.schedule.ScheduleBundle
import com.eignex.klause.localsearch.scoring.MoveScoring
import com.eignex.klause.localsearch.strategy.FeasibleDescent
import com.eignex.klause.localsearch.strategy.LocalSearchRecipe
import com.eignex.klause.localsearch.strategy.SourceDrivenStrategy
import kotlin.random.Random

/**
 * One choice per LS axis — a composable arm spec: `sources × scoring × acceptance ×
 * restart`. [toWorkerConfig] assembles a [SourceDrivenStrategy] over the four axes into a portfolio
 * worker, building fresh stateful instances each call (per the portfolio's no-shared-state rule).
 *
 * All four axes fold into the driver's single
 * [com.eignex.klause.localsearch.schedule.ScheduleBundle] schedule axis: the restart cadence
 * as its `restart` member, the SA temperature (via [AcceptanceOption.temperature]) as its
 * `temperature` member.
 */
internal class Recipe(
    val sources: SourcesPreset,
    val scoring: MoveScoring,
    val acceptance: AcceptanceOption,
    val restart: RestartOption,
) {
    /** Stable, unique-by-construction telemetry label. */
    val label: String get() = "${sources.name}|${scoring.name.lowercase()}|${acceptance.name}|${restart.name}"

    /** A fresh portfolio worker for this recipe. `optimizeStrategy` is left null so the engine's
     *  built-in objective descent owns the optimize phase (the recipe drives the feasibility fight),
     *  matching how the SAT-family / fjump arms are registered. */
    fun toWorkerConfig(tabu: TabuFilter = TabuFilter.Disabled): LocalSearchWorkerConfig = LocalSearchWorkerConfig(
        LocalSearchRecipe(
            "recipe/$label",
            SourceDrivenStrategy(
                MoveSourceCatalog.parse(sources.spec),
                scoring,
                acceptance.build(),
                schedule = ScheduleBundle(temperature = acceptance.temperature?.invoke(), restart = restart.build()),
                tabu = tabu,
                feasibleDescent = FeasibleDescent.RatchetAsConstraint,
            ),
        ),
    )
}

/** A named source-set, resolved through [MoveSourceCatalog] (the sources axis). */
internal class SourcesPreset(val name: String, val spec: String)

/** A named acceptance choice. [temperature] supplies the schedule-axis annealing schedule for the
 *  Metropolis rule (null for the non-temperature rules); a factory because schedules are stateful. */
internal class AcceptanceOption(
    val name: String,
    val temperature: (() -> Schedule)? = null,
    val build: () -> AcceptanceRule,
)

/** A named restart/perturbation cadence; a factory because restart policies are stateful per search. */
internal class RestartOption(val name: String, val build: () -> RestartPolicy)

/**
 * The LS recipe **space** — the option set per axis. [all] enumerates the full cross-product
 * `a × b × c × d`; [sample] draws a deterministic subset for an **exploration** campaign. This is a
 * generator for discovering good arms by benchmark, *not* the production portfolio — the curated
 * pool is re-derived wholesale from campaign results, so the defaults here are exploration-breadth
 * choices, not a tuned list.
 */
internal class RecipeSpace(
    val sources: List<SourcesPreset> = DEFAULT_SOURCES,
    val scorings: List<MoveScoring> = MoveScoring.entries,
    val acceptances: List<AcceptanceOption> = DEFAULT_ACCEPTANCES,
    val restarts: List<RestartOption> = DEFAULT_RESTARTS,
) {
    init {
        require(sources.isNotEmpty() && scorings.isNotEmpty() && acceptances.isNotEmpty() && restarts.isNotEmpty()) {
            "every recipe axis needs at least one option"
        }
    }

    /** Total number of recipes in the full cross-product. */
    val size: Int get() = sources.size * scorings.size * acceptances.size * restarts.size

    /** The full cross-product, in a stable nested order. */
    fun all(): List<Recipe> = buildList(size) {
        for (s in sources) {
            for (sc in scorings) {
                for (a in acceptances) {
                    for (r in restarts) {
                        add(Recipe(s, sc, a, r))
                    }
                }
            }
        }
    }

    /** A deterministic distinct sample of [n] recipes ([rng]-shuffled); the whole space when
     *  `n >= size`. For seeding an exploration bench campaign. */
    fun sample(n: Int, rng: Random): List<Recipe> {
        require(n >= 0) { "n >= 0, got $n" }
        val all = all()
        return if (n >= all.size) all else all.shuffled(rng).take(n)
    }

    /** Exploration-breadth defaults per axis. */
    companion object {
        val DEFAULT_SOURCES: List<SourcesPreset> = listOf(
            SourcesPreset("repair", "violated"),
            SourcesPreset("repair-frontier", "violated,frontier"),
            SourcesPreset("jump", "argmin"),
            SourcesPreset("repair-jump", "violated,argmin"),
            SourcesPreset("infeasible-full", "violated,frontier,argmin,stall-swaps"),
            SourcesPreset("feasible-descent", "violated,structured,objective"),
        )

        val DEFAULT_ACCEPTANCES: List<AcceptanceOption> = listOf(
            AcceptanceOption("greedy") { AcceptanceRule.Greedy },
            AcceptanceOption("walksat") { AcceptanceRule.WalkSatNoise(noise = 0.2) },
            AcceptanceOption("probsat") { AcceptanceRule.ProbSat() },
            AcceptanceOption("skew") { AcceptanceRule.Skew(alpha = 0.5) },
            AcceptanceOption("sa-geometric", temperature = { Geometric() }) { AcceptanceRule.Metropolis },
            AcceptanceOption("sa-reheat", temperature = { Reheating(Geometric(), period = 20_000) }) {
                AcceptanceRule.Metropolis
            },
        )

        val DEFAULT_RESTARTS: List<RestartOption> = listOf(
            RestartOption("fixed") { FixedCadenceRestart() },
            RestartOption("luby") { LubyRestart(unit = 200) },
            RestartOption("perturb") { AdaptivePerturbationRestart() },
        )
    }
}
