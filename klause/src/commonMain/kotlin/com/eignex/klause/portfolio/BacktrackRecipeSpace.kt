package com.eignex.klause.portfolio

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.RestartSchedule
import com.eignex.klause.backtrack.selector.ActivityBasedSearch
import com.eignex.klause.backtrack.selector.DomWdeg
import com.eignex.klause.backtrack.selector.IndomainMax
import com.eignex.klause.backtrack.selector.IndomainMin
import com.eignex.klause.backtrack.selector.SmallestDomain
import com.eignex.klause.backtrack.selector.SolutionGuided
import com.eignex.klause.backtrack.selector.ValueSelector
import com.eignex.klause.backtrack.selector.VariableSelector
import com.eignex.klause.backtrack.selector.Vsids
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.bounding.LpEmphasis
import kotlin.random.Random

/**
 * One point in the backtrack recipe space: a label plus a factory that builds its [BacktrackParams]
 * for a worker seed. The backtrack analogue of the local-search [Recipe] — a candidate for a sweep /
 * credit campaign over the complete-search tuning axes.
 */
internal class BacktrackRecipe(val label: String, val build: (seed: Long) -> BacktrackParams)

/** A named variable-order axis option. */
internal class BtVarOption(val label: String, val make: () -> VariableSelector)

/** A named value-order axis option. */
internal class BtValOption(val label: String, val make: () -> ValueSelector)

/**
 * A named restart-schedule axis option — the sweep counterpart of a [RestartSchedule] choice, applied
 * through [BacktrackParams]. A [lubyBase] selects the Luby schedule at that base; [adaptive] selects the
 * Glucose adaptive schedule; [ema] selects the EMA-based adaptive schedule; [modeSwitching] selects the
 * stable/focused mode-switching schedule; all left unset is the single-unbounded-run (no-restart)
 * schedule.
 */
internal class BtRestartOption(
    val label: String,
    val lubyBase: Long? = null,
    val adaptive: Boolean = false,
    val ema: Boolean = false,
    val modeSwitching: Boolean = false,
)

/** A named LP-emphasis axis option; [emphasis] `OFF` leaves the recipe with no LP relaxation. */
internal class BtLpOption(val label: String, val emphasis: LpEmphasis)

/** A named objective-guided-value axis option (#33): [enabled] dives toward each variable's
 *  cost-minimising polarity first (a no-op on a satisfaction problem). */
internal class BtObjGuidedOption(val label: String, val enabled: Boolean)

/**
 * Cross-product recipe space over the backtrack tuning axes — variable order × value order × restart
 * cadence × LP emphasis × objective-guided values — mirroring the local-search [RecipeSpace]. A generator
 * for a backtrack credit campaign: [all] enumerates the full product, [sample] draws a deterministic
 * distinct subset to seed a bench sweep. The curated [BacktrackWorkerConfig] ranking is not yet *derived*
 * from a campaign over this space; running that campaign and folding its credit back into the ranked order
 * is a follow-up.
 */
internal class BacktrackRecipeSpace(
    val variables: List<BtVarOption> = DEFAULT_VARIABLES,
    val values: List<BtValOption> = DEFAULT_VALUES,
    val restarts: List<BtRestartOption> = DEFAULT_RESTARTS,
    val lp: List<BtLpOption> = DEFAULT_LP,
    val objGuided: List<BtObjGuidedOption> = DEFAULT_OBJ_GUIDED,
) {
    init {
        require(
            variables.isNotEmpty() && values.isNotEmpty() && restarts.isNotEmpty() &&
                lp.isNotEmpty() && objGuided.isNotEmpty(),
        ) {
            "every recipe axis needs at least one option"
        }
    }

    /** Total number of recipes in the full cross-product. */
    val size: Int get() = variables.size * values.size * restarts.size * lp.size * objGuided.size

    /** The full cross-product, in a stable nested order, each with a unique label. */
    fun all(): List<BacktrackRecipe> = buildList(size) {
        for (v in variables) {
            for (va in values) {
                for (r in restarts) {
                    for (l in lp) {
                        for (og in objGuided) {
                            add(recipe(v, va, r, l, og))
                        }
                    }
                }
            }
        }
    }

    /** A deterministic distinct sample of [n] recipes ([rng]-shuffled); the whole space when
     *  `n >= size`. For seeding an exploration bench campaign. */
    fun sample(n: Int, rng: Random): List<BacktrackRecipe> {
        require(n >= 0) { "n >= 0, got $n" }
        val all = all()
        return if (n >= all.size) all else all.shuffled(rng).take(n)
    }

    private fun recipe(
        v: BtVarOption,
        va: BtValOption,
        r: BtRestartOption,
        l: BtLpOption,
        og: BtObjGuidedOption,
    ): BacktrackRecipe = BacktrackRecipe("${v.label}+${va.label}/${r.label}/${l.label}/${og.label}") { seed ->
        BacktrackParams(
            randomSeed = seed,
            variableSelector = v.make(),
            valueSelector = va.make(),
            lubyRestartBase = r.lubyBase,
            adaptiveRestart = r.adaptive,
            emaRestart = r.ema,
            modeSwitchingRestart = r.modeSwitching,
            lpConfig = if (l.emphasis == LpEmphasis.OFF) null else LpConfig(l.emphasis),
            objectiveGuidedValues = og.enabled,
        )
    }

    /** Exploration-breadth defaults per axis. */
    companion object {
        val DEFAULT_VARIABLES: List<BtVarOption> = listOf(
            BtVarOption("vsids") { Vsids() },
            BtVarOption("domwdeg") { DomWdeg() },
            BtVarOption("first-fail") { SmallestDomain },
            BtVarOption("activity") { ActivityBasedSearch() },
        )

        val DEFAULT_VALUES: List<BtValOption> = listOf(
            BtValOption("min") { IndomainMin },
            BtValOption("max") { IndomainMax },
            BtValOption("solguided") { SolutionGuided(IndomainMin) },
        )

        val DEFAULT_RESTARTS: List<BtRestartOption> = listOf(
            BtRestartOption("luby-100", lubyBase = 100L),
            BtRestartOption("luby-256", lubyBase = 256L),
            BtRestartOption("adaptive", adaptive = true),
            BtRestartOption("ema", ema = true),
            BtRestartOption("mode-switch", modeSwitching = true),
            BtRestartOption("no-restart"),
        )

        val DEFAULT_LP: List<BtLpOption> = listOf(
            BtLpOption("no-lp", LpEmphasis.OFF),
            BtLpOption("lp-default", LpEmphasis.DEFAULT),
            BtLpOption("lp-aggressive", LpEmphasis.AGGRESSIVE),
        )

        val DEFAULT_OBJ_GUIDED: List<BtObjGuidedOption> = listOf(
            BtObjGuidedOption("cost-agnostic", enabled = false),
            BtObjGuidedOption("obj-guided", enabled = true),
        )
    }
}
