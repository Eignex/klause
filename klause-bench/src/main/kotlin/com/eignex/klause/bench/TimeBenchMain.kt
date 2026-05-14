package com.eignex.klause.bench

import java.io.File
import java.time.Instant
import kotlinx.serialization.encodeToString

/**
 * Entry point: `./gradlew :klause-bench:runTime`. Wall-time benchmarks for `solve` /
 * `samples` / `enumerate` per backend per portfolio entry, plus the propagation
 * microbench. Output goes to `build/bench-time.json` and is compared against
 * `bench-baseline.json` for regression detection.
 */
fun main() {
    val (_, benchEntries, _) = BenchHarness.loadAndVerify()

    val baseline = loadBaseline()
    val baselineIndex: Map<Pair<String, String>, CellResult> = baseline
        ?.entries
        ?.flatMap { e -> e.backends.map { c -> (e.name to c.backend) to c } }
        ?.toMap()
        .orEmpty()
    val thresholdPct = System.getProperty("klause.bench.regressionThresholdPct")?.toDoubleOrNull() ?: 25.0

    println()
    val baselineNote = if (baseline == null) "no baseline" else "vs baseline @ ${baseline.timestamp}"
    println("=== benchmark (per entry, median of 3 reps × 5 samples; $baselineNote) ===")
    val results = mutableListOf<EntryResult>()
    val regressions = mutableListOf<String>()
    for (entry in benchEntries) {
        val report = Benchmarker.bench(entry.problem, repetitions = 3, sampleCount = 5)
        val cells = mutableListOf<CellResult>()
        val cellStrings = mutableListOf<String>()
        for ((name, t) in report.timings) {
            val solveMed = median(t.solveNanos)
            val sampleMed = median(t.sampleNanos)
            val enumMed = median(t.enumerateNanos)
            cells += CellResult(name, solveMed, sampleMed, enumMed)
            val prior = baselineIndex[entry.name to name]
            val solveStr = formatCell("solve", solveMed, prior?.solveNsMedian, thresholdPct, entry.name, name, regressions)
            val sampleStr = formatCell("sample", sampleMed, prior?.sampleNsMedian, thresholdPct, entry.name, name, regressions)
            val enumStr = formatCell("enum", enumMed, prior?.enumNsMedian, thresholdPct, entry.name, name, regressions)
            cellStrings += "$name $solveStr $sampleStr $enumStr"
        }
        println("[${entry.name}] ${cellStrings.joinToString(" | ")}")
        results += EntryResult(entry.name, entry.expectedSat, cells)
    }

    writeResults(BenchResults(timestamp = Instant.now().toString(), gitSha = BenchHarness.readGitSha(), entries = results))

    println()
    println("=== propagation microbenchmark (mean of 50 reps × 10 pins) ===")
    for (entry in benchEntries) {
        if (entry.problem.numBoolVars < 2) continue
        val pinCount = minOf(10, entry.problem.numBoolVars - 1).coerceAtLeast(1)
        val t = PropagationBench.bench(entry.problem, pinCount = pinCount)
        println(
            "[${entry.name}] bake=${BenchHarness.formatNs(t.bakeNanos)} " +
                "one-shot[${t.pinCount} pins]=${BenchHarness.formatNs(t.oneShotPinNanos)} " +
                "incr/pin=${BenchHarness.formatNs(t.incrementalPinNanos)}"
        )
    }

    if (regressions.isNotEmpty()) {
        error(
            "regression(s) past ${thresholdPct}% threshold:\n  " +
                regressions.joinToString("\n  ")
        )
    }
}

private fun median(times: LongArray): Long =
    if (times.isEmpty()) 0 else times.sortedArray()[times.size / 2]

private fun formatCell(
    label: String,
    now: Long,
    prior: Long?,
    thresholdPct: Double,
    entryName: String,
    backendName: String,
    regressions: MutableList<String>,
): String {
    val base = "$label=${BenchHarness.formatNs(now)}"
    if (prior == null || prior == 0L) return base
    val pct = (now - prior).toDouble() * 100.0 / prior
    if (pct > thresholdPct) {
        regressions += "$entryName/$backendName $label: ${BenchHarness.formatNs(prior)} → " +
            "${BenchHarness.formatNs(now)} (+${"%.1f".format(pct)}%)"
    }
    val sign = if (pct >= 0) "+" else ""
    return "$base (Δ$sign${"%.0f".format(pct)}%)"
}

private const val RESULTS_PATH = "build/bench-time.json"
private const val BASELINE_PATH = "bench-baseline.json"

private fun writeResults(results: BenchResults) {
    val file = File(RESULTS_PATH)
    file.parentFile?.mkdirs()
    file.writeText(BenchHarness.json.encodeToString(results))
    println()
    println("wrote $RESULTS_PATH")
}

private fun loadBaseline(): BenchResults? {
    val file = File(BASELINE_PATH)
    if (!file.exists()) return null
    return runCatching { BenchHarness.json.decodeFromString<BenchResults>(file.readText()) }.getOrNull()
}
