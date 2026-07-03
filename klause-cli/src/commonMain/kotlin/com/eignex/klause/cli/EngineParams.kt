package com.eignex.klause.cli

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.lp.LpConfig
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
import com.eignex.klause.backtrack.selector.RandomVariable
import com.eignex.klause.backtrack.selector.RegressionVariableSelector
import com.eignex.klause.backtrack.selector.SmallestDomain
import com.eignex.klause.backtrack.selector.SmallestLowerBound
import com.eignex.klause.backtrack.selector.SolutionGuided
import com.eignex.klause.backtrack.selector.ValueSelector
import com.eignex.klause.backtrack.selector.VariableSelector
import com.eignex.klause.backtrack.selector.Vsids
import com.eignex.klause.localsearch.AdaptivePerturbationRestart
import com.eignex.klause.localsearch.AspirationCriterion
import com.eignex.klause.localsearch.FixedCadenceRestart
import com.eignex.klause.localsearch.LubyRestart
import com.eignex.klause.localsearch.RestartPolicy
import com.eignex.klause.localsearch.TabuFilter
import com.eignex.klause.localsearch.acceptance.AcceptanceRule
import com.eignex.klause.localsearch.schedule.Geometric
import com.eignex.klause.localsearch.scoring.MoveScoring
import com.eignex.klause.localsearch.strategy.AxisEdits
import com.eignex.klause.localsearch.strategy.AxisToken
import com.eignex.klause.localsearch.strategy.Cbls
import com.eignex.klause.localsearch.strategy.FeasibilityJump
import com.eignex.klause.localsearch.strategy.LsCatalog
import com.eignex.klause.localsearch.strategy.LsRecipe
import com.eignex.klause.localsearch.strategy.ProbSat
import com.eignex.klause.localsearch.strategy.SimulatedAnnealing
import com.eignex.klause.localsearch.strategy.SourceDrivenStrategy
import com.eignex.klause.localsearch.strategy.WalkSat
import com.eignex.klause.portfolio.EngineMix
import com.eignex.klause.portfolio.Kind
import com.eignex.klause.portfolio.PortfolioScenario

/**
 * Engine tuning knobs passed as repeatable `--param key=value` flags. (`-p` is NOT an
 * alias: it is the MiniZinc-standard parallelism flag.)
 * Each engine consumes the keys it understands; an unrecognised or malformed key is a
 * hard usage error (exit 2) so typos never silently fall back to defaults.
 *
 * Keys per engine:
 *  - `cp`: `seed`, `max-decisions`, `luby`, `phase-saving`, `max-learned`, `lbd-glue`,
 *    `var-selector` (see [VarSelectorKind]), `val-selector` (see [ValSelectorKind])
 *  - `ls`: `strategy` (base `auto` (curated pool, default) | `cbls|feasibilityjump|walksat|probsat|sa`
 *    | `bare`), or `arm=<catalog-label>` to run one curated arm in isolation (the fair-tester sweep),
 *    plus per-axis edits applied across the pool — `sources` (bare force-exactly list or
 *    `+`/`-` add/remove), `scoring` (`weighted|raw|break`), `acceptance`
 *    (`greedy|walksat|probsat|skew|sa`), `restart` (`fixed|luby|perturb`); any token may carry an
 *    arm-family selector (`cbls.break`). Axis numerics `noise`/`cb`/`skew-alpha`/`initial-temp`/
 *    `cooling-rate`/`min-temp`/`smooth-prob`/`smooth-factor`/`tabu-tenure`; portfolio knobs `arms`,
 *    `ls`, `bt`, `seed`, `lambda`; `dry-run` lists the resolved arms instead of solving
 *  - `portfolio`: `arms`, `ls`, `bt`, `seed`, `lambda`
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

/** Resolved local-search arm pool for the `ls` engine: per-arm factories (a *fresh* recipe per slot,
 *  so parallel workers never share mutable strategy state). A null [pool] means the default curated
 *  pool — the portfolio builds it unchanged. [dryRunSolver] short-circuits the solve to print the
 *  resolved arm pool (the solver configuration) instead of solving. */
internal class LsResolution(val pool: List<() -> LsRecipe>?, val dryRunSolver: Boolean)

private fun parseScoring(s: String): MoveScoring = when (s.lowercase()) {
    "weighted" -> MoveScoring.Weighted
    "raw" -> MoveScoring.Raw
    "break" -> MoveScoring.Break
    else -> usageError("ls: scoring expects weighted|raw|break, got `$s`")
}

private fun parseAcceptance(s: String, noise: Double?, cb: Double, skewAlpha: Double): AcceptanceRule =
    when (s.lowercase()) {
        "greedy" -> AcceptanceRule.Greedy
        "walksat" -> AcceptanceRule.WalkSatNoise(noise ?: 0.5)
        "probsat" -> AcceptanceRule.ProbSat(cb)
        "skew" -> AcceptanceRule.Skew(skewAlpha)
        "sa" -> AcceptanceRule.Metropolis
        else -> usageError("ls: acceptance expects greedy|walksat|probsat|skew|sa, got `$s`")
    }

private fun parseRestart(s: String): RestartPolicy = when (s.lowercase()) {
    "fixed" -> FixedCadenceRestart()
    "luby" -> LubyRestart(unit = 200)
    "perturb", "adaptive-perturb" -> AdaptivePerturbationRestart()
    else -> usageError("ls: restart expects fixed|luby|perturb, got `$s`")
}

/** Parse a single-valued axis spec into its tokens, rejecting `+`/`-` (only the sources list axis
 *  takes set edits). */
private fun scalarTokens(spec: String?, axis: String): List<AxisToken> {
    val tokens = spec?.let { AxisEdits.tokens(it) } ?: return emptyList()
    tokens.firstOrNull { it.op != AxisToken.Op.SET }?.let {
        usageError("ls: $axis is single-valued and takes no +/- edit (got `${it.value}`)")
    }
    return tokens
}

private fun cblsStrategy(noise: Double?, smoothProb: Double, smoothFactor: Double, tabu: TabuFilter) =
    Cbls(noiseProbability = noise ?: 0.05, smoothProb = smoothProb, smoothFactor = smoothFactor, tabu = tabu)

/** A factory for a named base recipe with the CLI numerics applied. CBLS gets an independent optimize
 *  strategy (the unified minimize path); the SAT-family / fjump arms leave it null so the engine's
 *  built-in objective descent owns the optimize phase. */
private fun namedFactory(
    name: String,
    noise: Double?,
    cb: Double,
    initTemp: Double,
    coolRate: Double,
    minTemp: Double,
    smoothProb: Double,
    smoothFactor: Double,
    tabu: TabuFilter,
): () -> LsRecipe = when (name) {
    "cbls" -> {
        {
            LsRecipe(
                "cbls",
                cblsStrategy(noise, smoothProb, smoothFactor, tabu),
                optimizeStrategy = cblsStrategy(noise, smoothProb, smoothFactor, tabu),
            )
        }
    }

    "feasibilityjump", "feasibility-jump", "fjump" -> {
        { LsRecipe("fjump", FeasibilityJump()) }
    }

    "walksat" -> {
        { LsRecipe("walksat", WalkSat(noise = noise ?: 0.5, tabu = tabu)) }
    }

    "probsat" -> {
        { LsRecipe("probsat", ProbSat(cb = cb, tabu = tabu)) }
    }

    "sa", "annealing" -> {
        // The unified minimize path (both halves the optimizer form, independent schedules) so SA
        // anneals on the objective at feasibility like the catalog sa/* arms, rather than being
        // ratcheted as a plain finder on a COP.
        {
            LsRecipe(
                "sa",
                SimulatedAnnealing.optimizer(Geometric(initTemp, coolRate, minTemp), tabu = tabu),
                optimizeStrategy = SimulatedAnnealing.optimizer(Geometric(initTemp, coolRate, minTemp), tabu = tabu),
            )
        }
    }

    else -> usageError("ls: strategy expects auto|cbls|feasibilityjump|walksat|probsat|sa|bare, got `$name`")
}

/** A bare driver with no preset axes; its sources are supplied by a force-exactly `sources=` spec. */
private fun bareRecipe(tabu: TabuFilter): LsRecipe = LsRecipe(
    "bare",
    SourceDrivenStrategy(sources = emptyList(), tabu = tabu),
)

private fun applyEdits(
    recipe: LsRecipe,
    sources: List<AxisToken>,
    scoring: List<AxisToken>,
    acceptance: List<AxisToken>,
    restart: List<AxisToken>,
    noise: Double?,
    cb: Double,
    skewAlpha: Double,
    saTemperature: () -> Geometric,
): LsRecipe {
    var r = recipe
    val src = sources.filter { it.appliesTo(r.label) }
    if (src.isNotEmpty()) r = r.withSources { AxisEdits.applySources(it, src) }
    scoring.filter { it.appliesTo(r.label) }.forEach { r = r.withScoring(parseScoring(it.value)) }
    val acceptEdits = acceptance.filter { it.appliesTo(r.label) }
    acceptEdits.forEach { r = r.withAcceptance(parseAcceptance(it.value, noise, cb, skewAlpha)) }
    // An acceptance edit to simulated annealing needs a cooling schedule; attach one (a fresh
    // stateful instance) when the recipe carried none — a named `strategy=sa` already has one.
    if (acceptEdits.isNotEmpty() &&
        r.strategy.acceptance is AcceptanceRule.Metropolis &&
        r.strategy.schedule.temperature == null
    ) {
        r = r.withTemperature(saTemperature())
    }
    restart.filter { it.appliesTo(r.label) }.forEach { r = r.withRestart(parseRestart(it.value)) }
    return r
}

/**
 * Resolve the `ls` engine's `--param` overrides into an arm pool. `strategy=` picks the base —
 * `auto` (default; the curated [LsCatalog] pool), a named recipe (`cbls`/`feasibilityjump`/`walksat`/
 * `probsat`/`sa`), or `bare` (a driver whose sources come from a force-exactly `sources=` spec). The
 * axis keys then *edit* the base across all matching arms, presolve-style: `sources=` takes a bare
 * force-exactly list or `+`/`-` add/remove; `scoring`/`acceptance`/`restart` set a single value. Any
 * token may carry an arm-family selector (`cbls.break`) so an edit scopes to part of the pool. Plain
 * `-e ls` with no overrides keeps the curated pool unchanged (a null pool). `dry-run-solver=on` lists
 * the resolved arms instead of solving.
 */
internal fun resolveLsRecipes(p: EngineParams): LsResolution {
    val dryRunSolver = p.bool("dry-run-solver") ?: false
    val noiseRaw = p.double("noise")
    val cbRaw = p.double("cb")
    val skewRaw = p.double("skew-alpha")
    val initTempRaw = p.double("initial-temp")
    val coolRateRaw = p.double("cooling-rate")
    val minTempRaw = p.double("min-temp")
    val smoothProbRaw = p.double("smooth-prob")
    val smoothFactorRaw = p.double("smooth-factor")
    val tabuRaw = p.int("tabu-tenure")
    val cb = cbRaw ?: 2.06
    val skewAlpha = skewRaw ?: 0.0
    val initTemp = initTempRaw ?: 1.0
    val coolRate = coolRateRaw ?: 0.999
    val minTemp = minTempRaw ?: 1e-3
    val smoothProb = smoothProbRaw ?: 0.4
    val smoothFactor = smoothFactorRaw ?: 0.8
    val tabu = TabuFilter(tenure = tabuRaw ?: 10, aspiration = AspirationCriterion.OrImproving)

    val sourcesSpec = p.string("sources")
    val sources = sourcesSpec?.let { AxisEdits.tokens(it) }.orEmpty()
    val scoring = scalarTokens(p.string("scoring"), "scoring")
    val acceptance = scalarTokens(p.string("acceptance"), "acceptance")
    val restart = scalarTokens(p.string("restart"), "restart")
    val hasEdits = sources.isNotEmpty() || scoring.isNotEmpty() || acceptance.isNotEmpty() || restart.isNotEmpty()
    val strategyRaw = p.string("strategy")?.lowercase()
    val armLabel = p.string("arm")
    if (armLabel != null && (strategyRaw != null || sourcesSpec != null)) {
        usageError("ls: arm= selects one catalog arm and is mutually exclusive with strategy=/sources=")
    }
    if (armLabel != null && armLabel !in LsCatalog.labels()) {
        usageError("ls: arm=`$armLabel` is not a catalog arm (have ${LsCatalog.labels().joinToString()})")
    }
    val strategyName = strategyRaw ?: if (sourcesSpec != null) "bare" else "auto"

    rejectIneffectiveNumerics(
        canonicalBase(strategyName),
        acceptance.map { it.value.lowercase() }.toSet(),
        provided = listOfNotNull(
            noiseRaw?.let { "noise" },
            cbRaw?.let { "cb" },
            skewRaw?.let { "skew-alpha" },
            initTempRaw?.let { "initial-temp" },
            coolRateRaw?.let { "cooling-rate" },
            minTempRaw?.let { "min-temp" },
            smoothProbRaw?.let { "smooth-prob" },
            smoothFactorRaw?.let { "smooth-factor" },
            tabuRaw?.let { "tabu-tenure" },
        ),
    )

    fun edit(recipe: LsRecipe) = applyEdits(
        recipe,
        sources,
        scoring,
        acceptance,
        restart,
        noiseRaw,
        cb,
        skewAlpha,
    ) { Geometric(initTemp, coolRate, minTemp) }

    val pool: List<() -> LsRecipe>? = when {
        armLabel != null -> listOf({ edit(LsCatalog.byLabel(armLabel)) })

        strategyName == "auto" && !hasEdits -> null

        strategyName == "auto" -> LsCatalog.factories().map { factory -> { edit(factory()) } }

        strategyName == "bare" -> {
            if (sources.isEmpty()) usageError("ls: strategy=bare needs a sources= spec")
            listOf({ edit(bareRecipe(tabu)) })
        }

        else -> {
            val base =
                namedFactory(strategyName, noiseRaw, cb, initTemp, coolRate, minTemp, smoothProb, smoothFactor, tabu)
            listOf({ edit(base()) })
        }
    }
    return LsResolution(pool, dryRunSolver)
}

/** Canonical base name for consumption checks — collapses the `strategy=` aliases. */
private fun canonicalBase(strategyName: String): String = when (strategyName) {
    "annealing" -> "sa"
    "feasibility-jump", "feasibilityjump" -> "fjump"
    else -> strategyName
}

private val LS_KNOWN_BASES = setOf("auto", "cbls", "walksat", "probsat", "sa", "fjump", "bare")
private val LS_TABU_BASES = setOf("cbls", "walksat", "probsat", "sa", "bare")

/** The [provided] numeric knobs that the resolved [canonical] base + [acceptanceValues] edits can't
 *  consume (e.g. `noise` on the curated pool with no walksat acceptance). An unknown base consumes
 *  nothing here — its own factory reports the bad name. */
internal fun ineffectiveNumerics(
    canonical: String,
    acceptanceValues: Set<String>,
    provided: List<String>,
): List<String> {
    if (canonical !in LS_KNOWN_BASES) return emptyList()
    val consumed = buildSet {
        if (canonical == "cbls" || canonical == "walksat" || "walksat" in acceptanceValues) add("noise")
        if (canonical == "cbls") {
            add("smooth-prob")
            add("smooth-factor")
        }
        if (canonical == "probsat" || "probsat" in acceptanceValues) add("cb")
        if ("skew" in acceptanceValues) add("skew-alpha")
        if (canonical == "sa" || "sa" in acceptanceValues) {
            add("initial-temp")
            add("cooling-rate")
            add("min-temp")
        }
        if (canonical in LS_TABU_BASES) add("tabu-tenure")
    }
    return provided.filterNot { it in consumed }
}

/** Reject numeric knobs the resolved base + acceptance edits can't consume, rather than silently
 *  ignoring them. */
private fun rejectIneffectiveNumerics(canonical: String, acceptanceValues: Set<String>, provided: List<String>) {
    val unconsumed = ineffectiveNumerics(canonical, acceptanceValues, provided)
    if (unconsumed.isNotEmpty()) {
        usageError(
            "ls: ${unconsumed.joinToString()} set but no axis consumes ${if (unconsumed.size == 1) "it" else "them"} " +
                "(strategy=$canonical); set the matching strategy or acceptance",
        )
    }
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
    lsPool: List<() -> LsRecipe>? = null,
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
        lsPool = lsPool,
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
