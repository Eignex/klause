package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.ir.ObjectiveSense as ObjectiveDirection

/**
 * The second MPS reference, beside [ScipReference]: HiGHS as a native binary on the model file, its parallelism off and
 * its wall-clock capped by `--time_limit`. HiGHS reports the objective in the model's own sense, which `OBJSENSE`
 * sets. Rows are keyed by solver `highs`.
 */
internal object HighsReference {
    /** The highs executable, from `PATH` by default; override with `-Dklause.bench.highs=/path/to/highs`. */
    private val BINARY = System.getProperty("klause.bench.highs", "highs")

    fun available(): Boolean = NativeReference.available(BINARY)

    fun run(ref: ProblemRef, budget: Budget): SolverInvocation.Result =
        CorpusFiles.withPlainFile(CorpusFetcher.resolve(ref.source)) { file ->
            val maximize = runCatching {
                Mps.parse(
                    file.readText(),
                ).sense == ObjectiveDirection.MAXIMIZE
            }.getOrDefault(false)
            val timeoutSec = (budget.timeoutMillis / MS_PER_SEC).coerceAtLeast(1)
            val cmd = listOf(
                BINARY,
                "--model_file",
                file.absolutePath,
                "--time_limit",
                "$timeoutSec",
                "--parallel",
                "off",
            )
            val (stdout, elapsedMs) = NativeReference.exec(cmd, budget.timeoutMillis)
            parse(stdout, elapsedMs, cmd, maximize)
        }

    /**
     * A MIP's summary has `Status <status>` and `Primal bound <value>`; an LP's has `Model status : <status>` and
     * `Objective value : <value>`. Optimal or infeasible is a proof, a finite primal bound without one is a feasible
     * witness, anything else is undecided.
     */
    internal fun parse(stdout: String, elapsedMs: Long, cmd: List<String>, maximize: Boolean): SolverInvocation.Result {
        val lines = stdout.lineSequence().map { it.trim() }.toList()
        fun field(name: String) = lines.lastOrNull { Regex("""^$name\s*:?\s+\S""").containsMatchIn(it) }
            ?.replace(Regex("""^$name\s*:?\s+"""), "")?.trim()
        val status = (field("Model status") ?: field("Status")).orEmpty().lowercase()
        val value = (field("Primal bound") ?: field("Objective value"))?.substringBefore(' ')?.toDoubleOrNull()
        val optimal = status.startsWith("optimal")
        val infeasible = status.startsWith("infeasible")
        val objective = value?.takeIf { it.isFinite() && !infeasible }
        val feasible = when {
            infeasible -> false
            objective != null -> true
            else -> null
        }
        val timeMs = elapsedMs.takeIf { feasible == true }
        return SolverInvocation.Result(
            feasible = feasible,
            objective = objective.takeIf { feasible == true },
            timeToBestMs = timeMs,
            timeToFirstFeasibleMs = timeMs,
            proven = optimal || infeasible,
            stats = mapOf(
                "solveTime" to (elapsedMs / MS_PER_SEC.toDouble()).toString(),
                "maximize" to maximize.toString(),
            ),
            rawOutput = stdout,
            command = cmd.joinToString(" "),
        )
    }

    private const val MS_PER_SEC = 1000L
}
