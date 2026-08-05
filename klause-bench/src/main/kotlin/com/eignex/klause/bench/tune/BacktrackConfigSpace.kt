package com.eignex.klause.bench.tune

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackPresets
import com.eignex.klause.backtrack.selector.Chb
import com.eignex.klause.backtrack.selector.DomainMaxRegret
import com.eignex.klause.backtrack.selector.IndomainMax
import com.eignex.klause.backtrack.selector.IndomainMedian
import com.eignex.klause.backtrack.selector.IndomainMiddle
import com.eignex.klause.backtrack.selector.IndomainMin
import com.eignex.klause.backtrack.selector.IndomainRandom
import com.eignex.klause.backtrack.selector.IndomainSplit
import com.eignex.klause.backtrack.selector.InputOrder
import com.eignex.klause.backtrack.selector.LargestDomain
import com.eignex.klause.backtrack.selector.LargestUpperBound
import com.eignex.klause.backtrack.selector.LastConflict
import com.eignex.klause.backtrack.selector.ProbingSelectors
import com.eignex.klause.backtrack.selector.RandomVariable
import com.eignex.klause.backtrack.selector.RegressionVariableSelector
import com.eignex.klause.backtrack.selector.SmallestDomain
import com.eignex.klause.backtrack.selector.SmallestLowerBound
import com.eignex.klause.backtrack.selector.SolutionGuided
import com.eignex.klause.backtrack.selector.ValueSelector
import com.eignex.klause.backtrack.selector.VariableSelector
import com.eignex.klause.backtrack.selector.Vsids
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.bounding.LpEmphasis

/**
 * The backtrack config search space (task #21) — variable/value selectors × restart cadence × phase
 * saving × LP emphasis/plan × learned-DB knobs as a lazy [ConfigSpace], decoded into [BacktrackParams]
 * by [toParams] over a preset base. The BO searches this directly; each evaluated point builds one
 * params object on demand (the backtrack analogue of [LocalSearchConfigSpace]). The declared knobs
 * mirror the full manual-CLI `backtrackOverride` surface — every value here decodes to a real,
 * buildable selector/param.
 */
internal object BacktrackConfigSpace : ConfigSpace(PARAMS) {

    /** Decode a sampled assignment into [BacktrackParams] on top of the chosen preset base. */
    fun toParams(a: Map<String, Any>): BacktrackParams {
        val base = when (a.str("preset")) {
            "satOptimized" -> BacktrackPresets.satOptimized()
            "free" -> BacktrackParams()
            else -> BacktrackPresets.conflictDriven()
        }
        var p = base.copy(
            variableSelector = varSelector(a.str("var-selector"), base.randomSeed),
            valueSelector = valSelector(a.str("val-selector")),
            lubyRestartBase = a.str("luby").let { if (it == "off") null else it.toLong() },
            phaseSaving = a.str("phase-saving") == "true",
            targetPhasing = a.str("target-phasing") == "true",
            adaptiveRestart = a.str("adaptive-restart") == "true",
            vivification = a.str("vivification") == "true",
            rephaseInterval = a.int("rephase-interval").toLong(),
            lbdGlueThreshold = a.int("lbd-glue"),
            midLbdThreshold = a.int("mid-lbd"),
            vivifyBatch = a.int("vivify-batch"),
            subsumption = a.str("subsumption") == "true",
            subsumeBatch = a.int("subsume-batch"),
            inprocessingCadence = a.int("inprocessing-cadence"),
        )
        val maxLearned = a.str("max-learned").let { if (it == "off") null else it.toInt() }
        p = p.copy(maxLearnedClauses = maxLearned, tieredLearnedDb = a.str("tiered-db") == "true")
        val emphasis = a.str("lp.emphasis")
        p = if (emphasis == "off") {
            p.copy(lpConfig = null)
        } else {
            p.copy(lpConfig = LpConfig(LpEmphasis.valueOf(emphasis.uppercase())))
        }
        if (emphasis != "off") {
            p = p.copy(
                lpPlan = p.lpPlan.copy(
                    lbTreeSearch = a["lp.lbtree"] == "true",
                    objectiveCone = a["lp.objective-cone"] == "true",
                    autoOffReprobe = a["lp.auto-off-reprobe"] == "true",
                    knapsackLagrangian = a["lp.knapsack-lagrangian"] == "true",
                ),
            )
        }
        return p
    }

    // The selectors are `public` klause API (the same classes the CLI's internal VarSelectorKind /
    // ValSelectorKind resolve to); klause-bench depends only on `:klause`, so it reaches them directly
    // rather than through that CLI-internal resolver. Every declared value below maps to a real one.
    private fun varSelector(name: String, seed: Long?): VariableSelector = when (name) {
        "last-conflict-vsids" -> LastConflict(Vsids())
        "chb" -> Chb()
        "linucb" -> RegressionVariableSelector.linUcb(seed = seed ?: 0L)
        "domwdeg" -> ProbingSelectors.domWdeg()
        "activity" -> ProbingSelectors.activityBasedSearch()
        "random" -> RandomVariable
        "input-order" -> InputOrder
        "smallest-domain" -> SmallestDomain
        "largest-domain" -> LargestDomain
        "smallest-lower-bound" -> SmallestLowerBound
        "largest-upper-bound" -> LargestUpperBound
        "domain-max-regret" -> DomainMaxRegret
        else -> Vsids()
    }

    private fun valSelector(name: String): ValueSelector = when (name) {
        "max" -> IndomainMax
        "impact" -> ProbingSelectors.impact()
        "random" -> IndomainRandom
        "middle" -> IndomainMiddle
        "median" -> IndomainMedian
        "split" -> IndomainSplit
        "solution-guided" -> SolutionGuided(IndomainMin)
        else -> IndomainMin
    }

    private fun Map<String, Any>.str(k: String) = this[k] as String
    private fun Map<String, Any>.int(k: String) = (this[k] as Number).toInt()
}

private fun lpOn(): (Map<String, Any>) -> Boolean = { it["lp.emphasis"] != "off" }

/** The declared backtrack space: preset base + the branch/restart/LP/learned-DB knobs. Mirrors the
 *  full `backtrackOverride` CLI surface. */
private val PARAMS: List<ConfigParam> = listOf(
    CategoricalParam("preset", listOf("conflictDriven", "satOptimized", "free")),
    // Every value below resolves to a real selector: the public no-argument selectors directly, plus
    // domwdeg/activity/impact (whose classes stay `internal` in klause) through the public
    // [ProbingSelectors] factory. The `set` val-selector needs an allowed-values array, so it has no
    // bare form and stays out; the CLI `backtrackOverride` surface omits it for the same reason.
    CategoricalParam(
        "var-selector",
        listOf(
            "vsids", "last-conflict-vsids", "chb", "linucb", "domwdeg", "activity", "random", "input-order",
            "smallest-domain", "largest-domain", "smallest-lower-bound", "largest-upper-bound",
            "domain-max-regret",
        ),
    ),
    CategoricalParam(
        "val-selector",
        listOf("min", "max", "impact", "random", "middle", "median", "split", "solution-guided"),
    ),
    CategoricalParam("luby", listOf("off", "128", "256", "512")),
    CategoricalParam("phase-saving", listOf("false", "true")),
    CategoricalParam("target-phasing", listOf("false", "true")),
    CategoricalParam("adaptive-restart", listOf("false", "true")),
    CategoricalParam("vivification", listOf("false", "true")),
    CategoricalParam("tiered-db", listOf("false", "true")),
    CategoricalParam("max-learned", listOf("off", "20000", "50000")),
    IntParam("rephase-interval", 200, 4000),
    IntParam("lbd-glue", 1, 5),
    IntParam("mid-lbd", 3, 12),
    IntParam("vivify-batch", 64, 1024),
    CategoricalParam("subsumption", listOf("false", "true")),
    IntParam("subsume-batch", 256, 4096),
    IntParam("inprocessing-cadence", 1, 16),
    CategoricalParam("lp.emphasis", listOf("off", "conservative", "default", "aggressive")),
    // LP-plan dials only matter when LP is on (conditional child params, gated exactly like lp.lbtree).
    CategoricalParam("lp.lbtree", listOf("false", "true"), lpOn()),
    CategoricalParam("lp.objective-cone", listOf("false", "true"), lpOn()),
    CategoricalParam("lp.auto-off-reprobe", listOf("false", "true"), lpOn()),
    CategoricalParam("lp.knapsack-lagrangian", listOf("false", "true"), lpOn()),
)
