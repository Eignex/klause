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

    // Defaults chosen after measuring run-to-run variance on this portfolio: at
    // reps=3,sampleCount=5 the p50 cell-to-cell spread across three back-to-back runs
    // was 57%; bumping to reps=5,sampleCount=10,warmup=3 cuts it to ~25% p50 / 65% p90
    // but µs-scale cells remain inherently noisy (cold-JIT, GC) with ~100% spread tails.
    // A 100% regression threshold absorbs that floor and still catches real 2× slowdowns.
    val repetitions = System.getProperty("klause.bench.repetitions")?.toIntOrNull() ?: 5
    val sampleCount = System.getProperty("klause.bench.sampleCount")?.toIntOrNull() ?: 10
    val warmupReps = System.getProperty("klause.bench.warmupReps")?.toIntOrNull() ?: 3

    val baseline = loadBaseline()
    val baselineIndex: Map<Pair<String, String>, CellResult> = baseline
        ?.entries
        ?.flatMap { e -> e.backends.map { c -> (e.name to c.backend) to c } }
        ?.toMap()
        .orEmpty()
    // Base threshold applies to cells ≥1ms; smaller cells get a wider threshold via
    // [scaledThreshold]. 75% absorbs the per-call context creation noise on the Z3
    // sample path (~50% inherent spread) while still flagging real ≥2× slowdowns.
    val thresholdPct = System.getProperty("klause.bench.regressionThresholdPct")?.toDoubleOrNull() ?: 75.0

    println()
    val baselineNote = if (baseline == null) "no baseline" else "vs baseline @ ${baseline.timestamp}"
    println("=== benchmark (per entry, median of $repetitions reps × $sampleCount samples; " +
        "$warmupReps warmup reps; $baselineNote) ===")
    val results = mutableListOf<EntryResult>()
    val regressions = mutableListOf<String>()
    for (entry in benchEntries) {
        val report = Benchmarker.bench(entry.problem, repetitions = repetitions, sampleCount = sampleCount, warmupReps = warmupReps)
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

    writeResults(BenchResults(
        timestamp = Instant.now().toString(),
        gitSha = BenchHarness.readGitSha(),
        env = EnvInfo.capture(),
        repetitions = repetitions,
        sampleCount = sampleCount,
        warmupReps = warmupReps,
        entries = results,
    ))

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

/**
 * Scale-aware regression threshold. Sub-100µs cells are dominated by JIT / GC jitter
 * (measured p99 spread ~170% across back-to-back runs), so we widen the threshold
 * proportionally to absolute size. Cells over 1ms are stable enough for the configured
 * threshold to apply as-is.
 */
private fun scaledThreshold(base: Double, prior: Long): Double = when {
    prior >= 1_000_000 -> base                  // ≥1ms: use configured threshold (default 100%)
    prior >= 100_000 -> base + 50.0             // 100µs–1ms: +50pp
    prior >= 10_000 -> base + 100.0             // 10µs–100µs: +100pp
    else -> base + 200.0                        // <10µs: +200pp (essentially "report only")
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
    val base = "$label=${BenchHarness.formatNs(now)}"
    if (prior == null || prior == 0L) return base
    val pct = (now - prior).toDouble() * 100.0 / prior
    val effectiveThreshold = scaledThreshold(thresholdPct, prior)
    if (pct > effectiveThreshold) {
        regressions += "$entryName/$backendName $label: ${BenchHarness.formatNs(prior)} → " +
            "${BenchHarness.formatNs(now)} (+${"%.1f".format(pct)}%, threshold ${effectiveThreshold}%)"
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
