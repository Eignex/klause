@file:OptIn(ExperimentalAtomicApi::class)

package com.eignex.klause.portfolio

import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.MinimizeResult
import com.eignex.klause.solver.Objective
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.Session
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.SolverParams
import com.eignex.klause.solver.TerminationReason
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Parallel portfolio of klause [Session] workers. Each worker is single-threaded and
 * has its own assumption stack and learned state; the Portfolio coordinates parallel
 * execution and aggregates results.
 *
 * Usage:
 * ```
 * val workers = List(8) { LocalSearchSolver(problem).session() }
 * val portfolio = Portfolio(workers)
 * val result = runBlocking { portfolio.solve(LocalSearchParams(randomSeed = it.toLong())) }
 * ```
 *
 * Cancellation is wired through every worker's params via [SolverParams.withCancellation]:
 *
 *  - `solve`: once any worker reports a Sat or (complete-backend) Unsat, the
 *    cancellation flag is set; all other workers see it within their next polling
 *    interval and stop.
 *  - `samples`: each worker runs until its own budget is exhausted. The fan-in
 *    flow honours collector cancellation — when the consumer stops, every worker's
 *    coroutine context is cancelled and the workers see the cancellation predicate
 *    flip via the bridged token.
 *
 * Workers' params are augmented per call, so the caller doesn't need to pre-wire
 * cancellation tokens. Anything else on `params` (random seed, time limit, etc.)
 * passes through verbatim — if you want each worker to use a different seed, supply
 * already-distinct params at construction by mapping over the worker list.
 *
 * **Platform note**: the Portfolio runs on every klause target. JVM and Kotlin/Native
 * give workers real parallel execution via the default coroutines dispatcher (a pool of
 * OS threads). JS and WASM target single-threaded event loops, so workers cooperatively
 * interleave on one CPU — there's no wall-clock speedup, but the coordination layer
 * (cancellation, fan-in, bound sharing) still composes correctly.
 */
internal class Portfolio<P : SolverParams>(
    val workers: List<Session<P>>,
    private val strategy: PortfolioStrategy = PortfolioStrategy.RaceFirstFeasible,
) : AutoCloseable {

    init {
        require(workers.isNotEmpty()) { "Portfolio must have at least one worker" }
    }

    /**
     * Solve in parallel. Behaviour depends on [strategy]:
     *
     *  - [PortfolioStrategy.RaceFirstFeasible] (default): the first worker to produce a
     *    definitive answer (Sat or, on a complete backend, Unsat) wins; the rest are
     *    cancelled.
     *  - [PortfolioStrategy.Exhaustive]: every worker runs to its own budget without
     *    being cancelled by siblings. The portfolio reduces over the full set of results
     *    afterwards (prefer Sat, then Unsat, then Unknown). Use when each worker's run
     *    contributes telemetry / posterior data the caller wants to collect, or when
     *    cancellation cost dominates the savings.
     */
    suspend fun solve(params: P): SolveResult = coroutineScope {
        val winnerFlag = AtomicBoolean(false)
        val token: Cancellation = { winnerFlag.load() }

        @Suppress("UNCHECKED_CAST")
        val workerParams = when (strategy) {
            PortfolioStrategy.RaceFirstFeasible -> params.withCancellation(token) as P
            PortfolioStrategy.Exhaustive -> params // no cross-worker cancellation
        }

        val results = workers.map { session ->
            async {
                val r = session.solve(workerParams)
                if (strategy is PortfolioStrategy.RaceFirstFeasible) {
                    // Set the flag on a definitive answer so the other workers stop promptly.
                    if (r is SolveResult.Sat || r is SolveResult.Unsat) winnerFlag.store(true)
                }
                r
            }
        }.awaitAll()

        // Reduce: prefer Sat, then Unsat, then Unknown.
        results.firstOrNull { it is SolveResult.Sat }
            ?: results.firstOrNull { it is SolveResult.Unsat }
            ?: SolveResult.Unknown(TerminationReason.Cancelled)
    }

    /**
     * Parallel branch-and-bound minimisation. Each worker streams its own
     * [Session.improvements]; new incumbents are folded into a *shared best bound*
     * exposed back to every worker via [paramsWithBound]. When workers honour the bound
     * (klause's `BacktrackSolver` does, via `BacktrackParams.objectiveBoundSupplier`),
     * tightening from one worker prunes every other worker's subtree immediately — the
     * core wall-clock win on top of plain parallel search.
     *
     *  - [paramsWithBound] is supplied by the caller because the bound injection point
     *    is backend-specific. For `BacktrackParams`, pass
     *    `{ p, supplier -> p.copy(objectiveBoundSupplier = supplier) }`. Defaults to
     *    identity (no bound sharing — workers run independently and only the final
     *    reduce picks the best).
     *  - If any worker proves [MinimizeResult.Optimal], the portfolio cancels the rest
     *    and returns Optimal at the proven objective. If every worker stalls with a
     *    feasible-but-non-optimal result, the portfolio returns
     *    [MinimizeResult.BestFound] at the globally best objective.
     *
     *  Cancellation, like [solve], is wired through `withCancellation` so workers exit
     *  promptly when optimality is proven elsewhere.
     */
    suspend fun minimize(
        objective: Objective,
        params: P,
        paramsWithBound: (P, () -> Double) -> P = { p, _ -> p },
    ): MinimizeResult = coroutineScope {
        // AtomicLong stores bit-encoded Double — AtomicReference<Double> uses identity
        // equality and CAS would fail spuriously on autoboxed Doubles.
        val sharedBoundBits = AtomicLong(Double.POSITIVE_INFINITY.toRawBits())
        val bestSample = AtomicReference<Sample?>(null)
        val cancelled = AtomicBoolean(false)
        val token: Cancellation = { cancelled.load() }

        fun readBound(): Double = Double.fromBits(sharedBoundBits.load())

        @Suppress("UNCHECKED_CAST")
        val workerParams = paramsWithBound(params.withCancellation(token) as P, ::readBound)

        val deferreds = workers.map { session ->
            async {
                var local: MinimizeResult = MinimizeResult.Unknown(TerminationReason.BudgetExhausted)
                for (r in session.improvements(objective, workerParams)) {
                    when (r) {
                        is MinimizeResult.BestFound -> {
                            updateSharedBound(sharedBoundBits, bestSample, r.objectiveValue, r.sample)
                            local = r
                        }

                        is MinimizeResult.Optimal -> {
                            updateSharedBound(sharedBoundBits, bestSample, r.objectiveValue, r.sample)
                            cancelled.store(true)
                            local = r
                            break
                        }

                        is MinimizeResult.Infeasible -> {
                            local = r
                        }

                        is MinimizeResult.Unknown -> {
                            local = r
                        }
                    }
                }
                local
            }
        }
        val results = deferreds.awaitAll()

        // Reduce verdicts across workers. Soundness rules:
        //  - A worker's direct Optimal claim is only honoured if it didn't run under
        //    external bound sharing (the engine downgrades to BestFound when shared).
        //    Single-worker / unshared portfolios still produce direct Optimal here.
        //  - If every worker terminated cleanly (no Unknown from budget exhaustion or
        //    cancellation), the union of their searches covered the entire space — so
        //    the global incumbent is provably optimal.
        //  - Else, with a global incumbent, the best provable claim is BestFound.
        //  - Without any incumbent and at least one Unknown, the search was inconclusive.
        //  - Without any incumbent and all workers terminated cleanly, the problem is
        //    proven infeasible.
        val directOptimal = results.firstOrNull { it is MinimizeResult.Optimal }
        if (directOptimal != null) return@coroutineScope directOptimal

        // "Dirty" Unknown = a worker ran out of budget / hit a timeout / got cancelled
        // before fully exploring. SearchExhausted is *clean* — the worker's search space
        // was fully covered; absence of a local incumbent only reflects external pruning,
        // not incomplete work.
        val anyDirtyUnknown = results.any { r ->
            r is MinimizeResult.Unknown && r.reason != TerminationReason.SearchExhausted
        }
        val sample = bestSample.load()
        val finalBound = readBound()
        if (sample != null) {
            return@coroutineScope if (anyDirtyUnknown) {
                MinimizeResult.BestFound(
                    sample,
                    finalBound,
                    TerminationReason.BudgetExhausted,
                )
            } else {
                MinimizeResult.Optimal(sample, finalBound)
            }
        }
        if (anyDirtyUnknown) {
            MinimizeResult.Unknown(TerminationReason.BudgetExhausted)
        } else {
            MinimizeResult.Infeasible()
        }
    }

    private fun updateSharedBound(
        boundBits: AtomicLong,
        best: AtomicReference<Sample?>,
        objective: Double,
        sample: Sample,
    ) {
        while (true) {
            val curBits = boundBits.load()
            val cur = Double.fromBits(curBits)
            if (objective >= cur) return
            val newBits = objective.toRawBits()
            if (boundBits.compareAndSet(curBits, newBits)) {
                best.store(sample)
                return
            }
        }
    }

    /**
     * Stream samples in parallel across all workers, fanning in to a single flow.
     * Each worker runs its own [Session.samples] sequence with cancellation tied to
     * the collector — when the consumer stops collecting, every worker is cancelled.
     */
    fun samples(params: P): Flow<Sample> = channelFlow {
        for (session in workers) {
            launch {
                // Capture the worker coroutine's Job and bridge its cancellation state
                // into the (non-suspending) Cancellation predicate the engine checks.
                val job = requireNotNull(coroutineContext[Job])

                @Suppress("UNCHECKED_CAST")
                val workerParams = params.withCancellation { !job.isActive } as P
                for (s in session.samples(workerParams)) {
                    send(s)
                }
            }
        }
    }

    override fun close() {
        workers.forEach { runCatching { it.close() } }
    }
}

/** Strategy knobs for [Portfolio]. Each entry currently affects `solve` only; `samples`
 *  always fans-in from every worker; `minimize` always shares the global bound regardless
 *  of strategy (race semantics are honoured via cooperative cancellation on Optimal). */
internal sealed interface PortfolioStrategy {
    /** First worker to produce a definitive answer wins; others are cancelled. Default. */
    data object RaceFirstFeasible : PortfolioStrategy

    /**
     * Run every worker to its own budget without cross-worker cancellation. The portfolio
     * reduces over the full set of results afterwards. Useful when each worker contributes
     * telemetry / posterior data the caller wants to collect, when workers are pinning
     * different posterior priors and need to all complete, or when the cancellation cost
     * dominates the savings on cheap instances.
     */
    data object Exhaustive : PortfolioStrategy
}
