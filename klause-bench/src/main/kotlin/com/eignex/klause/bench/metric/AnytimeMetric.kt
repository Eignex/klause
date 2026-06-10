package com.eignex.klause.bench.metric

import com.eignex.klause.bench.report.EnvInfo
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.report.markdown
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.runner.ResolvedProblem
import com.eignex.klause.bench.solver.Backend
import com.eignex.klause.portfolio.EngineMix
import com.eignex.klause.portfolio.Kind
import com.eignex.klause.portfolio.Portfolio
import com.eignex.klause.portfolio.PortfolioBuilder
import com.eignex.klause.portfolio.PortfolioScenario
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.LinearObjective
import com.eignex.klause.solver.MinimizeResult
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SearchEvent
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.localsearch.CostShaping
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import com.eignex.klause.solver.localsearch.strategy.AspirationCriterion
import com.eignex.klause.solver.localsearch.strategy.Cbls
import com.eignex.klause.solver.localsearch.strategy.TabuFilter
import com.eignex.kumulant.core.Concurrency
import com.eignex.kumulant.stat.summary.ArgMinStat
import com.eignex.kumulant.stat.summary.MinStat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/**
 * Anytime optimization comparison: run klause's local-search engine and an in-process
 * [Reference] (OR-Tools by default, or Choco) on each optimization instance under the same
 * wall-clock budget, recording time-to-first-incumbent and best objective reached. Only
 * optimization entries (those with an [Objective]) participate. Override the reference with
 * `-Dklause.bench.anytime.reference=choco|ortools`.
 */
@Serializable
internal data class AnytimeRow(
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
private data class Anytime(
    val firstMs: Long,
    val bestMs: Long,
    val best: Double?,
    val solutions: Int,
    val provedOptimal: Boolean,
)

/**
 * Engine-side incumbent timing recorded straight off the [SearchEvent] seam (#140). The
 * harness otherwise stamps incumbents when it pulls them off the stream, which folds in
 * consumer latency — in portfolio mode a whole thread + queue bridge. Events fire inside the
 * engine at the moment of improvement; portfolio workers fire concurrently, hence the
 * [Concurrency.Strict] accumulators: time-to-first is a plain min over incumbent
 * timestamps, time-to-best the argmin of objective over time.
 */
internal class EngineTimes {
    private val t0 = System.nanoTime()
    private val first = MinStat(Concurrency.Strict)
    private val best = ArgMinStat(Concurrency.Strict)

    val listener: (SearchEvent) -> Unit = { e ->
        if (e is SearchEvent.Incumbent) {
            val at = System.nanoTime() - t0
            first.update(at.toDouble())
            best.update(e.objective, timestampNanos = at)
        }
    }

    /** Milliseconds to the first incumbent, or -1 when none arrived. */
    val firstMs: Long
        get() = first.read().min.let { if (it.isFinite()) (it / NANOS_PER_MILLI).toLong() else -1L }

    /** Milliseconds to the best (minimum-objective) incumbent, or -1 when none arrived. */
    val bestMs: Long
        get() = best.read().let { if (it.min.isFinite()) it.atTimestampNanos / NANOS_PER_MILLI.toLong() else -1L }

    private companion object {
        const val NANOS_PER_MILLI = 1e6
    }
}

@Serializable
internal data class AnytimeResults(
    val timestamp: String,
    val gitSha: String?,
    val env: EnvInfo,
    val budgetMillis: Long,
    val rows: List<AnytimeRow>,
)

internal object AnytimeMetric {
    fun run(entries: List<ResolvedProblem>, budget: Budget = Budget(), reference: Backend = Backend.ORTOOLS) {
        val ref = System.getProperty(
            "klause.bench.anytime.reference",
        )?.let { Reference.byId(it) } ?: Reference.of(reference)
        val opt = entries.filter { it.objective != null }
        println()
        println("=== anytime (klause-LS vs ${ref.name} reference; ${budget.timeoutMillis}ms budget; minimization) ===")
        if (opt.isEmpty()) {
            println("(no optimization instances in this corpus)")
            return
        }
        val rows = opt.map { row(it, budget, ref) }
        for (r in rows) {
            println(
                "[${r.name}] klause first=${r.klauseFirstMs}ms best=${fmt(
                    r.klauseBest,
                )}@${r.klauseBestMs}ms n=${r.klauseSolutions} | " +
                    "${r.referenceSolver} first=${r.referenceFirstMs}ms best=${fmt(
                        r.referenceBest,
                    )}@${r.referenceBestMs}ms n=${r.referenceSolutions}" +
                    "${if (r.referenceProvedOptimal) "*" else ""} | " +
                    "gap=${fmt(r.gap)}",
            )
        }
        Reports.writeJson(
            "build/anytime-report.json",
            AnytimeResults(
                Instant.now().toString(),
                Reports.readGitSha(),
                EnvInfo.capture(),
                budget.timeoutMillis,
                rows,
            ),
        )
        Reports.writeMarkdown(
            "build/anytime-report.md",
            markdown {
                h1("Anytime optimization — klause-LS vs ${ref.name}")
                para(
                    "Budget ${budget.timeoutMillis}ms; minimization. `*` = reference proved optimal; " +
                        "gap = klause − reference.",
                )
                table(
                    listOf("instance", "klause first/best/n", "${ref.name} first/best/n", "gap"),
                    rows.map { r ->
                        listOf(
                            r.name,
                            "${r.klauseFirstMs}ms / ${fmt(r.klauseBest)}@${r.klauseBestMs}ms / ${r.klauseSolutions}",
                            "${r.referenceFirstMs}ms / ${fmt(
                                r.referenceBest,
                            )}@${r.referenceBestMs}ms / ${r.referenceSolutions}" +
                                "${if (r.referenceProvedOptimal) " *" else ""}",
                            fmt(r.gap),
                        )
                    },
                )
            },
        )
    }

    private fun row(entry: ResolvedProblem, budget: Budget, ref: Reference): AnytimeRow {
        val obj = requireNotNull(entry.objective)
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
            definitionalSweep = entry.definitionalSweep,
            // #153: per-move invariant maintenance (default on, mirroring the shipped CLI);
            // -Dklause.anytime.invariants=false reverts to restart-only sweeping for A/B runs.
            perMoveInvariants = System.getProperty("klause.anytime.invariants")?.toBoolean() != false,
        )
        // Optional portfolio mode: -Dklause.anytime.portfolio=<ls>:<bt> runs a multi-core
        // Portfolio (ls local-search + bt backtrack workers) instead of the single CBLS solver,
        // streaming its fanned-in incumbents. Every engine minimises the linear objective; the
        // LS workers additionally descend the model's gradient view via params.lsObjective.
        val portfolioProp = System.getProperty("klause.anytime.portfolio")
        // Optional CP-seeding (#65, OFF by default): -Dklause.anytime.cpseed=true runs a short
        // backtrack solve for a *feasible* point and warm-starts LS from it (the #54 misses reach
        // feasibility trivially under CP but never under cold LS). This is an opt-in hybrid — the
        // shipped pure-LS CLI path never sets initialAssignment; only this opt-in and the
        // diag:cpseed probe do.
        val cpseed = System.getProperty("klause.anytime.cpseed")?.toBoolean() == true
        // Engine-side timing for the klause stream (#140); the reference adapters have no
        // event seam, so their timestamps stay consumer-side (the latency there is a plain
        // in-process sequence pull, not the portfolio's queue bridge).
        val engine = EngineTimes()
        val k = when {
            portfolioProp != null ->
                anytime(engine) { portfolioImprovements(entry, portfolioProp, obj, budget, engine.listener) }

            cpseed -> anytime(engine) { cpSeededImprovements(entry, solver, obj, budget, engine.listener) }

            else -> anytime(engine) {
                solver.improvements(obj, lsParams(budget, engine.listener).copy(lsObjective = entry.lsObjective))
            }
        }
        val r = anytime { ref.improvements(entry.problem, obj, budget) }
        val gap = if (k.best != null && r.best != null) k.best - r.best else null
        return AnytimeRow(
            entry.name, k.firstMs, k.bestMs, k.best, k.solutions,
            ref.name, r.firstMs, r.bestMs, r.best, r.solutions, r.provedOptimal, gap,
        )
    }

    /** Drive an anytime [MinimizeResult] stream, timing the first + best incumbent, counting
     *  solutions seen, and noting whether optimality was proven. */
    @Suppress("TooGenericExceptionCaught", "PrintStackTrace")
    private fun anytime(engine: EngineTimes? = null, stream: () -> Sequence<MinimizeResult>): Anytime {
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
                    val prevBest = best
                    if (prevBest == null || r.objective < prevBest) {
                        best = r.objective
                        bestMs = now
                    }
                    solutions++
                    if (r is MinimizeResult.Optimal) provedOptimal = true
                }
            }
        } catch (e: Exception) {
            System.err.println("[anytime] solver aborted on this instance: ${e.message}")
            if (System.getProperty("klause.bench.anytime.trace")?.toBoolean() == true) e.printStackTrace()
        }
        // Prefer engine-side stamps when the SearchEvent seam recorded any (#140); fall back
        // to the consumer-side ones for streams without a listener (the reference adapters).
        val first = engine?.firstMs?.takeIf { it >= 0 } ?: firstMs
        val bestAt = engine?.bestMs?.takeIf { it >= 0 } ?: bestMs
        return Anytime(if (first < 0) -1L else first, if (bestAt < 0) -1L else bestAt, best, solutions, provedOptimal)
    }

    /** Sentinel marking the end of the bridged portfolio stream. */
    private val streamDone = Any()

    /** Build a [com.eignex.klause.portfolio.Portfolio] from the `<ls>:<bt>` spec and bridge its
     *  fanned-in incumbents (a coroutine [kotlinx.coroutines.flow.Flow]) into the synchronous
     *  [Sequence] the anytime harness consumes, via a daemon collector thread + blocking queue.
     *
     *  Worker selection: `-Dklause.anytime.portfolio.configs=all` (the whole named pool) or a
     *  comma-separated label list overrides the `<ls>` count with explicit configs — the
     *  palette-tuning campaign knob. Per-worker credit is printed after each instance as a
     *  `[portfolio-stats]` line: which worker produced the first global incumbent (and when),
     *  which held the final best, and each worker's strict-improvement count. */
    @Suppress("InjectDispatcher")
    private fun portfolioImprovements(
        entry: ResolvedProblem,
        prop: String,
        objective: LinearObjective,
        budget: Budget,
        onEvent: ((SearchEvent) -> Unit)? = null,
    ): Sequence<MinimizeResult> {
        val parts = prop.split(":", ",")
        val ls = parts.getOrNull(0)?.toIntOrNull() ?: 4
        val bt = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val configsProp = System.getProperty("klause.anytime.portfolio.configs")
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        // Every worker minimises the linear objective; the LS workers additionally receive the
        // model's gradient view (entry.lsObjective) through their params, keeping the per-move
        // gradient without collapsing the pool onto two objective representations.
        val portfolioEvent: (worker: String, event: SearchEvent) -> Unit = { _, e -> onEvent?.invoke(e) }
        val workers = if (configsProp != null) {
            // Explicit config mix (the campaign override).
            PortfolioBuilder.buildExplicit(
                entry.problem,
                lsLabels = configsProp,
                backtrackWorkers = bt,
                kind = Kind.COP,
                seed = 1L,
                objective = objective,
                lsObjective = entry.lsObjective,
                definitionalSweep = entry.definitionalSweep,
                onEvent = if (onEvent != null) portfolioEvent else null,
            )
        } else {
            PortfolioBuilder.build(
                entry.problem,
                PortfolioScenario.parallel(
                    threads = ls + bt,
                    kind = Kind.COP,
                    engine = if (bt > 0) EngineMix.MIXED else EngineMix.LOCAL_SEARCH,
                    seed = 1L,
                ),
                objective = objective,
                lsObjective = entry.lsObjective,
                definitionalSweep = entry.definitionalSweep,
                onEvent = if (onEvent != null) portfolioEvent else null,
            )
        }
        val portfolio = Portfolio(workers)
        val deadline = System.currentTimeMillis() + budget.timeoutMillis
        val cancel = Cancellation { System.currentTimeMillis() > deadline }
        val queue = LinkedBlockingQueue<Any>()
        thread(isDaemon = true, name = "portfolio-anytime") {
            // Credit accumulation happens inside the (sequential) collector, so plain locals
            // suffice; the summary line prints once the stream ends.
            var first: String? = null
            var firstMs = -1L
            var last: String? = null
            val contrib = LinkedHashMap<String, Int>()
            try {
                // Dispatchers.Default → the channelFlow's per-worker launches get real OS threads
                // and run in parallel; plain runBlocking is single-threaded and CPU-bound workers
                // (which never suspend) would starve each other.
                runBlocking(Dispatchers.Default) {
                    portfolio.improvementsAttributed(cancel).collect { a ->
                        if (first == null) {
                            first = a.workerLabel
                            firstMs = a.elapsed.inWholeMilliseconds
                        }
                        last = a.workerLabel
                        contrib[a.workerLabel] = (contrib[a.workerLabel] ?: 0) + 1
                        queue.put(a.result)
                    }
                }
            } finally {
                portfolio.close()
                if (first != null) {
                    println(
                        "[portfolio-stats] ${entry.name} workers=${portfolio.workers.size} " +
                            "first=$first@${firstMs}ms best=$last " +
                            "contrib=${contrib.entries.joinToString(",") { "${it.key}:${it.value}" }}",
                    )
                } else {
                    println("[portfolio-stats] ${entry.name} workers=${portfolio.workers.size} first=none")
                }
                queue.put(streamDone)
            }
        }
        return generateSequence { queue.take().takeIf { it !== streamDone } as MinimizeResult? }
    }

    /**
     * CP-seeded LS (#65, opt-in): spend up to `-Dklause.anytime.cpseed.ms` (default 1000ms,
     * capped by the overall budget) running the backtrack solver for a feasible point, then
     * LS-optimize from it via [LocalSearchParams.initialAssignment] under the *remaining* budget —
     * so the comparison against the reference stays honest end-to-end (CP time counts). If CP
     * doesn't reach feasibility in its slice, the seed is null and LS runs cold (degrades exactly
     * to the non-seeded path). Bench-only opt-in; the shipped LS CLI never seeds by default.
     */
    private fun cpSeededImprovements(
        entry: ResolvedProblem,
        solver: LocalSearchSolver,
        objective: LinearObjective,
        budget: Budget,
        onEvent: ((SearchEvent) -> Unit)? = null,
    ): Sequence<MinimizeResult> {
        val overallDeadline = System.currentTimeMillis() + budget.timeoutMillis
        val cpMs = System.getProperty("klause.anytime.cpseed.ms")?.toLong() ?: 1000L
        val cpDeadline = minOf(System.currentTimeMillis() + cpMs, overallDeadline)
        val cp = BacktrackSolver(entry.problem).solve(
            (entry.searchParams ?: BacktrackParams()).copy(
                randomSeed = 1L,
                cancellation = Cancellation { System.currentTimeMillis() > cpDeadline },
            ),
        )
        val seed: Sample? = (cp as? SolveResult.Sat)?.assignment
        val params = LocalSearchParams(
            maxFlips = Long.MAX_VALUE,
            randomSeed = 1L,
            costShaping = shapingFromProps(),
            initialAssignment = seed,
            lsObjective = entry.lsObjective,
            onEvent = onEvent,
        ).withCancellation(Cancellation { System.currentTimeMillis() > overallDeadline })
        return solver.improvements(objective, params)
    }

    private fun lsParams(budget: Budget, onEvent: ((SearchEvent) -> Unit)? = null): LocalSearchParams {
        val deadline = System.currentTimeMillis() + budget.timeoutMillis
        // λ=1.0 cost shaping folds the objective delta into move scoring — without it CBLS is
        // objective-blind and only descends opportunistically via constraint repair (mirrors
        // the CLI's runWithLocalSearch). Override via -Dklause.anytime.shaping=feasibilityFirst
        // or -Dklause.anytime.lambda=<x> for A/B experiments on the feasibility/descent split.
        return LocalSearchParams(
            maxFlips = Long.MAX_VALUE,
            randomSeed = 1L,
            costShaping = shapingFromProps(),
            onEvent = onEvent,
        ).withCancellation(Cancellation { System.currentTimeMillis() > deadline })
    }

    private fun shapingFromProps(): CostShaping = when (System.getProperty("klause.anytime.shaping")?.lowercase()) {
        "feasibilityfirst", "feasibility-first", "ff" -> CostShaping.FeasibilityFirst
        else -> CostShaping.Linear(lambda = System.getProperty("klause.anytime.lambda")?.toDouble() ?: 1.0)
    }

    private fun fmt(v: Double?): String = v?.let { "%.1f".format(Locale.ROOT, it) } ?: "—"
}
