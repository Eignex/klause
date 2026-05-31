package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.Expected
import com.eignex.klause.bench.report.EnvInfo
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.runner.ResolvedProblem
import com.eignex.klause.choco.ChocoParams
import com.eignex.klause.choco.ChocoSolver
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.MinimizeResult
import com.eignex.klause.solver.Objective
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import java.time.Instant
import kotlinx.serialization.Serializable

/**
 * Differential parity: solve each problem with **klause** (complete backtracking) and the
 * **Choco** reference on the same in-process [com.eignex.klause.solver.Problem], then check
 * both against each other and against the recorded [Expected] oracle. Replaces the legacy
 * `MznParity`'s "klause vs Gecode via the minizinc CLI" with an in-process comparison — no
 * external solver binary.
 *
 *  - satisfaction problems compare feasibility (SAT/UNSAT) three ways (klause, choco, expected);
 *  - optimization problems additionally compare the optimal objective value.
 */
@Serializable
data class ParityRow(
    val name: String,
    val kind: String,            // "satisfy" | "optimize"
    val verdict: String,         // OK | MISMATCH | KLAUSE_ERROR | CHOCO_ERROR
    val klause: String,
    val choco: String,
    val expected: String,
    val detail: String = "",
)

@Serializable
data class ParityResults(
    val timestamp: String,
    val gitSha: String?,
    val env: EnvInfo,
    val rows: List<ParityRow>,
) {
    val mismatches: Int get() = rows.count { it.verdict != "OK" }
}

object ParityMetric {
    fun run(entries: List<ResolvedProblem>, budget: Budget = Budget()) {
        println()
        println("=== parity (klause backtrack vs choco reference; checked against recorded expected) ===")
        val rows = entries.map { row(it, budget) }
        for (r in rows) {
            val mark = if (r.verdict == "OK") "ok " else "!! "
            println("$mark[${r.name}] ${r.kind} klause=${r.klause} choco=${r.choco} expected=${r.expected}" +
                if (r.detail.isNotEmpty()) " — ${r.detail}" else "")
        }
        val results = ParityResults(Instant.now().toString(), Reports.readGitSha(), EnvInfo.capture(), rows)
        Reports.writeJson("build/parity-report.json", results)
        println("\n${rows.count { it.verdict == "OK" }}/${rows.size} OK, ${results.mismatches} mismatch(es)")
        if (System.getProperty("klause.bench.parity.failOnMismatch")?.toBoolean() == true && results.mismatches > 0) {
            error("${results.mismatches} parity mismatch(es)")
        }
    }

    private fun row(entry: ResolvedProblem, budget: Budget): ParityRow {
        val obj = entry.objective
        return if (obj == null) satisfyRow(entry, budget) else optimizeRow(entry, obj, budget)
    }

    private fun satisfyRow(entry: ResolvedProblem, budget: Budget): ParityRow {
        val klause = runCatching { feasibility(BacktrackSolver(entry.problem).solve(btParams(budget))) }
            .getOrElse { return errorRow(entry, "satisfy", "KLAUSE_ERROR", it) }
        val choco = runCatching { feasibility(ChocoSolver(entry.problem).solve(ChocoParams(budget.timeoutMillis))) }
            .getOrElse { return errorRow(entry, "satisfy", "CHOCO_ERROR", it) }
        val exp = expectedFeasible(entry.ref.expected)
        val verdict = when {
            !agree(klause, choco) -> "MISMATCH"
            exp != null && choco != null && choco != exp -> "MISMATCH"
            else -> "OK"
        }
        return ParityRow(entry.name, "satisfy", verdict, feasStr(klause), feasStr(choco), feasStr(exp))
    }

    private fun optimizeRow(entry: ResolvedProblem, obj: Objective, budget: Budget): ParityRow {
        val klause = runCatching { BacktrackSolver(entry.problem).minimize(obj, btParams(budget)) }
            .getOrElse { return errorRow(entry, "optimize", "KLAUSE_ERROR", it) }
        val choco = runCatching { ChocoSolver(entry.problem).minimize(obj, ChocoParams(budget.timeoutMillis)) }
            .getOrElse { return errorRow(entry, "optimize", "CHOCO_ERROR", it) }
        val kv = klause.objectiveValue
        val cv = choco.objectiveValue
        val exp = (entry.ref.expected as? Expected.Opt)?.value?.toDouble()
        val verdict = when {
            kv == null || cv == null -> if (kv == cv) "OK" else "MISMATCH"
            kv != cv -> "MISMATCH"
            exp != null && cv != exp -> "MISMATCH"
            else -> "OK"
        }
        return ParityRow(entry.name, "optimize", verdict,
            optStr(klause), optStr(choco), exp?.toString() ?: "?")
    }

    private fun btParams(budget: Budget): BacktrackParams {
        val deadline = System.currentTimeMillis() + budget.timeoutMillis
        return BacktrackParams(randomSeed = 1L, cancellation = Cancellation { System.currentTimeMillis() > deadline })
    }

    /** true = feasible, false = infeasible, null = unknown/timeout. */
    private fun feasibility(r: SolveResult): Boolean? = when (r) {
        is SolveResult.Sat -> true
        is SolveResult.Unsat -> false
        is SolveResult.Unknown -> null
    }

    private fun expectedFeasible(e: Expected): Boolean? = when (e) {
        Expected.Sat -> true
        Expected.Unsat -> false
        is Expected.Opt -> true
        Expected.Unknown -> null
    }

    /** Two feasibility verdicts agree if neither is a definite contradiction of the other. */
    private fun agree(a: Boolean?, b: Boolean?): Boolean = a == null || b == null || a == b

    private fun feasStr(b: Boolean?): String = when (b) { true -> "SAT"; false -> "UNSAT"; null -> "?" }

    private fun optStr(r: MinimizeResult): String = when (r) {
        is MinimizeResult.Optimal -> "opt=${r.objective}"
        is MinimizeResult.BestFound -> "best=${r.objective}"
        is MinimizeResult.Infeasible -> "UNSAT"
        is MinimizeResult.Unknown -> "?"
    }

    private fun errorRow(entry: ResolvedProblem, kind: String, verdict: String, t: Throwable) = ParityRow(
        entry.name, kind, verdict, "-", "-", entry.ref.expected.toString(), t.message?.take(160) ?: t::class.simpleName ?: "error",
    )
}
