@file:Suppress(
    "MaxLineLength",
    "UndocumentedPublicClass",
    "UndocumentedPublicFunction",
    "UndocumentedPublicProperty",
)

package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackRecipe
import com.eignex.klause.backtrack.NodeBudget
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
import com.eignex.klause.backtrack.selector.MaxSd
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
import com.eignex.klause.localsearch.ObjectiveGuidance
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
import com.eignex.klause.lp.engine.LpZeroObjectivePricing
import com.eignex.klause.meta.coreguided.CoreRefinementMode
import com.eignex.klause.portfolio.BacktrackCatalog
import com.eignex.klause.portfolio.EngineMix
import com.eignex.klause.portfolio.Kind
import com.eignex.klause.portfolio.LocalSearchCatalog
import com.eignex.klause.portfolio.PortfolioScenario

/** Invalid solver-policy configuration. */
class PipelineConfigException(message: String) : RuntimeException(message)

fun pipelineConfigError(message: String): Nothing = throw PipelineConfigException(message)

/**
 * Engine tuning knobs passed as repeatable `--param key=value` flags. (`-p` is NOT an
 * alias: it is the MiniZinc-standard parallelism flag.)
 * Each engine consumes the keys it understands; an unrecognised or malformed key is a
 * hard usage error (exit 2) so typos never silently fall back to defaults.
 *
 * Keys per engine:
 *  - `cp`: `bt-arm=label,label` pins named catalog arms; the per-solver overrides `seed`,
 *    `max-decisions`, `luby`, `phase-saving`, `max-learned`, `lbd-glue`, `pb-learning`, `var-selector`
 *    (see [VarSelectorKind]), `val-selector` (see [ValSelectorKind]), … edit **every** arm of whatever
 *    pool results. The two compose, so `bt-arm=X` with an override is the single-solver heuristic A/B —
 *    one arm, one knob moved — where an override alone spreads that knob across the curated pool and
 *    dilutes it with arms that cannot express it; plus the portfolio knobs below
 *  - `ls`: `strategy` (base `auto` (curated pool, default) | `cbls|feasibilityjump|walksat|probsat|sa`
 *    | `bare`), or `arm=<catalog-label>` to run one curated arm in isolation,
 *    plus per-axis edits applied across the pool — `sources` (bare force-exactly list or
 *    `+`/`-` add/remove), `scoring` (`weighted|raw|break`), `acceptance`
 *    (`greedy|walksat|probsat|skew|sa`), `restart` (`fixed|luby|perturb`); any token may carry an
 *    arm-family selector (`cbls.break`). Axis numerics `noise`/`cb`/`skew-alpha`/`initial-temp`/
 *    `cooling-rate`/`min-temp`/`smooth-prob`/`smooth-factor`/`tabu-tenure`; portfolio knobs `arms`,
 *    `ls`, `bt`, `seed`, `lambda`; `dry-run-solver` lists the resolved arms instead of solving
 *  - `portfolio`: `arms`, `ls`, `bt`, `seed`, `lambda`; `bt-arm=label,label` (cp/mixed only) pins the
 *    backtrack pool to named catalog arms, the backtrack analogue of `ls`'s `arm=`
 */
class EngineParams(pairs: List<String>) {
    private val map: MutableMap<String, String> = mutableMapOf()

    init {
        for (pair in pairs) {
            val eq = pair.indexOf('=')
            if (eq <= 0) fail("malformed engine param `$pair`; expected key=value")
            map[pair.take(eq)] = pair.substring(eq + 1)
        }
    }

    internal constructor(key: String, value: String) : this(emptyList()) {
        map[key] = value
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
    internal fun varSelectorKind(key: String): VarSelectorKind? = map.remove(key)?.let { raw ->
        VarSelectorKind.fromId(raw) ?: fail("engine param `$key` expects ${VarSelectorKind.ids()}, got `$raw`")
    }

    /** Consume and validate the val-selector [key] into its [ValSelectorKind]; null when absent. */
    internal fun valSelectorKind(key: String): ValSelectorKind? = map.remove(key)?.let { raw ->
        ValSelectorKind.fromId(raw) ?: fail("engine param `$key` expects ${ValSelectorKind.ids()}, got `$raw`")
    }

    /** Call after an engine consumed its keys: anything left over is a typo or a key for
     *  another engine — reject loudly rather than ignore. */
    fun finish(engine: String, valid: String) {
        if (map.isNotEmpty()) {
            fail("unknown $engine engine param(s) ${map.keys.joinToString()}; valid keys: $valid")
        }
    }

    internal fun reader(): ExecutionOptionReader = object : ExecutionOptionReader() {
        override fun <T> get(option: ExecutionOption<T>): T? = option.decode(this@EngineParams)
        override fun finish(engine: String, valid: String) = this@EngineParams.finish(engine, valid)
    }

    private fun fail(msg: String): Nothing {
        pipelineConfigError(msg)
    }
}

/** The backtrack override keys consumed by [backtrackOverride], excluding `seed` (owned by the naked
 *  engine / the portfolio scenario) — the set that, when present, edits the `cp` arm pool. */
val BACKTRACK_OVERRIDE_KEYS = listOf(
    "max-decisions", "luby", "adaptive-restart", "ema-restart", "mode-switching-restart", "phase-saving",
    "target-phasing", "solution-phasing", "rephase-interval", "max-learned", "lbd-glue", "tiered-db",
    "learned-memory-bytes", "learned-memory-work", "learned-memory-scratch-bytes",
    "mid-lbd", "vivification", "vivify-batch", "subsumption", "subsume-batch", "inprocessing-cadence",
    "root-simplification", "root-simplify-batch", "root-probe-batch", "root-probe-work",
    "root-rebuild-capture-visits",
    "inprocessing-work", "inprocessing-root-work", "inprocessing-probe-work", "inprocessing-subsume-work",
    "inprocessing-vivify-work", "inprocessing-interval", "inprocessing-growth", "inprocessing-cooldown",
    "lp-objective-cone", "lp-auto-off-reprobe",
    "lp-knapsack-lagrangian", "lp-component-split", "lp-component-ownership", "lp-bound-max-pivots", "lp-adaptive-work",
    "lp-branching", "lp-directional-gains", "lp-variable-shaving", "lp-prune-hulls", "lp-root-cuts",
    "pb-learning", "pb-objective-cutoff", "propagation-attribution", "historical-table-reasons",
    "objective-guided-values",
    "var-selector", "val-selector",
)

private val BACKTRACK_SELECTOR_KEYS = setOf("var-selector", "val-selector")

/** Read the backtrack `--param` overrides in [BACKTRACK_OVERRIDE_KEYS] **once** (consuming them) into a
 *  reusable [BacktrackParams] edit — applied to *each arm* of a pool the way the `ls` axis edits are, so
 *  `-e cp -p8 --param var-selector=vsids` still runs a full 8-worker portfolio with the override pinned
 *  across it (the arms keep their own seed/lp/luby diversity). Selectors are rebuilt **per worker** (the
 *  edit closure constructs a fresh instance from the arm's seed), so parallel arms never share mutable
 *  heuristic state. `seed` is left to the caller. [allowSelectors] gates the `var-selector`/`val-selector`
 *  keys: `cp`/`mixed` always pass `true` (inherently free-search); the `fixed` engine passes `false` only
 *  while a source annotation selects its heuristic, and `true` otherwise (no heuristic to preserve).
 *  Null when no override key is present. */
fun backtrackOverride(p: EngineParams, allowSelectors: Boolean): ((BacktrackParams) -> BacktrackParams)? =
    backtrackOverride(p.reader(), allowSelectors)

internal fun backtrackOverride(p: ExecutionOptionReader, allowSelectors: Boolean): ((BacktrackParams) -> BacktrackParams)? {
    val maxDecisions = p[ExecutionOption.maxDecisions]
    val luby = p[ExecutionOption.luby]
    val adaptiveRestart = p[ExecutionOption.adaptiveRestart]
    val emaRestart = p[ExecutionOption.emaRestart]
    val modeSwitchingRestart = p[ExecutionOption.modeSwitchingRestart]
    val phaseSaving = p[ExecutionOption.phaseSaving]
    val targetPhasing = p[ExecutionOption.targetPhasing]
    val solutionPhasing = p[ExecutionOption.solutionPhasing]
    val rephaseInterval = p[ExecutionOption.rephaseInterval]
    val maxLearned = p[ExecutionOption.maxLearned]
    val learnedMemoryBytes = p[ExecutionOption.learnedMemoryBytes]
    val learnedMemoryWork = p[ExecutionOption.learnedMemoryWork]
    val learnedMemoryScratchBytes = p[ExecutionOption.learnedMemoryScratchBytes]
    val lbdGlue = p[ExecutionOption.lbdGlue]
    val tieredDb = p[ExecutionOption.tieredDb]
    val midLbd = p[ExecutionOption.midLbd]
    val vivification = p[ExecutionOption.vivification]
    val vivifyBatch = p[ExecutionOption.vivifyBatch]
    val subsumption = p[ExecutionOption.subsumption]
    val subsumeBatch = p[ExecutionOption.subsumeBatch]
    val inprocessingCadence = p[ExecutionOption.inprocessingCadence]
    val rootSimplification = p[ExecutionOption.rootSimplification]
    val rootSimplifyBatch = p[ExecutionOption.rootSimplifyBatch]
    val rootRebuildCaptureVisits = p[ExecutionOption.rootRebuildCaptureVisits]
    val rootProbeBatch = p[ExecutionOption.rootProbeBatch]
    val rootProbeWork = p[ExecutionOption.rootProbeWork]
    val inprocessingWork = p[ExecutionOption.inprocessingWork]
    val inprocessingRootWork = p[ExecutionOption.inprocessingRootWork]
    val inprocessingProbeWork = p[ExecutionOption.inprocessingProbeWork]
    val inprocessingSubsumeWork = p[ExecutionOption.inprocessingSubsumeWork]
    val inprocessingVivifyWork = p[ExecutionOption.inprocessingVivifyWork]
    val inprocessingInterval = p[ExecutionOption.inprocessingInterval]
    val inprocessingGrowth = p[ExecutionOption.inprocessingGrowth]
    val inprocessingCooldown = p[ExecutionOption.inprocessingCooldown]
    val lpCone = p[ExecutionOption.lpObjectiveCone]
    val lpAutoOff = p[ExecutionOption.lpAutoOffReprobe]
    val lpKnapsack = p[ExecutionOption.lpKnapsackLagrangian]
    val lpComponentSplit = p[ExecutionOption.lpComponentSplit]
    val lpComponentOwnership = p[ExecutionOption.lpComponentOwnership]
    val lpBoundMaxPivots = p[ExecutionOption.lpBoundMaxPivots]
    val lpAdaptiveWork = p[ExecutionOption.lpAdaptiveWork]
    val lpBranching = p[ExecutionOption.lpBranching]
    val lpDirectionalGains = p[ExecutionOption.lpDirectionalGains]
    val lpVariableShaving = p[ExecutionOption.lpVariableShaving]
    val lpPruneHulls = p[ExecutionOption.lpPruneHulls]
    val lpRootCuts = p[ExecutionOption.lpRootCuts]
    val pbLearning = p[ExecutionOption.pbLearning]
    val integerRowLearning = p[ExecutionOption.integerRowLearning]
    val propagationAttribution = p[ExecutionOption.propagationAttribution]
    val historicalTableReasons = p[ExecutionOption.historicalTableReasons]
    val costAwarePropagation = p[ExecutionOption.costAwarePropagation]
    val reasonPreflight = p[ExecutionOption.reasonPreflight]
    val pbObjectiveCutoff = p[ExecutionOption.pbObjectiveCutoff]
    val objectiveGuidedValues = p[ExecutionOption.objectiveGuidedValues]
    val varKind = if (allowSelectors) p[ExecutionOption.variableSelector] else null
    val valKind = if (allowSelectors) p[ExecutionOption.valueSelector] else null
    val scalars = listOf(
        maxDecisions, luby, adaptiveRestart, emaRestart, modeSwitchingRestart, phaseSaving, targetPhasing,
        solutionPhasing, rephaseInterval, maxLearned, lbdGlue, tieredDb, midLbd, vivification, vivifyBatch,
        learnedMemoryBytes, learnedMemoryWork, learnedMemoryScratchBytes,
        subsumption, subsumeBatch, inprocessingCadence, rootSimplification, rootSimplifyBatch, rootProbeBatch, rootProbeWork,
        rootRebuildCaptureVisits,
        inprocessingWork, inprocessingRootWork, inprocessingProbeWork, inprocessingSubsumeWork,
        inprocessingVivifyWork, inprocessingInterval, inprocessingGrowth, inprocessingCooldown,
        lpCone, lpAutoOff, lpKnapsack, lpComponentSplit, lpComponentOwnership, lpBoundMaxPivots, lpAdaptiveWork,
        lpBranching, lpVariableShaving, lpPruneHulls, lpRootCuts, pbLearning,
        pbObjectiveCutoff, integerRowLearning, propagationAttribution, historicalTableReasons, costAwarePropagation,
        reasonPreflight,
        objectiveGuidedValues, lpDirectionalGains,
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
        learnedMemoryBytes?.let { out = out.copy(learnedMemoryBytes = it) }
        learnedMemoryWork?.let { out = out.copy(learnedMemoryWork = it) }
        learnedMemoryScratchBytes?.let { out = out.copy(learnedMemoryScratchBytes = it) }
        lbdGlue?.let { out = out.copy(lbdGlueThreshold = it) }
        tieredDb?.let { out = out.copy(tieredLearnedDb = it) }
        midLbd?.let { out = out.copy(midLbdThreshold = it) }
        vivification?.let { out = out.copy(vivification = it) }
        vivifyBatch?.let { out = out.copy(vivifyBatch = it) }
        subsumption?.let { out = out.copy(subsumption = it) }
        subsumeBatch?.let { out = out.copy(subsumeBatch = it) }
        inprocessingCadence?.let { out = out.copy(inprocessingCadence = it) }
        rootSimplification?.let { out = out.copy(rootSimplification = it) }
        rootSimplifyBatch?.let { out = out.copy(rootSimplifyBatch = it) }
        rootRebuildCaptureVisits?.let { out = out.copy(rootRebuildCaptureVisits = it) }
        rootProbeBatch?.let { out = out.copy(rootProbeBatch = it) }
        rootProbeWork?.let { out = out.copy(rootProbeWork = it) }
        inprocessingWork?.let { out = out.copy(inprocessingWork = it) }
        inprocessingRootWork?.let { out = out.copy(inprocessingRootWork = it) }
        inprocessingProbeWork?.let { out = out.copy(inprocessingProbeWork = it) }
        inprocessingSubsumeWork?.let { out = out.copy(inprocessingSubsumeWork = it) }
        inprocessingVivifyWork?.let { out = out.copy(inprocessingVivifyWork = it) }
        inprocessingInterval?.let { out = out.copy(inprocessingInterval = it) }
        inprocessingGrowth?.let { out = out.copy(inprocessingGrowth = it) }
        inprocessingCooldown?.let { out = out.copy(inprocessingCooldown = it) }
        lpCone?.let { out = out.copy(lpPlan = out.lpPlan.copy(objectiveCone = it)) }
        lpAutoOff?.let { out = out.copy(lpPlan = out.lpPlan.copy(autoOffReprobe = it)) }
        lpKnapsack?.let { out = out.copy(lpPlan = out.lpPlan.copy(knapsackLagrangian = it)) }
        lpComponentSplit?.let { out = out.copy(lpPlan = out.lpPlan.copy(componentSplit = it)) }
        lpComponentOwnership?.let { out = out.copy(lpPlan = out.lpPlan.copy(componentOwnership = it)) }
        lpBoundMaxPivots?.let { out = out.copy(lpPlan = out.lpPlan.copy(boundMaxPivots = it)) }
        lpAdaptiveWork?.let { out = out.copy(lpPlan = out.lpPlan.copy(boundAdaptiveWork = it)) }
        lpBranching?.let { out = out.copy(lpPlan = out.lpPlan.copy(branching = it)) }
        lpDirectionalGains?.let { out = out.copy(lpDirectionalGains = it) }
        lpVariableShaving?.let { out = out.copy(lpPlan = out.lpPlan.copy(variableShaving = it)) }
        lpPruneHulls?.let { out = out.copy(lpPlan = out.lpPlan.copy(pruneHulls = it)) }
        lpRootCuts?.let { out = out.copy(lpPlan = out.lpPlan.copy(rootCutHarvest = it)) }
        pbLearning?.let { out = out.copy(pbLearning = it) }
        integerRowLearning?.let { out = out.copy(integerRowLearning = it) }
        propagationAttribution?.let { out = out.copy(propagationAttribution = it) }
        historicalTableReasons?.let { out = out.copy(historicalTableReasons = it) }
        costAwarePropagation?.let { out = out.copy(costAwarePropagation = it) }
        reasonPreflight?.let { out = out.copy(reasonPreflight = it) }
        pbObjectiveCutoff?.let { out = out.copy(pbObjectiveCutoff = it) }
        objectiveGuidedValues?.let { out = out.copy(objectiveGuidedValues = it) }
        varKind?.let { out = out.copy(variableSelector = it.selector(out.randomSeed)) }
        valKind?.let { out = out.copy(valueSelector = it.selector()) }
        out
    }
}

/** Apply `--param` overrides for the annotation-following fixed backtrack solve on top of [base]. */
fun applyBacktrackParams(base: BacktrackParams, p: EngineParams): BacktrackParams =
    applyFixedBacktrackParams(base, p, allowSelectors = false)

/** Apply fixed-route overrides. [allowSelectors] is false only when a source annotation selects the
 *  heuristic; an unannotated finite model has no heuristic to preserve, so its single fixed run accepts
 *  selector overrides without becoming a portfolio. */
internal fun applyFixedBacktrackParams(
    base: BacktrackParams,
    p: EngineParams,
    allowSelectors: Boolean,
): BacktrackParams =
    applyFixedBacktrackParams(base, p.reader(), allowSelectors)

internal fun applyFixedBacktrackParams(
    base: BacktrackParams,
    p: ExecutionOptionReader,
    allowSelectors: Boolean,
): BacktrackParams {
    var out = base
    p[ExecutionOption.seed]?.let { out = out.copy(randomSeed = it) }
    backtrackOverride(p, allowSelectors)?.let { out = it(out) }
    val accepted = if (allowSelectors) BACKTRACK_OVERRIDE_KEYS else BACKTRACK_OVERRIDE_KEYS - BACKTRACK_SELECTOR_KEYS
    p.finish("fixed", "seed, ${accepted.joinToString()}")
    return out
}

/** Resolved local-search arm pool for the `ls` engine: per-arm factories (a *fresh* recipe per slot,
 *  so parallel workers never share mutable strategy state). A null [pool] means the default curated
 *  pool — the portfolio builds it unchanged. [dryRunSolver] short-circuits the solve to print the
 *  resolved arm pool (the solver configuration) instead of solving. */
class LsResolution(val pool: List<() -> LocalSearchRecipe>?, val dryRunSolver: Boolean)

private fun parseScoring(s: String): MoveScoring = when (s.lowercase()) {
    "weighted" -> MoveScoring.Weighted
    "raw" -> MoveScoring.Raw
    "break" -> MoveScoring.Break
    else -> pipelineConfigError("ls: scoring expects weighted|raw|break, got `$s`")
}

private fun parseAcceptance(s: String, noise: Double?, cb: Double, skewAlpha: Double): AcceptanceRule =
    when (s.lowercase()) {
        "greedy" -> AcceptanceRule.Greedy
        "walksat" -> AcceptanceRule.WalkSatNoise(noise ?: 0.5)
        "probsat" -> AcceptanceRule.ProbSat(cb)
        "skew" -> AcceptanceRule.Skew(skewAlpha)
        "sa" -> AcceptanceRule.Metropolis
        else -> pipelineConfigError("ls: acceptance expects greedy|walksat|probsat|skew|sa, got `$s`")
    }

private fun parseRestart(s: String): RestartPolicy = when (s.lowercase()) {
    "fixed" -> FixedCadenceRestart()
    "luby" -> LubyRestart(unit = 200)
    "perturb", "adaptive-perturb" -> AdaptivePerturbationRestart()
    else -> pipelineConfigError("ls: restart expects fixed|luby|perturb, got `$s`")
}

/** Parse a single-valued axis spec into its tokens, rejecting `+`/`-` (only the sources list axis
 *  takes set edits). */
internal fun scalarTokens(spec: String?, axis: String): List<AxisToken> {
    val tokens = spec?.let { AxisEdits.tokens(it) } ?: return emptyList()
    return scalarTokens(tokens, axis)
}

internal fun scalarTokens(tokens: List<AxisToken>, axis: String): List<AxisToken> {
    tokens.firstOrNull { it.op != AxisToken.Op.SET }?.let {
        pipelineConfigError("ls: $axis is single-valued and takes no +/- edit (got `${it.value}`)")
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

    else -> pipelineConfigError("ls: strategy expects auto|cbls|feasibilityjump|walksat|probsat|sa|bare, got `$name`")
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
fun resolveLocalSearchRecipes(p: EngineParams): LsResolution =
    resolveLocalSearchRecipes(p.reader())

internal fun resolveLocalSearchRecipes(p: ExecutionOptionReader): LsResolution {
    val guidance = p[ExecutionOption.objectiveGuidance]?.split(',')?.map { raw ->
        ObjectiveGuidance.entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
            ?: pipelineConfigError("objective-guidance expects canonical|functional|geometric, got `$raw`")
    }
    val dryRunSolver = p[ExecutionOption.dryRunSolver] ?: false
    val noiseRaw = p[ExecutionOption.noise]
    val cbRaw = p[ExecutionOption.cb]
    val skewRaw = p[ExecutionOption.skewAlpha]
    val initTempRaw = p[ExecutionOption.initialTemp]
    val coolRateRaw = p[ExecutionOption.coolingRate]
    val minTempRaw = p[ExecutionOption.minTemp]
    val smoothProbRaw = p[ExecutionOption.smoothProb]
    val smoothFactorRaw = p[ExecutionOption.smoothFactor]
    val tabuRaw = p[ExecutionOption.tabuTenure]
    val cb = cbRaw ?: 2.06
    val skewAlpha = skewRaw ?: 0.0
    val initTemp = initTempRaw ?: 1.0
    val coolRate = coolRateRaw ?: 0.999
    val minTemp = minTempRaw ?: 1e-3
    val smoothProb = smoothProbRaw ?: 0.4
    val smoothFactor = smoothFactorRaw ?: 0.8
    val tabu = TabuFilter(tenure = tabuRaw ?: 10, aspiration = AspirationCriterion.OrImproving)

    val sourcesSpec = p[ExecutionOption.sources]
    val sources = sourcesSpec.orEmpty()
    val scoring = p[ExecutionOption.scoring].orEmpty()
    val acceptance = p[ExecutionOption.acceptance].orEmpty()
    val restart = p[ExecutionOption.restart].orEmpty()
    val hasEdits = sources.isNotEmpty() || scoring.isNotEmpty() || acceptance.isNotEmpty() || restart.isNotEmpty()
    val strategyRaw = p[ExecutionOption.strategy]?.lowercase()
    val armLabel = p[ExecutionOption.localSearchArm]
    if (armLabel != null && strategyRaw != null) {
        pipelineConfigError("ls: arm= selects one catalog arm and is mutually exclusive with strategy=")
    }
    if (armLabel != null && armLabel !in LocalSearchCatalog.labels()) {
        pipelineConfigError(
            "ls: arm=`$armLabel` is not a catalog arm (have ${LocalSearchCatalog.labels().joinToString()})",
        )
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
            if (sources.isEmpty()) pipelineConfigError("ls: strategy=bare needs a sources= spec")
            listOf({ edit(bareRecipe(tabu)) })
        }

        else -> {
            val base =
                namedFactory(strategyName, noiseRaw, cb, initTemp, coolRate, minTemp, smoothProb, smoothFactor, tabu)
            listOf({ edit(base()) })
        }
    }
    val guidedPool = guidance?.let { modes ->
        val selected = pool ?: pipelineConfigError(
            "objective-guidance requires arm=, a named strategy=, or explicit LS axis edits",
        )
        selected.flatMap { factory ->
            modes.map { mode -> { factory().withObjectiveGuidance(mode) } }
        }
    } ?: pool
    return LsResolution(guidedPool, dryRunSolver)
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
fun ineffectiveNumerics(canonical: String, acceptanceValues: Set<String>, provided: List<String>): List<String> {
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
        pipelineConfigError(
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
fun buildPortfolioScenario(
    p: EngineParams,
    fallbackSeed: Long?,
    cores: Int,
    kind: Kind,
    defaultEngine: EngineMix,
    defaultArms: Int,
    lpCeiling: LpConfig = LpConfig.AGGRESSIVE,
    zeroObjectivePricing: LpZeroObjectivePricing = LpZeroObjectivePricing.MIN_BOUND_SUPPORT,
    lsPool: List<() -> LocalSearchRecipe>? = null,
    btPool: List<() -> BacktrackRecipe>? = null,
    annotationArm: BacktrackParams? = null,
    nodeBudget: NodeBudget? = null,
    btEdit: ((BacktrackParams) -> BacktrackParams)? = null,
): PortfolioScenario =
    buildPortfolioScenario(p.reader(), fallbackSeed, cores, kind, defaultEngine, defaultArms, lpCeiling, zeroObjectivePricing, lsPool, btPool, annotationArm, nodeBudget, btEdit)

internal fun buildPortfolioScenario(
    p: ExecutionOptionReader,
    fallbackSeed: Long?,
    cores: Int,
    kind: Kind,
    defaultEngine: EngineMix,
    defaultArms: Int,
    lpCeiling: LpConfig = LpConfig.AGGRESSIVE,
    zeroObjectivePricing: LpZeroObjectivePricing = LpZeroObjectivePricing.MIN_BOUND_SUPPORT,
    lsPool: List<() -> LocalSearchRecipe>? = null,
    btPool: List<() -> BacktrackRecipe>? = null,
    annotationArm: BacktrackParams? = null,
    nodeBudget: NodeBudget? = null,
    btEdit: ((BacktrackParams) -> BacktrackParams)? = null,
): PortfolioScenario {
    val seed = p[ExecutionOption.seed] ?: fallbackSeed ?: 1L
    val lambda = p[ExecutionOption.lambda] ?: 1.0
    val armsParam = p[ExecutionOption.arms]
    val ls = p[ExecutionOption.localSearchArms]
    val bt = p[ExecutionOption.backtrackArms]
    val clauseShareLbd = p[ExecutionOption.clauseShareLbd]
    val clauseShareLen = p[ExecutionOption.clauseShareLen]
    val sliceWork = p[ExecutionOption.sliceWork]
    val maxSliceWork = p[ExecutionOption.sliceMaxWork]
    val sliceGrowth = p[ExecutionOption.sliceGrowth]
    val diagnosticWorkLimit = p[ExecutionOption.portfolioWork]
    val sharingMemory = p[ExecutionOption.sharingMemory] ?: false
    val sharingMemoryBytes = p[ExecutionOption.sharingMemoryBytes]
    if (sharingMemoryBytes != null && sharingMemoryBytes < 0L) pipelineConfigError("sharing memory bytes must be nonnegative")
    val sharingMemoryWork = p[ExecutionOption.sharingMemoryWork] ?: 32_768L
    val sharingMemoryScratch = p[ExecutionOption.sharingMemoryScratch] ?: 4_096
    if (sharingMemoryWork < 0L || sharingMemoryScratch < 0) pipelineConfigError("sharing memory limits must be nonnegative")
    val earlyCoreAdmission = p[ExecutionOption.coreEarly] ?: false
    val companionIntegers = p[ExecutionOption.companionIntegers] ?: false
    val companionAfterIncumbent = p[ExecutionOption.companionAfterIncumbent] ?: false
    val companionActiveMillis = p[ExecutionOption.companionActiveMillis] ?: 4_000L
    val portfolioProgress = p[ExecutionOption.portfolioProgress] ?: false
    if (companionActiveMillis !in 1..60_000) pipelineConfigError("companion-active-ms must be in [1, 60000]")
    val coreRefinement = p[ExecutionOption.coreRefinement] ?: CoreRefinementMode.NONE
    if (diagnosticWorkLimit != null && (diagnosticWorkLimit <= 0L || cores != 1)) {
        pipelineConfigError("portfolio-work requires one core and a positive work limit")
    }
    val phaseRetention = p[ExecutionOption.phaseRetention]
    val reseedStaleThreshold = p[ExecutionOption.reseedStaleThreshold]
    val reportLsResiduals = p[ExecutionOption.lsResiduals] ?: false
    val reportLsPhaseTimes = p[ExecutionOption.lsPhaseTimes] ?: false
    if (reseedStaleThreshold != null && reseedStaleThreshold < 0) {
        pipelineConfigError("reseed-stale-threshold must be >= 0")
    }
    if (sliceWork != null && sliceWork <= 0L) pipelineConfigError("slice-work must be > 0")
    val effectiveSliceWork = sliceWork ?: PortfolioScenario.DEFAULT_SLICE_WORK
    val effectiveMaxSliceWork = maxSliceWork ?: 150_000L
    if (effectiveMaxSliceWork < effectiveSliceWork) pipelineConfigError("slice-max-work must be >= slice-work")
    if (sliceGrowth != null && (!sliceGrowth.isFinite() || sliceGrowth < 1.0)) {
        pipelineConfigError("slice-growth must be finite and >= 1")
    }
    p.finish("portfolio", "arms, ls, bt, seed, lambda, clause-share-lbd, clause-share-len, slice-work, slice-max-work, slice-growth, portfolio-work, sharing-memory, sharing-memory-bytes, sharing-memory-work, sharing-memory-scratch, portfolio-progress, core-early, companion-integers, companion-after-incumbent, companion-active-ms, core-refinement, phase-retention, reseed-stale-threshold, ls-residuals, ls-phase-times")
    if (armsParam != null && (ls != null || bt != null)) {
        pipelineConfigError("portfolio: set either `arms=N` or `ls=/bt=`, not both")
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
    if (earlyCoreAdmission && (cores != 1 || kind != Kind.COP || engine != EngineMix.MIXED || btPool != null)) {
        pipelineConfigError("core-early requires a mixed optimization pool with automatic backtrack workers on one core")
    }
    var scenario = PortfolioScenario(
        cores = cores,
        arms = maxOf(arms, cores),
        kind = kind,
        engine = engine,
        seed = seed,
        lsLambda = lambda,
        lpCeiling = lpCeiling,
        zeroObjectivePricing = zeroObjectivePricing,
        lsPool = lsPool,
        btPool = btPool,
        btEdit = btEdit,
        annotationArm = annotationArm,
        nodeBudget = nodeBudget,
        reportLsResiduals = reportLsResiduals,
        reportLsPhaseTimes = reportLsPhaseTimes,
        sliceWork = effectiveSliceWork,
        maxSliceWork = effectiveMaxSliceWork,
        sliceGrowth = sliceGrowth ?: 1.5,
        diagnosticWorkLimit = diagnosticWorkLimit,
        sharingMemory = sharingMemory,
        sharingMemoryBytes = sharingMemoryBytes,
        sharingMemoryWork = sharingMemoryWork,
        sharingMemoryScratch = sharingMemoryScratch,
        earlyCoreAdmission = earlyCoreAdmission,
        coreRefinement = coreRefinement,
        companionIntegers = companionIntegers,
        companionAfterIncumbent = companionAfterIncumbent,
        companionActiveMillis = companionActiveMillis,
        reportPortfolioProgress = portfolioProgress,
        explicitArms = armsParam != null || ls != null || bt != null,
    )
    clauseShareLbd?.let { scenario = scenario.copy(clauseShareMaxLbd = it) }
    clauseShareLen?.let { scenario = scenario.copy(clauseShareMaxLen = it) }
    phaseRetention?.let { scenario = scenario.copy(phaseRetention = it) }
    reseedStaleThreshold?.let { scenario = scenario.copy(reseedStaleThreshold = it) }
    return scenario
}

/**
 * The backtrack arms `--param` asks for: [pool] pins named [BacktrackCatalog] arms, null for the curated pool, and
 * [edit] is applied to every arm built, whichever pool supplies it.
 */
class BtResolution(
    /** The pinned arms, or null for the curated pool. */
    val pool: List<() -> BacktrackRecipe>?,
    /** The override every arm's [BacktrackParams] goes through, or null for none. */
    val edit: ((BacktrackParams) -> BacktrackParams)?,
)

/**
 * Resolve the `cp`/`mixed` engine's backtrack arms from `--param`, the backtrack analogue of
 * [resolveLocalSearchRecipes]. Two forms, which compose:
 *  - `bt-arm=label,label` pins named [BacktrackCatalog] arms, each validated for [kind] (so a CSP
 *    rejects the COP-only LP/LinUCB arms); an unknown label is a hard usage error.
 *  - the per-solver override keys ([BACKTRACK_OVERRIDE_KEYS] — `var-selector`/`val-selector`/`luby`/…)
 *    *edit* the arms ([backtrackOverride]): every arm keeps its own seed/lp/luby diversity with the override
 *    pinned across it, so `-p8 -e cp --param var-selector=vsids` stays a full 8-worker pool. An edit without
 *    `bt-arm` edits the curated pool after it is composed, so the arms the model offers nothing to stay out.
 *
 * A pinned pool is the *set* of arms; the worker *count* still comes from `arms=`/`bt=`/the default and wraps
 * over it, exactly as `lsPool` does.
 */
fun resolveBtRecipes(p: EngineParams, kind: Kind): BtResolution =
    resolveBtRecipes(p.reader(), kind)

internal fun resolveBtRecipes(p: ExecutionOptionReader, kind: Kind): BtResolution {
    val btArm = p[ExecutionOption.backtrackArmsByLabel]
    val edit = backtrackOverride(p, allowSelectors = true)
    if (btArm == null) return BtResolution(null, edit)
    val labels = btArm
    if (labels.isEmpty()) pipelineConfigError("bt-arm: expected a comma-separated list of backtrack arm labels")
    val known = BacktrackCatalog.labels(kind).toSet()
    for (label in labels) {
        if (label !in known) {
            pipelineConfigError(
                "bt-arm: `$label` is not a backtrack arm for this problem (have ${known.joinToString()})",
            )
        }
    }
    return BtResolution(labels.map { label -> { BacktrackCatalog.byLabel(label) } }, edit)
}

/** The `--param` key naming a [NodeBudget]; consumed in `SolveCore` rather than here, because one
 *  budget has to serve the whole invocation and [EngineParams] consumes keys per instance. */
const val NODE_LIMIT_KEY = "node-limit"

/** Auto-tuned default arm-pool size, scaling with the core count: [ARMS_PER_CORE] arms per
 *  core — always *more* arms than cores, so the bandit (single core) / parallel race always has a
 *  pool to draw on — floored at [PortfolioScenario.DEFAULT_ARMS] so the single-core free track still
 *  gets a real pool. Overridable via `--param arms=N`. */
fun autoArms(cores: Int): Int = maxOf(PortfolioScenario.DEFAULT_ARMS, cores * ARMS_PER_CORE)

/** Default arms-per-core oversubscription factor for [autoArms]. */
private const val ARMS_PER_CORE = 2

/** `var-selector` `--param` values for the `cp` engine's override pool (each maps to a [VariableSelector]).
 *  Covers the public no-argument selectors; the objective-/base-parameterised ones (MaxRegret,
 *  IndomainBest, LastConflict, …) and the `internal` ones (DomWdeg, ActivityBasedSearch) are not
 *  exposable as a bare value here. */
enum class VarSelectorKind(val id: String) {
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
 *  Covers no-argument selectors and bounded MaxSd with its default probe cap.
 *  IndomainBest, IndomainSet and Impact require other context. */
enum class ValSelectorKind(val id: String) {
    RANDOM("random"),
    MIN("min"),
    MAX("max"),
    MIDDLE("middle"),
    MEDIAN("median"),
    SPLIT("split"),
    SOLUTION_GUIDED("solution-guided"),
    MAX_SD("max-sd"),
    ;

    /** The value selector for this kind; the stateful [SolutionGuided] is constructed fresh per call so
     *  parallel arms never share it. */
    fun selector(): ValueSelector = when (this) {
        RANDOM -> IndomainRandom
        MIN -> IndomainMin
        MAX -> IndomainMax
        MIDDLE -> IndomainMiddle
        MEDIAN -> IndomainMedian
        SPLIT -> IndomainSplit
        SOLUTION_GUIDED -> SolutionGuided(IndomainMin)
        MAX_SD -> MaxSd()
    }

    companion object {
        private val byId = entries.associateBy { it.id }
        fun fromId(token: String): ValSelectorKind? = byId[token.trim().lowercase()]
        fun ids(): String = entries.joinToString("|") { it.id }
    }
}
