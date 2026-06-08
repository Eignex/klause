package com.eignex.klause.bench.metric

import com.eignex.klause.bench.report.BenchResults
import com.eignex.klause.bench.report.CellResult
import com.eignex.klause.bench.report.EntryResult
import com.eignex.klause.bench.report.EnvInfo
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.runner.ResolvedProblem
import com.eignex.klause.bench.solver.InProcessSolver
import com.eignex.klause.bench.solver.Solvers
import kotlinx.serialization.encodeToString
import java.io.File
import java.time.Instant

/** Per-rep `nanoTime` deltas for one backend's three call kinds. */
private class BackendTimings(val solveNanos: LongArray, val sampleNanos: LongArray, val enumerateNanos: LongArray)

/**
 * Wall-time bench for `solve` / `samples` / `enumerate` per backend per problem, plus the
 * propagation microbench. Output goes to `build/bench-time.json` and is compared against
 * `bench-baseline.json` for regression detection.
 */
object TimeMetric {
    private const val RESULTS_PATH = "build/bench-time.json"
    private const val BASELINE_PATH = "bench-baseline.json"

    internal fun run(entries: List<ResolvedProblem>) {
        val repetitions = System.getProperty("klause.bench.repetitions")?.toIntOrNull() ?: 5
        val sampleCount = System.getProperty("klause.bench.sampleCount")?.toIntOrNull() ?: 10
        val warmupReps = System.getProperty("klause.bench.warmupReps")?.toIntOrNull() ?: 3
        val thresholdPct = System.getProperty("klause.bench.regressionThresholdPct")?.toDoubleOrNull() ?: 75.0

        val baseline = loadBaseline()
        val baselineIndex: Map<Pair<String, String>, CellResult> = baseline
            ?.entries
            ?.flatMap { e -> e.backends.map { c -> (e.name to c.backend) to c } }
            ?.toMap()
            .orEmpty()

        println()
        val baselineNote = if (baseline == null) "no baseline" else "vs baseline @ ${baseline.timestamp}"
        println(
            "=== benchmark (per entry, median of $repetitions reps × $sampleCount samples; " +
                "$warmupReps warmup reps; $baselineNote) ===",
        )
        val results = mutableListOf<EntryResult>()
        val regressions = mutableListOf<String>()
        for (entry in entries) {
            val timings = bench(
                entry.problem.let { Solvers.defaultPortfolio(it) },
                repetitions,
                sampleCount,
                warmupReps,
            )
            val cells = mutableListOf<CellResult>()
            val cellStrings = mutableListOf<String>()
            for ((name, t) in timings) {
                val solveMed = median(t.solveNanos)
                val sampleMed = median(t.sampleNanos)
                val enumMed = median(t.enumerateNanos)
                cells += CellResult(name, solveMed, sampleMed, enumMed)
                val prior = baselineIndex[entry.name to name]
                val solveStr = formatCell(
                    "solve",
                    solveMed,
                    prior?.solveNsMedian,
                    thresholdPct,
                    entry.name,
                    name,
                    regressions,
                )
                val sampleStr = formatCell(
                    "sample",
                    sampleMed,
                    prior?.sampleNsMedian,
                    thresholdPct,
                    entry.name,
                    name,
                    regressions,
                )
                val enumStr = formatCell(
                    "enum",
                    enumMed,
                    prior?.enumNsMedian,
                    thresholdPct,
                    entry.name,
                    name,
                    regressions,
                )
                cellStrings += "$name $solveStr $sampleStr $enumStr"
            }
            println("[${entry.name}] ${cellStrings.joinToString(" | ")}")
            results += EntryResult(entry.name, entry.ref.expected.expectsSat, cells)
        }

        writeResults(
            BenchResults(
                timestamp = Instant.now().toString(),
                gitSha = Reports.readGitSha(),
                env = EnvInfo.capture(),
                repetitions = repetitions,
                sampleCount = sampleCount,
                warmupReps = warmupReps,
                entries = results,
            ),
        )

        println()
        println("=== propagation microbenchmark (mean of 50 reps × 10 pins) ===")
        for (entry in entries) {
            if (entry.problem.numBoolVars < 2) continue
            val pinCount = minOf(10, entry.problem.numBoolVars - 1).coerceAtLeast(1)
            val t = PropagationMetric.bench(entry.problem, pinCount = pinCount)
            println(
                "[${entry.name}] bake=${Reports.formatNs(t.bakeNanos)} " +
                    "one-shot[${t.pinCount} pins]=${Reports.formatNs(t.oneShotPinNanos)} " +
                    "incr/pin=${Reports.formatNs(t.incrementalPinNanos)}",
            )
        }

        if (regressions.isNotEmpty()) {
            error("regression(s) past $thresholdPct% threshold:\n  " + regressions.joinToString("\n  "))
        }
    }

    private fun bench(
        solvers: List<InProcessSolver>,
        repetitions: Int,
        sampleCount: Int,
        warmupReps: Int,
    ): Map<String, BackendTimings> = solvers.associate { sampler ->
        repeat(warmupReps) {
            sampler.solve()
            sampler.samples(sampleCount)
            sampler.enumerated(sampleCount)
        }
        val solveTimes = LongArray(repetitions)
        val sampleTimes = LongArray(repetitions)
        val enumTimes = LongArray(repetitions)
        for (rep in 0 until repetitions) {
            solveTimes[rep] = timeIt { sampler.solve() }
            sampleTimes[rep] = timeIt { sampler.samples(sampleCount) }
            enumTimes[rep] = timeIt { sampler.enumerated(sampleCount) }
        }
        sampler.name to BackendTimings(solveTimes, sampleTimes, enumTimes)
    }

    private inline fun timeIt(block: () -> Unit): Long {
        val t0 = System.nanoTime()
        block()
        return System.nanoTime() - t0
    }

    private fun median(times: LongArray): Long = if (times.isEmpty()) 0 else times.sortedArray()[times.size / 2]

    private fun scaledThreshold(base: Double, prior: Long): Double = when {
        prior >= 1_000_000 -> base
        prior >= 100_000 -> base + 50.0
        prior >= 10_000 -> base + 100.0
        else -> base + 200.0
    }

    private fun formatCell(
        label: String,
        now: Long,
        prior: Long?,
        thresholdPct: Double,
        entryName: String,
        backendName: String,
        regressions: MutableList<String>,
    ): String {
        val base = "$label=${Reports.formatNs(now)}"
        if (prior == null || prior == 0L) return base
        val pct = (now - prior).toDouble() * 100.0 / prior
        val effectiveThreshold = scaledThreshold(thresholdPct, prior)
        if (pct > effectiveThreshold) {
            regressions += "$entryName/$backendName $label: ${Reports.formatNs(prior)} → " +
                "${Reports.formatNs(now)} (+${"%.1f".format(pct)}%, threshold $effectiveThreshold%)"
        }
        val sign = if (pct >= 0) "+" else ""
        return "$base (Δ$sign${"%.0f".format(pct)}%)"
    }

    private fun writeResults(results: BenchResults) {
        val file = File(RESULTS_PATH)
        file.parentFile?.mkdirs()
        file.writeText(Reports.json.encodeToString(results))
        println()
        println("wrote $RESULTS_PATH")
    }

    private fun loadBaseline(): BenchResults? {
        val file = File(BASELINE_PATH)
        if (!file.exists()) return null
        return runCatching { Reports.json.decodeFromString<BenchResults>(file.readText()) }.getOrNull()
    }
}
