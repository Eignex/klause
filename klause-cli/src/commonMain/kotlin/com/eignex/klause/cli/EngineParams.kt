package com.eignex.klause.cli

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackRecipe
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
import com.eignex.klause.localsearch.strategy.FeasibleDescent
import com.eignex.klause.localsearch.strategy.LocalSearchRecipe
import com.eignex.klause.localsearch.strategy.ProbSat
import com.eignex.klause.localsearch.strategy.SimulatedAnnealing
import com.eignex.klause.localsearch.strategy.SourceDrivenStrategy
import com.eignex.klause.localsearch.strategy.WalkSat
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.portfolio.BacktrackCatalog
import com.eignex.klause.portfolio.EngineMix
import com.eignex.klause.portfolio.Kind
import com.eignex.klause.portfolio.LocalSearchCatalog
import com.eignex.klause.portfolio.LsRecipeSweep
import com.eignex.klause.portfolio.PortfolioScenario

/**
 * Engine tuning knobs passed as repeatable `--param key=value` flags. (`-p` is NOT an
 * alias: it is the MiniZinc-standard parallelism flag.)
 * Each engine consumes the keys it understands; an unrecognised or malformed key is a
 * hard usage error (exit 2) so typos never silently fall back to defaults.
 *
 * Keys per engine:
 *  - `cp`: `bt-arm=label,label` pins named catalog arms, or the per-solver overrides `seed`,
 *    `max-decisions`, `luby`, `phase-saving`, `max-learned`, `lbd-glue`, `pb-learning`, `var-selector`
 *    (see [VarSelectorKind]), `val-selector` (see [ValSelectorKind]), … resolve a one-arm pool
 *    (single-solver heuristic A/B); plus the portfolio knobs below
 *  - `ls`: `strategy` (base `auto` (curated pool, default) | `cbls|feasibilityjump|walksat|probsat|sa`
 *    | `bare` | `sweep`, the full recipe cross-product as the arm pool for the recalibration campaign,
 *    which takes no edits), or `arm=<catalog-label>` to run one curated arm in isolation,
 *    plus per-axis edits applied across the pool — `sources` (bare force-exactly list or
 *    `+`/`-` add/remove), `scoring` (`weighted|raw|break`), `acceptance`
 *    (`greedy|walksat|probsat|skew|sa`), `restart` (`fixed|luby|perturb`); any token may carry an
 *    arm-family selector (`cbls.break`). Axis numerics `noise`/`cb`/`skew-alpha`/`initial-temp`/
 *    `cooling-rate`/`min-temp`/`smooth-prob`/`smooth-factor`/`tabu-tenure`; portfolio knobs `arms`,
 *    `ls`, `bt`, `seed`, `lambda`; `dry-run` lists the resolved arms instead of solving
 *  - `portfolio`: `arms`, `ls`, `bt`, `seed`, `lambda`; `bt-arm=label,label` (cp/mixed only) pins the
 *    backtrack pool to named catalog arms, the backtrack analogue of `ls`'s `arm=`
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

    /** Consume and validate the var-selector [key] into its [VarSelectorKind] (the instance is built
     *  fresh per worker via [VarSelectorKind.selector]); null when absent. */
    fun varSelectorKind(key: String): VarSelectorKind? = map.remove(key)?.let { raw ->
        VarSelectorKind.fromId(raw) ?: fail("engine param `$key` expects ${VarSelectorKind.ids()}, got `$raw`")
    }

    /** Consume and validate the val-selector [key] into its [ValSelectorKind]; null when absent. */
    fun valSelectorKind(key: String): ValSelectorKind? = map.remove(key)?.let { raw ->
        ValSelectorKind.fromId(raw) ?: fail("engine param `$key` expects ${ValSelectorKind.ids()}, got `$raw`")
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

/** The backtrack override keys consumed by [backtrackOverride], excluding `seed` (owned by the naked
 *  engine / the portfolio scenario) — the set that, when present, edits the `cp` arm pool. */
internal val BACKTRACK_OVERRIDE_KEYS = listOf(
    "max-decisions", "luby", "adaptive-restart", "ema-restart", "mode-switching-restart", "phase-saving",
    "target-phasing", "solution-phasing", "rephase-interval", "max-learned", "lbd-glue", "tiered-db",
    "mid-lbd", "vivification", "vivify-batch", "subsumption", "subsume-batch", "inprocessing-cadence",
    "lp-objective-cone", "lp-auto-off-reprobe",
    "lp-knapsack-lagrangian", "lp-component-split", "pb-learning", "pb-objective-cutoff", "objective-guided-values",
    "var-selector", "val-selector",
)

/** Read the backtrack `--param` overrides in [BACKTRACK_OVERRIDE_KEYS] **once** (consuming them) into a
 *  reusable [BacktrackParams] edit — applied to *each arm* of a pool the way the `ls` axis edits are, so
 *  `-e cp -p8 --param var-selector=vsids` still runs a full 8-worker portfolio with the override pinned
 *  across it (the arms keep their own seed/lp/luby diversity). Selectors are rebuilt **per worker** (the
 *  edit closure constructs a fresh instance from the arm's seed), so parallel arms never share mutable
 *  heuristic state. `seed` is left to the caller. [allowSelectors] gates the `var-selector`/`val-selector`
 *  keys: the annotation-following `fixed` engine passes `false` (the annotation decides the heuristic).
 *  Null when no override key is present. */
internal fun backtrackOverride(p: EngineParams, allowSelectors: Boolean): ((BacktrackParams) -> BacktrackParams)? {
    val maxDecisions = p.long("max-decisions")
    val luby = p.long("luby")
    val adaptiveRestart = p.bool("adaptive-restart")
    val emaRestart = p.bool("ema-restart")
    val modeSwitchingRestart = p.bool("mode-switching-restart")
    val phaseSaving = p.bool("phase-saving")
    val targetPhasing = p.bool("target-phasing")
    val solutionPhasing = p.bool("solution-phasing")
    val rephaseInterval = p.long("rephase-interval")
    val maxLearned = p.int("max-learned")
    val lbdGlue = p.int("lbd-glue")
    val tieredDb = p.bool("tiered-db")
    val midLbd = p.int("mid-lbd")
    val vivification = p.bool("vivification")
    val vivifyBatch = p.int("vivify-batch")
    val subsumption = p.bool("subsumption")
    val subsumeBatch = p.int("subsume-batch")
    val inprocessingCadence = p.int("inprocessing-cadence")
    val lpCone = p.bool("lp-objective-cone")
    val lpAutoOff = p.bool("lp-auto-off-reprobe")
    val lpKnapsack = p.bool("lp-knapsack-lagrangian")
    val lpComponentSplit = p.bool("lp-component-split")
    val lpBranching = p.bool("lp-branching")
    val pbLearning = p.bool("pb-learning")
    val pbObjectiveCutoff = p.bool("pb-objective-cutoff")
    val objectiveGuidedValues = p.bool("objective-guided-values")
    val varKind = if (allowSelectors) p.varSelectorKind("var-selector") else null
    val valKind = if (allowSelectors) p.valSelectorKind("val-selector") else null
    val scalars = listOf(
        maxDecisions, luby, adaptiveRestart, emaRestart, modeSwitchingRestart, phaseSaving, targetPhasing,
        solutionPhasing, rephaseInterval, maxLearned, lbdGlue, tieredDb, midLbd, vivification, vivifyBatch,
        subsumption, subsumeBatch, inprocessingCadence,
        lpCone, lpAutoOff, lpKnapsack, lpComponentSplit, lpBranching, pbLearning, pbObjectiveCutoff,
        objectiveGuidedValues,
    )
    if (scalars.all { it == null } && varKind == null && valKind == null) return null
    return { base ->
        var out = base
        maxDecisions?.let { out = out.copy(maxDecisions = it) }
        luby?.let { out = out.copy(lubyRestartBase = it) }
        adaptiveRestart?.let { out = out.copy(adaptiveRestart = it) }
        emaRestart?.let { out = out.copy(emaRestart = it) }
        modeSwitchingRestart?.let { out = out.copy(modeSwitchingRestart = it) }
        phaseSaving?.let { out = out.copy(phaseSaving = it) }
        targetPhasing?.let { out = out.copy(targetPhasing = it) }
        solutionPhasing?.let { out = out.copy(solutionPhasing = it) }
        rephaseInterval?.let { out = out.copy(rephaseInterval = it) }
        maxLearned?.let { out = out.copy(maxLearnedClauses = it) }
        lbdGlue?.let { out = out.copy(lbdGlueThreshold = it) }
        tieredDb?.let { out = out.copy(tieredLearnedDb = it) }
        midLbd?.let { out = out.copy(midLbdThreshold = it) }
        vivification?.let { out = out.copy(vivification = it) }
        vivifyBatch?.let { out = out.copy(vivifyBatch = it) }
        subsumption?.let { out = out.copy(subsumption = it) }
        subsumeBatch?.let { out = out.copy(subsumeBatch = it) }
        inprocessingCadence?.let { out = out.copy(inprocessingCadence = it) }
        lpCone?.let { out = out.copy(lpPlan = out.lpPlan.copy(objectiveCone = it)) }
        lpAutoOff?.let { out = out.copy(lpPlan = out.lpPlan.copy(autoOffReprobe = it)) }
        lpKnapsack?.let { out = out.copy(lpPlan = out.lpPlan.copy(knapsackLagrangian = it)) }
        lpComponentSplit?.let { out = out.copy(lpPlan = out.lpPlan.copy(componentSplit = it)) }
        lpBranching?.let { out = out.copy(lpPlan = out.lpPlan.copy(branching = it)) }
        pbLearning?.let { out = out.copy(pbLearning = it) }
        pbObjectiveCutoff?.let { out = out.copy(pbObjectiveCutoff = it) }
        objectiveGuidedValues?.let { out = out.copy(objectiveGuidedValues = it) }
        varKind?.let { out = out.copy(variableSelector = it.selector(out.randomSeed)) }
        valKind?.let { out = out.copy(valueSelector = it.selector()) }
        out
    }
}

/** Apply `--param` overrides for the naked `fixed` backtrack solve on top of [base] and reject any
 *  leftover keys. Selector keys are not accepted (the annotation decides the heuristic — free-search
 *  heuristic A/B lives on `-e cp`). */
internal fun applyBacktrackParams(base: BacktrackParams, p: EngineParams): BacktrackParams {
    var out = base
    p.long("seed")?.let { out = out.copy(randomSeed = it) }
    backtrackOverride(p, allowSelectors = false)?.let { out = it(out) }
    p.finish("cp", "seed, ${BACKTRACK_OVERRIDE_KEYS.dropLast(2).joinToString()}")
    return out
}

/** Resolved local-search arm pool for the `ls` engine: per-arm factories (a *fresh* recipe per slot,
 *  so parallel workers never share mutable strategy state). A null [pool] means the default curated
 *  pool — the portfolio builds it unchanged. [dryRunSolver] short-circuits the solve to print the
 *  resolved arm pool (the solver configuration) instead of solving. [forceArms], when set, pins the
 *  worker count to the pool size so **every** resolved recipe runs as its own arm — the `strategy=sweep`
 *  campaign, where the bandit must schedule the whole cross-product rather than an `autoArms` prefix. */
internal class LsResolution(
    val pool: List<() -> LocalSearchRecipe>?,
    val dryRunSolver: Boolean,
    val forceArms: Int? = null,
)

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
): () -> LocalSearchRecipe = when (name) {
    "cbls" -> {
        {
            LocalSearchRecipe(
                "cbls",
                cblsStrategy(noise, smoothProb, smoothFactor, tabu),
                optimizeStrategy = cblsStrategy(noise, smoothProb, smoothFactor, tabu),
            )
        }
    }

    "feasibilityjump", "feasibility-jump", "fjump" -> {
        { LocalSearchRecipe("fjump", FeasibilityJump()) }
    }

    "walksat" -> {
        { LocalSearchRecipe("walksat", WalkSat(noise = noise ?: 0.5, tabu = tabu)) }
    }

    "probsat" -> {
        { LocalSearchRecipe("probsat", ProbSat(cb = cb, tabu = tabu)) }
    }

    "sa", "annealing" -> {
        // The unified minimize path (both halves the optimizer form, independent schedules) so SA
        // anneals on the objective at feasibility like the catalog sa/* arms, rather than being
        // ratcheted as a plain finder on a COP.
        {
            LocalSearchRecipe(
                "sa",
                SimulatedAnnealing.optimizer(Geometric(initTemp, coolRate, minTemp), tabu = tabu),
                optimizeStrategy = SimulatedAnnealing.optimizer(Geometric(initTemp, coolRate, minTemp), tabu = tabu),
            )
        }
    }

    else -> usageError("ls: strategy expects auto|cbls|feasibilityjump|walksat|probsat|sa|bare, got `$name`")
}

/** A bare driver with no preset axes; its sources are supplied by a force-exactly `sources=` spec. */
private fun bareRecipe(tabu: TabuFilter): LocalSearchRecipe = LocalSearchRecipe(
    "bare",
    SourceDrivenStrategy(sources = emptyList(), tabu = tabu, feasibleDescent = FeasibleDescent.RatchetAsConstraint),
)

private fun applyEdits(
    recipe: LocalSearchRecipe,
    sources: List<AxisToken>,
    scoring: List<AxisToken>,
    acceptance: List<AxisToken>,
    restart: List<AxisToken>,
    noise: Double?,
    cb: Double,
    skewAlpha: Double,
    saTemperature: () -> Geometric,
): LocalSearchRecipe {
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
 * `auto` (default; the curated [LocalSearchCatalog] pool), a named recipe (`cbls`/`feasibilityjump`/`walksat`/
 * `probsat`/`sa`), or `bare` (a driver whose sources come from a force-exactly `sources=` spec). The
 * axis keys then *edit* the base across all matching arms, presolve-style: `sources=` takes a bare
 * force-exactly list or `+`/`-` add/remove; `scoring`/`acceptance`/`restart` set a single value. Any
 * token may carry an arm-family selector (`cbls.break`) so an edit scopes to part of the pool. Plain
 * `-e ls` with no overrides keeps the curated pool unchanged (a null pool). `dry-run-solver=on` lists
 * the resolved arms instead of solving.
 */
internal fun resolveLocalSearchRecipes(p: EngineParams): LsResolution {
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
    if (armLabel != null && armLabel !in LocalSearchCatalog.labels()) {
        usageError("ls: arm=`$armLabel` is not a catalog arm (have ${LocalSearchCatalog.labels().joinToString()})")
    }
    // `strategy=sweep` is the exploration campaign: the entire cross-product as the arm pool, with
    // forceArms pinning the worker count to it so the bandit schedules every recipe rather than an
    // autoArms prefix. It *is* the whole space, so an arm= or an axis edit would contradict it.
    if (strategyRaw == "sweep") {
        if (armLabel != null || hasEdits || sourcesSpec != null) {
            usageError("ls: strategy=sweep runs the full recipe cross-product and takes no arm=/sources=/axis edits")
        }
        val sweep = LsRecipeSweep.pool()
        return LsResolution(sweep, dryRunSolver, forceArms = sweep.size)
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

    fun edit(recipe: LocalSearchRecipe) = applyEdits(
        recipe,
        sources,
        scoring,
        acceptance,
        restart,
        noiseRaw,
        cb,
        skewAlpha,
    ) { Geometric(initTemp, coolRate, minTemp) }

    val pool: List<() -> LocalSearchRecipe>? = when {
        armLabel != null -> listOf({ edit(LocalSearchCatalog.byLabel(armLabel)) })

        strategyName == "auto" && !hasEdits -> null

        // The CLI `auto` pool is the COP superset (all arms); the portfolio applies per-kind ordering
        // to its own curated default (an explicit CLI pool is used as-is, like the backtrack side).
        strategyName == "auto" -> LocalSearchCatalog.factories(Kind.COP).map { factory -> { edit(factory()) } }

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
    lsPool: List<() -> LocalSearchRecipe>? = null,
    btPool: List<() -> BacktrackRecipe>? = null,
    annotationArm: BacktrackParams? = null,
): PortfolioScenario {
    val seed = p.long("seed") ?: fallbackSeed ?: 1L
    val lambda = p.double("lambda") ?: 1.0
    val armsParam = p.int("arms")
    val ls = p.int("ls")
    val bt = p.int("bt")
    val clauseShareLbd = p.int("clause-share-lbd")
    val clauseShareLen = p.int("clause-share-len")
    p.finish("portfolio", "arms, ls, bt, seed, lambda, clause-share-lbd, clause-share-len")
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
    var scenario = PortfolioScenario(
        cores = cores,
        arms = maxOf(arms, cores),
        kind = kind,
        engine = engine,
        seed = seed,
        lsLambda = lambda,
        lpCeiling = lpCeiling,
        lsPool = lsPool,
        btPool = btPool,
        annotationArm = annotationArm,
    )
    clauseShareLbd?.let { scenario = scenario.copy(clauseShareMaxLbd = it) }
    clauseShareLen?.let { scenario = scenario.copy(clauseShareMaxLen = it) }
    return scenario
}

/**
 * Resolve the `cp`/`mixed` engine's backtrack arm pool from `--param`, the backtrack analogue of
 * [resolveLocalSearchRecipes]. Two mutually-exclusive forms:
 *  - `bt-arm=label,label` pins named [BacktrackCatalog] arms, each validated for [kind] (so a CSP
 *    rejects the COP-only LP/LinUCB arms); an unknown label is a hard usage error.
 *  - the per-solver override keys ([BACKTRACK_OVERRIDE_KEYS] — `var-selector`/`val-selector`/`luby`/…)
 *    *edit the curated pool* ([backtrackOverride]): every arm keeps its own seed/lp/luby diversity with
 *    the override pinned across it, so `-p8 -e cp --param var-selector=vsids` stays a full 8-worker pool
 *    (the arms converge to one only if the overrides pin every distinguishing axis). A one-solver A/B
 *    is this same pool at `-p1`.
 *
 * `null` when neither is set — the curated pool is used. A resolved pool is the *set* of arms; the
 * worker *count* still comes from `arms=`/`bt=`/the default and wraps over it, exactly as `lsPool` does.
 */
internal fun resolveBtRecipes(p: EngineParams, kind: Kind): List<() -> BacktrackRecipe>? {
    val btArm = p.string("bt-arm")
    val edit = backtrackOverride(p, allowSelectors = true)
    if (btArm != null && edit != null) {
        usageError(
            "cp: bt-arm= pins catalog arms and is mutually exclusive with per-solver overrides " +
                "(${BACKTRACK_OVERRIDE_KEYS.joinToString()})",
        )
    }
    if (btArm != null) {
        val labels = btArm.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (labels.isEmpty()) usageError("bt-arm: expected a comma-separated list of backtrack arm labels")
        val known = BacktrackCatalog.labels(kind).toSet()
        for (label in labels) {
            if (label !in known) {
                usageError("bt-arm: `$label` is not a backtrack arm for this problem (have ${known.joinToString()})")
            }
        }
        return labels.map { label -> { BacktrackCatalog.byLabel(label) } }
    }
    if (edit == null) return null
    // Edit every curated arm, exactly as resolveLocalSearchRecipes edits its pool. The edit rebuilds selectors
    // per worker (fresh mutable state), so the wrapped factory stays safe across parallel slots.
    return BacktrackCatalog.factories(kind).map { factory -> { editRecipe(factory(), edit) } }
}

/** Wrap [recipe] so [edit] is applied to the [BacktrackParams] it builds — per worker, so selector
 *  state stays unshared. Preserves the arm's label for telemetry / the `dry-run-solver` listing. */
private fun editRecipe(recipe: BacktrackRecipe, edit: (BacktrackParams) -> BacktrackParams): BacktrackRecipe =
    BacktrackRecipe(recipe.label) { seed, onEvent -> edit(recipe.build(seed, onEvent)) }

/** Auto-tuned default arm-pool size, scaling with the core count: [ARMS_PER_CORE] arms per
 *  core — always *more* arms than cores, so the bandit (single core) / parallel race always has a
 *  pool to draw on — floored at [PortfolioScenario.DEFAULT_ARMS] so the single-core free track still
 *  gets a real pool. Overridable via `--param arms=N`. */
internal fun autoArms(cores: Int): Int = maxOf(PortfolioScenario.DEFAULT_ARMS, cores * ARMS_PER_CORE)

/** Default arms-per-core oversubscription factor for [autoArms]. */
private const val ARMS_PER_CORE = 2

/** `var-selector` `--param` values for the `cp` engine's override pool (each maps to a [VariableSelector]).
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

    /** A **fresh** selector instance (constructed per worker so parallel arms never share mutable
     *  heuristic state); [seed] feeds the LinUCB RNG so an A/B is reproducible under the run seed. */
    fun selector(seed: Long?): VariableSelector = when (this) {
        VSIDS -> Vsids()
        CHB -> Chb()
        LINUCB -> RegressionVariableSelector.linUcb(seed = seed ?: 0L)
        RANDOM -> RandomVariable
        INPUT_ORDER -> InputOrder
        SMALLEST_DOMAIN -> SmallestDomain
        LARGEST_DOMAIN -> LargestDomain
        SMALLEST_LOWER_BOUND -> SmallestLowerBound
        LARGEST_UPPER_BOUND -> LargestUpperBound
        DOMAIN_MAX_REGRET -> DomainMaxRegret
    }

    companion object {
        private val byId = entries.associateBy { it.id }
        fun fromId(token: String): VarSelectorKind? = byId[token.trim().lowercase()]
        fun ids(): String = entries.joinToString("|") { it.id }
    }
}

/** `val-selector` `--param` values for the `cp` engine's override pool (each maps to a [ValueSelector]).
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

    /** A **fresh** value selector instance (constructed per worker so parallel arms never share
     *  mutable state, e.g. the random selector's RNG). */
    fun selector(): ValueSelector = when (this) {
        RANDOM -> IndomainRandom
        MIN -> IndomainMin
        MAX -> IndomainMax
        MIDDLE -> IndomainMiddle
        MEDIAN -> IndomainMedian
        SPLIT -> IndomainSplit
        SOLUTION_GUIDED -> SolutionGuided(IndomainMin)
    }

    companion object {
        private val byId = entries.associateBy { it.id }
        fun fromId(token: String): ValSelectorKind? = byId[token.trim().lowercase()]
        fun ids(): String = entries.joinToString("|") { it.id }
    }
}
