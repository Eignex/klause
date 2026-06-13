package com.eignex.klause.cli

import com.eignex.klause.portfolio.EngineMix
import com.eignex.klause.portfolio.Kind
import com.eignex.klause.portfolio.PortfolioScenario
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.selector.Chb
import com.eignex.klause.solver.backtrack.selector.IndomainMax
import com.eignex.klause.solver.backtrack.selector.IndomainMiddle
import com.eignex.klause.solver.backtrack.selector.IndomainMin
import com.eignex.klause.solver.backtrack.selector.IndomainRandom
import com.eignex.klause.solver.backtrack.selector.InputOrder
import com.eignex.klause.solver.backtrack.selector.RandomVariable
import com.eignex.klause.solver.backtrack.selector.SmallestDomain
import com.eignex.klause.solver.backtrack.selector.ValueSelector
import com.eignex.klause.solver.backtrack.selector.VariableSelector
import com.eignex.klause.solver.backtrack.selector.Vsids
import com.eignex.klause.solver.localsearch.LocalSearchParams

/**
 * Engine tuning knobs passed as repeatable `--param key=value` flags. (`-p` is NOT an
 * alias: it is the MiniZinc-standard parallelism flag.)
 * Each engine consumes the keys it understands; an unrecognised or malformed key is a
 * hard usage error (exit 2) so typos never silently fall back to defaults.
 *
 * Keys per engine:
 *  - `cp`: `seed`, `max-decisions`, `luby`, `phase-saving`, `max-learned`,
 *    `lbd-glue`, `var-heuristic` (`vsids|random|smallest-domain|input-order`),
 *    `val-heuristic` (`random|min|max|middle`)
 *  - `ls`: `seed`, `max-flips`, `lambda`, `tabu-tenure`, `pair-swap-budget`
 *  - `portfolio`: `ls`, `bt`, `seed`, `lambda`
 */
internal class EngineParams(pairs: List<String>) {
    private val map: MutableMap<String, String> = mutableMapOf()

    init {
        for (pair in pairs) {
            val eq = pair.indexOf('=')
            if (eq <= 0) fail("malformed engine param `$pair`; expected key=value")
            map[pair.take(eq)] = pair.substring(eq + 1)
        }
    }

    fun long(key: String): Long? = map.remove(key)?.let {
        it.toLongOrNull() ?: fail("engine param `$key` expects an integer, got `$it`")
    }

    fun int(key: String): Int? = map.remove(key)?.let {
        it.toIntOrNull() ?: fail("engine param `$key` expects an integer, got `$it`")
    }

    fun double(key: String): Double? = map.remove(key)?.let {
        it.toDoubleOrNull() ?: fail("engine param `$key` expects a number, got `$it`")
    }

    fun bool(key: String): Boolean? = map.remove(key)?.let {
        when (it.lowercase()) {
            "true", "1", "on", "yes" -> true
            "false", "0", "off", "no" -> false
            else -> fail("engine param `$key` expects a boolean, got `$it`")
        }
    }

    fun varSelector(key: String): VariableSelector? = map.remove(key)?.let {
        when (it.lowercase()) {
            "vsids" -> Vsids()
            "chb" -> Chb()
            "random" -> RandomVariable
            "smallest-domain" -> SmallestDomain
            "input-order" -> InputOrder
            else -> fail("engine param `$key` expects vsids|chb|random|smallest-domain|input-order, got `$it`")
        }
    }

    fun valSelector(key: String): ValueSelector? = map.remove(key)?.let {
        when (it.lowercase()) {
            "random" -> IndomainRandom
            "min" -> IndomainMin
            "max" -> IndomainMax
            "middle" -> IndomainMiddle
            else -> fail("engine param `$key` expects random|min|max|middle, got `$it`")
        }
    }

    /** Call after an engine consumed its keys: anything left over is a typo or a key for
     *  another engine — reject loudly rather than ignore. */
    fun finish(engine: String, valid: String) {
        if (map.isNotEmpty()) {
            fail("unknown $engine engine param(s) ${map.keys.joinToString()}; valid keys: $valid")
        }
    }

    private fun fail(msg: String): Nothing {
        errPrintln("klause-cli: $msg")
        exitCli(2)
    }
}

/** Apply `--param` overrides for the cp engine on top of [base]. */
internal fun applyBacktrackParams(base: BacktrackParams, p: EngineParams): BacktrackParams {
    var out = base
    p.long("seed")?.let { out = out.copy(randomSeed = it) }
    p.long("max-decisions")?.let { out = out.copy(maxDecisions = it) }
    p.long("luby")?.let { out = out.copy(lubyRestartBase = it) }
    p.bool("adaptive-restart")?.let { out = out.copy(adaptiveRestart = it) }
    p.bool("phase-saving")?.let { out = out.copy(phaseSaving = it) }
    p.bool("target-phasing")?.let { out = out.copy(targetPhasing = it) }
    p.long("rephase-interval")?.let { out = out.copy(rephaseInterval = it) }
    p.int("max-learned")?.let { out = out.copy(maxLearnedClauses = it) }
    p.int("lbd-glue")?.let { out = out.copy(lbdGlueThreshold = it) }
    p.bool("tiered-db")?.let { out = out.copy(tieredLearnedDb = it) }
    p.int("mid-lbd")?.let { out = out.copy(midLbdThreshold = it) }
    p.bool("vivification")?.let { out = out.copy(vivification = it) }
    p.int("vivify-batch")?.let { out = out.copy(vivifyBatch = it) }
    p.varSelector("var-heuristic")?.let { out = out.copy(variableSelector = it) }
    p.valSelector("val-heuristic")?.let { out = out.copy(valueSelector = it) }
    p.finish(
        "cp",
        "seed, max-decisions, luby, adaptive-restart, phase-saving, target-phasing, " +
            "rephase-interval, max-learned, lbd-glue, tiered-db, mid-lbd, vivification, " +
            "vivify-batch, var-heuristic, val-heuristic",
    )
    return out
}

/** Constructor-level LS knobs (the rest ride on [LocalSearchParams]). */
internal class LsSetup(val tabuTenure: Int, val pairSwapBudget: Int, val lambda: Double)

/** Split `--param` overrides for the ls engine into constructor knobs and per-call params. */
internal fun applyLsParams(base: LocalSearchParams, p: EngineParams): Pair<LocalSearchParams, LsSetup> {
    var out = base
    p.long("seed")?.let { out = out.copy(randomSeed = it) }
    p.long("max-flips")?.let { out = out.copy(maxFlips = it) }
    val setup = LsSetup(
        tabuTenure = p.int("tabu-tenure") ?: 10,
        pairSwapBudget = p.int("pair-swap-budget") ?: 1024,
        lambda = p.double("lambda") ?: 1.0,
    )
    p.finish("ls", "seed, max-flips, lambda, tabu-tenure, pair-swap-budget")
    return out to setup
}

/** Build the portfolio [PortfolioScenario] from `--param` overrides on top of the worker-count
 *  defaults the caller derived (from `-p N` parallelism or its own fallbacks). [fallbackSeed] is
 *  the `-r` flag, [kind] is whether the model optimizes. The `ls`/`bt` params set the total width
 *  (`threads = ls + bt`) and select the engine mix (LS-only / backtrack-only / mixed); the exact
 *  LS:backtrack split within a mixed pool is the scenario composition's (kind-derived) decision. */
internal fun buildPortfolioScenario(
    p: EngineParams,
    fallbackSeed: Long?,
    defaultLs: Int,
    defaultBt: Int,
    kind: Kind,
): PortfolioScenario {
    val ls = p.int("ls") ?: defaultLs
    val bt = p.int("bt") ?: defaultBt
    val seed = p.long("seed") ?: fallbackSeed ?: 1L
    val lambda = p.double("lambda") ?: 1.0
    p.finish("portfolio", "ls, bt, seed, lambda")
    require(ls >= 0 && bt >= 0 && ls + bt >= 1) { "portfolio needs ls + bt ≥ 1 (got ls=$ls, bt=$bt)" }
    val engine = when {
        ls == 0 -> EngineMix.BACKTRACK
        bt == 0 -> EngineMix.LOCAL_SEARCH
        else -> EngineMix.MIXED
    }
    return PortfolioScenario(threads = ls + bt, kind = kind, engine = engine, seed = seed, lsLambda = lambda)
}
