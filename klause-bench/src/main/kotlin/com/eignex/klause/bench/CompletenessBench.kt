package com.eignex.klause.bench

import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.brute.BruteForceParams
import com.eignex.klause.solver.brute.BruteForceSolver
import kotlinx.serialization.Serializable

/**
 * Per-backend enumeration completeness for one [com.eignex.klause.solver.Problem]: how
 * many distinct SAT assignments each backend reaches inside a sequence of wall-time
 * budgets. When the feasible space is enumerable via [BruteForceSolver], reach is also
 * reported as a fraction of the total ([oracleFeasibleCount]).
 */
@Serializable
data class CompletenessReport(
    val entryName: String,
    val backendName: String,
    val budgetsMillis: LongArray,
    val reachAtBudget: IntArray,
    val totalDistinct: Int,
    val oracleFeasibleCount: Int?,
    /** Fraction of feasible models reached at the largest budget, when known. */
    val coverageAtMaxBudget: Double?,
)

object CompletenessBench {

    private const val ORACLE_MAX_MODELS = 4096

    fun analyse(
        entryName: String,
        backend: BenchSolver,
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
            // Snapshot reach at any budgets crossed since the last sample.
            while (budgetIdx < budgetsMillis.size && elapsed >= budgetsMillis[budgetIdx]) {
                reach[budgetIdx] = distinct.size
                budgetIdx++
            }
            if (elapsed > maxBudget) break
            distinct.add(sample)
        }
        // Snapshot any remaining budgets the loop didn't cross (search ended early).
        while (budgetIdx < budgetsMillis.size) {
            reach[budgetIdx] = distinct.size
            budgetIdx++
        }
        val coverage = oracle?.let { distinct.size.toDouble() / it.coerceAtLeast(1) }
        return CompletenessReport(
            entryName = entryName,
            backendName = backend.name,
            budgetsMillis = budgetsMillis,
            reachAtBudget = reach,
            totalDistinct = distinct.size,
            oracleFeasibleCount = oracle,
            coverageAtMaxBudget = coverage,
        )
    }

    private fun oracleSize(backend: BenchSolver): Int? {
        if (!BruteForceSolver.fits(backend.problem)) return null
        val count = BruteForceSolver(backend.problem)
            .enumerate(BruteForceParams())
            .take(ORACLE_MAX_MODELS + 1)
            .count()
        return if (count > ORACLE_MAX_MODELS) null else count
    }
}
