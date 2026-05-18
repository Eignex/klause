package com.eignex.klause.portfolio

import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.Session
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.SolverParams
import com.eignex.klause.solver.TerminationReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

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
 */
class Portfolio<P : SolverParams>(
    val workers: List<Session<P>>,
    private val strategy: PortfolioStrategy = PortfolioStrategy.RaceFirstFeasible,
) : AutoCloseable {

    init {
        require(workers.isNotEmpty()) { "Portfolio must have at least one worker" }
    }

    /**
     * Solve in parallel. The first worker to produce a definitive answer (Sat or, on
     * a complete backend, Unsat) wins; the rest are cancelled. If all workers exhaust
     * their budgets and return Unknown, the portfolio returns Unknown.
     */
    suspend fun solve(params: P): SolveResult = coroutineScope {
        val winnerFlag = AtomicBoolean(false)
        val token: Cancellation = { winnerFlag.get() }
        @Suppress("UNCHECKED_CAST")
        val workerParams = params.withCancellation(token) as P

        val results = workers.map { session ->
            async(Dispatchers.Default) {
                val r = session.solve(workerParams)
                // Set the flag on a definitive answer so the other workers stop promptly.
                if (r is SolveResult.Sat || r is SolveResult.Unsat) winnerFlag.set(true)
                r
            }
        }.awaitAll()

        // Reduce: prefer Sat, then Unsat, then Unknown. Race semantics are preserved
        // by the winnerFlag short-circuit above — losers return Unknown quickly.
        results.firstOrNull { it is SolveResult.Sat }
            ?: results.firstOrNull { it is SolveResult.Unsat }
            ?: SolveResult.Unknown(TerminationReason.Cancelled)
    }

    /**
     * Stream samples in parallel across all workers, fanning in to a single flow.
     * Each worker runs its own [Session.samples] sequence with cancellation tied to
     * the collector — when the consumer stops collecting, every worker is cancelled.
     */
    fun samples(params: P): Flow<Sample> = channelFlow {
        for (session in workers) {
            launch(Dispatchers.Default) {
                // Capture the worker coroutine's Job and bridge its cancellation state
                // into the (non-suspending) Cancellation predicate the engine checks.
                val job = coroutineContext[Job]!!
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
 *  always fans-in from every worker. */
sealed interface PortfolioStrategy {
    /** First worker to produce a definitive answer wins; others are cancelled. Default. */
    data object RaceFirstFeasible : PortfolioStrategy
}
