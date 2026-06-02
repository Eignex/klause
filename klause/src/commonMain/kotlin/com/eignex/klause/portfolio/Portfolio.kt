@file:OptIn(ExperimentalAtomicApi::class)

package com.eignex.klause.portfolio

import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.MinimizeResult
import com.eignex.klause.solver.Objective
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
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
 * Parallel portfolio of klause solver workers. Each [PortfolioWorker] is a single-threaded
 * engine instance carrying its *own* params, so a portfolio may be **heterogeneous** — local
 * search and backtrack workers in the same race — without the orchestrator caring which is
 * which. (The former generic `Portfolio<P>` is subsumed: a homogeneous portfolio is just one
 * whose workers happen to share an engine, built via [PortfolioWorker.of].)
 *
 * Usage:
 * ```
 * val workers = listOf(
 *     PortfolioWorker.of("cbls", lsSession, LocalSearchParams(randomSeed = 1)),
 *     PortfolioWorker.of("bt", btSession, BacktrackParams(randomSeed = 2)) { p, s ->
 *         p.copy(objectiveBoundSupplier = s)
 *     },
 * )
 * val result = runBlocking { Portfolio(workers).minimize(objective) }
 * ```
 *
 * Cancellation is wired through each worker's params (via the worker's captured
 * `withCancellation`):
 *  - `solve`: once any worker reports Sat or (complete-backend) Unsat, the flag is set and the
 *    others stop within their next polling interval.
 *  - `minimize`: a worker proving Optimal cancels the rest; otherwise the global incumbent is
 *    returned as BestFound.
 *  - `samples`: each worker runs to its own budget; the fan-in honours collector cancellation.
 *
 * **Platform note**: JVM / Kotlin/Native give workers real parallelism via the default
 * dispatcher; JS / WASM interleave cooperatively on one thread (correct, no wall-clock speedup).
 */
class Portfolio(
    val workers: List<PortfolioWorker>,
    private val strategy: PortfolioStrategy = PortfolioStrategy.RaceFirstFeasible,
) : AutoCloseable {

    init {
        require(workers.isNotEmpty()) { "Portfolio must have at least one worker" }
    }

    /**
     * Solve in parallel. [PortfolioStrategy.RaceFirstFeasible] (default) cancels siblings once
     * any worker produces a definitive Sat/Unsat; [PortfolioStrategy.Exhaustive] runs every
     * worker to its own budget and reduces afterwards (prefer Sat, then Unsat, then Unknown).
     */
    suspend fun solve(): SolveResult = coroutineScope {
        val winnerFlag = AtomicBoolean(false)
        val token: Cancellation = { winnerFlag.load() }
        val cancelToken: Cancellation = when (strategy) {
            PortfolioStrategy.RaceFirstFeasible -> token
            PortfolioStrategy.Exhaustive -> Cancellation.Never
        }

        val results = workers.map { worker ->
            async {
                val r = worker.solve(cancelToken)
                if (strategy is PortfolioStrategy.RaceFirstFeasible &&
                    (r is SolveResult.Sat || r is SolveResult.Unsat)
                ) {
                    winnerFlag.store(true)
                }
                r
            }
        }.awaitAll()

        results.firstOrNull { it is SolveResult.Sat }
            ?: results.firstOrNull { it is SolveResult.Unsat }
            ?: SolveResult.Unknown(TerminationReason.Cancelled)
    }

    /**
     * Parallel branch-and-bound minimisation with a shared best bound. Each worker streams its
     * own improvements; new incumbents fold into a shared bound exposed back to every worker
     * through the bound supplier its [PortfolioWorker.of] `withBound` wired in (backtrack prunes
     * on it; LS ignores it). A worker proving Optimal cancels the rest; otherwise the global
     * incumbent is returned as BestFound, or Optimal if every worker terminated cleanly.
     */
    suspend fun minimize(objective: Objective): MinimizeResult = coroutineScope {
        // AtomicLong stores bit-encoded Double — AtomicReference<Double> uses identity equality
        // and CAS would fail spuriously on autoboxed Doubles.
        val sharedBoundBits = AtomicLong(Double.POSITIVE_INFINITY.toRawBits())
        val bestSample = AtomicReference<Sample?>(null)
        val cancelled = AtomicBoolean(false)
        val token: Cancellation = { cancelled.load() }

        fun readBound(): Double = Double.fromBits(sharedBoundBits.load())

        val deferreds = workers.map { worker ->
            async {
                var local: MinimizeResult = MinimizeResult.Unknown(TerminationReason.BudgetExhausted)
                for (r in worker.improvements(objective, ::readBound, token)) {
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

                        is MinimizeResult.Infeasible -> local = r
                        is MinimizeResult.Unknown -> local = r
                    }
                }
                local
            }
        }
        val results = deferreds.awaitAll()

        // Reduce verdicts. A direct Optimal claim is only honoured from a worker that didn't run
        // under external bound sharing (the engine downgrades to BestFound when shared); single-
        // worker / unshared portfolios still produce direct Optimal here.
        val directOptimal = results.firstOrNull { it is MinimizeResult.Optimal }
        if (directOptimal != null) return@coroutineScope directOptimal

        // "Dirty" Unknown = ran out of budget / timed out / cancelled before fully exploring.
        // SearchExhausted is clean — the worker's space was fully covered.
        val anyDirtyUnknown = results.any { r ->
            r is MinimizeResult.Unknown && r.reason != TerminationReason.SearchExhausted
        }
        val sample = bestSample.load()
        val finalBound = readBound()
        if (sample != null) {
            return@coroutineScope if (anyDirtyUnknown) {
                MinimizeResult.BestFound(sample, finalBound, TerminationReason.BudgetExhausted)
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
            if (boundBits.compareAndSet(curBits, objective.toRawBits())) {
                best.store(sample)
                return
            }
        }
    }

    /**
     * Stream samples in parallel across all workers, fanning in to a single flow. Each worker
     * runs its own sample sequence with cancellation tied to the collector — when the consumer
     * stops collecting, every worker is cancelled.
     */
    fun samples(): Flow<Sample> = channelFlow {
        for (worker in workers) {
            launch {
                val job = requireNotNull(coroutineContext[Job])
                for (s in worker.samples({ !job.isActive })) {
                    send(s)
                }
            }
        }
    }

    override fun close() {
        workers.forEach { runCatching { it.close() } }
    }
}

/** Strategy knobs for [Portfolio]. Affects `solve` only; `samples` always fans in from every
 *  worker and `minimize` always shares the global bound (race honoured via cancellation on
 *  Optimal). */
sealed interface PortfolioStrategy {
    /** First worker to produce a definitive answer wins; others are cancelled. Default. */
    data object RaceFirstFeasible : PortfolioStrategy

    /** Run every worker to its own budget without cross-worker cancellation, then reduce over
     *  the full result set. Useful when each worker contributes telemetry / posterior data. */
    data object Exhaustive : PortfolioStrategy
}
