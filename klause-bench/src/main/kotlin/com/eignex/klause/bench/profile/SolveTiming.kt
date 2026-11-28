package com.eignex.klause.bench.profile

import com.eignex.klause.backtrack.BacktrackPresets
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.runner.Runners
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.ExecutionControl
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File

/**
 * Times repeated in-process solves of one catalog problem as a lab profile job, and is the template for one: the lab
 * runs a class directly in this package under JFR with `$OUT` naming a directory it keeps, and runs no shell scripts.
 *
 * Arguments are `suite=<id> problem=<name> repeats=<n> timeout=<ms>`. Each repeat bakes the model and runs the
 * backtrack engine in this JVM, so the recording sees the solver rather than a CLI subprocess. Runs go to
 * `$OUT/timings.json`.
 */
object SolveTiming {
    private const val SEED = 3L
    private const val DEFAULT_REPEATS = 3

    @Serializable
    internal data class Run(val wallMs: Long, val feasible: Boolean?, val objective: Double?, val proven: Boolean)

    @Serializable
    internal data class Timings(val suite: String, val problem: String, val timeoutMs: Long, val runs: List<Run>)

    /** Runs the measurement, writing into `$OUT` (`build/profile-out` when unset). */
    @JvmStatic
    fun main(args: Array<String>) {
        val out = System.getenv("OUT")?.let(::File) ?: File("build/profile-out")
        val timings = measure(args.toList(), out)
        val walls = timings.runs.map { it.wallMs }.sorted()
        println("${timings.suite}/${timings.problem}: ${walls.size} run(s), wall ms min=${walls.first()} " +
            "median=${walls[walls.size / 2]} max=${walls.last()}")
        timings.runs.forEachIndexed { i, r ->
            println("  run ${i + 1}: ${r.wallMs} ms feasible=${r.feasible} objective=${r.objective} proven=${r.proven}")
        }
        println("wrote ${File(out, "timings.json")}")
    }

    internal fun measure(args: List<String>, out: File): Timings {
        val f = args.associate { arg ->
            require('=' in arg) { "expected key=value, got '$arg' (keys: suite, problem, repeats, timeout)" }
            arg.substringBefore('=') to arg.substringAfter('=')
        }
        val unknown = f.keys - setOf("suite", "problem", "repeats", "timeout")
        require(unknown.isEmpty()) { "unknown argument(s) $unknown (keys: suite, problem, repeats, timeout)" }
        val suite = f["suite"] ?: "opb-core"
        val name = f["problem"] ?: "sporttournament06"
        val repeats = f["repeats"]?.let { positive("repeats", it).toInt() } ?: DEFAULT_REPEATS
        val timeoutMs = f["timeout"]?.let { positive("timeout", it) } ?: Budget().timeoutMillis
        val ref = Catalog.uncapped(suite).problems.singleOrNull { it.name == name }
            ?: error("no problem '$name' in suite '$suite'")
        val entry = Runners.resolve(ref)
        val runs = List(repeats) {
            val start = System.nanoTime()
            val deadline = System.currentTimeMillis() + timeoutMs
            val params = BacktrackPresets.conflictDriven(
                randomSeed = SEED,
                cancellation = ExecutionControl { System.currentTimeMillis() > deadline },
            )
            val solver = BacktrackSolver(entry.problem.bake())
            val objective = entry.objective
            val run = if (objective == null) {
                when (solver.solve(params)) {
                    is SolveResult.Sat -> Run(0, true, null, true)
                    is SolveResult.Unsat -> Run(0, false, null, true)
                    is SolveResult.Unknown -> Run(0, null, null, false)
                }
            } else {
                // Report the minimization-canonical objective in the source's own sense.
                val r = solver.minimize(objective, params)
                val value = r.objectiveValue?.let { v -> if (entry.maximize) -v else v }
                when (r) {
                    is MinimizeResult.Optimal -> Run(0, true, value, true)
                    is MinimizeResult.Infeasible -> Run(0, false, null, true)
                    is MinimizeResult.Unknown -> Run(0, null, null, false)
                    else -> Run(0, true, value, false)
                }
            }
            run.copy(wallMs = (System.nanoTime() - start) / 1_000_000)
        }
        val timings = Timings(suite, name, timeoutMs, runs)
        out.mkdirs()
        File(out, "timings.json").writeText(Reports.json.encodeToString(timings))
        return timings
    }

    private fun positive(key: String, value: String): Long =
        requireNotNull(value.toLongOrNull()?.takeIf { it > 0 }) { "$key must be a positive integer, got '$value'" }
}
