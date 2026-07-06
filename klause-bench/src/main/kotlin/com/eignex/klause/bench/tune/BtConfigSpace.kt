package com.eignex.klause.bench.tune

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackPresets
import com.eignex.klause.backtrack.lp.LpConfig
import com.eignex.klause.backtrack.lp.LpEmphasis
import com.eignex.klause.backtrack.selector.Chb
import com.eignex.klause.backtrack.selector.IndomainMax
import com.eignex.klause.backtrack.selector.IndomainMin
import com.eignex.klause.backtrack.selector.LastConflict
import com.eignex.klause.backtrack.selector.SmallestDomain
import com.eignex.klause.backtrack.selector.SolutionGuided
import com.eignex.klause.backtrack.selector.ValueSelector
import com.eignex.klause.backtrack.selector.VariableSelector
import com.eignex.klause.backtrack.selector.Vsids

/**
 * The backtrack config search space (task #21) — variable/value selectors × restart cadence × phase
 * saving × LP emphasis/plan × learned-DB knobs as a lazy [ConfigSpace], decoded into [BacktrackParams]
 * by [toParams] over a preset base. The BO searches this directly; each evaluated point builds one
 * params object on demand (the backtrack analogue of [LsConfigSpace]).
 */
object BtConfigSpace : ConfigSpace(PARAMS) {

    /** Decode a sampled assignment into [BacktrackParams] on top of the chosen preset base. */
    fun toParams(a: Map<String, Any>): BacktrackParams {
        val base = when (a.str("preset")) {
            "satOptimized" -> BacktrackPresets.satOptimized()
            "free" -> BacktrackParams()
            else -> BacktrackPresets.conflictDriven()
        }
        var p = base.copy(
            variableSelector = varSelector(a.str("var-selector")),
            valueSelector = valSelector(a.str("val-selector")),
            lubyRestartBase = a.str("luby").let { if (it == "off") null else it.toLong() },
            phaseSaving = a.str("phase-saving") == "true",
            adaptiveRestart = a.str("adaptive-restart") == "true",
        )
        val maxLearned = a.str("max-learned").let { if (it == "off") null else it.toInt() }
        p = p.copy(maxLearnedClauses = maxLearned, tieredLearnedDb = a.str("tiered-db") == "true")
        val emphasis = a.str("lp.emphasis")
        p = if (emphasis == "off") {
            p.copy(lpConfig = null)
        } else {
            p.copy(lpConfig = LpConfig(LpEmphasis.valueOf(emphasis.uppercase())))
        }
        if (a["lp.lbtree"] == "true" && emphasis != "off") {
            p = p.copy(lpPlan = p.lpPlan.copy(lbTreeSearch = true))
        }
        return p
    }

    private fun varSelector(name: String): VariableSelector = when (name) {
        "last-conflict-vsids" -> LastConflict(Vsids())
        "chb" -> Chb()
        "smallest-domain" -> SmallestDomain
        else -> Vsids()
    }

    private fun valSelector(name: String): ValueSelector = when (name) {
        "max" -> IndomainMax
        "solution-guided" -> SolutionGuided(IndomainMin)
        else -> IndomainMin
    }

    private fun Map<String, Any>.str(k: String) = this[k] as String
}

private fun lpOn(): (Map<String, Any>) -> Boolean = { it["lp.emphasis"] != "off" }

/** The declared backtrack space: preset base + the branch/restart/LP/learned-DB knobs. */
private val PARAMS: List<ConfigParam> = listOf(
    CategoricalParam("preset", listOf("conflictDriven", "satOptimized", "free")),
    // NOTE: domwdeg/activity var-selectors and the impact val-selector are `internal` in klause
    // (not exposable across modules) — add them once they're public or BtConfigSpace moves into klause.
    CategoricalParam("var-selector", listOf("vsids", "last-conflict-vsids", "chb", "smallest-domain")),
    CategoricalParam("val-selector", listOf("min", "max", "solution-guided")),
    CategoricalParam("luby", listOf("off", "128", "256", "512")),
    CategoricalParam("phase-saving", listOf("false", "true")),
    CategoricalParam("adaptive-restart", listOf("false", "true")),
    CategoricalParam("tiered-db", listOf("false", "true")),
    CategoricalParam("max-learned", listOf("off", "20000", "50000")),
    CategoricalParam("lp.emphasis", listOf("off", "conservative", "default", "aggressive")),
    // LP tree-search dive only matters when LP is on (conditional child param).
    CategoricalParam("lp.lbtree", listOf("false", "true"), lpOn()),
)
