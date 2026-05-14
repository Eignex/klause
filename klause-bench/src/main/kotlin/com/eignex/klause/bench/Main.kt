package com.eignex.klause.bench

import com.eignex.klause.solver.SolveResult
import java.io.File
import java.time.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

fun main() {
    val dimacs = DimacsLoader.loadBundled()
    val opb = OpbLoader.loadBundled()
    val jsonSchema = JsonSchemaLoader.loadBundled()
    val satlib = SatlibLoader.discover()
    val externals = dimacs + opb + jsonSchema + satlib
    val verifyEntries = Portfolio.all + externals
    val benchEntries = Portfolio.sat + externals.filter { it.expectedSat }

    println(
        "=== verification (${verifyEntries.size} entries: " +
            "${Portfolio.all.size} hard-coded, " +
            "${dimacs.size} DIMACS, " +
            "${opb.size} OPB, " +
            "${jsonSchema.size} JSON-Schema, " +
            "${satlib.size} SATLIB" +
            (if (satlib.isEmpty()) " — run :klause-bench:downloadSatlib to enable" else "") +
            ") ==="
    )
    var disagreements = 0
    for (entry in verifyEntries) {
        val report = Verifier.verify(entry.problem)
        val verdicts = report.verdicts.entries.joinToString(", ") {
            "${it.key}=${formatVerdict(it.value)}"
        }
        val sampleSummary = report.sampleChecks.entries.joinToString(", ") {
            "${it.key}=${it.value.count { c -> c.satisfies }}/${it.value.size}"
        }
        println("[${entry.name}] agreement=${report.agreement} verdicts={$verdicts} samples-ok={$sampleSummary}")
        if (report.agreement == Agreement.Disagree) disagreements++
        require(report.allSamplesSatisfy) {
            "${entry.name}: at least one backend produced a sample that does not satisfy the problem"
        }
    }
    if (disagreements > 0) error("$disagreements portfolio entries disagreed across backends")

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

    writeResults(BenchResults(timestamp = Instant.now().toString(), gitSha = readGitSha(), entries = results))

    println()
    println("=== propagation microbenchmark (mean of 50 reps × 10 pins) ===")
    for (entry in benchEntries) {
        if (entry.problem.numBoolVars < 2) continue  // need at least a few pinnable bools
        val pinCount = minOf(10, entry.problem.numBoolVars - 1).coerceAtLeast(1)
        val t = PropagationBench.bench(entry.problem, pinCount = pinCount)
        println(
            "[${entry.name}] bake=${formatNs(t.bakeNanos)} " +
                "one-shot[${t.pinCount} pins]=${formatNs(t.oneShotPinNanos)} " +
                "incr/pin=${formatNs(t.incrementalPinNanos)}"
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

private fun formatVerdict(v: SolveResult): String = when (v) {
    is SolveResult.Sat -> "Sat"
    SolveResult.Unsat -> "Unsat"
    SolveResult.Unknown -> "Unknown"
}

private fun formatNs(ns: Long): String = when {
    ns < 1_000 -> "${ns}ns"
    ns < 1_000_000 -> "${ns / 1_000}µs"
    ns < 1_000_000_000 -> "${ns / 1_000_000}ms"
    else -> "${ns / 1_000_000_000}s"
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
    val base = "$label=${formatNs(now)}"
    if (prior == null || prior == 0L) return base
    val pct = (now - prior).toDouble() * 100.0 / prior
    if (pct > thresholdPct) {
        regressions += "$entryName/$backendName $label: ${formatNs(prior)} → ${formatNs(now)} (+${"%.1f".format(pct)}%)"
    }
    val sign = if (pct >= 0) "+" else ""
    return "$base (Δ$sign${"%.0f".format(pct)}%)"
}

private const val RESULTS_PATH = "build/bench-results.json"
private const val BASELINE_PATH = "bench-baseline.json"

private val json = Json { prettyPrint = true; encodeDefaults = true }

private fun writeResults(results: BenchResults) {
    val file = File(RESULTS_PATH)
    file.parentFile?.mkdirs()
    file.writeText(json.encodeToString(results))
    println()
    println("wrote $RESULTS_PATH")
}

private fun loadBaseline(): BenchResults? {
    val file = File(BASELINE_PATH)
    if (!file.exists()) return null
    return runCatching { json.decodeFromString<BenchResults>(file.readText()) }.getOrNull()
}

private fun readGitSha(): String? = runCatching {
    val proc = ProcessBuilder("git", "rev-parse", "HEAD")
        .redirectErrorStream(true)
        .start()
    val out = proc.inputStream.bufferedReader().readText().trim()
    if (proc.waitFor() == 0 && out.isNotEmpty()) out else null
}.getOrNull()
