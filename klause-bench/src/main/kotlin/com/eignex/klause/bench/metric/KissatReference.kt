package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles

/**
 * The second CNF reference, beside [ClaspReference]: kissat as a native binary, which is single-threaded. The instance
 * goes on stdin as clasp reads it ([ClaspReference.ClaspInput]), so SATLIB's `%` end marker is honoured the same way.
 * kissat answers `s SATISFIABLE` / `s UNSATISFIABLE` / `s UNKNOWN`; a decision is a proof. Rows are keyed by solver
 * `kissat`.
 */
internal object KissatReference {
    /** The kissat executable, from `PATH` by default; override with `-Dklause.bench.kissat=/path/to/kissat`. */
    private val BINARY = System.getProperty("klause.bench.kissat", "kissat")

    fun available(): Boolean = NativeReference.available(BINARY)

    fun run(ref: ProblemRef, budget: Budget): SolverInvocation.Result {
        val file = CorpusFetcher.resolve(ref.source)
        val input = ClaspReference.ClaspInput.of(Format.DIMACS) { CorpusFiles.open(file).bufferedReader() }
        val timeoutSec = (budget.timeoutMillis / MS_PER_SEC).coerceAtLeast(1)
        // -q: no progress, -n: no witness; the status line is all the reference reads.
        val cmd = listOf(BINARY, "-q", "-n", "--time=$timeoutSec")
        val (stdout, elapsedMs) = NativeReference.exec(cmd, budget.timeoutMillis, input::writeTo)
        return parse(stdout, elapsedMs, cmd)
    }

    internal fun parse(stdout: String, elapsedMs: Long, cmd: List<String>): SolverInvocation.Result {
        val status = stdout.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("s ") }
        val feasible = when (status) {
            "s SATISFIABLE" -> true
            "s UNSATISFIABLE" -> false
            else -> null
        }
        val timeMs = elapsedMs.takeIf { feasible == true }
        return SolverInvocation.Result(
            feasible = feasible,
            objective = null,
            elapsedMs = elapsedMs,
            timeToBestMs = timeMs,
            timeToFirstFeasibleMs = timeMs,
            proven = feasible != null,
            stats = mapOf("solveTime" to (elapsedMs / MS_PER_SEC.toDouble()).toString(), "maximize" to "false"),
            rawOutput = stdout,
            command = cmd.joinToString(" "),
        )
    }

    private const val MS_PER_SEC = 1000L
}
