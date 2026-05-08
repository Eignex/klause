package com.eignex.klause.bench

import com.eignex.klause.solver.SolveResult

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

    println()
    println("=== benchmark (per entry, median of 3 reps × 5 samples) ===")
    for (entry in benchEntries) {
        val report = Benchmarker.bench(entry.problem, repetitions = 3, sampleCount = 5)
        val cells = report.timings.entries.joinToString(" | ") { (name, t) ->
            "$name solve=${formatNs(median(t.solveNanos))} " +
                "sample=${formatNs(median(t.sampleNanos))} " +
                "enum=${formatNs(median(t.enumerateNanos))}"
        }
        println("[${entry.name}] $cells")
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
