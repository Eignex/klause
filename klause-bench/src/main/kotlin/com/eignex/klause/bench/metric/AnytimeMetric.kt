package com.eignex.klause.bench.metric

import com.eignex.klause.bench.report.EnvInfo
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.report.markdown
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.runner.ResolvedProblem
import com.eignex.klause.bench.solver.Backend
import com.eignex.klause.portfolio.PortfolioBuilder
import com.eignex.klause.portfolio.PortfolioSpec
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.MinimizeResult
import com.eignex.klause.solver.Objective
import com.eignex.klause.solver.localsearch.CostShaping
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import com.eignex.klause.solver.localsearch.strategy.AspirationCriterion
import com.eignex.klause.solver.localsearch.strategy.Cbls
import com.eignex.klause.solver.localsearch.strategy.TabuFilter
import java.time.Instant
import kotlinx.serialization.Serializable

/**
 * Anytime optimization comparison: run klause's local-search engine and an in-process
 * [Reference] (OR-Tools by default, or Choco) on each optimization instance under the same
 * wall-clock budget, recording time-to-first-incumbent and best objective reached. Replaces
 * the legacy `LsBench`'s klause-LS-vs-Yuck comparison (Yuck is unavailable as a JVM
 * dependency). Only optimization entries (those with an [Objective]) participate. Override the
 * reference with `-Dklause.bench.anytime.reference=choco|ortools`.
 */
@Serializable
data class AnytimeRow(
    val name: String,
    val klauseFirstMs: Long,
    val klauseBestMs: Long,
    val klauseBest: Double?,
    val klauseSolutions: Int,
    val referenceSolver: String,
    val referenceFirstMs: Long,
    val referenceBestMs: Long,
    val referenceBest: Double?,
    val referenceSolutions: Int,
    val referenceProvedOptimal: Boolean,
    /** klauseBest - referenceBest (minimization; negative = klause better, positive = worse). */
    val gap: Double?,
)

/** Outcome of driving one anytime stream. */
private data class Anytime(val firstMs: Long, val bestMs: Long, val best: Double?, val solutions: Int, val provedOptimal: Boolean)

@Serializable
data class AnytimeResults(
    val timestamp: String,
    val gitSha: String?,
    val env: EnvInfo,
    val budgetMillis: Long,
    val rows: List<AnytimeRow>,
)

object AnytimeMetric {
    fun run(entries: List<ResolvedProblem>, budget: Budget = Budget(), reference: Backend = Backend.ORTOOLS) {
        val ref = System.getProperty("klause.bench.anytime.reference")?.let { Reference.byId(it) } ?: Reference.of(reference)
        val opt = entries.filter { it.objective != null }
        println()
        println("=== anytime (klause-LS vs ${ref.name} reference; ${budget.timeoutMillis}ms budget; minimization) ===")
        if (opt.isEmpty()) { println("(no optimization instances in this corpus)"); return }
        val rows = opt.map { row(it, budget, ref) }
        for (r in rows) {
            println("[${r.name}] klause first=${r.klauseFirstMs}ms best=${fmt(r.klauseBest)}@${r.klauseBestMs}ms n=${r.klauseSolutions} | " +
                "${r.referenceSolver} first=${r.referenceFirstMs}ms best=${fmt(r.referenceBest)}@${r.referenceBestMs}ms n=${r.referenceSolutions}${if (r.referenceProvedOptimal) "*" else ""} | " +
                "gap=${fmt(r.gap)}")
        }
        Reports.writeJson("build/anytime-report.json",
            AnytimeResults(Instant.now().toString(), Reports.readGitSha(), EnvInfo.capture(), budget.timeoutMillis, rows))
        Reports.writeMarkdown("build/anytime-report.md", markdown {
            h1("Anytime optimization — klause-LS vs ${ref.name}")
            para("Budget ${budget.timeoutMillis}ms; minimization. `*` = reference proved optimal; gap = klause − reference.")
            table(listOf("instance", "klause first/best/n", "${ref.name} first/best/n", "gap"),
                rows.map { r ->
                    listOf(r.name,
                        "${r.klauseFirstMs}ms / ${fmt(r.klauseBest)}@${r.klauseBestMs}ms / ${r.klauseSolutions}",
                        "${r.referenceFirstMs}ms / ${fmt(r.referenceBest)}@${r.referenceBestMs}ms / ${r.referenceSolutions}${if (r.referenceProvedOptimal) " *" else ""}",
                        fmt(r.gap))
                })
        })
    }

    private fun row(entry: ResolvedProblem, budget: Budget, ref: Reference): AnytimeRow {
        val obj = entry.objective!!
        // Mirror the shipped LS configuration (klause-ls.msc → CLI): CBLS for both the satisfy
        // fight and the objective descent, with the functional (gradient-bearing) objective when
        // the model provides one. A bare LocalSearchSolver(problem) would use the ProbSat default
        // with no objective shaping — not representative of the product. The reference keeps the
        // plain (linear) objective, which its native model builder requires.
        val tabu = TabuFilter(tenure = 10, aspiration = AspirationCriterion.OrImproving)
        val solver = LocalSearchSolver(
            entry.problem,
            strategy = Cbls(tabu = tabu),
            optimizeStrategy = Cbls(tabu = tabu),
            pairSwapBudget = 1024,
        )
        val klauseObj = entry.lsObjective ?: obj
        // Optional portfolio mode: -Dklause.anytime.portfolio=<ls>:<bt> runs a multi-core
        // Portfolio (ls local-search + bt backtrack workers) instead of the single CBLS solver,
        // streaming its fanned-in incumbents. Mixed pools use the linear objective both engines
        // share; pure-LS pools keep the functional/gradient objective.
        val portfolioProp = System.getProperty("klause.anytime.portfolio")
        val k = if (portfolioProp != null) {
            anytime { portfolioImprovements(entry, portfolioProp, klauseObj, obj, budget) }
        } else {
            anytime { solver.improvements(klauseObj, lsParams(budget)) }
        }
        val r = anytime { ref.improvements(entry.problem, obj, budget) }
        val gap = if (k.best != null && r.best != null) k.best - r.best else null
        return AnytimeRow(entry.name, k.firstMs, k.bestMs, k.best, k.solutions,
            ref.name, r.firstMs, r.bestMs, r.best, r.solutions, r.provedOptimal, gap)
    }

    /** Drive an anytime [MinimizeResult] stream, timing the first + best incumbent, counting
     *  solutions seen, and noting whether optimality was proven. */
    private fun anytime(stream: () -> Sequence<MinimizeResult>): Anytime {
        val t0 = System.currentTimeMillis()
        var firstMs = -1L
        var bestMs = -1L
        var best: Double? = null
        var solutions = 0
        var provedOptimal = false
        // A solver that can't model the instance (e.g. the reference adapter hits an
        // unsupported factor) must not abort the whole sweep — record what was produced (often
        // nothing) and move on. The instance still appears in the report with that solver's
        // results blank, so "reference can't model this" reads distinctly from "no incumbent".
        try {
            for (r in stream()) {
                if (r is MinimizeResult.WithSample) {
                    val now = System.currentTimeMillis() - t0
                    if (firstMs < 0) firstMs = now
                    if (best == null || r.objective < best!!) { best = r.objective; bestMs = now }
                    solutions++
                    if (r is MinimizeResult.Optimal) provedOptimal = true
                }
            }
        } catch (e: Exception) {
            System.err.println("[anytime] solver aborted on this instance: ${e.message}")
        }
        return Anytime(if (firstMs < 0) -1L else firstMs, if (bestMs < 0) -1L else bestMs, best, solutions, provedOptimal)
    }

    /** Sentinel marking the end of the bridged portfolio stream. */
    private val streamDone = Any()

    /** Build a [com.eignex.klause.portfolio.Portfolio] from the `<ls>:<bt>` spec and bridge its
     *  fanned-in incumbents (a coroutine [kotlinx.coroutines.flow.Flow]) into the synchronous
     *  [Sequence] the anytime harness consumes, via a daemon collector thread + blocking queue. */
    private fun portfolioImprovements(
        entry: ResolvedProblem,
        prop: String,
        klauseObj: Objective,
        linearObj: Objective,
        budget: Budget,
    ): Sequence<MinimizeResult> {
        val parts = prop.split(":", ",")
        val ls = parts.getOrNull(0)?.toIntOrNull() ?: 4
        val bt = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val portfolio = PortfolioBuilder.build(
            entry.problem, PortfolioSpec(localSearchWorkers = ls, backtrackWorkers = bt, seed = 1L),
        )
        // Pure-LS pool can use the gradient-bearing functional objective; a mixed pool must use
        // the linear objective both engines share (backtrack bounds on it).
        val objective = if (bt == 0) klauseObj else linearObj
        val deadline = System.currentTimeMillis() + budget.timeoutMillis
        val cancel = Cancellation { System.currentTimeMillis() > deadline }
        val queue = java.util.concurrent.LinkedBlockingQueue<Any>()
        kotlin.concurrent.thread(isDaemon = true, name = "portfolio-anytime") {
            try {
                // Dispatchers.Default → the channelFlow's per-worker launches get real OS threads
                // and run in parallel; plain runBlocking is single-threaded and CPU-bound workers
                // (which never suspend) would starve each other.
                kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.Default) {
                    portfolio.improvements(objective, cancel).collect { queue.put(it) }
                }
            } finally {
                portfolio.close()
                queue.put(streamDone)
            }
        }
        return generateSequence { queue.take().takeIf { it !== streamDone } as MinimizeResult? }
    }

    private fun lsParams(budget: Budget): LocalSearchParams {
        val deadline = System.currentTimeMillis() + budget.timeoutMillis
        // λ=1.0 cost shaping folds the objective delta into move scoring — without it CBLS is
        // objective-blind and only descends opportunistically via constraint repair (mirrors
        // the CLI's runWithLocalSearch). Override via -Dklause.anytime.shaping=feasibilityFirst
        // or -Dklause.anytime.lambda=<x> for A/B experiments on the feasibility/descent split.
        return LocalSearchParams(
            maxFlips = Long.MAX_VALUE, randomSeed = 1L,
            costShaping = shapingFromProps(),
        ).withCancellation(Cancellation { System.currentTimeMillis() > deadline }) as LocalSearchParams
    }

    private fun shapingFromProps(): CostShaping =
        when (System.getProperty("klause.anytime.shaping")?.lowercase()) {
            "feasibilityfirst", "feasibility-first", "ff" -> CostShaping.FeasibilityFirst
            else -> CostShaping.Linear(lambda = System.getProperty("klause.anytime.lambda")?.toDouble() ?: 1.0)
        }

    private fun fmt(v: Double?): String = v?.let { "%.1f".format(it) } ?: "—"
}
