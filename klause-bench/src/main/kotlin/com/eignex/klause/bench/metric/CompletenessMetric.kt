package com.eignex.klause.bench.metric

import com.eignex.klause.bench.report.EnvInfo
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.runner.ResolvedProblem
import com.eignex.klause.bench.solver.InProcessSolver
import com.eignex.klause.bench.solver.Solvers
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.brute.BruteForceParams
import com.eignex.klause.solver.brute.BruteForceSolver
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * Per-backend enumeration completeness for one problem: how many distinct SAT assignments
 * each backend reaches inside a sequence of wall-time budgets, optionally as a fraction of
 * the brute-force oracle total.
 */
@Serializable
internal data class CompletenessReport(
    val entryName: String,
    val backendName: String,
    val budgetsMillis: LongArray,
    val reachAtBudget: IntArray,
    val totalDistinct: Int,
    val oracleFeasibleCount: Int?,
    val coverageAtMaxBudget: Double?,
)

@Serializable
internal data class CompletenessResults(
    val timestamp: String,
    val gitSha: String?,
    val env: EnvInfo,
    val budgetsMillis: LongArray,
    val entries: List<CompletenessReport>,
)

internal object CompletenessMetric {
    private const val ORACLE_MAX_MODELS = 4096

    fun run(entries: List<ResolvedProblem>) {
        val budgets = budgetsFromProperty()
        println()
        println(
            "=== completeness bench (reach @ ${budgets.joinToString { "${it}ms" }}; coverage = " +
                "distinct/feasible when oracle fits) ===",
        )
        val results = mutableListOf<CompletenessReport>()
        for (entry in entries) {
            val rows = mutableListOf<String>()
            for (backend in Solvers.defaultPortfolio(entry.problem)) {
                val r = analyse(entry.name, backend, budgets)
                results += r
                rows += "${backend.name} reach=${r.reachAtBudget.joinToString(",")} total=${r.totalDistinct} " +
                    "cov=${r.coverageAtMaxBudget?.let { "%.2f".format(java.util.Locale.ROOT, it) } ?: "—"}"
            }
            println("[${entry.name}] ${rows.joinToString(" | ")}")
        }
        Reports.writeJson(
            "build/bench-completeness.json",
            CompletenessResults(Instant.now().toString(), Reports.readGitSha(), EnvInfo.capture(), budgets, results),
        )
    }

    fun analyse(
        entryName: String,
        backend: InProcessSolver,
        budgetsMillis: LongArray = longArrayOf(50, 250, 1000),
    ): CompletenessReport {
        val oracle = oracleSize(backend)
        val reach = IntArray(budgetsMillis.size)
        val distinct = LinkedHashSet<Sample>()
        val start = System.currentTimeMillis()
        val maxBudget = budgetsMillis.max()
        var budgetIdx = 0
        for (sample in backend.enumerateSequence()) {
            val elapsed = System.currentTimeMillis() - start
            while (budgetIdx < budgetsMillis.size && elapsed >= budgetsMillis[budgetIdx]) {
                reach[budgetIdx] = distinct.size
                budgetIdx++
            }
            if (elapsed > maxBudget) break
            distinct.add(sample)
        }
        while (budgetIdx < budgetsMillis.size) {
            reach[budgetIdx] = distinct.size
            budgetIdx++
        }
        val coverage = oracle?.let { distinct.size.toDouble() / it.coerceAtLeast(1) }
        return CompletenessReport(entryName, backend.name, budgetsMillis, reach, distinct.size, oracle, coverage)
    }

    private fun oracleSize(backend: InProcessSolver): Int? {
        if (!BruteForceSolver.fits(backend.problem)) return null
        val count = BruteForceSolver(backend.problem)
            .enumerate(BruteForceParams())
            .take(ORACLE_MAX_MODELS + 1)
            .count()
        return if (count > ORACLE_MAX_MODELS) null else count
    }

    private fun budgetsFromProperty(): LongArray {
        val raw = System.getProperty("klause.bench.completeness.budgets")
        if (raw.isNullOrBlank()) return longArrayOf(50, 250, 1000)
        val parsed = raw.split(",").mapNotNull { it.trim().toLongOrNull() }.toLongArray()
        return if (parsed.isEmpty()) longArrayOf(50, 250, 1000) else parsed
    }
}
