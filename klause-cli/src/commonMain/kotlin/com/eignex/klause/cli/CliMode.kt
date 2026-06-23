package com.eignex.klause.cli

import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.lp.LpEmphasis
import com.eignex.klause.solver.backtrack.lp.lpHarvest
import com.eignex.klause.solver.localsearch.DefinitionalSweep
import com.eignex.klause.solver.objective.IncrementalObjective
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.presolve.PresolveConfig
import com.eignex.klause.solver.presolve.PresolveContext
import com.eignex.klause.solver.presolve.PresolveEmphasis
import com.eignex.klause.solver.presolve.PresolvePass
import com.eignex.klause.solver.presolve.Presolver
import com.eignex.klause.solver.result.SolveStats

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
 *  Docker build) can ship a different default — else [Engine.DEFAULT]. `-e` and `-f` override it. */
internal fun defaultEngine(): Engine = cliProp(CliKnobs.engine)
    ?.let { Engine.fromId(it) ?: usageError("unknown KLAUSE_ENGINE `$it`; expected ${Engine.ids()}") }
    ?: Engine.DEFAULT

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
    ) { o.solutionCap = requireNotNull(it).toLong() },
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
    ) { o.timeLimitMs = requireNotNull(it).toLong() },
    FlagSpec(
        listOf("-r", "--random-seed"),
        true,
        FlagGroup.STANDARD,
        valueLabel = "i",
        help = "random seed",
    ) { o.randomSeed = requireNotNull(it).toLong() },
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
        help = "${Engine.ids()}; -f selects cp",
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
 * Apply a presolve [config] to this Solvable, returning one whose [Solvable.problem] is the
 * transformed problem and whose [Solvable.render] / [Solvable.objectiveValue] reconstruct the
 * solution back to the original variables first. Every other field is valid unchanged because
 * the same-space passes keep variable ids. Returns `this` when nothing changed.
 */
internal fun Solvable.presolved(
    config: PresolveConfig,
    solutionSetSensitive: Boolean,
    cancellation: Cancellation = Cancellation.Never,
): Solvable {
    val context = PresolveContext.of(linearObjective, solutionSetSensitive, problem.hasSymmetryBreaking)
    val pre = Presolver.run(problem, config, context, cancellation)
    // LP-relaxation harvest (#10): when LP variable shaving is configured, fold the LP's proven domain
    // tightenings into the problem permanently so every backend (local search included) sees them — the
    // backtrack solver's own root shave reaches only its search session. Solution-set-preserving (every
    // shaved value is proven infeasible), so it is safe even for solution-set-sensitive queries.
    val harvested = annotatedBacktrackParams
        ?.takeIf { it.lpConfig != null || it.lpPlan.variableShaving }
        ?.let { lpHarvest(pre.problem, linearObjective ?: LinearObjective(), it, cancellation) }
        ?: pre.problem
    if (harvested === problem) return this
    return Solvable(
        problem = harvested,
        optimize = optimize,
        maximize = maximize,
        lsObjective = lsObjective,
        linearObjective = linearObjective,
        objVarId = objVarId,
        definitionalSweep = definitionalSweep,
        render = { sample -> render(pre.reconstruct(sample)) },
        objectiveValue = objectiveValue?.let { ov -> { sample -> ov(pre.reconstruct(sample)) } },
        annotatedBacktrackParams = annotatedBacktrackParams,
    )
}

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

internal fun usageError(msg: String): Nothing {
    errPrintln("klause-cli: $msg")
    exitCli(2)
}

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

    /** `-s` on a portfolio optimize: one line per strict global improvement, naming the arm that
     *  produced it (`-p N` races several). A machine-readable FlatZinc comment — every other output
     *  parser treats a `%`-prefixed line as a comment, so it is mode-agnostic; the default renders it
     *  and modes need not override. [objective] is the model-oriented value of the incumbent. */
    fun onImprovement(arm: String, objective: Long, elapsedMs: Long) {
        println("%%%klause-arm: label=$arm objective=$objective time=$elapsedMs")
    }
}

/**
 * A parsed instance, lowered to exactly what [SolveCore] needs — mode-neutral. Both the
 * FlatZinc objective-by-name + lsObjective/linear split and XCSP/SMT's single LinearObjective
 * collapse onto this shape.
 */
internal class Solvable(
    val problem: Problem,
    val optimize: Boolean,
    /** True when the user goal is maximisation (the objectives below already negate for it). */
    val maximize: Boolean,
    /** Per-move gradient view of the objective for the LS workers (null for satisfy / when
     *  none); rides into `LocalSearchParams.lsObjective`. */
    val lsObjective: IncrementalObjective?,
    /** The canonical linear objective every optimising backend minimises (null for satisfy). */
    val linearObjective: LinearObjective?,
    /** Single objective int var id, when the objective is one variable — enables the
     *  enumerate-over-objective fallback. Null for weighted-sum objectives / satisfy. */
    val objVarId: Int?,
    /** FlatZinc definitional-sweep DAG; null for XCSP/SMT. */
    val definitionalSweep: DefinitionalSweep?,
    /** Render one solution to the mode's solution text. */
    val render: (Sample) -> String,
    /** Sign-corrected objective value in original problem units, or null for satisfy. */
    val objectiveValue: ((Sample) -> Long)?,
    /** Model-supplied backtrack search (FlatZinc `solve :: *_search(...)`); the driver uses
     *  it unless `-f` (free search) is set. Null when the mode carries no search annotations
     *  (XCSP/SMT), so the driver falls back to its default CDCL configuration. */
    val annotatedBacktrackParams: BacktrackParams? = null,
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
): Solvable {
    if (objective == null) {
        return Solvable(
            problem = problem, optimize = false, maximize = false,
            lsObjective = null, linearObjective = null, objVarId = null,
            definitionalSweep = null, render = render, objectiveValue = null,
        )
    }
    return Solvable(
        problem = problem,
        optimize = true,
        maximize = maximize,
        // No gradient view for these modes: LS descends the linear objective directly.
        lsObjective = null,
        linearObjective = objective,
        objVarId = objective.singleIntObjective()?.varId,
        definitionalSweep = null,
        render = render,
        objectiveValue = { s -> objective.evaluateLong(s).let { if (maximize) -it else it } },
    )
}

/** A front-end. Stateless; all per-run state lives in the [ModeSession] it creates. */
internal interface CliMode {
    /** `--format` names; the first is canonical. */
    val names: List<String>

    /** Recognised file extensions (lowercase, no dot). */
    val extensions: List<String>
    fun newSession(): ModeSession
}
