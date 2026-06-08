package com.eignex.klause.yuck

import com.eignex.klause.solver.LinearObjective
import com.eignex.klause.solver.MinimizeResult
import com.eignex.klause.solver.Objective
import com.eignex.klause.solver.Optimizer
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.TerminationReason
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Yuck adapter — a local-search **reference** for klause, mirroring the side-door shape of
 * `klause-choco` / `klause-ortools`: it renders a klause [Problem] as FlatZinc (see [FznModel])
 * and runs Yuck's `FlatZincRunner` in a subprocess. Used for differential parity of the LS
 * functionality (klause-LS vs Yuck); the module is temporary scaffolding for that sweep.
 *
 * Unlike the complete references, Yuck cannot prove UNSAT or optimality in general — it only
 * reports `UNSATISFIABLE` when the problem is trivially inconsistent and `==========` when the
 * search space was exhausted. Everything else maps to `Unknown` / `BestFound` accordingly.
 *
 * Yuck is not on Maven Central; it ships as a GitHub release zip. [YuckHome] locates a local
 * distribution (`klause.yuck.home` system property → `YUCK_HOME` env → the `installYuck`
 * Gradle task's cache) and the adapter invokes its jars with the current JVM — no launcher
 * script, no extra runtime beyond a JDK.
 */
class YuckSolver(override val problem: Problem) : Optimizer<YuckParams> {

    override fun solve(params: YuckParams): SolveResult {
        val run = execute(FznModel.emit(problem), params, intermediate = false)
        return when {
            run.solutions.isNotEmpty() -> SolveResult.Sat(readSample(run.solutions.last()))
            run.unsatisfiable -> SolveResult.Unsat()
            else -> SolveResult.Unknown(TerminationReason.Timeout)
        }
    }

    override fun samples(params: YuckParams): Sequence<Sample> = enumerate(params)

    /** Yuck has no exhaustive enumeration (local search); each model is an independent run with
     *  a derived seed, so duplicates are possible and UNSAT is indistinguishable from "not
     *  found". The sequence stops at the first run that yields nothing. */
    override fun enumerate(params: YuckParams): Sequence<Sample> = sequence {
        var k = 0L
        while (k < params.maxModels) {
            val run = execute(FznModel.emit(problem), params.copy(seed = params.seed + k), intermediate = false)
            if (run.solutions.isEmpty()) break
            yield(readSample(run.solutions.last()))
            k++
        }
    }

    override fun minimize(objective: Objective, params: YuckParams): MinimizeResult =
        improvements(objective, params).last()

    override fun improvements(objective: Objective, params: YuckParams): Sequence<MinimizeResult> {
        require(objective is LinearObjective) {
            "klause-yuck only optimizes LinearObjective (got ${objective::class.simpleName})"
        }
        val run = execute(FznModel.emit(problem, objective), params, intermediate = true)
        val incumbents = run.solutions.map { solution ->
            MinimizeResult.BestFound(
                readSample(solution),
                objectiveValueOf(solution, objective),
                TerminationReason.BudgetExhausted,
            )
        }
        val terminal: MinimizeResult = when {
            run.complete && incumbents.isNotEmpty() -> {
                val best = incumbents.last()
                MinimizeResult.Optimal(best.sample, best.objective)
            }

            run.complete || run.unsatisfiable -> MinimizeResult.Infeasible()

            incumbents.isNotEmpty() -> incumbents.last()

            else -> MinimizeResult.Unknown(TerminationReason.Timeout)
        }
        return (incumbents + terminal).asSequence()
    }

    /** The objective value from the emitted `objv` channel plus the constant offset; falls back
     *  to evaluating the objective on the sample if a solution line was somehow throttled away. */
    private fun objectiveValueOf(solution: Map<String, String>, objective: LinearObjective): Double {
        val channelled = solution[FznModel.OBJECTIVE_VAR]?.toIntOrNull()
        if (channelled != null) return (channelled + objective.constant).toDouble()
        return objective.evaluate(readSample(solution))
    }

    private fun readSample(solution: Map<String, String>): Sample = Sample(
        bools = BooleanArray(problem.numBoolVars) { solution["b$it"] == "true" },
        ints = IntArray(problem.numIntVars) { solution["i$it"]?.toInt() ?: 0 },
    )

    /** One Yuck subprocess run over a FlatZinc model: solutions in emission order plus status markers. */
    private class RunResult(
        val solutions: List<Map<String, String>>,
        val unsatisfiable: Boolean,
        /** `==========` seen — search space exhausted (optimum proven / no further solutions). */
        val complete: Boolean,
    )

    private fun execute(fzn: String, params: YuckParams, intermediate: Boolean): RunResult {
        val file = File.createTempFile("klause-yuck", ".fzn")
        try {
            file.writeText(fzn)
            return runProcess(file, params, intermediate)
        } finally {
            file.delete()
        }
    }

    private fun runProcess(fzn: File, params: YuckParams, intermediate: Boolean): RunResult {
        val command = buildList {
            add(YuckHome.javaBinary())
            add("-Djava.lang.Integer.IntegerCache.high=10000") // mirrors Yuck's launcher script
            add("-XX:+UseParallelGC")
            add("-cp")
            add(YuckHome.classpath())
            add("yuck.flatzinc.runner.FlatZincRunner")
            if (intermediate) {
                add("--intermediate-solutions")
                add("--output-throttling-interval")
                add("0")
            }
            params.timeoutMillis?.let {
                add("--runtime-limit")
                add(((it + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND).toString())
            }
            add("--seed")
            add(params.seed.toString())
            add("--number-of-solvers")
            add(params.solvers.toString())
            add(fzn.absolutePath)
        }
        val process = ProcessBuilder(command).redirectErrorStream(false).start()
        val stderr = StringBuilder()
        val drain = Thread { process.errorStream.bufferedReader().forEachLine { stderr.appendLine(it) } }
        drain.isDaemon = true
        drain.start()

        val solutions = ArrayList<Map<String, String>>()
        val current = HashMap<String, String>()
        var unsatisfiable = false
        var complete = false
        process.inputStream.bufferedReader().forEachLine { line ->
            when {
                line == SOLUTION_SEPARATOR -> {
                    solutions.add(HashMap(current))
                    current.clear()
                }

                line == SEARCH_COMPLETE -> complete = true

                line == UNSATISFIABLE -> unsatisfiable = true

                line == UNKNOWN -> Unit

                else -> ASSIGNMENT.matchEntire(line)?.let { m ->
                    current[m.groupValues[1]] = m.groupValues[2]
                }
            }
        }
        // The runtime limit is enforced by Yuck itself; the hard wait below is a backstop for a
        // hung process (grace period on top of the requested budget).
        val graceMillis = (params.timeoutMillis ?: DEFAULT_WAIT_MILLIS) + GRACE_MILLIS
        if (!process.waitFor(graceMillis, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            process.waitFor()
        }
        check(process.exitValue() == 0 || solutions.isNotEmpty() || unsatisfiable) {
            "yuck failed (exit ${process.exitValue()}): ${stderr.toString().take(STDERR_REPORT_CAP)}"
        }
        return RunResult(solutions, unsatisfiable, complete)
    }

    private companion object {
        const val SOLUTION_SEPARATOR = "----------"
        const val SEARCH_COMPLETE = "=========="
        const val UNSATISFIABLE = "=====UNSATISFIABLE====="
        const val UNKNOWN = "=====UNKNOWN====="
        const val MILLIS_PER_SECOND = 1000L
        const val DEFAULT_WAIT_MILLIS = 86_400_000L
        const val GRACE_MILLIS = 30_000L
        const val STDERR_REPORT_CAP = 2000

        /** `name = value;` solution line (bools print as `true`/`false`). */
        val ASSIGNMENT = Regex("""(\w+) = ([^;]+);""")
    }
}

/**
 * Locates the Yuck distribution directory (the unpacked release zip, with its jars in `lib/`).
 * Resolution order: `klause.yuck.home` system property → `YUCK_HOME` environment variable →
 * the conventional `installYuck` cache (`~/.cache/klause-yuck/yuck-<version>`).
 */
object YuckHome {
    /** The pinned Yuck release the Gradle `installYuck` task provisions. */
    const val VERSION = "20251106"

    /** The distribution directory, or error with provisioning instructions if none is found. */
    fun resolve(): File {
        val candidates = listOfNotNull(
            System.getProperty("klause.yuck.home"),
            System.getenv("YUCK_HOME"),
            File(System.getProperty("user.home"), ".cache/klause-yuck/yuck-$VERSION").absolutePath,
        )
        for (c in candidates) {
            val dir = File(c)
            if (dir.resolve("lib").isDirectory) return dir
        }
        error(
            "Yuck distribution not found (tried: $candidates). Yuck is not on Maven Central — " +
                "run `./gradlew :klause-yuck:installYuck` or set -Dklause.yuck.home / \$YUCK_HOME.",
        )
    }

    /** Classpath string over every jar in the distribution's `lib/`. */
    fun classpath(): String {
        val jars = resolve().resolve("lib").listFiles { f -> f.extension == "jar" }
        check(!jars.isNullOrEmpty()) { "no jars under ${resolve().resolve("lib")}" }
        return jars.sortedBy { it.name }.joinToString(File.pathSeparator) { it.absolutePath }
    }

    /** The current JVM's `java` binary — Yuck needs only a JRE 11+, so reusing it always works. */
    fun javaBinary(): String = File(System.getProperty("java.home"), "bin/java").absolutePath
}
