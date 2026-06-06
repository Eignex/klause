package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.Expected
import com.eignex.klause.bench.report.EnvInfo
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.runner.ResolvedProblem
import com.eignex.klause.bench.solver.Backend
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.MinimizeResult
import com.eignex.klause.solver.Objective
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import java.time.Instant
import kotlinx.serialization.Serializable

/**
 * Differential parity: solve each problem with **klause** (complete backtracking) and an
 * in-process [Reference] solver (Choco or OR-Tools) on the same
 * [com.eignex.klause.solver.Problem], then check both against each other and against the
 * recorded [Expected] oracle. The comparison is in-process — no external solver binary.
 *
 *  - satisfaction problems compare feasibility (SAT/UNSAT) three ways (klause, reference, expected);
 *  - optimization problems additionally compare the optimal objective value.
 *
 * The reference defaults to the target's choice and can be overridden with
 * `-Dklause.bench.parity.reference=choco|ortools`.
 */
@Serializable
data class ParityRow(
    val name: String,
    val kind: String,            // "satisfy" | "optimize"
    val verdict: String,         // OK | MISMATCH | KLAUSE_ERROR | REFERENCE_ERROR
    val klause: String,
    val reference: String,
    val referenceSolver: String, // "choco" | "ortools"
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
    fun run(entries: List<ResolvedProblem>, budget: Budget = Budget(), reference: Backend = Backend.CHOCO) {
        val ref = System.getProperty("klause.bench.parity.reference")?.let { Reference.byId(it) } ?: Reference.of(reference)
        println()
        println("=== parity (klause backtrack vs ${ref.name} reference; checked against recorded expected) ===")
        val rows = entries.map { entry ->
            val r = row(entry, budget, ref)
            val mark = if (r.verdict == "OK") "ok " else "!! "
            println("$mark[${r.name}] ${r.kind} klause=${r.klause} ${r.referenceSolver}=${r.reference} expected=${r.expected}" +
                if (r.detail.isNotEmpty()) " — ${r.detail}" else "")
            r
        }
        val results = ParityResults(Instant.now().toString(), Reports.readGitSha(), EnvInfo.capture(), rows)
        Reports.writeJson("build/parity-report.json", results)
        println("\n${rows.count { it.verdict == "OK" }}/${rows.size} OK, ${results.mismatches} mismatch(es)")
        if (System.getProperty("klause.bench.parity.failOnMismatch")?.toBoolean() == true && results.mismatches > 0) {
            error("${results.mismatches} parity mismatch(es)")
        }
    }

    private fun row(entry: ResolvedProblem, budget: Budget, ref: Reference): ParityRow {
        val obj = entry.objective
        return if (obj == null) satisfyRow(entry, budget, ref) else optimizeRow(entry, obj, budget, ref)
    }

    private fun satisfyRow(entry: ResolvedProblem, budget: Budget, ref: Reference): ParityRow {
        val klause = runCatching { feasibility(BacktrackSolver(entry.problem).solve(btParams(budget, entry))) }
            .getOrElse { return errorRow(entry, "satisfy", "KLAUSE_ERROR", ref, it) }
        val refFeas = runCatching { feasibility(ref.solve(entry.problem, budget)) }
            .getOrElse { return errorRow(entry, "satisfy", "REFERENCE_ERROR", ref, it) }
        val exp = expectedFeasible(entry.ref.expected)
        val verdict = when {
            !agree(klause, refFeas) -> "MISMATCH"
            exp != null && refFeas != null && refFeas != exp -> "MISMATCH"
            else -> "OK"
        }
        return ParityRow(entry.name, "satisfy", verdict, feasStr(klause), feasStr(refFeas), ref.name, feasStr(exp))
    }

    private fun optimizeRow(entry: ResolvedProblem, obj: Objective, budget: Budget, ref: Reference): ParityRow {
        val klause = runCatching { BacktrackSolver(entry.problem).minimize(obj, btParams(budget, entry)) }
            .getOrElse { return errorRow(entry, "optimize", "KLAUSE_ERROR", ref, it) }
        val refRes = runCatching { ref.minimize(entry.problem, obj, budget) }
            .getOrElse { return errorRow(entry, "optimize", "REFERENCE_ERROR", ref, it) }
        val kv = klause.objectiveValue
        val cv = refRes.objectiveValue
        val exp = (entry.ref.expected as? Expected.Opt)?.value?.toDouble()
        val verdict = when {
            kv == null || cv == null -> if (kv == cv) "OK" else "MISMATCH"
            kv != cv -> "MISMATCH"
            exp != null && cv != exp -> "MISMATCH"
            else -> "OK"
        }
        return ParityRow(entry.name, "optimize", verdict,
            optStr(klause), optStr(refRes), ref.name, exp?.toString() ?: "?")
    }

    private fun btParams(budget: Budget, entry: ResolvedProblem): BacktrackParams {
        val deadline = System.currentTimeMillis() + budget.timeoutMillis
        // Start from the model's search annotation (when present) so benchmark runs honour
        // the author's intended heuristics the same way the competition CLI does, then
        // layer the bench's budget/seed/restart config on top.
        // Luby restarts: the anytime configuration. Branch-and-bound leaves a permanent
        // blocking nogood per incumbent, so restarts diversify without revisiting solved
        // leaves; on plateau-prone instances they are the difference between stalling on
        // the first incumbents and walking to the optimum.
        return (entry.searchParams ?: BacktrackParams()).copy(
            randomSeed = 1L,
            lubyRestartBase = 256L,
            cancellation = Cancellation { System.currentTimeMillis() > deadline },
        )
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

    private fun errorRow(entry: ResolvedProblem, kind: String, verdict: String, ref: Reference, t: Throwable): ParityRow {
        if (System.getProperty("klause.bench.parity.trace")?.toBoolean() == true) {
            System.err.println(">>> $verdict on ${entry.name}:")
            t.printStackTrace()
        }
        return ParityRow(
            entry.name, kind, verdict, "-", "-", ref.name, entry.ref.expected.toString(),
            t.message?.take(160) ?: t::class.simpleName ?: "error",
        )
    }
}
