package com.eignex.klause.cli

import com.eignex.klause.solver.DefinitionalSweep
import com.eignex.klause.solver.LinearObjective
import com.eignex.klause.solver.Objective
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveStats
import com.eignex.klause.solver.backtrack.BacktrackParams

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
    var randomSeed: Long? = null
    var verbose = false
    var statistics = false
    var allSolutions = false
    var solutionCap: Long? = null
    var parallel: Int? = null
    var freeSearch = false
    var cpSeed = false

    /** `--format NAME` / `--mode NAME`: force a specific mode regardless of file extension. */
    var formatOverride: String? = null

    /** Raw repeatable `--param key=value` engine params; interpreted per engine (see [EngineParams]). */
    val engineParams = mutableListOf<String>()

    /** The single positional input file. */
    var inputPath: String? = null
}

/** One recognised flag. [takesValue] flags consume the following argument; [apply] receives
 *  it (or `null` for boolean flags). The parser derives its value-flag set from these specs,
 *  so routing and parsing never drift. */
internal class FlagSpec(val names: List<String>, val takesValue: Boolean, val apply: (String?) -> Unit)

/** The solver-control flags every mode accepts. Mode-specific flags are appended per mode. */
internal fun commonFlagSpecs(o: CommonOptions): List<FlagSpec> = listOf(
    FlagSpec(listOf("-a", "--all-solutions"), false) { o.allSolutions = true },
    // Improving incumbents stream on the optimize path unconditionally — `-i` semantics — so
    // the flag is accepted as a no-op for MiniZinc-protocol compatibility.
    FlagSpec(listOf("-i", "--intermediate", "--intermediate-solutions"), false) { },
    FlagSpec(listOf("-f", "--free-search"), false) { o.freeSearch = true },
    FlagSpec(listOf("-n"), true) { o.solutionCap = requireNotNull(it).toLong() },
    FlagSpec(listOf("-s", "--statistics"), false) { o.statistics = true },
    FlagSpec(listOf("-v", "--verbose"), false) { o.verbose = true },
    FlagSpec(listOf("-t", "--time-limit"), true) { o.timeLimitMs = requireNotNull(it).toLong() },
    FlagSpec(listOf("-r", "--random-seed"), true) { o.randomSeed = requireNotNull(it).toLong() },
    FlagSpec(listOf("-e", "--engine"), true) { o.engine = it },
    FlagSpec(listOf("-p", "--parallel"), true) {
        o.parallel = requireNotNull(it).toIntOrNull() ?: usageError("-p expects an integer, got `$it`")
    },
    FlagSpec(listOf("--cp-seed"), false) { o.cpSeed = true },
    FlagSpec(listOf("--param"), true) { o.engineParams.add(requireNotNull(it)) },
    FlagSpec(listOf("--format", "--mode"), true) { o.formatOverride = it },
)

/**
 * Spec-driven argument parser. Walks [args], dispatching each recognised flag to its
 * [FlagSpec]; unknown `-flags` are tolerated (printed to stderr) to stay forward-compatible
 * with MiniZinc additions; the first non-flag token is the positional via [onPositional].
 */
internal fun parseArgs(args: Array<String>, specs: List<FlagSpec>, onPositional: (String) -> Unit) {
    val byName = HashMap<String, FlagSpec>()
    for (s in specs) for (n in s.names) byName[n] = s
    var i = 0
    while (i < args.size) {
        val a = args[i]
        val spec = byName[a]
        when {
            spec != null -> {
                if (spec.takesValue) {
                    val v = args.getOrNull(i + 1) ?: usageError("flag $a expects a value")
                    i++
                    spec.apply(v)
                } else {
                    spec.apply(null)
                }
                i++
            }

            a.startsWith("-") -> {
                errPrintln("klause-cli: ignoring unknown flag $a")
                i++
            }

            else -> {
                onPositional(a)
                i++
            }
        }
    }
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
    /** Gradient-bearing objective the LS workers descend (null for satisfy / when none). */
    val lsObjective: Objective?,
    /** Linear objective the complete backends bound-prune on (null for satisfy). */
    val linearObjective: Objective?,
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
        lsObjective = objective,
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
