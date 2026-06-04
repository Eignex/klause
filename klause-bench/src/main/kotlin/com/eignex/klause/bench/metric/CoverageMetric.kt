package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.report.EnvInfo
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.report.markdown
import com.eignex.klause.bench.runner.MiniZincRunner
import java.time.Instant
import kotlinx.serialization.Serializable

/**
 * Native-predicate coverage: compile each MiniZinc instance to FlatZinc against klause's
 * redefinitions, then measure what fraction of the surviving constraint predicates klause
 * handles **natively** (vs decomposed by MiniZinc's standard library). The headline number to
 * push toward 100%.
 *
 * "Native" = every predicate declared in klause's `redefinitions.mzn` plus every constraint
 * name the `klause-cli` FlatZinc parser dispatches on (read from `FlatZincConstraints.kt`).
 */
@Serializable
data class CoverageRow(
    val name: String,
    val nativeCount: Int,
    val decomposedCount: Int,
    val coverage: Double,
    /** Decomposed predicate names with counts, for spotting what to make native next. */
    val decomposedPredicates: Map<String, Int>,
)

@Serializable
data class CoverageResults(
    val timestamp: String,
    val gitSha: String?,
    val env: EnvInfo,
    val rows: List<CoverageRow>,
    val overallCoverage: Double,
)

object CoverageMetric {
    fun run(refs: List<ProblemRef>) {
        val runner = MiniZincRunner()
        val nativeSet = FznPredicates.nativeSet
        println()
        println("=== native-predicate coverage (klause native vs MiniZinc-decomposed; push toward 100%) ===")
        val rows = refs.mapNotNull { ref -> row(ref, runner, nativeSet) }
        for (r in rows) {
            val worst = r.decomposedPredicates.entries.sortedByDescending { it.value }.take(3)
                .joinToString(", ") { "${it.key}×${it.value}" }
            println("[${r.name}] ${"%.0f".format(r.coverage * 100)}% native (${r.nativeCount}/${r.nativeCount + r.decomposedCount})" +
                if (worst.isNotEmpty()) " — decomposed: $worst" else "")
        }
        val overall = rows.sumOf { it.nativeCount }.toDouble() /
            rows.sumOf { it.nativeCount + it.decomposedCount }.coerceAtLeast(1)
        println("\noverall native coverage: ${"%.1f".format(overall * 100)}% over ${rows.size} instances")

        Reports.writeJson("build/coverage-report.json",
            CoverageResults(Instant.now().toString(), Reports.readGitSha(), EnvInfo.capture(), rows, overall))
        Reports.writeMarkdown("build/coverage-report.md", markdown {
            h1("Native-predicate coverage")
            para("Overall: **${"%.1f".format(overall * 100)}%** native over ${rows.size} instances.")
            table(listOf("instance", "coverage", "native", "decomposed", "top decomposed"),
                rows.sortedBy { it.coverage }.map { r ->
                    listOf(r.name, "${"%.0f".format(r.coverage * 100)}%", r.nativeCount, r.decomposedCount,
                        r.decomposedPredicates.entries.sortedByDescending { it.value }.take(3).joinToString(", ") { "${it.key}×${it.value}" })
                })
        })
    }

    private fun row(ref: ProblemRef, runner: MiniZincRunner, nativeSet: Set<String>): CoverageRow? {
        val fzn = runCatching { runner.compileFzn(ref) }.getOrNull() ?: return null
        val counts = FznPredicates.counts(fzn)
        val native = counts.filterKeys { it in nativeSet }
        val decomposed = counts.filterKeys { it !in nativeSet }
        val nativeCount = native.values.sum()
        val decomposedCount = decomposed.values.sum()
        val total = nativeCount + decomposedCount
        return CoverageRow(ref.name, nativeCount, decomposedCount,
            if (total == 0) 1.0 else nativeCount.toDouble() / total, decomposed)
    }
}
