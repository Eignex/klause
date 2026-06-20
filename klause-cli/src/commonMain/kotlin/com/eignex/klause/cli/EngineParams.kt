package com.eignex.klause.cli

import com.eignex.klause.portfolio.EngineMix
import com.eignex.klause.portfolio.Kind
import com.eignex.klause.portfolio.PortfolioScenario
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.lp.LpConfig
import com.eignex.klause.solver.backtrack.selector.Chb
import com.eignex.klause.solver.backtrack.selector.DomainMaxRegret
import com.eignex.klause.solver.backtrack.selector.IndomainMax
import com.eignex.klause.solver.backtrack.selector.IndomainMedian
import com.eignex.klause.solver.backtrack.selector.IndomainMiddle
import com.eignex.klause.solver.backtrack.selector.IndomainMin
import com.eignex.klause.solver.backtrack.selector.IndomainRandom
import com.eignex.klause.solver.backtrack.selector.IndomainSplit
import com.eignex.klause.solver.backtrack.selector.InputOrder
import com.eignex.klause.solver.backtrack.selector.LargestDomain
import com.eignex.klause.solver.backtrack.selector.LargestUpperBound
import com.eignex.klause.solver.backtrack.selector.RandomVariable
import com.eignex.klause.solver.backtrack.selector.RegressionVariableSelector
import com.eignex.klause.solver.backtrack.selector.SmallestDomain
import com.eignex.klause.solver.backtrack.selector.SmallestLowerBound
import com.eignex.klause.solver.backtrack.selector.SolutionGuided
import com.eignex.klause.solver.backtrack.selector.ValueSelector
import com.eignex.klause.solver.backtrack.selector.VariableSelector
import com.eignex.klause.solver.backtrack.selector.Vsids
import com.eignex.klause.solver.localsearch.AspirationCriterion
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.TabuFilter
import com.eignex.klause.solver.localsearch.acceptance.AcceptanceRule
import com.eignex.klause.solver.localsearch.movesource.MoveSourceCatalog
import com.eignex.klause.solver.localsearch.schedule.Geometric
import com.eignex.klause.solver.localsearch.schedule.ScheduleBundle
import com.eignex.klause.solver.localsearch.scoring.MoveScoring
import com.eignex.klause.solver.localsearch.strategy.Cbls
import com.eignex.klause.solver.localsearch.strategy.FeasibilityJump
import com.eignex.klause.solver.localsearch.strategy.ProbSat
import com.eignex.klause.solver.localsearch.strategy.SimulatedAnnealing
import com.eignex.klause.solver.localsearch.strategy.SourceDrivenStrategy
import com.eignex.klause.solver.localsearch.strategy.WalkSat

/**
 * Engine tuning knobs passed as repeatable `--param key=value` flags. (`-p` is NOT an
 * alias: it is the MiniZinc-standard parallelism flag.)
 * Each engine consumes the keys it understands; an unrecognised or malformed key is a
 * hard usage error (exit 2) so typos never silently fall back to defaults.
 *
 * Keys per engine:
 *  - `cp`: `seed`, `max-decisions`, `luby`, `phase-saving`, `max-learned`, `lbd-glue`,
 *    `var-selector` (see [VarSelectorKind]), `val-selector` (see [ValSelectorKind])
 *  - `ls`: `strategy` (base recipe `cbls|feasibilityjump|walksat|probsat|sa|bare`, default `cbls`)
 *    plus per-axis overrides — `sources` (e.g. `violated,argmin`), `scoring` (`weighted|raw|break`),
 *    `acceptance` (`greedy|walksat|probsat|skew|sa`) and the axis numerics `noise`/`cb`/`skew-alpha`,
 *    `initial-temp`/`cooling-rate`/`min-temp`, `smooth-prob`/`smooth-factor`; engine knobs `seed`,
 *    `max-flips`, `lambda`, `tabu-tenure`, `pair-swap-budget`
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

    /** Raw string value for [key] (consumed), or null when absent. */
    fun string(key: String): String? = map.remove(key)

    fun bool(key: String): Boolean? = map.remove(key)?.let {
        when (it.lowercase()) {
            "true", "1", "on", "yes" -> true
            "false", "0", "off", "no" -> false
            else -> fail("engine param `$key` expects a boolean, got `$it`")
        }
    }

    /** [seed] feeds the LinUCB selector's RNG so an A/B is reproducible under the run's `seed`. */
    fun varSelector(key: String, seed: Long?): VariableSelector? = map.remove(key)?.let { raw ->
        when (VarSelectorKind.fromId(raw) ?: fail("engine param `$key` expects ${VarSelectorKind.ids()}, got `$raw`")) {
            VarSelectorKind.VSIDS -> Vsids()
            VarSelectorKind.CHB -> Chb()
            VarSelectorKind.LINUCB -> RegressionVariableSelector.linUcb(seed = seed ?: 0L)
            VarSelectorKind.RANDOM -> RandomVariable
            VarSelectorKind.INPUT_ORDER -> InputOrder
            VarSelectorKind.SMALLEST_DOMAIN -> SmallestDomain
            VarSelectorKind.LARGEST_DOMAIN -> LargestDomain
            VarSelectorKind.SMALLEST_LOWER_BOUND -> SmallestLowerBound
            VarSelectorKind.LARGEST_UPPER_BOUND -> LargestUpperBound
            VarSelectorKind.DOMAIN_MAX_REGRET -> DomainMaxRegret
        }
    }

    fun valSelector(key: String): ValueSelector? = map.remove(key)?.let { raw ->
        when (ValSelectorKind.fromId(raw) ?: fail("engine param `$key` expects ${ValSelectorKind.ids()}, got `$raw`")) {
            ValSelectorKind.RANDOM -> IndomainRandom
            ValSelectorKind.MIN -> IndomainMin
            ValSelectorKind.MAX -> IndomainMax
            ValSelectorKind.MIDDLE -> IndomainMiddle
            ValSelectorKind.MEDIAN -> IndomainMedian
            ValSelectorKind.SPLIT -> IndomainSplit
            ValSelectorKind.SOLUTION_GUIDED -> SolutionGuided(IndomainMin)
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

/** Apply `--param` overrides for a backtrack solve on top of [base]. [allowSelectors] gates the
 *  `var-selector`/`val-selector` keys: they are only meaningful for a **single** naked backtrack
 *  solver (`-e cp-single`), so portfolios and the annotation-following `fixed` engine pass `false`
 *  and reject those keys (the annotation or the per-arm config decides the heuristic instead). */
internal fun applyBacktrackParams(base: BacktrackParams, p: EngineParams, allowSelectors: Boolean): BacktrackParams {
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
    p.bool("lp-objective-cone")?.let { out = out.copy(lpPlan = out.lpPlan.copy(objectiveCone = it)) }
    p.bool("lp-auto-off-reprobe")?.let { out = out.copy(lpPlan = out.lpPlan.copy(autoOffReprobe = it)) }
    p.bool("lp-knapsack-lagrangian")?.let { out = out.copy(lpPlan = out.lpPlan.copy(knapsackLagrangian = it)) }
    if (allowSelectors) {
        p.varSelector("var-selector", out.randomSeed)?.let { out = out.copy(variableSelector = it) }
        p.valSelector("val-selector")?.let { out = out.copy(valueSelector = it) }
    }
    p.finish(
        "cp",
        "seed, max-decisions, luby, adaptive-restart, phase-saving, target-phasing, " +
            "rephase-interval, max-learned, lbd-glue, tiered-db, mid-lbd, vivification, vivify-batch, " +
            "lp-objective-cone, lp-auto-off-reprobe, lp-knapsack-lagrangian" +
            (if (allowSelectors) ", var-selector, val-selector" else ""),
    )
    return out
}

/** Resolved ls-single engine config: the fully-built four-axis [SourceDrivenStrategy] plus the
 *  solver knobs the strategy itself doesn't own. */
internal class LsSetup(val pairSwapBudget: Int, val lambda: Double, val strategy: SourceDrivenStrategy)

private fun parseScoring(s: String): MoveScoring = when (s.lowercase()) {
    "weighted" -> MoveScoring.Weighted
    "raw" -> MoveScoring.Raw
    "break" -> MoveScoring.Break
    else -> usageError("ls-single: scoring expects weighted|raw|break, got `$s`")
}

private fun parseAcceptance(s: String, noise: Double?, cb: Double, skewAlpha: Double): AcceptanceRule =
    when (s.lowercase()) {
        "greedy" -> AcceptanceRule.Greedy
        "walksat" -> AcceptanceRule.WalkSatNoise(noise ?: 0.5)
        "probsat" -> AcceptanceRule.ProbSat(cb)
        "skew" -> AcceptanceRule.Skew(skewAlpha)
        "sa" -> AcceptanceRule.Metropolis
        else -> usageError("ls-single: acceptance expects greedy|walksat|probsat|skew|sa, got `$s`")
    }

/**
 * Split `--param` overrides for the `ls-single` engine into per-call [LocalSearchParams] and the
 * built strategy ([LsSetup]).
 *
 * `strategy=` picks a base recipe — `cbls` (default) / `feasibilityjump` / `walksat` / `probsat` /
 * `sa`, or `bare` for a driver with no preset schedule. Each axis param then *overrides* that base's
 * corresponding axis: `sources=`, `scoring=`, `acceptance=`, and the numeric knobs the chosen
 * acceptance reads (`noise`/`cb`/`skew-alpha`) and the temperature schedule (`initial-temp`/
 * `cooling-rate`/`min-temp`). Unset axes keep the base's. Every combination is valid: each axis has a
 * conservative default, and a temperature schedule is auto-attached when the acceptance is
 * simulated annealing but none is otherwise present. Omitting `strategy`/`sources` keeps the default
 * tuned CBLS engine byte-identical.
 */
internal fun applyLsParams(base: LocalSearchParams, p: EngineParams): Pair<LocalSearchParams, LsSetup> {
    var out = base
    p.long("seed")?.let { out = out.copy(randomSeed = it) }
    p.long("max-flips")?.let { out = out.copy(maxFlips = it) }
    val noise = p.double("noise")
    val cb = p.double("cb") ?: 2.06
    val skewAlpha = p.double("skew-alpha") ?: 0.0
    val initTemp = p.double("initial-temp") ?: 1.0
    val coolRate = p.double("cooling-rate") ?: 0.999
    val minTemp = p.double("min-temp") ?: 1e-3
    val smoothProb = p.double("smooth-prob") ?: 0.4
    val smoothFactor = p.double("smooth-factor") ?: 0.8
    val tabu = TabuFilter(tenure = p.int("tabu-tenure") ?: 10, aspiration = AspirationCriterion.OrImproving)

    // Base recipe: a named preset, or a bare driver when only axes are given.
    val sourcesSpec = p.string("sources")
    val strategyName = p.string("strategy")?.lowercase() ?: if (sourcesSpec != null) "bare" else "cbls"
    val baseRecipe: SourceDrivenStrategy? = when (strategyName) {
        "cbls" -> Cbls(noiseProbability = noise ?: 0.05, smoothProb = smoothProb, smoothFactor = smoothFactor)

        "feasibilityjump", "feasibility-jump", "fjump" -> FeasibilityJump()

        "walksat" -> WalkSat(noise = noise ?: 0.5)

        "probsat" -> ProbSat(cb = cb)

        "sa", "annealing" -> SimulatedAnnealing(initTemp, coolRate, minTemp)

        "bare", "custom" -> null

        else -> usageError(
            "ls-single: strategy expects cbls|feasibilityjump|walksat|probsat|sa|bare, got `$strategyName`",
        )
    }

    // Per-axis overrides: each replaces the base's axis; unset axes keep the base's.
    val sources = sourcesSpec?.let { spec ->
        MoveSourceCatalog.parse(
            spec,
        ).also { if (it.isEmpty()) usageError("ls-single: sources=`$spec` selected no sources") }
    } ?: baseRecipe?.sources ?: usageError("ls-single: strategy=bare needs a sources= spec")
    val scoring = p.string("scoring")?.let { parseScoring(it) } ?: baseRecipe?.scoring ?: MoveScoring.Weighted
    val acceptance = p.string("acceptance")?.let { parseAcceptance(it, noise, cb, skewAlpha) }
        ?: baseRecipe?.acceptance ?: AcceptanceRule.Greedy

    // Schedule axis: keep the base's members; auto-attach a temperature when the acceptance is
    // simulated annealing and none is present, so any acceptance override stays runnable.
    val baseSchedule = baseRecipe?.schedule ?: ScheduleBundle()
    val temperature = baseSchedule.temperature
        ?: if (acceptance is AcceptanceRule.Metropolis) Geometric(initTemp, coolRate, minTemp) else null
    val schedule = ScheduleBundle(
        temperature = temperature,
        weights = baseSchedule.weights,
        noise = baseSchedule.noise,
        restart = baseSchedule.restart,
    )

    val setup = LsSetup(
        pairSwapBudget = p.int("pair-swap-budget") ?: 1024,
        lambda = p.double("lambda") ?: 1.0,
        strategy = SourceDrivenStrategy(
            sources = sources,
            scoring = scoring,
            acceptance = acceptance,
            schedule = schedule,
            tabu = tabu,
            configurationChecking = baseRecipe?.configurationChecking ?: false,
            perturbation = baseRecipe?.perturbation,
            drivesObjectiveDescent = baseRecipe?.drivesObjectiveDescent ?: false,
        ),
    )
    p.finish(
        "ls",
        "seed, max-flips, lambda, tabu-tenure, pair-swap-budget, strategy, sources, scoring, acceptance, " +
            "noise, cb, skew-alpha, smooth-prob, smooth-factor, initial-temp, cooling-rate, min-temp",
    )
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
    lpCeiling: LpConfig = LpConfig.AGGRESSIVE,
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
        lpCeiling = lpCeiling,
    )
}

/** Auto-tuned default arm-pool size, scaling with the core count (#406): [ARMS_PER_CORE] arms per
 *  core — always *more* arms than cores, so the bandit (single core) / parallel race always has a
 *  pool to draw on — floored at [PortfolioScenario.DEFAULT_ARMS] so the single-core free track still
 *  gets a real pool. Overridable via `--param arms=N`. */
internal fun autoArms(cores: Int): Int = maxOf(PortfolioScenario.DEFAULT_ARMS, cores * ARMS_PER_CORE)

/** Default arms-per-core oversubscription factor for [autoArms] (a #9 tuning knob). */
private const val ARMS_PER_CORE = 2

/** `var-selector` `--param` values for the `cp-single` engine (each maps to a [VariableSelector]).
 *  Covers the public no-argument selectors; the objective-/base-parameterised ones (MaxRegret,
 *  IndomainBest, LastConflict, …) and the `internal` ones (DomWdeg, ActivityBasedSearch) are not
 *  exposable as a bare value here. */
internal enum class VarSelectorKind(val id: String) {
    VSIDS("vsids"),
    CHB("chb"),
    LINUCB("linucb"),
    RANDOM("random"),
    INPUT_ORDER("input-order"),
    SMALLEST_DOMAIN("smallest-domain"),
    LARGEST_DOMAIN("largest-domain"),
    SMALLEST_LOWER_BOUND("smallest-lower-bound"),
    LARGEST_UPPER_BOUND("largest-upper-bound"),
    DOMAIN_MAX_REGRET("domain-max-regret"),
    ;

    companion object {
        private val byId = entries.associateBy { it.id }
        fun fromId(token: String): VarSelectorKind? = byId[token.trim().lowercase()]
        fun ids(): String = entries.joinToString("|") { it.id }
    }
}

/** `val-selector` `--param` values for the `cp-single` engine (each maps to a [ValueSelector]).
 *  Covers the public no-argument value selectors; IndomainBest (needs the objective) and the
 *  `internal` ones (IndomainSet, MaxSd, Impact) are not exposable as a bare value here. */
internal enum class ValSelectorKind(val id: String) {
    RANDOM("random"),
    MIN("min"),
    MAX("max"),
    MIDDLE("middle"),
    MEDIAN("median"),
    SPLIT("split"),
    SOLUTION_GUIDED("solution-guided"),
    ;

    companion object {
        private val byId = entries.associateBy { it.id }
        fun fromId(token: String): ValSelectorKind? = byId[token.trim().lowercase()]
        fun ids(): String = entries.joinToString("|") { it.id }
    }
}
