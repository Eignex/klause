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
 *    `lbd-glue`, `var-selector` (`vsids|random|smallest-domain|input-order`),
 *    `val-selector` (`random|min|max|middle`)
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
    p.varSelector("var-selector")?.let { out = out.copy(variableSelector = it) }
    p.valSelector("val-selector")?.let { out = out.copy(valueSelector = it) }
    p.finish(
        "cp",
        "seed, max-decisions, luby, adaptive-restart, phase-saving, target-phasing, " +
            "rephase-interval, max-learned, lbd-glue, tiered-db, mid-lbd, vivification, " +
            "vivify-batch, var-selector, val-selector",
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

/**
 * Build the [PortfolioScenario] from `-p N` parallelism ([cores]) and `--param` overrides.
 * [fallbackSeed] is `-r`, [kind] is whether the model optimizes, [defaultEngine] comes from `-e`,
 * and [defaultArms] is the auto-tuned pool size used when the user doesn't override it.
 *
 * Arm count and engine mix:
 *  - `--param arms=N` sets the pool size directly (the common tuning knob); the engine mix is
 *    [defaultEngine]. Clamped up to [cores] (a parallel track needs ≥ one arm per core).
 *  - `--param ls=N` / `bt=N` instead set BOTH the count (`ls + bt`) and the mix (LS-only /
 *    backtrack-only / mixed). Mutually exclusive with `arms`.
 *  - neither ⇒ [defaultArms] arms of [defaultEngine].
 *
 * The exact LS:backtrack split within a MIXED pool stays the composition's (kind-derived) decision.
 */
internal fun buildPortfolioScenario(
    p: EngineParams,
    fallbackSeed: Long?,
    cores: Int,
    kind: Kind,
    defaultEngine: EngineMix,
    defaultArms: Int,
): PortfolioScenario {
    val seed = p.long("seed") ?: fallbackSeed ?: 1L
    val lambda = p.double("lambda") ?: 1.0
    val armsParam = p.int("arms")
    val ls = p.int("ls")
    val bt = p.int("bt")
    p.finish("portfolio", "arms, ls, bt, seed, lambda")
    if (armsParam != null && (ls != null || bt != null)) {
        usageError("portfolio: set either `arms=N` or `ls=/bt=`, not both")
    }
    val (engine, arms) = if (ls != null || bt != null) {
        val l = ls ?: 0
        val b = bt ?: 0
        require(l >= 0 && b >= 0 && l + b >= 1) { "portfolio needs ls + bt ≥ 1 (got ls=$l, bt=$b)" }
        val e = when {
            l == 0 -> EngineMix.BACKTRACK
            b == 0 -> EngineMix.LOCAL_SEARCH
            else -> EngineMix.MIXED
        }
        e to (l + b)
    } else {
        defaultEngine to (armsParam ?: defaultArms)
    }
    require(arms >= 1) { "portfolio needs arms ≥ 1 (got $arms)" }
    return PortfolioScenario(
        cores = cores,
        arms = maxOf(arms, cores),
        kind = kind,
        engine = engine,
        seed = seed,
        lsLambda = lambda,
    )
}

/** Auto-tuned default arm-pool size, scaling with the core count (#406): [ARMS_PER_CORE] arms per
 *  core — always *more* arms than cores, so the bandit (single core) / parallel race always has a
 *  pool to draw on — floored at [PortfolioScenario.DEFAULT_ARMS] so the single-core free track still
 *  gets a real pool. Overridable via `--param arms=N`. */
internal fun autoArms(cores: Int): Int = maxOf(PortfolioScenario.DEFAULT_ARMS, cores * ARMS_PER_CORE)

/** Default arms-per-core oversubscription factor for [autoArms] (a #9 tuning knob). */
private const val ARMS_PER_CORE = 2
