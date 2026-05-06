package com.eignex.klause.bench

import com.eignex.klause.solver.SolveResult

fun main() {
    println("=== verification ===")
    var disagreements = 0
    for (entry in Portfolio.all) {
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
    println("=== benchmark (first sat entry) ===")
    val benchEntry = Portfolio.sat.first()
    val report = Benchmarker.bench(benchEntry.problem, repetitions = 3, sampleCount = 5)
    for ((name, t) in report.timings) {
        println("  $name: " +
            "solve=${summarize(t.solveNanos)} " +
            "sample=${summarize(t.sampleNanos)} " +
            "enumerate=${summarize(t.enumerateNanos)}")
    }
}

private fun formatVerdict(v: SolveResult): String = when (v) {
    is SolveResult.Sat -> "Sat"
    SolveResult.Unsat -> "Unsat"
    SolveResult.Unknown -> "Unknown"
}

private fun summarize(times: LongArray): String {
    if (times.isEmpty()) return "-"
    val sorted = times.sortedArray()
    val median = sorted[sorted.size / 2]
    return "${formatNs(sorted.first())}-${formatNs(sorted.last())} (median ${formatNs(median)})"
}

private fun formatNs(ns: Long): String = when {
    ns < 1_000 -> "${ns}ns"
    ns < 1_000_000 -> "${ns / 1_000}µs"
    ns < 1_000_000_000 -> "${ns / 1_000_000}ms"
    else -> "${ns / 1_000_000_000}s"
}
