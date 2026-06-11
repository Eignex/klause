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
import com.eignex.klause.solver.backtrack.IndomainMin
import com.eignex.klause.solver.backtrack.RegressionVariableHeuristic
import com.eignex.klause.solver.backtrack.SolutionGuided
import com.eignex.klause.solver.backtrack.Vsids
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SolveStats
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Complete-search effort per problem, run as an A/B between two [BacktrackSolver] configurations
 * under a fixed seed and per-instance timeout, alongside the **LogicNG** (bit-blasted MiniSAT)
 * reference as a pure-SAT yardstick (the #117 comparison). The two legs (`legA`/`legB`) are chosen
 * from a named palette — `vsids` (the historical baseline: VSIDS + phase + Luby + LBD), `satopt`
 * ([BacktrackPresets.satOptimized]), `conflict` ([BacktrackPresets.conflictDriven]), and `linucb`
 * (the learned [RegressionVariableHeuristic]) — so any heuristic/explanation change can be A/B'd by
 * holding the suite fixed; the default pair `vsids` vs `satopt` preserves the original comparison.
 *
 * Each leg reports the engine's own [SolveStats] — nodes, conflicts (fails), learned clauses,
 * restarts — plus verdict and wall time (LogicNG exposes no conflict counter, so only verdict +
 * wall time). A **CSP** runs through [BacktrackSolver.solve] (satisfaction); a **COP** (the problem
 * carries an objective) runs through branch-and-bound [BacktrackSolver.minimize] and additionally
 * reports the best objective reached.
 *
 * Two summaries: for CSP/UNSAT the conflict count is the search-size signal (fewer fails over the
 * both-solved set = the stronger config); for COP the objective head-to-head (who reached the
 * better bound over the both-feasible set) is the quality signal the fails count can't capture.
 *
 * Knobs: `-Dklause.bench.search.{seed,legA,legB,logicng}`.
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
    /** Best objective reached (COP); null for a CSP or an unsolved/errored run. The COP-quality
     *  signal — for optimization the leg that closes more instances *and* reaches a better bound
     *  wins, where the fails count alone (effort) does not capture solution quality. */
    internal val objective: Double? = null,
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
    /** The two backtrack configs compared (`legA`/`legB`); see [SearchEffortMetric.legParams]. */
    val legA: String = "vsids",
    val legB: String = "satopt",
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
        // The two backtrack configs to A/B (#8 follow-up). Default is the historical baseline pair
        // (vsids vs satopt); set `-Dklause.bench.search.legA/legB` to compare any of vsids / satopt /
        // conflict / linucb — e.g. legA=conflict legB=linucb to ask whether the learned LinUCB arm
        // subsumes conflict-driven on a COP slice (read the objective column, not just fails).
        val legA = System.getProperty("klause.bench.search.legA") ?: "vsids"
        val legB = System.getProperty("klause.bench.search.legB") ?: "satopt"
        println()
        println(
            "=== search-effort A/B (legA=$legA vs legB=$legB vs LogicNG, " +
                "seed=$seed, ${budget.timeoutMillis}ms/instance) ===",
        )
        println(
            "%-20s %16s %12s %16s %12s %7s %7s".format(
                Locale.ROOT,
                "instance",
                "$legA v/fails",
                "$legA obj",
                "$legB v/fails",
                "$legB obj",
                "$legA ms",
                "$legB ms",
            ),
        )
        val pairs = mutableListOf<SearchEffortPair>()
        for (e in entries) {
            val baseline = solveWith(e, budget) { deadline -> legParams(legA, seed, deadline) }
            val satOpt = solveWith(e, budget) { deadline -> legParams(legB, seed, deadline) }
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
                "%-20s %8s/%-7d %12s %8s/%-7d %12s %7d %7d".format(
                    Locale.ROOT,
                    e.name.take(20),
                    baseline.verdict.take(8),
                    baseline.fails,
                    baseline.objective?.let { "%.3g".format(Locale.ROOT, it) } ?: "-",
                    satOpt.verdict.take(8),
                    satOpt.fails,
                    satOpt.objective?.let { "%.3g".format(Locale.ROOT, it) } ?: "-",
                    baseline.wallMs,
                    satOpt.wallMs,
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
            "--- solved: $legA $baseSolved/${pairs.size}, $legB $satSolved/${pairs.size}, " +
                "logicng $lngSolved/${pairs.size}  |  fails over both-solved (${both.size}): " +
                "$legA=$baseSum $legB=$satSum" +
                (if (baseSum > 0) " (%.2fx)".format(Locale.ROOT, satSum.toDouble() / baseSum) else "") + " ---",
        )
        // COP head-to-head: among instances where both legs found a feasible objective, who reached
        // the better (lower — klause objectives are internally minimize) bound. The quality signal
        // the fails count can't show: on COP neither leg may prove optimality, so reach + bound is
        // what separates them.
        val bothFeasible = pairs.mapNotNull { p ->
            val a = p.baseline.objective
            val b = p.satOpt.objective
            if (a != null && b != null) a to b else null
        }
        if (bothFeasible.isNotEmpty()) {
            val aBetter = bothFeasible.count { (a, b) -> a < b }
            val bBetter = bothFeasible.count { (a, b) -> b < a }
            val tie = bothFeasible.size - aBetter - bBetter
            val aFeas = pairs.count { it.baseline.objective != null }
            val bFeas = pairs.count { it.satOpt.objective != null }
            println(
                "--- objective (COP): feasible $legA=$aFeas $legB=$bFeas  |  better bound over " +
                    "both-feasible (${bothFeasible.size}): $legA=$aBetter $legB=$bBetter tie=$tie ---",
            )
        }
        Reports.writeJson(
            "build/bench-search.json",
            SearchEffortResults(
                timestamp = Instant.now().toString(),
                gitSha = Reports.readGitSha(),
                env = EnvInfo.capture(),
                seed = seed,
                timeoutMillis = budget.timeoutMillis,
                legA = legA,
                legB = legB,
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

    /** The backtrack config for a leg name (`vsids` | `satopt` | `conflict` | `linucb`); each
     *  carries the shared [seed] and a [deadline]-based cancellation. `linucb` / `conflict` mirror
     *  the portfolio's COP backtrack arms so the A/B measures exactly those. */
    private fun legParams(name: String, seed: Long, deadline: Long): BacktrackParams {
        val cancel = Cancellation { System.currentTimeMillis() > deadline }
        return when (name.lowercase(Locale.ROOT)) {
            "satopt" -> BacktrackPresets.satOptimized(randomSeed = seed, cancellation = cancel)

            "conflict" -> BacktrackPresets.conflictDriven(randomSeed = seed, cancellation = cancel)

            "linucb" -> BacktrackParams(
                randomSeed = seed,
                variableHeuristic = RegressionVariableHeuristic.linUcb(seed = seed),
                valueHeuristic = SolutionGuided(IndomainMin),
                phaseSaving = true,
                lubyRestartBase = 256L,
                cancellation = cancel,
            )

            else -> BacktrackParams( // "vsids": the historical baseline (VSIDS + phase + Luby + LBD)
                randomSeed = seed,
                variableHeuristic = Vsids(),
                phaseSaving = true,
                lubyRestartBase = 100L,
                maxLearnedClauses = 20_000,
                cancellation = cancel,
            )
        }
    }

    /**
     * Run one config over [e] under a fresh per-instance deadline, capturing its effort stats.
     * COP (the problem carries an [ResolvedProblem.objective]) goes through branch-and-bound
     * [BacktrackSolver.minimize] so the variable heuristic is exercised on optimization and the
     * best objective is captured; a CSP stays on [BacktrackSolver.solve] (satisfaction). `solved`
     * means a definitive verdict — Sat/Unsat for a CSP, proven Optimal/Infeasible for a COP (a
     * timed-out BestFound is *not* solved, but its objective is still recorded for the head-to-head).
     */
    private fun solveWith(
        e: ResolvedProblem,
        budget: Budget,
        params: (deadline: Long) -> BacktrackParams,
    ): SearchEffortReport {
        val deadline = System.currentTimeMillis() + budget.timeoutMillis
        val start = System.currentTimeMillis()
        val objective = e.objective
        val solver = BacktrackSolver(e.problem)
        val verdict: String
        val solved: Boolean
        val st: SolveStats?
        val obj: Double?
        if (objective != null) {
            val r = runCatching { solver.minimize(objective, params(deadline)) }.getOrNull()
            verdict = r?.let { it::class.simpleName ?: "?" } ?: "ERROR"
            solved = r is MinimizeResult.Optimal || r is MinimizeResult.Infeasible
            st = r?.stats
            obj = r?.objectiveValue
        } else {
            val r = runCatching { solver.solve(params(deadline)) }.getOrNull()
            verdict = r?.let { it::class.simpleName ?: "?" } ?: "ERROR"
            solved = r is SolveResult.Sat || r is SolveResult.Unsat
            st = r?.stats
            obj = null
        }
        val ms = System.currentTimeMillis() - start
        return SearchEffortReport(
            name = e.name,
            verdict = verdict,
            solved = solved,
            nodes = (st?.nodes?.sum ?: 0.0).toLong(),
            fails = (st?.fails?.sum ?: 0.0).toLong(),
            learned = (st?.learnedClauses?.sum ?: 0.0).toLong(),
            restarts = (st?.restarts?.sum ?: 0.0).toLong(),
            wallMs = ms,
            timedOut = st?.timedOut ?: false,
            objective = obj,
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
