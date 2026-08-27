package com.eignex.klause.cli

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.BoolFoldDefinition
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.lp.bounding.LpEmphasis
import com.eignex.klause.presolve.PresolveEmphasis
import com.eignex.klause.presolve.PresolvePass
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.ProblemSpec
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.IncrementalObjective
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.FinitePipelinePreparation
import com.eignex.klause.solver.pipeline.OpenTheoryRequest
import com.eignex.klause.solver.result.PresolveStats
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.theory.lia.GeneralLiaAssignment
import com.eignex.klause.theory.qflra.ExactLiraAssignment
import com.eignex.klause.theory.qflra.ExactLraAssignment

/*
 * Generic multi-mode CLI framework.
 *
 * The CLI is a thin dispatcher over a registry of [CliMode]s — one per front-end /
 * competition (MiniZinc, XCSP3, SMT-LIB, …). Everything that is genuinely shared lives here
 * or in [SolveCore]: the solver-control flag set ([CommonOptions] + [commonFlagSpecs]), the
 * spec-driven argument parser ([parseArgs]), and the unified solve driver ([SolveCore.solve]).
 *
 * A mode contributes only what differs between front-ends:
 *  1. **detection** — which `--format` names and file extensions route to it;
 *  2. **parsing** — turn an input file into a mode-neutral [Solvable];
 *  3. **mode flags** — any flags beyond the common set (e.g. MiniZinc's `--ozn`);
 *  4. **output protocol** — how verdicts and solutions are printed ([OutputProtocol]).
 *
 * Adding a new competition front-end is therefore a new [CliMode] object plus its
 * [OutputProtocol]; no change to the parser, the router, or the engines.
 */

/** Solver-control flags shared by every mode; populated by [commonFlagSpecs] during parsing. */
internal class CommonOptions {
    var engine: String? = null
    var timeLimitMs: Long? = null

    /** Absolute wall-clock instant (in [nowMillis] units) at which the `-t` budget expires,
     *  anchored once at process start. A single deadline shared by the construction-time bake
     *  ([CliMode.load]) and the solve phase ([SolveCore]) so the two phases don't each spend a
     *  fresh full budget (which would let `-t` overshoot ~2×). Null when no `-t` is given. */
    var deadlineAtMs: Long? = null

    /** Wall time (ms) the front-end load took — parse + construction-time bake — set by the driver
     *  around `session.load`, so `dry-run-presolve` can split parse from bake. */
    var loadElapsedMs: Long? = null
    var randomSeed: Long? = null
    var verbose = false
    var statistics = false
    var allSolutions = false
    var solutionCap: Long? = null
    var parallel: Int? = null
    var freeSearch = false

    /** `--format NAME` / `--mode NAME`: force a specific mode regardless of file extension. */
    var formatOverride: String? = null

    /** `--presolve STRENGTH`: the presolve effort level — `off` | `conservative` | `default` |
     *  `aggressive`. Parsed into a `PresolveConfig` by `PresolveConfig.parse`. */
    var presolve: String? = null

    /** `--lp CEILING`: the LP-relaxation ceiling for the portfolio — an emphasis (`off` | `conservative`
     *  | `default` | `aggressive`) optionally followed by `+/-<technique>` deltas (e.g.
     *  `aggressive,-cuts`). Absent ⇒ uncapped (the pool spreads the LP intensity itself, so LP is on by
     *  default); `off` disables LP across the portfolio. Parsed by `LpConfig.parse`. */
    var lp: String? = null

    /** Raw repeatable `--param key=value` engine params; interpreted per engine (see [EngineParams]). */
    val engineParams = mutableListOf<String>()

    /** `--help` / `-h`: print usage and exit 0 (checked before any input file is required). */
    var showHelp = false

    /** `--version`: print `<name> <version>` and exit 0. */
    var showVersion = false

    /** The single positional input file. */
    var inputPath: String? = null
}

/** `--help` sections, rendered in declaration order. A flag's [FlagSpec.group] places it here. */
internal enum class FlagGroup(val title: String) {
    STANDARD("Standard FlatZinc options (MiniZinc fzn-spec)"),
    ENGINE("Engine selection"),
    KLAUSE("klause options"),
    MODE("Front-end options"),
}

/**
 * One recognised flag. [takesValue] flags consume the following argument; [apply] receives it
 * (or `null` for boolean flags). The parser derives its value-flag set from these specs, so
 * routing and parsing never drift — and `--help` renders its option list from [group] /
 * [valueLabel] / [help] / [default], so the help text can't drift from the real flags either.
 * A `null` [help] hides the flag from `--help` (aliases / no-op compat flags / advanced knobs).
 * [default], when set, is shown as `(default: X)` after the description.
 */
internal class FlagSpec(
    val names: List<String>,
    val takesValue: Boolean,
    val group: FlagGroup = FlagGroup.KLAUSE,
    val valueLabel: String? = null,
    val help: String? = null,
    val default: String? = null,
    val apply: (String?) -> Unit,
)

/** The engine for a bare invocation (no `-e`, no `-f`): the `KLAUSE_ENGINE` env var /
 *  `klause.engine` property when set — so a packaged image (e.g. a MiniZinc-Challenge-compliant
 *  Docker build) can ship a different default — else [FiniteEngine.DEFAULT]. `-e` and `-f` override it. */
internal fun defaultEngine(): FiniteEngine = cliProp(CliKnobs.engine)
    ?.let { parseEngine(it) ?: usageError("unknown KLAUSE_ENGINE `$it`; expected ${engineIds()}") }
    ?: FiniteEngine.DEFAULT

/** Translate a CLI engine token into the orchestration route it requests. */
internal fun parseEngine(token: String): FiniteEngine? = when (token.trim().lowercase()) {
    "fixed" -> FiniteEngine.FIXED
    "backtrack", "bt", "cp" -> FiniteEngine.BACKTRACK
    "localsearch", "ls", "local-search" -> FiniteEngine.LOCAL_SEARCH
    "mixed", "portfolio", "pf" -> FiniteEngine.MIXED
    "alns", "lns", "hybrid-lns" -> FiniteEngine.ALNS
    else -> null
}

/** Canonical engine ids rendered by CLI help and diagnostics. */
internal fun engineIds(): String = FiniteEngine.entries.joinToString(" | ") { it.id }

/** The `--lp` ceiling spec for a bare invocation: the `KLAUSE_LP` env var / `klause.lp` property when
 *  set — so a packaged image can ship a different LP default — else null (the per-engine built-in
 *  default applies). An explicit `--lp` overrides it. The spec is parsed by `LpConfig.parse`. */
internal fun defaultLp(): String? = cliProp(CliKnobs.lp)

/** The solver-control flags every mode accepts. Mode-specific flags are appended per mode. */
internal fun commonFlagSpecs(o: CommonOptions): List<FlagSpec> = listOf(
    FlagSpec(
        listOf("-a", "--all-solutions"),
        false,
        FlagGroup.STANDARD,
        help = "report all solutions (satisfy) / all improving incumbents (optimize)",
    ) { o.allSolutions = true },
    // Improving incumbents stream on the optimize path unconditionally — `-i` semantics — so
    // the flag is accepted as a no-op for MiniZinc-protocol compatibility.
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
        help = "LP relaxation ceiling: " + LpEmphasis.ids() + "; append +/-<technique> to toggle one",
        // env-aware: KLAUSE_LP overrides the built-in default, and --help reflects it.
        default = defaultLp() ?: LpEmphasis.AGGRESSIVE.id,
    ) { o.lp = it },
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
)

/**
 * Adapt a prepared finite pipeline into this mode's rendering shape. The CLI keeps reconstruction only
 * because rendering belongs to the front-end; presolve policy and model preparation live in the pipeline.
 */
internal fun Solvable.withPreparation(preparation: FinitePipelinePreparation): Solvable {
    if (preparation.problem === finiteProblem) return this
    return copyWith(
        preparation.problem,
        preparation.presolve,
        { sample -> render(preparation.reconstruct(sample)) },
        objectiveValue?.let { objectiveValue -> { sample -> objectiveValue(preparation.reconstruct(sample)) } },
        preparation.objective,
    )
}

/** Rebuild a [Solvable] with a new [problem]/[presolve]/[render]/[objectiveValue]. */
private fun Solvable.copyWith(
    problem: Problem,
    presolve: PresolveStats?,
    render: (Sample) -> String,
    objectiveValue: ((Sample) -> Long)?,
    linearObjective: LinearObjective? = this.linearObjective,
): Solvable = Solvable(
    problem = problem,
    presolve = presolve,
    optimize = optimize,
    maximize = maximize,
    lsObjective = lsObjective,
    linearObjective = linearObjective,
    objVarId = objVarId,
    definitionalSweep = definitionalSweep,
    render = render,
    objectiveValue = objectiveValue,
    annotatedBacktrackParams = annotatedBacktrackParams,
)

/**
 * Spec-driven, getopt-style argument parser. Walks [args], dispatching each recognised flag to
 * its [FlagSpec]. It accepts the conventional Unix spellings:
 *  - long options with a space- or `=`-attached value: `--time-limit 5000` / `--time-limit=5000`;
 *  - short options with a space- or directly-attached value: `-t 5000` / `-t5000`;
 *  - bundled boolean shorts: `-as` ≡ `-a -s` (a value-taking short ends the bundle and takes the
 *    remainder as its value, so `-asn10` ≡ `-a -s -n 10`);
 *  - a bare `--` terminator after which every token is a positional.
 *
 * Unknown `-flags` are tolerated (noted on stderr, not fatal) to stay forward-compatible with
 * MiniZinc passing standard flags this solver doesn't implement. Each non-flag token (and `-`,
 * the stdin convention) is a positional reported via [onPositional].
 */
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

/** Parse one `--name` / `--name=value` token; returns the next index to read. */
private fun parseLong(token: String, args: Array<String>, i: Int, byName: Map<String, FlagSpec>): Int {
    val eq = token.indexOf('=')
    val name = if (eq >= 0) token.substring(0, eq) else token
    val attached = if (eq >= 0) token.substring(eq + 1) else null
    val spec = byName[name]
    return when {
        spec == null -> {
            errPrintln("klause-cli: ignoring unknown flag $name")
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

/** Parse one short-option cluster (`-a`, `-as`, `-t5000`, `-asn10`); returns the next index. */
private fun parseShortCluster(token: String, args: Array<String>, i: Int, byName: Map<String, FlagSpec>): Int {
    var j = 1
    while (j < token.length) {
        val name = "-" + token[j]
        val spec = byName[name]
        when {
            spec == null -> {
                errPrintln("klause-cli: ignoring unknown flag $name")
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

/** A command-line usage error. [main] catches it to print the message and exit 2; throwing (rather
 *  than exiting in place) keeps arg parsing testable and unwinds cleanly through the CLI. */
internal class CliUsageException(message: String) : RuntimeException(message)

internal fun usageError(msg: String): Nothing = throw CliUsageException(msg)

/** Terminal classification reported to an [OutputProtocol] once the solve loop ends. */
internal enum class Verdict {
    /** Satisfaction: at least one solution found (search may or may not be exhausted). */
    SATISFIABLE,

    /** Optimisation: optimality proven for the last emitted incumbent. */
    OPTIMAL,

    /** Optimisation: incumbent(s) found but optimality NOT proven (budget/incomplete). */
    BEST_FOUND,

    /** No solution exists (only a complete search that ran to completion may report this). */
    UNSATISFIABLE,

    /** Budget/cancellation hit before any verdict, or an incomplete search found nothing. */
    UNKNOWN,
}

/**
 * Why a run stopped, for the modes that explain a soft verdict.
 *
 * `unknown` is emitted for causes that call for completely different responses — a longer budget, a
 * different pool, or nothing at all — and the verdict alone cannot tell them apart, so the cause
 * travels alongside it.
 */
internal class VerdictContext(
    /** The wall-clock budget fired before the search finished. */
    val budgetExhausted: Boolean = false,
    /** Whether the pool carried an arm that can prove. A pure-local-search pool reports `unknown` at
     *  every budget, so its `unknown` is structural rather than a matter of time. */
    val completePool: Boolean = true,
)

/**
 * The cause of a soft verdict: out of time, out of a pool that could have proved anything, or neither —
 * in which case the caller learns only that it was not reached.
 *
 * Every format reports this, so a census of `unknown` by cause covers whatever corpus it is run over
 * rather than only the formats that happened to implement it.
 */
internal fun VerdictContext.softVerdictCause(): String = when {
    !completePool -> "no arm in the pool can prove a verdict"
    budgetExhausted -> "budget exhausted"
    else -> "search stopped without a verdict"
}

/**
 * How a mode prints solutions and verdicts. The [SolveCore] driver calls these in order:
 * [begin] once, [onSolution] per feasible/improving solution (streamed), [onComplete] once
 * with the terminal [Verdict], and [onStatistics] last when `-s` is set.
 *
 * Streaming vs buffering is the mode's choice: MiniZinc prints each solution immediately and
 * emits a terminator in [onComplete]; XCSP/SMT buffer the best solution and print the final
 * `v`/model line only in [onComplete], after the status line.
 */
internal interface OutputProtocol {
    fun begin(optimize: Boolean, maximize: Boolean) {}
    fun onSolution(rendered: String, objective: Long?)
    fun onComplete(verdict: Verdict)
    fun onStatistics(stats: SolveStats, solveTimeMs: Long, solutions: Long)

    /** Record why the run stopped, before [onComplete] renders the verdict. Ignored by default — only
     *  the modes that report a reason consume it. */
    fun onVerdictContext(context: VerdictContext) {}

    /** `-s` on a portfolio optimize: one line per strict global improvement, naming the arm that
     *  produced it (`-p N` races several). A machine-readable FlatZinc comment — every other output
     *  parser treats a `%`-prefixed line as a comment, so it is mode-agnostic; the default renders it
     *  and modes need not override. [objective] is the model-oriented value of the incumbent. */
    fun onImprovement(arm: String, objective: Long, elapsedMs: Long) {
        println("%%%klause-arm: label=$arm objective=$objective time=$elapsedMs")
    }
}

/** Build an instance whose open integer rows are decided by difference theory, minimizing [objective]
 *  when one is given. */
internal fun differenceTheorySolvable(
    model: ProblemSpec,
    render: (Sample) -> String,
    objective: LinearObjective? = null,
    maximize: Boolean = false,
): Solvable = Solvable(
    problem = null,
    optimize = objective != null,
    maximize = maximize,
    lsObjective = null,
    linearObjective = null,
    objVarId = null,
    definitionalSweep = null,
    render = render,
    objectiveValue = null,
    pipeline = SolvablePipeline.OpenTheory(
        OpenTheoryRequest(model, objective, maximize),
        render = { assignment ->
            render((assignment as com.eignex.klause.solver.pipeline.OpenTheoryAssignment.Difference).sample)
        },
    ),
)

/** Build an instance whose open integer rows are decided by General LIA, minimizing [objective] when one
 *  is given. */
internal fun generalLiaSolvable(
    model: ProblemSpec,
    objective: LinearObjective? = null,
    maximize: Boolean = false,
    render: (GeneralLiaAssignment) -> String,
): Solvable = Solvable(
    problem = null,
    optimize = objective != null,
    maximize = maximize,
    lsObjective = null,
    linearObjective = null,
    objVarId = null,
    definitionalSweep = null,
    render = { error("General LIA witnesses are rendered without narrowing to Sample") },
    objectiveValue = null,
    pipeline = SolvablePipeline.OpenTheory(
        OpenTheoryRequest(model, objective, maximize),
        render = { assignment ->
            render((assignment as com.eignex.klause.solver.pipeline.OpenTheoryAssignment.GeneralLia).assignment)
        },
    ),
)

/** Build a satisfiability instance whose open real rows are decided by exact QF_LRA. */
internal fun exactLraSolvable(model: ProblemSpec, render: (ExactLraAssignment) -> String): Solvable = Solvable(
    problem = null,
    optimize = false,
    maximize = false,
    lsObjective = null,
    linearObjective = null,
    objVarId = null,
    definitionalSweep = null,
    render = { error("exact LRA witnesses are rendered without narrowing to Sample") },
    objectiveValue = null,
    pipeline = SolvablePipeline.OpenTheory(
        OpenTheoryRequest(model),
        render = { assignment ->
            render(
                (assignment as com.eignex.klause.solver.pipeline.OpenTheoryAssignment.ExactLra).assignment,
            )
        },
    ),
)

/** Build an instance whose open mixed rows are decided by exact QF_LIRA, minimizing [objective] when one
 *  is given. An objective weighting a continuous column is not admitted; see the integral descent. */
internal fun exactLiraSolvable(
    model: ProblemSpec,
    objective: LinearObjective? = null,
    maximize: Boolean = false,
    render: (ExactLiraAssignment) -> String,
): Solvable = Solvable(
    problem = null,
    optimize = objective != null,
    maximize = maximize,
    lsObjective = null,
    linearObjective = null,
    objVarId = null,
    definitionalSweep = null,
    render = { error("exact LIRA witnesses are rendered without narrowing to Sample") },
    objectiveValue = null,
    pipeline = SolvablePipeline.OpenTheory(
        OpenTheoryRequest(model, objective, maximize),
        render = { assignment ->
            render((assignment as com.eignex.klause.solver.pipeline.OpenTheoryAssignment.ExactLira).assignment)
        },
    ),
)

/** Per-invocation parsing + loading + output for one front-end. Created fresh per run via
 *  [CliMode.newSession] so mode-specific flag state never leaks between invocations. */
internal interface ModeSession {
    /** Mode-specific flags appended to [commonFlagSpecs]. */
    fun flags(): List<FlagSpec>
    fun load(path: String, common: CommonOptions): Solvable
    fun output(common: CommonOptions): OutputProtocol
}

/**
 * Build a [Solvable] from a single [LinearObjective]-shaped instance — the common case for
 * XCSP3 and SMT-LIB, where one parsed [LinearObjective] serves as both the LS-gradient and
 * the bounding objective. [maximize] is the original goal direction; the objective already
 * negates for it, so the reported value is sign-corrected back here.
 */
internal fun linearSolvable(
    problem: Problem,
    objective: LinearObjective?,
    maximize: Boolean,
    render: (Sample) -> String,
    definedVars: IntArray = IntArray(0),
    boolFolds: List<BoolFoldDefinition> = emptyList(),
): Solvable {
    // Feasibility sweep derives functionally-defined vars and excludes them from search. A bool AND
    // fold is derived only when all its literals are objective variables: deriving an OPB product
    // indicator lets local search drop it and re-derive it from its literals, which pays off when
    // those literals are the ones the objective drives, but stalls repair when they are pure
    // feasibility-structure variables entangled in the hard constraints.
    val derivedFolds = if (objective == null) emptyList() else foldsOverObjectiveVars(boolFolds, objective)
    val sweep = DefinitionalSweep.infer(problem.factors, problem.numIntVars, definedVars, derivedFolds)
    if (objective == null) {
        return Solvable(
            problem = problem, optimize = false, maximize = false,
            lsObjective = null, linearObjective = null, objVarId = null,
            definitionalSweep = sweep,
            render = render, objectiveValue = null,
        )
    }
    // The gradient view reads every fold (evaluating through a fold is always safe — it only needs
    // to see the objective through the indicator, not exclude the indicator from search).
    val objSweep = if (boolFolds.isEmpty()) {
        sweep
    } else {
        DefinitionalSweep.infer(problem.factors, problem.numIntVars, definedVars, boolFolds)
    }
    return Solvable(
        problem = problem,
        optimize = true,
        maximize = maximize,
        // Gradient view over the definitional cone (when the objective's terms are functionally
        // defined), so LS descends the true objective on the decision vars; else null and LS
        // descends the linear objective directly.
        lsObjective = functionalObjectiveFor(objective, objSweep),
        linearObjective = objective,
        objVarId = objective.singleIntObjective()?.varId,
        definitionalSweep = sweep,
        render = render,
        objectiveValue = { s -> objective.evaluateLong(s).let { if (maximize) -it else it } },
    )
}

/** Build a solvable whose source model deliberately has no CP search domains yet. */
internal fun linearModelSolvable(
    model: com.eignex.klause.solver.ProblemSpec,
    objective: LinearObjective?,
    maximize: Boolean,
    render: (Sample) -> String,
): Solvable {
    val sweep = DefinitionalSweep.infer(model.factors, model.numIntVars, IntArray(0), emptyList())
    if (objective == null) {
        return Solvable(
            problem = null, optimize = false, maximize = false,
            lsObjective = null, linearObjective = null, objVarId = null,
            definitionalSweep = sweep, render = render, objectiveValue = null,
        )
    }
    return Solvable(
        problem = null,
        optimize = true,
        maximize = maximize,
        lsObjective = functionalObjectiveFor(objective, sweep),
        linearObjective = objective,
        objVarId = objective.singleIntObjective()?.varId,
        definitionalSweep = sweep,
        render = render,
        objectiveValue = { s -> objective.evaluateLong(s).let { if (maximize) -it else it } },
    )
}

/** Keep only the AND folds whose every literal is an objective variable (nonzero bool weight). These
 *  are the OPB product indicators worth deriving in the feasibility sweep — the ones whose literals
 *  the objective drives, rather than pure feasibility-structure variables whose exclusion stalls
 *  constraint repair. */
private fun foldsOverObjectiveVars(
    folds: List<BoolFoldDefinition>,
    objective: LinearObjective,
): List<BoolFoldDefinition> {
    if (folds.isEmpty()) return folds
    val bw = objective.boolWeights
    fun isObjectiveVar(v: Int) = v in bw.indices && bw[v] != 0L
    return folds.filter { spec -> spec.lits.all { isObjectiveVar(Lit.variable(it)) } }
}

/** Build a functional-objective gradient view of [objective] over [sweep]'s int and bool cones, or
 *  null when none of its terms are functionally defined (a plain [LinearObjective] suffices).
 *  [objective]'s coefficients already encode the maximize sign ("lower is better"), so the functional
 *  objective minimizes them directly. */
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

/** A front-end. Stateless; all per-run state lives in the [ModeSession] it creates. */
internal interface CliMode {
    /** `--format` names; the first is canonical. */
    val names: List<String>

    /** Recognised file extensions (lowercase, no dot). */
    val extensions: List<String>
    fun newSession(): ModeSession
}

/**
 * [problem] rewritten so it is plainly unsatisfiable: every integer pinned to zero and one row demanding
 * `x₀ ≥ 1`. Used when the model has already been refuted over its genuinely open ranges — the search must
 * not be handed the original domains (it would explore an invented box and could only answer `unknown`),
 * and it must not be handed a merely *pinned* problem either, since that could be satisfiable by accident
 * and would then report a model the original does not have.
 */
internal fun refutedProblem(problem: Problem): Problem {
    // The contradiction is carried by an integer column, so one has to exist. A refutation can only come
    // from open integer domains, which implies at least one — the guard keeps the row from naming a
    // variable that is not there should that ever stop holding.
    if (problem.numIntVars == 0) return problem
    val pinned = Array(problem.numIntVars) { IntDomain(0L, 0L) }
    val contradiction = Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1)
    return Problem(
        numBoolVars = problem.numBoolVars,
        numIntVars = problem.numIntVars,
        intDomains = pinned,
        factors = problem.factors.toList() + contradiction,
        numRealVars = problem.numRealVars,
        realLower = problem.realLower,
        realUpper = problem.realUpper,
    )
}
