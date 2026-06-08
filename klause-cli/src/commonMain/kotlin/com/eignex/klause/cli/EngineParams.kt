package com.eignex.klause.cli

import com.eignex.klause.portfolio.PortfolioSpec
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.IndomainMax
import com.eignex.klause.solver.backtrack.IndomainMiddle
import com.eignex.klause.solver.backtrack.IndomainMin
import com.eignex.klause.solver.backtrack.IndomainRandom
import com.eignex.klause.solver.backtrack.InputOrder
import com.eignex.klause.solver.backtrack.RandomVariable
import com.eignex.klause.solver.backtrack.SmallestDomain
import com.eignex.klause.solver.backtrack.ValueHeuristic
import com.eignex.klause.solver.backtrack.VariableHeuristic
import com.eignex.klause.solver.backtrack.Vsids
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

    fun varHeuristic(key: String): VariableHeuristic? = map.remove(key)?.let {
        when (it.lowercase()) {
            "vsids" -> Vsids()
            "random" -> RandomVariable
            "smallest-domain" -> SmallestDomain
            "input-order" -> InputOrder
            else -> fail("engine param `$key` expects vsids|random|smallest-domain|input-order, got `$it`")
        }
    }

    fun valHeuristic(key: String): ValueHeuristic? = map.remove(key)?.let {
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
    p.int("max-learned")?.let { out = out.copy(maxLearnedClauses = it) }
    p.int("lbd-glue")?.let { out = out.copy(lbdGlueThreshold = it) }
    p.varHeuristic("var-heuristic")?.let { out = out.copy(variableHeuristic = it) }
    p.valHeuristic("val-heuristic")?.let { out = out.copy(valueHeuristic = it) }
    p.finish(
        "cp",
        "seed, max-decisions, luby, adaptive-restart, phase-saving, max-learned, lbd-glue, " +
            "var-heuristic, val-heuristic",
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

/** Apply `--param` overrides for the portfolio engine on top of the worker-count defaults
 *  the caller derived (from `-p N` parallelism or its own fallbacks). [fallbackSeed] is the
 *  `-r` flag. */
internal fun buildPortfolioSpec(p: EngineParams, fallbackSeed: Long?, defaultLs: Int, defaultBt: Int): PortfolioSpec {
    val spec = PortfolioSpec(
        localSearchWorkers = p.int("ls") ?: defaultLs,
        backtrackWorkers = p.int("bt") ?: defaultBt,
        seed = p.long("seed") ?: fallbackSeed ?: 1L,
        lsLambda = p.double("lambda") ?: 1.0,
    )
    p.finish("portfolio", "ls, bt, seed, lambda")
    return spec
}
