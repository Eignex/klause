@file:OptIn(ExperimentalAtomicApi::class)

package com.eignex.klause.portfolio

import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.MinimizeResult
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.TerminationReason
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration
import kotlin.time.TimeSource

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
 *     PortfolioWorker.of("cbls", lsSession, LocalSearchParams(randomSeed = 1), objective = functional),
 *     PortfolioWorker.of("bt", btSession, BacktrackParams(randomSeed = 2), objective = linear) { p, s ->
 *         p.copy(objectiveBoundSupplier = s)
 *     },
 * )
 * val result = runBlocking { Portfolio(workers).minimize() }
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
    /** The configured engine instances raced in parallel; each carries its own params and (for
     *  optimisation) its own objective representation. */
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
    suspend fun solve(cancellation: Cancellation = Cancellation.Never): SolveResult = coroutineScope {
        val winnerFlag = AtomicBoolean(false)
        val token: Cancellation = { winnerFlag.load() || cancellation() }
        val cancelToken: Cancellation = when (strategy) {
            PortfolioStrategy.RaceFirstFeasible -> token
            PortfolioStrategy.Exhaustive -> cancellation
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
     * own improvements **against the objective representation it was built with** (#63: LS
     * descends the functional/gradient objective, backtrack bounds the linear one); new
     * incumbents fold into a shared bound exposed back to every worker through the bound supplier
     * its [PortfolioWorker.of] `withBound` wired in (backtrack prunes on it; LS ignores it). The
     * shared bound is a single scalar all workers agree on — they minimise the same objective
     * var. A worker proving Optimal cancels the rest; otherwise the global incumbent is returned
     * as BestFound, or Optimal if every worker terminated cleanly.
     */
    suspend fun minimize(cancellation: Cancellation = Cancellation.Never): MinimizeResult = coroutineScope {
        // Bound and best sample travel together in one atomically-swapped holder so a reported
        // bound always matches the stored sample (#81).
        val incumbent = AtomicReference(Incumbent(Double.POSITIVE_INFINITY, null))
        val cancelled = AtomicBoolean(false)
        val token: Cancellation = { cancelled.load() || cancellation() }

        fun readBound(): Double = incumbent.load().bound

        val deferreds = workers.map { worker ->
            async {
                var local: MinimizeResult = MinimizeResult.Unknown(TerminationReason.BudgetExhausted)
                for (r in worker.improvements(::readBound, token)) {
                    when (r) {
                        is MinimizeResult.BestFound -> {
                            updateSharedBound(incumbent, r.objectiveValue, r.sample)
                            local = r
                        }

                        is MinimizeResult.Optimal -> {
                            updateSharedBound(incumbent, r.objectiveValue, r.sample)
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
        val snapshot = incumbent.load()
        val sample = snapshot.sample
        val finalBound = snapshot.bound
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

    private fun updateSharedBound(incumbent: AtomicReference<Incumbent>, objective: Double, sample: Sample) {
        while (true) {
            val cur = incumbent.load()
            if (objective >= cur.bound) return
            // One CAS swaps bound and sample together, so a worker that wins the bound can never be
            // preempted between updating the bound and the sample: there is no separate store for a
            // racing worker to interleave with (#81). CAS uses identity equality, which is correct
            // here — each update publishes a fresh Incumbent instance.
            if (incumbent.compareAndSet(cur, Incumbent(objective, sample))) return
        }
    }

    /**
     * Streaming branch-and-bound: fan in every worker's improving incumbents into one flow,
     * emitting only those that strictly beat the shared global best (so the consumer sees a
     * monotonically-improving sequence). The shared bound is exposed to bound-pruning workers
     * exactly as in [minimize]. Each worker streams against its own objective representation
     * (#63). Collector cancellation (and [cancellation]) stops all workers. This is the anytime
     * entry point the bench's optimisation metric consumes.
     */
    fun improvements(cancellation: Cancellation = Cancellation.Never): Flow<MinimizeResult> =
        improvementsAttributed(cancellation).map { it.result }

    /**
     * [improvements] with per-worker attribution: each strict global improvement is tagged with
     * the producing [PortfolioWorker.label] and the elapsed time since collection began. Because
     * only strict improvements are emitted, the stream *is* the credit log: the first element is
     * the first global incumbent (which config reached feasibility first), the last element's
     * owner holds the final best, and per-label counts measure each config's contribution — the
     * signal palette-tuning campaigns use to rank worker configs.
     */
    fun improvementsAttributed(cancellation: Cancellation = Cancellation.Never): Flow<AttributedImprovement> =
        channelFlow {
            val start = TimeSource.Monotonic.markNow()
            val incumbent = AtomicReference(Incumbent(Double.POSITIVE_INFINITY, null))
            fun readBound(): Double = incumbent.load().bound
            for (worker in workers) {
                launch {
                    val job = requireNotNull(coroutineContext[Job])
                    val token: Cancellation = { !job.isActive || cancellation() }
                    for (r in worker.improvements(::readBound, token)) {
                        if (r is MinimizeResult.WithSample && r.objectiveValue < readBound()) {
                            updateSharedBound(incumbent, r.objectiveValue, r.sample)
                            send(AttributedImprovement(worker.label, start.elapsedNow(), r))
                        }
                    }
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

/** Immutable (bound, sample) pair published as one [AtomicReference] cell so the shared bound and
 *  the best sample are swapped together in a single CAS — they can never desync under a worker race
 *  (#81). `bound` is the objective value; `sample` is null only before the first incumbent. */
private class Incumbent(val bound: Double, val sample: Sample?)

/** One strict global improvement from [Portfolio.improvementsAttributed], tagged with the
 *  producing worker's label and the elapsed time since collection began. */
data class AttributedImprovement(
    /** [PortfolioWorker.label] of the worker that produced this incumbent. */
    val workerLabel: String,
    /** Time since the attributed stream started collecting. */
    val elapsed: Duration,
    /** The strict global improvement itself (always a [MinimizeResult.WithSample]). */
    val result: MinimizeResult,
)

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
