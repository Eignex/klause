package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.catalog.ProblemSource
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.runner.MiniZincRunner
import com.eignex.klause.bench.runner.ResolvedProblem
import com.eignex.klause.bench.source.CorpusFetcher
import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Uniform subprocess solving: every solver is run as an external process emitting MiniZinc-format
 * output, then parsed identically. Two backends:
 *  - **klause** → the `klause-cli` binary on the model (the compiled `.fzn` for a MiniZinc instance,
 *    or the original file for XCSP3 / SMT-LIB). klause-cli renders the model's objective,
 *    so a maximize objective is reported in the model's orientation (not klause's internal minimised
 *    form) — the comparison is sign-correct by construction.
 *  - **a reference** (`choco` / `gecode` / `yuck` / …) → `minizinc --solver <id>` end-to-end on the
 *    original `.mzn`, so the solver compiles with its own globals library (the competition setup).
 *
 * Output is parsed off the standard FlatZinc stream — `_objective`/`objective = N`, `----------`
 * (timestamped as read for time-to-best), `==========` (proven), `=====UNSATISFIABLE=====` — plus
 * `%%%mzn-stat` lines (klause `-s` statistics). The raw stdout is returned verbatim for saving.
 */
internal object SolverInvocation {

    /** Solver-side knobs. [engine] (`cp`/`ls`/`portfolio`) and [params] (repeatable `--param
     *  key=value` engine knobs, e.g. `var-selector=vsids`) apply to klause only; references run
     *  their own search. [processors] maps to `-p` — **null means "unset"**: no `-p` is passed and the
     *  solver applies its own default (klause-cli's is single-core), so the bench never duplicates the
     *  default. `-p` is emitted only when set above 1.
     *
     *  [free] (`-f`, ignore the model's search annotation) **defaults to true** — a deliberate
     *  divergence from MiniZinc's CLI default (which honours the annotation). Free search measures
     *  the solver rather than the model's hand-written heuristic, which is the fairer strength
     *  comparison for modern CDCL/portfolio solvers; the annotation-following ("fixed") track is the
     *  explicit opt-in (`fixed=true`). */
    internal data class Settings(
        val engine: String? = null,
        val processors: Int? = null,
        val free: Boolean = true,
        val seed: Long = 3L,
        val params: List<String> = emptyList(),
        /** klause-cli `--lp CEILING` (LP-relaxation emphasis); klause only, null = unset. */
        val lp: String? = null,
        /** klause-cli `--presolve` (presolve emphasis + per-pass deltas); klause only, null = unset. */
        val presolve: String? = null,
    )

    /** One subprocess solve: verdict, best objective + time-to-best, proof status, the captured
     *  `%%%mzn-stat` lines, the per-arm [attribution] stream (klause portfolio `-s` only), and the
     *  raw stdout (saved as the run's "minizinc output"). Serializable so [BenchCache] can persist
     *  and replay it. */
    @Serializable
    internal data class Result(
        val feasible: Boolean?,
        val objective: Double?,
        val timeToBestMs: Long?,
        /** Wall-clock ms to the *first* feasible solution (the first `----------`), distinct from
         *  [timeToBestMs] (the last/best). Null when never feasible. Defaulted for backward-compatible
         *  decode of pre-existing cache blobs. */
        val timeToFirstFeasibleMs: Long? = null,
        val proven: Boolean,
        val stats: Map<String, String>,
        val attribution: List<Attribution> = emptyList(),
        val rawOutput: String,
        val command: String,
    )

    /** Registered MiniZinc solver ids, parsed once from `minizinc --solvers` (the parenthesised tag
     *  lists). Lets a reference report "not available" instead of failing obscurely. */
    private val registered: Set<String> by lazy {
        runCatching {
            val proc = ProcessBuilder("minizinc", "--solvers").redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor(REGISTRY_WAIT_MILLIS, TimeUnit.MILLISECONDS)
            Regex("""\(([^)]*)\)""").findAll(out)
                .flatMap { it.groupValues[1].split(',') }
                .map { it.trim().lowercase() }
                .toSet()
        }.getOrElse { emptySet() }
    }

    fun referenceAvailable(solverId: String): Boolean = solverId.lowercase() in registered

    /** Run [solverId] over [entry] under [budget]. `solverId == "klause"` runs the cli; any other id
     *  is a registered MiniZinc reference. [optimize] selects intermediate-solution mode (`-a`). */
    fun run(entry: ResolvedProblem, solverId: String, settings: Settings, budget: Budget, optimize: Boolean): Result {
        val cmd = if (solverId == KLAUSE) {
            klauseCommand(entry, settings, budget, optimize)
        } else {
            minizincCommand(entry.ref, solverId, settings, budget, optimize)
        }
        // Hard wall-clock ceiling: a solve that ignores its `-t` deadline (e.g. an expensive move source
        // that polls cancellation too rarely) must not hang the harness. Generous over the budget so it
        // only ever kills a genuine runaway, never a solve that's merely flushing at the deadline.
        return invoke(cmd, dialectFor(solverId, entry.ref.format), hardTimeoutMs = budget.timeoutMillis * 2 + 20_000)
    }

    /** Run a registered MiniZinc [solverId] reference directly on [ref] — no klause `Problem` is
     *  built (the reference oracle solves the `.mzn`/`.fzn` itself), so a whole-corpus reference
     *  sweep stays flat in memory. [optimize] selects intermediate-solution mode (`-a`). */
    fun runReference(ref: ProblemRef, solverId: String, settings: Settings, budget: Budget, optimize: Boolean): Result =
        invoke(
            minizincCommand(ref, solverId, settings, budget, optimize),
            Dialect.MINIZINC,
            hardTimeoutMs = budget.timeoutMillis * 2 + 20_000,
        )

    /** The provisioned klause-cli binary (its mtime keys the cache so a klause rebuild invalidates
     *  klause's cached results while references stay frozen). */
    fun klauseCliBin(): File = File(CorpusFetcher.workspaceRoot(), KLAUSE_CLI_BIN)

    /** klause-cli on the model file (compiled `.fzn` for MiniZinc, else the original source). */
    private fun klauseCommand(entry: ResolvedProblem, s: Settings, budget: Budget, optimize: Boolean): List<String> {
        val bin = klauseCliBin()
        check(bin.canExecute()) { "klause-cli not installed at $bin (run :klause-cli:installJvmDist)" }
        val file = if (entry.ref.format == Format.MINIZINC) {
            MiniZincRunner().compileFzn(entry.ref)
        } else {
            CorpusFetcher.resolve(entry.ref.source)
        }
        return buildList {
            add(bin.absolutePath)
            // MPS instances (notably extension-less MIPLIB files) can't be auto-detected by suffix;
            // the bench knows the format, so pass it explicitly.
            if (entry.ref.format == Format.MPS) {
                add("--format")
                add("mps")
            }
            add(file.absolutePath)
            add("-t")
            add(budget.timeoutMillis.toString())
            add("-r")
            add(s.seed.toString())
            add("-s")
            if (optimize) {
                add("-a")
                // Emit `_objective = <value>;` per solution (parity with the reference path's
                // `minizinc --output-objective`). The objective var is usually not in the model's
                // `output` section, so without this the parser records objective=null even when
                // klause proves the optimum (#477).
                add("--output-objective")
            }
            // No -f for klause: the engine enum encodes free vs fixed (cp/mixed/ls = free,
            // fixed = annotation). -f is only for references (minizincCommand).
            s.engine?.let {
                add("-e")
                add(it)
            }
            s.processors?.takeIf { it > 1 }?.let {
                add("-p")
                add(it.toString())
            }
            s.lp?.let {
                add("--lp")
                add(it)
            }
            s.presolve?.let {
                add("--presolve")
                add(it)
            }
            for (param in s.params) {
                add("--param")
                add(param)
            }
        }
    }

    /** `minizinc --solver <id>` end-to-end on the original `.mzn` (+ `.dzn`). `-s` requests the
     *  solver's `%%%mzn-stat` block (parity with klause's `-s`); [invoke] already parses those lines
     *  into [Result.stats], so references carry search statistics too. */
    private fun minizincCommand(
        ref: ProblemRef,
        solverId: String,
        s: Settings,
        budget: Budget,
        optimize: Boolean,
    ): List<String> {
        require(ref.format == Format.MINIZINC) {
            "reference '$solverId' only runs MiniZinc instances (got ${ref.format})"
        }
        val mzn = CorpusFetcher.resolve(ref.source)
        val dzn = ref.data?.let { CorpusFetcher.resolve(it) }
        val src = ref.source
        val includeDirs = if (src is ProblemSource.External) {
            val root = CorpusFetcher.ensure(src.collection)
            src.collection.includeDirs.map { File(root, it) }.filter { it.isDirectory }
        } else {
            emptyList()
        }
        return buildList {
            add("minizinc")
            add("--solver")
            add(solverId)
            add("--time-limit")
            add(budget.timeoutMillis.toString())
            add("--output-mode")
            add("dzn")
            add("-s")
            if (optimize) {
                add("-a")
                add("--output-objective")
            }
            if (s.free) add("-f")
            // Pin the reference solver's own worker count when set. Unlike the klause path, emit `-p`
            // even at 1: cp-sat/choco otherwise fan out to every core, so N instance-level jobs would
            // each spawn a full worker pool and oversubscribe cores + memory. The reference sweep sets
            // this to a small per-instance count so `jobs × workers` stays within the machine.
            s.processors?.let {
                add("-p")
                add(it.toString())
            }
            for (dir in includeDirs) {
                add("-I")
                add(dir.absolutePath)
            }
            add(mzn.absolutePath)
            if (dzn != null) add(dzn.absolutePath)
        }
    }

    /** Which output stream a subprocess emits, so the read loop parses the right markers. References
     *  always speak MiniZinc; klause-cli speaks the front-end bound to the *input* format — MiniZinc
     *  for `.mzn`/`.fzn`, the PB-competition stream (`s`/`o`/`c key=value`) for XCSP3 `.xml` and MPS. */
    private enum class Dialect { MINIZINC, XCSP3 }

    private fun dialectFor(solverId: String, format: Format): Dialect =
        if (solverId == KLAUSE && (format == Format.XCSP3 || format == Format.MPS)) Dialect.XCSP3 else Dialect.MINIZINC

    private fun invoke(cmd: List<String>, dialect: Dialect, hardTimeoutMs: Long = Long.MAX_VALUE): Result {
        val process = ProcessBuilder(cmd).redirectErrorStream(false).start()
        // Watchdog: force-kill a runaway that blew past [hardTimeoutMs] so the read loop below (which
        // blocks until the child's stdout closes) can't hang forever on a child that never exits. A
        // killed child's stream closes, the loop returns, and the partial output is scored as usual.
        Thread {
            runCatching { if (!process.waitFor(hardTimeoutMs, TimeUnit.MILLISECONDS)) process.destroyForcibly() }
        }.apply {
            isDaemon = true
            start()
        }
        val stderr = StringBuilder()
        val drain = Thread { process.errorStream.bufferedReader().forEachLine { stderr.appendLine(it) } }
        drain.isDaemon = true
        drain.start()

        val raw = StringBuilder()
        val stats = LinkedHashMap<String, String>()
        val attribution = ArrayList<Attribution>()
        var objective: Double? = null
        var timeToBestMs: Long? = null
        var timeToFirstFeasibleMs: Long? = null
        var proven = false
        var unsat = false
        var anySolution = false
        val startNanos = System.nanoTime()

        // An improving incumbent (a new solution / better objective): stamp time-to-best afresh.
        fun markIncumbent() {
            anySolution = true
            val elapsed = (System.nanoTime() - startNanos) / NANOS_PER_MILLI
            timeToBestMs = elapsed
            if (timeToFirstFeasibleMs == null) timeToFirstFeasibleMs = elapsed
        }

        // A terminal status proving a solution exists but without its own timestamp: don't clobber the
        // incumbent time already stamped by an `o`/`----------` line; only stamp if none was seen.
        fun markFeasible() {
            anySolution = true
            if (timeToBestMs == null) markIncumbent()
        }
        fun recordStat(kv: String) = kv.split('=', limit = 2)
            .takeIf { it.size == 2 }?.let { stats[it[0].trim()] = it[1].trim() }
        process.inputStream.bufferedReader().forEachLine { rawLine ->
            raw.appendLine(rawLine)
            val line = rawLine.trim()
            when (dialect) {
                Dialect.MINIZINC -> when (line) {
                    SOLUTION_SEPARATOR -> markIncumbent()

                    SEARCH_COMPLETE -> proven = true

                    UNSATISFIABLE -> unsat = true

                    UNKNOWN, ERROR -> Unit

                    else -> when {
                        line.startsWith(
                            ARM_PREFIX,
                        ) -> parseArm(line.removePrefix(ARM_PREFIX))?.let { attribution.add(it) }

                        line.startsWith(STAT_PREFIX) -> recordStat(line.removePrefix(STAT_PREFIX).trim())

                        line.startsWith(OBJECTIVE_KEY) || line.startsWith(MODEL_OBJECTIVE_KEY) ->
                            line.substringAfter('=').trim().removeSuffix(";").trim().toDoubleOrNull()
                                ?.let { objective = it }
                    }
                }

                Dialect.XCSP3 -> when {
                    line == XCSP_SATISFIABLE -> markFeasible()

                    line == XCSP_OPTIMUM -> {
                        markFeasible()
                        proven = true
                    }

                    line == XCSP_UNSATISFIABLE -> {
                        unsat = true
                        proven = true
                    }

                    line == XCSP_UNKNOWN -> Unit

                    // `o <cost>`: one line per improving incumbent (model-oriented objective).
                    line.startsWith(XCSP_OBJECTIVE_PREFIX) ->
                        line.removePrefix(XCSP_OBJECTIVE_PREFIX).trim().toDoubleOrNull()?.let {
                            objective = it
                            markIncumbent()
                        }

                    line.startsWith(ARM_PREFIX) -> parseArm(line.removePrefix(ARM_PREFIX))?.let { attribution.add(it) }

                    // `c <key>=<value>` statistics (same shape as `%%%mzn-stat:`, different prefix).
                    line.startsWith(XCSP_COMMENT_PREFIX) -> recordStat(line.removePrefix(XCSP_COMMENT_PREFIX).trim())
                }
            }
        }
        if (!process.waitFor(GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            process.waitFor()
        }
        check(process.exitValue() == 0 || anySolution || unsat) {
            "${cmd.first()} failed (exit ${process.exitValue()}): ${stderr.toString().take(STDERR_CAP)}"
        }
        return Result(
            feasible = when {
                anySolution -> true
                unsat -> false
                else -> null
            },
            objective = objective.takeIf { anySolution },
            timeToBestMs = timeToBestMs,
            timeToFirstFeasibleMs = timeToFirstFeasibleMs,
            proven = proven,
            stats = stats,
            attribution = attribution,
            rawOutput = raw.toString(),
            command = cmd.joinToString(" "),
        )
    }

    /** Parse a `%%%klause-arm:` body (`label=<l> objective=<v> time=<ms>`); null if malformed. */
    private fun parseArm(body: String): Attribution? {
        val kv = body.trim().split(' ').mapNotNull {
            it.split('=', limit = 2).takeIf { p -> p.size == 2 }?.let { p -> p[0] to p[1] }
        }.toMap()
        val label = kv["label"] ?: return null
        return Attribution(
            label = label,
            objective = kv["objective"]?.toDoubleOrNull(),
            elapsedMs = kv["time"]?.toLongOrNull() ?: return null,
        )
    }

    const val KLAUSE = "klause"
    private const val KLAUSE_CLI_BIN = "klause-cli/build/install/klause-cli-jvm/bin/klause-cli"
    private const val SOLUTION_SEPARATOR = "----------"
    private const val SEARCH_COMPLETE = "=========="
    private const val UNSATISFIABLE = "=====UNSATISFIABLE====="
    private const val UNKNOWN = "=====UNKNOWN====="
    private const val ERROR = "=====ERROR====="
    private const val OBJECTIVE_KEY = "objective ="
    private const val MODEL_OBJECTIVE_KEY = "_objective"
    private const val STAT_PREFIX = "%%%mzn-stat:"
    private const val ARM_PREFIX = "%%%klause-arm:"

    // XCSP3 competition output stream (klause-cli's `.xml` front-end).
    private const val XCSP_SATISFIABLE = "s SATISFIABLE"
    private const val XCSP_OPTIMUM = "s OPTIMUM FOUND"
    private const val XCSP_UNSATISFIABLE = "s UNSATISFIABLE"
    private const val XCSP_UNKNOWN = "s UNKNOWN"
    private const val XCSP_OBJECTIVE_PREFIX = "o "
    private const val XCSP_COMMENT_PREFIX = "c "
    private const val NANOS_PER_MILLI = 1_000_000L
    private const val GRACE_MILLIS = 30_000L
    private const val STDERR_CAP = 2000
    private const val REGISTRY_WAIT_MILLIS = 10_000L
}

/** One strict global improvement, parsed off a klause portfolio's `%%%klause-arm:` line: the arm
 *  ([label]) that produced the incumbent, its model-oriented [objective], and the [elapsedMs] at
 *  which it was found. The ordered list across a solve is the per-arm credit signal (which arm got
 *  the first incumbent, held the last/best, how many it contributed). */
@Serializable
internal data class Attribution(val label: String, val objective: Double?, val elapsedMs: Long)
