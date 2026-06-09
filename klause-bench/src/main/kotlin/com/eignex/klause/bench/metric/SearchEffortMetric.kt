package com.eignex.klause.bench.metric

import com.eignex.klause.bench.report.EnvInfo
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.runner.ResolvedProblem
import com.eignex.klause.logicng.LogicNGParams
import com.eignex.klause.logicng.LogicNGSolver
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackPresets
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.backtrack.Vsids
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Complete-search effort per problem, run as an A/B between two [BacktrackSolver]
 * configurations under a fixed seed and per-instance timeout — a **baseline** CDCL config
 * (VSIDS + phase saving + Luby + LBD) and the **SAT-optimized** preset
 * ([BacktrackPresets.satOptimized] — adaptive restarts, target phasing, three-tier learned DB,
 * binary-resolution minimization, vivification) — alongside the **LogicNG** (bit-blasted
 * MiniSAT) reference as a pure-SAT yardstick (the #117 comparison). For the klause configs it
 * reports the engine's own [com.eignex.klause.solver.SolveStats] — nodes, conflicts (fails),
 * learned clauses, restarts — plus the verdict and wall time; LogicNG exposes no conflict
 * counter, so only its verdict and wall time are captured.
 *
 * The conflict count is the search-size signal: a stronger SAT configuration should close the
 * same instances in fewer conflicts. The summary compares total fails over the instances both
 * klause configs solved, so the SAT-optimized stack's effect is read off directly, and lists
 * each backend's solved count for reach against the reference.
 *
 * Knobs: `-Dklause.bench.search.seed` (default 1).
 */
@Serializable
data class SearchEffortReport(
    internal val name: String,
    internal val verdict: String,
    internal val solved: Boolean,
    internal val nodes: Long,
    internal val fails: Long,
    internal val learned: Long,
    internal val restarts: Long,
    internal val wallMs: Long,
    internal val timedOut: Boolean,
)

@Serializable
internal data class SearchEffortPair(
    val name: String,
    val baseline: SearchEffortReport,
    val satOpt: SearchEffortReport,
    val logicNg: SearchEffortReport,
)

@Serializable
internal data class SearchEffortResults(
    val timestamp: String,
    val gitSha: String?,
    val env: EnvInfo,
    val seed: Long,
    val timeoutMillis: Long,
    val baselineSolved: Int,
    val satOptSolved: Int,
    val logicNgSolved: Int,
    val total: Int,
    val bothSolved: Int,
    val baselineFailsSumBothSolved: Long,
    val satOptFailsSumBothSolved: Long,
    val entries: List<SearchEffortPair>,
)

internal object SearchEffortMetric {
    fun run(entries: List<ResolvedProblem>, budget: Budget) {
        val seed = System.getProperty("klause.bench.search.seed")?.toLongOrNull() ?: 1L
        val runLogicNg = System.getProperty("klause.bench.search.logicng")?.toBooleanStrictOrNull() ?: true
        println()
        println(
            "=== search-effort A/B (baseline VSIDS+phase+Luby vs SAT-optimized preset vs LogicNG, " +
                "seed=$seed, ${budget.timeoutMillis}ms/instance) ===",
        )
        println(
            "%-20s %18s %18s %16s %7s %7s %7s".format(
                Locale.ROOT,
                "instance",
                "base verdict/fails",
                "sat verdict/fails",
                "logicng verdict",
                "base ms",
                "sat ms",
                "lng ms",
            ),
        )
        val pairs = mutableListOf<SearchEffortPair>()
        for (e in entries) {
            val baseline = solveWith(e, budget) { deadline ->
                BacktrackParams(
                    randomSeed = seed,
                    variableHeuristic = Vsids(),
                    phaseSaving = true,
                    lubyRestartBase = 100L,
                    maxLearnedClauses = 20_000,
                    cancellation = Cancellation { System.currentTimeMillis() > deadline },
                )
            }
            val satOpt = solveWith(e, budget) { deadline ->
                BacktrackPresets.satOptimized(
                    randomSeed = seed,
                    cancellation = Cancellation { System.currentTimeMillis() > deadline },
                )
            }
            // The LogicNG reference leg translates CP problems to CNF, which on large
            // instances dominates allocation/GC and pollutes a klause CPU profile. Set
            // -Dklause.bench.search.logicng=false to skip it for clean engine profiling.
            val logicNg = if (runLogicNg) {
                solveLogicNg(e, budget.timeoutMillis)
            } else {
                SearchEffortReport(e.name, "off", solved = false, 0, 0, 0, 0, wallMs = 0, timedOut = false)
            }
            pairs += SearchEffortPair(e.name, baseline, satOpt, logicNg)
            println(
                "%-20s %10s/%-7d %10s/%-7d %16s %7d %7d %7d".format(
                    Locale.ROOT,
                    e.name.take(20),
                    baseline.verdict.take(10),
                    baseline.fails,
                    satOpt.verdict.take(10),
                    satOpt.fails,
                    logicNg.verdict.take(16),
                    baseline.wallMs,
                    satOpt.wallMs,
                    logicNg.wallMs,
                ),
            )
        }
        val both = pairs.filter { it.baseline.solved && it.satOpt.solved }
        val baseSum = both.sumOf { it.baseline.fails }
        val satSum = both.sumOf { it.satOpt.fails }
        val baseSolved = pairs.count { it.baseline.solved }
        val satSolved = pairs.count { it.satOpt.solved }
        val lngSolved = pairs.count { it.logicNg.solved }
        println(
            "--- solved: baseline $baseSolved/${pairs.size}, sat-opt $satSolved/${pairs.size}, " +
                "logicng $lngSolved/${pairs.size}  |  fails over base+sat both-solved (${both.size}): " +
                "baseline=$baseSum sat-opt=$satSum" +
                (if (baseSum > 0) " (%.2fx)".format(Locale.ROOT, satSum.toDouble() / baseSum) else "") + " ---",
        )
        Reports.writeJson(
            "build/bench-search.json",
            SearchEffortResults(
                timestamp = Instant.now().toString(),
                gitSha = Reports.readGitSha(),
                env = EnvInfo.capture(),
                seed = seed,
                timeoutMillis = budget.timeoutMillis,
                baselineSolved = baseSolved,
                satOptSolved = satSolved,
                logicNgSolved = lngSolved,
                total = pairs.size,
                bothSolved = both.size,
                baselineFailsSumBothSolved = baseSum,
                satOptFailsSumBothSolved = satSum,
                entries = pairs,
            ),
        )
    }

    /** Run one config over [e] under a fresh per-instance deadline, capturing its effort stats. */
    private fun solveWith(
        e: ResolvedProblem,
        budget: Budget,
        params: (deadline: Long) -> BacktrackParams,
    ): SearchEffortReport {
        val deadline = System.currentTimeMillis() + budget.timeoutMillis
        val start = System.currentTimeMillis()
        val result = runCatching { BacktrackSolver(e.problem).solve(params(deadline)) }.getOrNull()
        val ms = System.currentTimeMillis() - start
        val st = result?.stats
        return SearchEffortReport(
            name = e.name,
            verdict = result?.let { it::class.simpleName ?: "?" } ?: "ERROR",
            solved = result is SolveResult.Sat || result is SolveResult.Unsat,
            nodes = (st?.nodes?.sum ?: 0.0).toLong(),
            fails = (st?.fails?.sum ?: 0.0).toLong(),
            learned = (st?.learnedClauses?.sum ?: 0.0).toLong(),
            restarts = (st?.restarts?.sum ?: 0.0).toLong(),
            wallMs = ms,
            timedOut = st?.timedOut ?: false,
        )
    }

    /**
     * Reference comparison: solve [e] with the LogicNG (bit-blasted MiniSAT) backend, the
     * pure-SAT yardstick for #117. LogicNG's single `sat()` call can't be interrupted
     * mid-solve, so run it on a worker thread and join with [timeoutMillis]; a thread still
     * running past the deadline is left to finish in the background and reported as a timeout.
     * LogicNG reports no CDCL conflict counter, so only the verdict and wall time are captured.
     */
    private fun solveLogicNg(e: ResolvedProblem, timeoutMillis: Long): SearchEffortReport {
        // Single-element holder written by the worker; safe to read after a completed join
        // (thread termination establishes a happens-before edge). When the worker is still
        // alive past the deadline we ignore the holder and report a timeout.
        val holder = arrayOfNulls<SolveResult>(1)
        val start = System.currentTimeMillis()
        val worker = thread(start = true, isDaemon = true, name = "logicng-${e.name}") {
            holder[0] = runCatching {
                LogicNGSolver(e.problem).solve(LogicNGParams(randomSeed = 0L, timeoutMillis = timeoutMillis))
            }.getOrNull()
        }
        worker.join(timeoutMillis + JOIN_GRACE_MS)
        val ms = System.currentTimeMillis() - start
        val r = if (worker.isAlive) null else holder[0]
        return SearchEffortReport(
            name = e.name,
            verdict = if (worker.isAlive) "Timeout" else (r?.let { it::class.simpleName ?: "?" } ?: "ERROR"),
            solved = r is SolveResult.Sat || r is SolveResult.Unsat,
            nodes = 0,
            fails = 0,
            learned = 0,
            restarts = 0,
            wallMs = ms,
            timedOut = worker.isAlive,
        )
    }

    private const val JOIN_GRACE_MS = 2_000L
}
