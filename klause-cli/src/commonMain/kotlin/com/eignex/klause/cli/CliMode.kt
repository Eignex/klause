package com.eignex.klause.cli

import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.ir.BoolFoldDefinition
import com.eignex.klause.ir.EffectiveSourceSemantics
import com.eignex.klause.ir.LinearObjectiveSpec
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SemanticOverrides
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.lp.bounding.LpEmphasis
import com.eignex.klause.lp.engine.LpZeroObjectivePricing
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveEmphasis
import com.eignex.klause.presolve.PresolvePass
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.objective.IncrementalObjective
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.SourceProblemRoute
import com.eignex.klause.solver.result.LinearOptimizationResult
import com.eignex.klause.solver.result.LpStats
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.WorkMeter
import com.eignex.util.numbers.BigFraction
import com.eignex.util.process.printErrorLine
import com.eignex.util.process.processProperty
import kotlin.time.Duration.Companion.milliseconds

/*
 * CLI modes supply format detection, input loading, mode-specific flags and output
 * protocols. Shared flag parsing lives here; [SolveCore] owns the solve driver.
 */

// Consumes `open-bound-proof` once and retains the token across frontend requests.
internal fun CommonOptions.routingCancellation(): ExecutionControl {
    routingToken?.let { return it }
    if (!takeOpenBoundProof()) return ExecutionControl { true }.also { routingToken = it }
    val solveStop = invocationCancellation()
    val budget = sharedPresolveBudget()
    val phaseStop = presolveTimeCancellation or solveStop
    val token = budget?.let { routingSlice(it, phaseStop) } ?: phaseStop
    routingToken = token
    return token
}

// Required admission observes the containing stop while retaining the shared routing work ledger.
internal fun CommonOptions.routingPreparationCancellation(): RoutingPreparationScope = RoutingPreparationScope(
    invocationCancellation(),
    routingCancellation().accounting,
)

internal fun CommonOptions.invocationCancellation(): ExecutionControl {
    invocationStop?.let { return it }
    val common = this
    return object : ExecutionControl() {
        override fun isCancelled(): Boolean = common.invocationDeadline.isCancelled()
        override fun deadline() = common.invocationDeadline.deadline()
    }.also { invocationStop = it }
}

internal class RoutingPreparationScope(containing: ExecutionControl, private var meter: WorkMeter?) : AutoCloseable {
    val control = containing.withAccounting(object : ExecutionControl() {
        override val accounting get() = meter
    })

    override fun close() {
        // Retained source invocations keep the containing stop, not the preparation ledger.
        meter = null
    }
}

// The elapsed ceiling can stop routing before its work allowance is exhausted, so both are logged.
internal fun CommonOptions.logRoute(route: SourceProblemRoute, elapsedMs: Long) {
    val lane = when (route) {
        is SourceProblemRoute.NativeFormula -> "native-formula"
        is SourceProblemRoute.PolynomialTheory -> "polynomial"
        is SourceProblemRoute.Finite -> "finite"
        is SourceProblemRoute.OpenTheory -> "open"
        is SourceProblemRoute.UnsupportedOpen -> "unsupported"
        SourceProblemRoute.Refuted -> "refuted"
    }
    frontendRouting = CliRoutingStats(lane, elapsedMs)
    cliLogger(verbose, diagnostic).v {
        "route: $lane in ${elapsedMs}ms" + presolveBudget?.let { ", routing work ${it.spent()}" }.orEmpty()
    }
}

// Routing receives half of the remaining shared allowance; source preparation keeps the rest.
internal fun routingSlice(budget: PresolveBudget, solveStop: ExecutionControl): ExecutionControl =
    budget.slice((budget.remaining() / 2).coerceAtLeast(1L)) or solveStop

// One work allowance for routing and the source preparation after it; null when the cap is disabled.
// Milliseconds derived from `-t` convert to work, so `-t` scales deterministic effort. The shared elapsed
// ceiling stops preparation when the work calibration underestimates its cost.
internal fun CommonOptions.sharedPresolveBudget(): PresolveBudget? {
    if (!presolveAllowanceInitialized) {
        val explicit = processProperty(CliKnobs.presolveBudgetMs)?.toLongOrNull()
        val fraction = processProperty(CliKnobs.presolveBudgetFraction)?.toDoubleOrNull()
            ?: CliKnobs.DEFAULT_PRESOLVE_BUDGET_FRACTION
        val budgetMs = explicit ?: SolveCore.derivedPresolveBudgetMs(timeLimitMs, fraction)
        presolveBudget = when (val setting = takeAllowanceSetting(engineParams, "presolve-work-limit")) {
            AllowanceSetting.Automatic -> if (budgetMs <= 0L) null else PresolveBudget(presolveWorkFor(budgetMs))
            AllowanceSetting.Unlimited -> null
            is AllowanceSetting.Remaining -> PresolveBudget(setting.units)
        }
        presolveTimeCancellation = if (budgetMs <= 0L) ExecutionControl.Never else
            ExecutionControl.after(budgetMs.milliseconds)
        presolveAllowanceInitialized = true
    }
    return presolveBudget
}

internal fun presolveWorkFor(budgetMs: Long): Long = if (budgetMs > Long.MAX_VALUE / CliKnobs.PRESOLVE_WORK_PER_MS) {
    Long.MAX_VALUE
} else {
    budgetMs * CliKnobs.PRESOLVE_WORK_PER_MS
}

// Removes `open-bound-proof` from the engine params so engines do not reject the CLI-owned key.
internal fun CommonOptions.takeOpenBoundProof(): Boolean {
    val entries = engineParams.filter { it.startsWith("$OPEN_BOUND_PROOF_KEY=") }
    if (entries.isEmpty()) return true
    engineParams.removeAll(entries)
    // Same vocabulary as `EngineParams.bool`, so one spelling does not depend on which side reads the key;
    // the raw token is reported unfolded, since a rejected value is easier to spot as it was typed.
    val values = entries.map { entry ->
        val raw = entry.substringAfter('=')
        when (raw.lowercase()) {
            "true", "on", "1", "yes" -> true
            "false", "off", "0", "no" -> false
            else -> usageError("engine param `$OPEN_BOUND_PROOF_KEY` expects a boolean, got `$raw`")
        }
    }.distinct()
    return values.singleOrNull()
        ?: usageError("engine param `$OPEN_BOUND_PROOF_KEY` cannot be set to conflicting values")
}

private const val OPEN_BOUND_PROOF_KEY = "open-bound-proof"

internal class CommonOptions {
    var diagnostic: (String) -> Unit = ::printErrorLine
    var config: KlauseConfig = KlauseConfig.current
    var semanticOverrides: SemanticOverrides = SemanticOverrides()
    val sourceOptions = SourceOptions()
    var sourceSemantics: EffectiveSourceSemantics? = null

    var engine: String? = null
    var timeLimitMs: Long? = null

    // Anchored once before loading and shared by the load-time bake and the solve, so the two phases
    // do not each spend a full `-t` budget.
    var deadlineAtMs: Long? = null
        set(value) {
            field = value
            invocationDeadline = value?.let { Cancellation.after((it - nowMillis()).coerceAtLeast(0L).milliseconds) }
                ?: Cancellation.Never
        }

    // Null budget also means an explicitly disabled cap, so initialization is tracked separately.
    internal var presolveAllowanceInitialized = false
    internal var presolveBudget: PresolveBudget? = null
    internal var presolveTimeCancellation: ExecutionControl = ExecutionControl.Never
    internal var routingToken: ExecutionControl? = null
    internal var invocationStop: ExecutionControl? = null
    internal var invocationDeadline: Cancellation = Cancellation.Never
    internal var nodeBudget: NodeBudget? = null

    // Includes parsing, lowering, routing and solvable construction.
    var loadElapsedMs: Long? = null
    internal var frontendRouting: CliRoutingStats? = null
    var randomSeed: Long? = null
    var verbose = false
    var exact = false
    var statistics = false
    var debug = false
    internal var debugStats: CliDebugStats? = null
    var allSolutions = false
    var solutionCap: Long? = null
    var parallel: Int? = null
    var freeSearch = false

    var formatOverride: String? = null

    var presolve: String? = null

    // Absent or `aggressive` means uncapped; `off` disables LP across the pool. `-e fixed` accepts only `off`.
    var lp: String? = null

    var lpPricingExplicit: Boolean = false
        private set

    var lpPricing: LpZeroObjectivePricing = LpZeroObjectivePricing.MIN_BOUND_SUPPORT
        set(value) {
            field = value
            lpPricingExplicit = true
        }

    val engineParams = mutableListOf<String>()

    // Checked before any input file is required.
    var showHelp = false

    var showVersion = false

    var inputPath: String? = null
}

// `--help` sections, rendered in declaration order.
internal enum class FlagGroup(val title: String) {
    STANDARD("Standard FlatZinc options (MiniZinc fzn-spec)"),
    ENGINE("Engine selection"),
    KLAUSE("klause options"),
    MODE("Front-end options"),
}

// The parser and `--help` both derive from these specs, so neither drifts from the real flags.
// A null help hides the flag from `--help`.
internal class FlagSpec(
    val names: List<String>,
    val takesValue: Boolean,
    val group: FlagGroup = FlagGroup.KLAUSE,
    val valueLabel: String? = null,
    val help: String? = null,
    val default: String? = null,
    val apply: (String?) -> Unit,
)

// `KLAUSE_ENGINE` lets a packaged image ship a different default; `-e` and `-f` override it.
internal fun defaultEngine(): FiniteEngine = processProperty(CliKnobs.engine)
    ?.let { parseEngine(it) ?: usageError("unknown KLAUSE_ENGINE `$it`; expected ${engineIds()}") }
    ?: FiniteEngine.DEFAULT

internal fun CommonOptions.resolvedEngine(): FiniteEngine = engine
    ?.let { parseEngine(it) ?: usageError("unknown engine `$it`; expected ${engineIds()}") }
    ?: if (freeSearch) FiniteEngine.BACKTRACK else defaultEngine()

internal fun parseEngine(token: String): FiniteEngine? = when (token.trim().lowercase()) {
    "fixed" -> FiniteEngine.FIXED
    "backtrack", "bt", "cp" -> FiniteEngine.BACKTRACK
    "localsearch", "ls", "local-search" -> FiniteEngine.LOCAL_SEARCH
    "mixed", "portfolio", "pf" -> FiniteEngine.MIXED
    "alns", "lns", "hybrid-lns" -> FiniteEngine.ALNS
    else -> null
}

internal fun engineIds(): String = FiniteEngine.entries.joinToString(" | ") { it.id }

// `KLAUSE_LP` lets a packaged image ship a different LP default; null keeps the per-engine default.
internal fun defaultLp(): String? = processProperty(CliKnobs.lp)

internal fun commonFlagSpecs(o: CommonOptions): List<FlagSpec> = listOf(
    FlagSpec(
        listOf("--exact"),
        false,
        FlagGroup.KLAUSE,
        help = "certify LP deductions exactly; continuous floats and zero source tolerance where applicable",
    ) { o.exact = true },
    FlagSpec(
        listOf("-a", "--all-solutions"),
        false,
        FlagGroup.STANDARD,
        help = "report all solutions (satisfy) / all improving incumbents (optimize)",
    ) { o.allSolutions = true },
    // Improving incumbents always stream on the optimize path, so `-i` is a no-op kept for the
    // MiniZinc protocol.
    FlagSpec(
        listOf("-i", "--intermediate", "--intermediate-solutions"),
        false,
        FlagGroup.STANDARD,
        help = "print intermediate solutions of increasing quality (optimize)",
    ) { },
    FlagSpec(
        listOf("-f", "--free-search"),
        false,
        FlagGroup.STANDARD,
        help = "ignore the model's search annotations (≡ -e cp)",
    ) { o.freeSearch = true },
    FlagSpec(
        listOf("-n"),
        true,
        FlagGroup.STANDARD,
        valueLabel = "i",
        help = "stop after reporting i solutions (satisfy)",
    ) { o.solutionCap = requireNotNull(it).toLongOrNull() ?: usageError("-n expects an integer, got `$it`") },
    FlagSpec(
        listOf("-s", "--statistics"),
        false,
        FlagGroup.STANDARD,
        help = "print solving statistics (%%%mzn-stat lines for FlatZinc)",
    ) { o.statistics = true },
    FlagSpec(
        listOf("--debug"),
        false,
        FlagGroup.KLAUSE,
        help = "print statistics with lightweight runtime instrumentation",
    ) { o.debug = true },
    FlagSpec(
        listOf("-v", "--verbose"),
        false,
        FlagGroup.STANDARD,
        help = "log progress to stderr (as % comment lines)",
    ) { o.verbose = true },
    FlagSpec(
        listOf("-t", "--time-limit"),
        true,
        FlagGroup.STANDARD,
        valueLabel = "ms",
        help = "wall-clock time limit in milliseconds",
    ) { o.timeLimitMs = requireNotNull(it).toLongOrNull() ?: usageError("-t expects an integer, got `$it`") },
    FlagSpec(
        listOf("-r", "--random-seed"),
        true,
        FlagGroup.STANDARD,
        valueLabel = "i",
        help = "random seed",
    ) { o.randomSeed = requireNotNull(it).toLongOrNull() ?: usageError("-r expects an integer, got `$it`") },
    FlagSpec(
        listOf("-p", "--parallel"),
        true,
        FlagGroup.STANDARD,
        valueLabel = "i",
        help = "solve with i parallel cores (portfolio engines only)",
        default = "1",
    ) {
        o.parallel = requireNotNull(it).toIntOrNull() ?: usageError("-p expects an integer, got `$it`")
    },
    FlagSpec(
        listOf("-e", "--engine"),
        true,
        FlagGroup.ENGINE,
        valueLabel = "name",
        help = "${engineIds()}; -f selects cp",
        // env-aware: KLAUSE_ENGINE overrides the built-in default, and --help reflects it.
        default = defaultEngine().id,
    ) { o.engine = it },
    FlagSpec(
        listOf("--param"),
        true,
        FlagGroup.ENGINE,
        valueLabel = "key=value",
        help = "repeatable engine tuning knob (e.g. arms=8, val-selector=max)",
    ) { o.engineParams.add(requireNotNull(it)) },
    FlagSpec(
        listOf("--format", "--mode"),
        true,
        FlagGroup.KLAUSE,
        valueLabel = "name",
        help = "force a front-end: " + MODES.joinToString(" | ") { it.names.first() },
    ) { o.formatOverride = it },
    FlagSpec(
        listOf("--presolve"),
        true,
        FlagGroup.KLAUSE,
        valueLabel = "strength",
        help = "presolve strength: " + PresolveEmphasis.ids() +
            "; append +/-<pass> to toggle one (passes: " + PresolvePass.ids() + ")",
        default = PresolveEmphasis.DEFAULT.id,
    ) { o.presolve = it },
    FlagSpec(
        listOf("--lp"),
        true,
        FlagGroup.KLAUSE,
        valueLabel = "ceiling",
        help = "LP relaxation ceiling: " + LpEmphasis.ids() +
            "; append +/-<technique> or preparation=disabled|object|index|admitted; not available under -e fixed",
        // env-aware: KLAUSE_LP overrides the built-in default, and --help reflects it.
        default = defaultLp() ?: LpEmphasis.AGGRESSIVE.id,
    ) { o.lp = it },
    FlagSpec(
        listOf("--lp-pricing"),
        true,
        FlagGroup.KLAUSE,
        valueLabel = "policy",
        help = "zero-objective LP entering pricing: min-bound-support | largest-pivot",
        default = "min-bound-support",
    ) {
        o.lpPricing = when (val policy = requireNotNull(it).lowercase()) {
            "min-bound-support" -> LpZeroObjectivePricing.MIN_BOUND_SUPPORT

            "largest-pivot" -> LpZeroObjectivePricing.LARGEST_PIVOT

            else -> usageError(
                "--lp-pricing expects min-bound-support | largest-pivot, got `$policy`",
            )
        }
    },
    FlagSpec(
        listOf("-h", "--help"),
        false,
        FlagGroup.KLAUSE,
        help = "show this help and exit",
    ) { o.showHelp = true },
    FlagSpec(
        listOf("--version"),
        false,
        FlagGroup.KLAUSE,
        help = "print the solver version and exit",
    ) { o.showVersion = true },
) + sourceFlagSpecs(o)

// Getopt-style: `--name value`, `--name=value`, `-t 5000`, `-t5000`, bundled shorts (`-asn10` is
// `-a -s -n 10`) and a `--` terminator. Unknown flags are only noted, since MiniZinc passes standard
// flags this solver does not implement.
internal fun parseArgs(args: Array<String>, specs: List<FlagSpec>, onPositional: (String) -> Unit) {
    val byName = HashMap<String, FlagSpec>()
    for (s in specs) for (n in s.names) byName[n] = s

    var i = 0
    var optionsEnded = false
    while (i < args.size) {
        val a = args[i]
        when {
            optionsEnded || a == "-" || !a.startsWith("-") -> {
                onPositional(a)
                i++
            }

            a == "--" -> {
                optionsEnded = true
                i++
            }

            a.startsWith("--") -> i = parseLong(a, args, i, byName)

            else -> i = parseShortCluster(a, args, i, byName)
        }
    }
}

// Returns the next index to read.
private fun parseLong(token: String, args: Array<String>, i: Int, byName: Map<String, FlagSpec>): Int {
    val eq = token.indexOf('=')
    val name = if (eq >= 0) token.substring(0, eq) else token
    val attached = if (eq >= 0) token.substring(eq + 1) else null
    val spec = byName[name]
    return when {
        spec == null -> {
            printErrorLine("klause-cli: ignoring unknown flag $name")
            i + 1
        }

        spec.takesValue -> {
            if (attached != null) {
                spec.apply(attached)
                i + 1
            } else {
                spec.apply(args.getOrNull(i + 1) ?: usageError("flag $name expects a value"))
                i + 2
            }
        }

        else -> {
            if (attached != null) usageError("flag $name takes no value")
            spec.apply(null)
            i + 1
        }
    }
}

// Returns the next index to read.
private fun parseShortCluster(token: String, args: Array<String>, i: Int, byName: Map<String, FlagSpec>): Int {
    var j = 1
    while (j < token.length) {
        val name = "-" + token[j]
        val spec = byName[name]
        when {
            spec == null -> {
                printErrorLine("klause-cli: ignoring unknown flag $name")
                j++
            }

            spec.takesValue -> {
                // The rest of the token is the value (`-t5000`); otherwise the next arg (`-t 5000`).
                val rest = token.substring(j + 1)
                if (rest.isNotEmpty()) {
                    spec.apply(rest)
                    return i + 1
                }
                spec.apply(args.getOrNull(i + 1) ?: usageError("flag $name expects a value"))
                return i + 2
            }

            else -> {
                spec.apply(null)
                j++
            }
        }
    }
    return i + 1
}

// Thrown rather than exiting in place so argument parsing stays testable; [runCli] prints it and exits 2.
internal class CliUsageException(message: String) : RuntimeException(message)

internal fun usageError(msg: String): Nothing = throw CliUsageException(msg)

internal enum class Verdict {
    SATISFIABLE,

    // Optimality proven for the last emitted incumbent.
    OPTIMAL,

    // Incumbents found, optimality not proven.
    BEST_FOUND,

    // The objective descends without limit: solutions exist but no optimum.
    UNBOUNDED,

    // Only a complete search that ran to completion may report this.
    UNSATISFIABLE,

    UNKNOWN,
}

// An unknown verdict covers causes that call for different responses (a longer budget, a different
// pool, or nothing), so the cause travels alongside it.
internal class VerdictContext(
    val budgetExhausted: Boolean = false,
    val terminationReason: TerminationReason? = null,
    // Absent when preparation ended before strategy selection.
    val completePool: Boolean? = null,
)

internal fun VerdictContext.softVerdictCause(): String = when {
    terminationReason == TerminationReason.Unsupported -> "unsupported feature or operation"
    terminationReason == TerminationReason.RepresentationDeclined -> "required representation declined"
    terminationReason == TerminationReason.Timeout -> "wall timeout"
    terminationReason == TerminationReason.BudgetExhausted -> "fixed work exhausted"
    terminationReason == TerminationReason.Cancelled -> "cancelled"
    terminationReason == TerminationReason.SearchExhausted -> "search exhausted without a global proof"
    completePool == false -> "no arm in the pool can prove a verdict"
    budgetExhausted -> "budget exhausted"
    else -> "search stopped without a verdict"
}

// The driver calls begin once, onSolution per solution, onComplete once, and onStatistics last under
// `-s`. Each mode chooses whether to stream solutions or buffer the best one until onComplete.
internal interface OutputProtocol {
    fun begin(optimize: Boolean, maximize: Boolean) {}

    // continuousObjective is the whole value when a continuous column carries cost.
    fun onSolution(rendered: String, objective: Long?, continuousObjective: Double? = null)

    fun onExactSolution(rendered: String, objective: BigFraction)

    fun arithmeticSourceValue(objective: SourceValue): SourceValue? = objective
    fun onArithmeticIncumbent(rendered: String, objective: SourceValue): Boolean
    fun onQuadraticResult(rendered: String?, result: QuadraticOptimizationResult) {
        rendered?.let { onSolution(it, null) }
    }
    fun onLinearResult(result: LinearOptimizationResult) {}
    fun onLinearSourceResult(result: com.eignex.klause.solver.pipeline.SourceExecutionResult) {}
    fun onComplete(verdict: Verdict)
    fun onStatistics(stats: SolveStats, solveTimeMs: Long, solutions: Long)
    fun onAllowanceStatistics(pairs: List<Pair<String, String>>) {}

    // Called before onComplete; only modes that report a stop reason consume it.
    fun onVerdictContext(context: VerdictContext) {}

    // One line per strict global improvement under `-s`, naming the arm. Every output parser reads a `%`
    // line as a comment, so the default serves all modes. A non-finite continuous value is omitted.
    fun onImprovement(arm: String, objective: Long, continuousObjective: Double? = null, elapsedMs: Long) {
        val continuous = continuousObjective?.takeIf(Double::isFinite)?.let { " continuousObjective=$it" }.orEmpty()
        println("%%%klause-arm: label=$arm objective=$objective$continuous time=$elapsedMs")
    }

    fun onImprovement(arm: String, objective: BigFraction, elapsedMs: Long) {
        println("%%%klause-arm: label=$arm objective=$objective time=$elapsedMs")
    }
}

// Created fresh per run so mode-specific flag state never leaks between invocations.
internal interface ModeSession {
    fun flags(): List<FlagSpec>
    fun load(path: String, common: CommonOptions): Solvable
    fun output(common: CommonOptions): OutputProtocol

    // Returns false to use the single-source driver.
    fun runCommands(path: String, common: CommonOptions): Boolean = false
}

// maximize is the original goal direction; the objective already negates for it, so reported values
// are sign-corrected here.
internal fun linearSolvable(
    problem: Problem,
    objective: LinearObjectiveSpec?,
    maximize: Boolean,
    render: (Sample) -> String,
    definedVars: IntArray = IntArray(0),
    boolFolds: List<BoolFoldDefinition> = emptyList(),
    routingLpStats: LpStats = LpStats(),
    routingElapsedMs: Long = 0L,
): Solvable = linearSolvable(
    problem,
    objective?.toLinearObjective(),
    maximize,
    render,
    definedVars,
    boolFolds,
    routingLpStats,
    routingElapsedMs,
)

internal fun linearSolvable(
    problem: Problem,
    objective: LinearObjective?,
    maximize: Boolean,
    render: (Sample) -> String,
    definedVars: IntArray = IntArray(0),
    boolFolds: List<BoolFoldDefinition> = emptyList(),
    routingLpStats: LpStats = LpStats(),
    routingElapsedMs: Long = 0L,
    searchHints: com.eignex.klause.ir.SearchHints? = null,
    preparedRoute: SourceProblemRoute.Finite? = null,
): Solvable {
    // Unit folds preserve literal moves. Wider folds derive from priced inputs;
    // excluding unpriced feasibility structure can stall constraint repair.
    val derivedFolds = if (objective == null) emptyList() else foldsOverObjectiveVars(boolFolds, objective)
    val sweep = DefinitionalSweep.infer(problem, definedVars, derivedFolds)
    if (objective == null) {
        return Solvable(
            problem = problem, optimize = false, maximize = false,
            lsObjective = null, linearObjective = null, objVarId = null,
            definitionalSweep = sweep,
            render = render, objectiveValue = null,
            routingLpStats = routingLpStats,
            routingElapsedMs = routingElapsedMs,
            searchHints = searchHints,
            preparedRoute = preparedRoute,
        )
    }
    // The gradient view reads every fold: evaluating through one never excludes its indicator from search.
    val objSweep = if (boolFolds.isEmpty()) {
        sweep
    } else {
        DefinitionalSweep.infer(problem, definedVars, boolFolds)
    }
    return Solvable(
        problem = problem,
        optimize = true,
        maximize = maximize,
        // Null when no objective term is functionally defined; LS then descends the linear objective.
        lsObjective = functionalObjectiveFor(objective, objSweep),
        linearObjective = objective,
        objVarId = objective.singleIntObjective()?.varId,
        definitionalSweep = sweep,
        render = render,
        objectiveValue = { s -> objective.evaluateLong(s).let { if (maximize) -it else it } },
        continuousObjectiveValue = objective.continuousObjectiveValue(maximize),
        routingLpStats = routingLpStats,
        routingElapsedMs = routingElapsedMs,
        searchHints = searchHints,
        preparedRoute = preparedRoute,
    )
}

// Null when no continuous column carries cost. evaluate reads real values that only a leaf whose
// residual LP resolved them carries; a reported solution is such a leaf.
private fun LinearObjective.continuousObjectiveValue(maximize: Boolean): ((Sample) -> Double)? {
    if (realCoefficients.none { it != 0.0 }) return null
    return { s -> evaluate(s).let { if (maximize) -it else it } }
}

private fun foldsOverObjectiveVars(
    folds: List<BoolFoldDefinition>,
    objective: LinearObjective,
): List<BoolFoldDefinition> {
    if (folds.isEmpty()) return folds
    val bw = objective.boolWeights
    fun isObjectiveVar(v: Int) = v in bw.indices && bw[v] != 0L
    return folds.filter { spec -> spec.lits.size == 1 || spec.lits.all { isObjectiveVar(Lit.variable(it)) } }
}

// The coefficients already encode the maximize sign, so the functional objective minimizes them.
private fun functionalObjectiveFor(objective: LinearObjective, sweep: DefinitionalSweep?): IncrementalObjective? {
    if (sweep == null) return null
    val ic = objective.intCoefficients
    val terms = ic.indices.filter { ic[it] != 0L }.toIntArray()
    val coeffs = LongArray(terms.size) { ic[terms[it]] }
    val bw = objective.boolWeights
    val boolTerms = bw.indices.filter { bw[it] != 0L }.toIntArray()
    val boolCoeffs = LongArray(boolTerms.size) { bw[boolTerms[it]] }
    if (terms.isEmpty() && boolTerms.isEmpty()) return null
    return sweep.functionalObjective(terms, coeffs, objective.constant, minimize = true, boolTerms, boolCoeffs)
}

// Stateless; per-run state lives in the ModeSession it creates.
internal interface CliMode {
    // `--format` names; the first is canonical.
    val names: List<String>

    // Lowercase, no dot.
    val extensions: List<String>
    fun newSession(): ModeSession
}
