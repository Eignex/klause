package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.source.CorpusFetcher
import java.util.concurrent.TimeUnit

/**
 * Faithful reference path for the MiniZinc corpus: run a registered MiniZinc solver
 * (`minizinc --solver <id>`) end-to-end on the original `.mzn`(+`.dzn`), exactly as the
 * competition does. The solver compiles the model with **its own** globals library, so it uses
 * its native propagators — unlike the in-process adapters, which re-derive the reference from
 * klause's already-decomposed [com.eignex.klause.solver.Problem] and so inherit klause's lowering
 * (the source of `unsupported factor GaussianXor` / `Subcircuit`).
 *
 * Output is parsed off the standard FlatZinc solution stream: `_objective = N;` (from
 * `--output-objective`) per incumbent, `----------` per solution, `==========` once the search is
 * proven complete, `=====UNSATISFIABLE=====` when infeasible. Each `----------` is timestamped as
 * it is read, giving a true time-to-best for the optimization track.
 */
internal object MznReference {

    /** Solver ids registered with the local `minizinc`, parsed once from `minizinc --solvers`
     *  (the tag list in parentheses, e.g. `... (yuck, cbls, ...)`). Lets a reference fall back to
     *  its in-process adapter when its MiniZinc config isn't installed (e.g. Choco before
     *  `installChoco`). */
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

    /** True when [solverId] is a registered MiniZinc solver, so the end-to-end path is usable. */
    fun available(solverId: String): Boolean = solverId.lowercase() in registered

    /** One end-to-end MiniZinc-solver run: best objective + time-to-best (optimization), and the
     *  feasibility verdict / proof status. [objective] / [timeToBestMs] are null when no incumbent
     *  was emitted. */
    data class Outcome(val feasible: Boolean?, val objective: Double?, val timeToBestMs: Long?, val proven: Boolean)

    /** Run [solverId] on [ref]'s model under [budget]. [optimize] selects intermediate-solution
     *  mode (`-a`, improving incumbents) for COP; satisfaction stops at the first solution. */
    fun run(ref: ProblemRef, solverId: String, budget: Budget, optimize: Boolean): Outcome {
        val mzn = CorpusFetcher.resolve(ref.source)
        val dzn = ref.data?.let { CorpusFetcher.resolve(it) }
        val cmd = buildList {
            add("minizinc")
            add("--solver")
            add(solverId)
            add("--time-limit")
            add(budget.timeoutMillis.toString())
            add("--output-mode")
            add("dzn")
            if (optimize) {
                add("-a") // all (improving) intermediate solutions
                add("--output-objective")
            }
            add(mzn.absolutePath)
            if (dzn != null) add(dzn.absolutePath)
        }
        val process = ProcessBuilder(cmd).redirectErrorStream(false).start()
        val stderr = StringBuilder()
        val drain = Thread { process.errorStream.bufferedReader().forEachLine { stderr.appendLine(it) } }
        drain.isDaemon = true
        drain.start()

        var objective: Double? = null
        var timeToBestMs: Long? = null
        var proven = false
        var unsat = false
        var anySolution = false
        val startNanos = System.nanoTime()
        process.inputStream.bufferedReader().forEachLine { raw ->
            when (val line = raw.trim()) {
                SOLUTION_SEPARATOR -> {
                    anySolution = true
                    timeToBestMs = (System.nanoTime() - startNanos) / NANOS_PER_MILLI
                }

                SEARCH_COMPLETE -> proven = true

                UNSATISFIABLE -> unsat = true

                UNKNOWN, ERROR -> Unit

                else -> if (line.startsWith(OBJECTIVE_KEY)) {
                    line.substringAfter('=').trim().removeSuffix(";").trim().toDoubleOrNull()?.let { objective = it }
                }
            }
        }
        val grace = budget.timeoutMillis + GRACE_MILLIS
        if (!process.waitFor(grace, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            process.waitFor()
        }
        check(process.exitValue() == 0 || anySolution || unsat) {
            "minizinc --solver $solverId failed (exit ${process.exitValue()}): ${stderr.toString().take(STDERR_CAP)}"
        }
        return Outcome(
            feasible = when {
                anySolution -> true
                unsat -> false
                else -> null
            },
            objective = objective.takeIf { anySolution },
            timeToBestMs = timeToBestMs,
            proven = proven,
        )
    }

    private const val SOLUTION_SEPARATOR = "----------"
    private const val SEARCH_COMPLETE = "=========="
    private const val UNSATISFIABLE = "=====UNSATISFIABLE====="
    private const val UNKNOWN = "=====UNKNOWN====="
    private const val ERROR = "=====ERROR====="
    private const val OBJECTIVE_KEY = "_objective"
    private const val NANOS_PER_MILLI = 1_000_000L
    private const val GRACE_MILLIS = 30_000L
    private const val STDERR_CAP = 2000
    private const val REGISTRY_WAIT_MILLIS = 10_000L
}
