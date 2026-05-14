package com.eignex.klause.bench

import java.time.Instant
import kotlinx.serialization.Serializable

@Serializable
data class CompletenessResults(
    val timestamp: String,
    val gitSha: String?,
    val budgetsMillis: LongArray,
    val entries: List<CompletenessReport>,
)

/**
 * Entry point: `./gradlew :klause-bench:runCompleteness`. For each (entry, backend),
 * runs `enumerate` under a sequence of wall-time budgets and reports how many distinct SAT
 * leaves each backend reached. Backends that hit `Unsat` early count whatever they yielded.
 */
fun main() {
    val budgets = budgetsFromProperty()
    val (_, benchEntries, _) = BenchHarness.loadAndVerify()
    println()
    println("=== completeness bench (reach @ ${budgets.joinToString { "${it}ms" }}; coverage = " +
        "distinct/feasible when oracle fits) ===")
    val results = mutableListOf<CompletenessReport>()
    for (entry in benchEntries) {
        val rows = mutableListOf<String>()
        for (backend in defaultSolvers(entry.problem)) {
            val r = CompletenessBench.analyse(entry.name, backend, budgets)
            results += r
            val reachStr = r.reachAtBudget.joinToString(",")
            val cov = r.coverageAtMaxBudget?.let { "%.2f".format(it) } ?: "—"
            rows += "${backend.name} reach=$reachStr total=${r.totalDistinct} cov=$cov"
        }
        println("[${entry.name}] ${rows.joinToString(" | ")}")
    }
    BenchHarness.writeJson(
        "build/bench-completeness.json",
        CompletenessResults(Instant.now().toString(), BenchHarness.readGitSha(), budgets, results),
    )
}

private fun budgetsFromProperty(): LongArray {
    val raw = System.getProperty("klause.bench.completeness.budgets")
    if (raw.isNullOrBlank()) return longArrayOf(50, 250, 1000)
    val parsed = raw.split(",").mapNotNull { it.trim().toLongOrNull() }.toLongArray()
    return if (parsed.isEmpty()) longArrayOf(50, 250, 1000) else parsed
}
