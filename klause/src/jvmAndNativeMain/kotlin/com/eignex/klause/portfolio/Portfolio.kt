@file:OptIn(ExperimentalAtomicApi::class)

package com.eignex.klause.portfolio

import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.kumulant.core.Concurrency
import com.eignex.kumulant.stream.lock
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.TimeSource

/**
 * Parallel portfolio of klause solver workers, **coroutine-free**: every worker runs on its own
 * real thread ([parallelRun]) and the public API is blocking. Each [PortfolioWorker] is a
 * single-threaded engine carrying its *own* params, so a portfolio may be **heterogeneous** —
 * local search and backtrack workers in the same race.
 *
 * Lives in `jvmAndNativeMain`: it needs real threads, which the single-threaded js/wasm targets
 * lack — those use only [SequentialPortfolio] from `commonMain`.
 *
 * Cancellation is wired through each worker's params:
 *  - `solve`: once any worker reports Sat/Unsat the flag is set and the others stop at their next poll;
 *  - `minimize`: a worker proving Optimal cancels the rest; else the global incumbent is BestFound;
 *  - `samples`/`improvements`: each worker runs to its budget, fanning into a callback.
 */
class Portfolio(
    /** The configured engine instances raced in parallel; each carries its own params and (for
     *  optimisation) its own objective representation. */
    val workers: List<PortfolioWorker>,
    private val strategy: PortfolioStrategy = PortfolioStrategy.RaceFirstFeasible,
) : PortfolioExecutor {

    init {
        require(workers.isNotEmpty()) { "Portfolio must have at least one worker" }
    }

    // Streaming (samples/improvements) hands each worker loop to a daemon producer thread, fanned in
    // through a lazy Sequence. A Sequence gives no close hook, so abandoning the iterator (e.g.
    // `.take(20)`) cannot by itself signal the producers — left unbounded they would spin to their
    // budget (forever, for an unbudgeted LS worker) and leak across calls. `close()` flips this flag;
    // it is OR-ed into the cancellation each streaming worker polls, so the use-block boundary stops
    // every producer promptly. Solve/minimize don't need it (they join their workers before returning).
    private val streamStop = AtomicBoolean(false)

    /**
     * Solve in parallel (blocking). [PortfolioStrategy.RaceFirstFeasible] (default) cancels siblings
     * once any worker produces a definitive Sat/Unsat; [PortfolioStrategy.Exhaustive] runs every
     * worker to its own budget and reduces afterwards (prefer Sat, then Unsat, then Unknown).
     */
    override fun solve(cancellation: Cancellation): SolveResult {
        val winnerFlag = AtomicBoolean(false)
        val token: Cancellation = { winnerFlag.load() || cancellation() }
        val cancelToken: Cancellation = when (strategy) {
            PortfolioStrategy.RaceFirstFeasible -> token
            PortfolioStrategy.Exhaustive -> cancellation
        }

        val results = parallelRun(
            workers.map { worker ->
                {
                    val r = worker.solve(cancelToken)
                    if (strategy is PortfolioStrategy.RaceFirstFeasible &&
                        (r is SolveResult.Sat || r is SolveResult.Unsat)
                    ) {
                        winnerFlag.store(true)
                    }
                    r
                }
            },
        )

        // Fold every worker's counters into the verdict — the pool's cost, not the winner's.
        val stats = results.fold(SolveStats.EMPTY) { acc, r -> acc.mergedWith(r.stats) }
        return when (
            val winner = results.firstOrNull { it is SolveResult.Sat }
                ?: results.firstOrNull { it is SolveResult.Unsat }
        ) {
            is SolveResult.Sat -> winner.copy(stats = stats)
            is SolveResult.Unsat -> winner.copy(stats = stats)
            else -> SolveResult.Unknown(TerminationReason.Cancelled, stats)
        }
    }

    /**
     * Parallel branch-and-bound minimisation (blocking) with a shared best bound. Each worker
     * streams its own improvements **against the objective representation it was built with** (#63);
     * new incumbents fold into a shared bound exposed back to every worker through its bound supplier
     * (backtrack prunes on it; LS ignores it). A worker proving Optimal cancels the rest; otherwise
     * the global incumbent is returned as BestFound, or Optimal if every worker terminated cleanly.
     */
    override fun minimize(
        cancellation: Cancellation,
        onImprovement: ((AttributedImprovement) -> Unit)?,
    ): MinimizeResult {
        val incumbent = AtomicReference(Incumbent(Double.POSITIVE_INFINITY, null))
        val cancelled = AtomicBoolean(false)
        val token: Cancellation = { cancelled.load() || cancellation() }
        fun readBound(): Double = incumbent.load().bound
        // Workers improve concurrently; serialise the attribution callback so the consumer (e.g. the
        // CLI's `-s` per-arm line) never sees interleaved invocations. Only the thread whose CAS
        // actually installed the new global best fires it — a loser's stale value never reports.
        val start = TimeSource.Monotonic.markNow()
        val emitLock = onImprovement?.let { Concurrency.Strict.lock() }
        fun fold(worker: PortfolioWorker, r: MinimizeResult.WithSample) {
            if (updateSharedBound(incumbent, r.objectiveValue, r.sample)) {
                // emitLock is non-null iff onImprovement is; the ?. on both keeps the no-callback path
                // lock-free (and dodges a !! on the nullable mutex).
                emitLock?.withLock { onImprovement?.invoke(AttributedImprovement(worker.label, start.elapsedNow(), r)) }
            }
        }

        val results = parallelRun(
            workers.map { worker ->
                {
                    var local: MinimizeResult = MinimizeResult.Unknown(TerminationReason.BudgetExhausted)
                    for (r in worker.improvements(::readBound, token)) {
                        when (r) {
                            is MinimizeResult.BestFound -> {
                                fold(worker, r)
                                local = r
                            }

                            is MinimizeResult.Optimal -> {
                                fold(worker, r)
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
            },
        )
        val stats = results.fold(SolveStats.EMPTY) { acc, r -> acc.mergedWith(r.stats) }

        // A direct Optimal claim is only produced by a worker not running under shared bounds
        // (single-worker / unshared); the engine downgrades to BestFound when a bound is shared.
        val directOptimal = results.firstOrNull { it is MinimizeResult.Optimal }
        if (directOptimal != null) return (directOptimal as MinimizeResult.Optimal).copy(stats = stats)

        // The pool proves optimality only when EVERY worker exhausted its space; a worker that timed
        // out or was cancelled mid-search is dirty regardless of verdict shape.
        val anyDirty = results.any { r ->
            when (r) {
                is MinimizeResult.BestFound -> r.reason != TerminationReason.SearchExhausted
                is MinimizeResult.Unknown -> r.reason != TerminationReason.SearchExhausted
                is MinimizeResult.Optimal, is MinimizeResult.Infeasible -> false
            }
        }
        val snapshot = incumbent.load()
        val sample = snapshot.sample
        val finalBound = snapshot.bound
        return when {
            sample != null && anyDirty ->
                MinimizeResult.BestFound(sample, finalBound, TerminationReason.BudgetExhausted, stats)

            sample != null -> MinimizeResult.Optimal(sample, finalBound, stats)

            anyDirty -> MinimizeResult.Unknown(TerminationReason.BudgetExhausted, stats)

            else -> MinimizeResult.Infeasible(stats = stats)
        }
    }

    /** CAS the (bound, sample) cell to [objective] when strictly better. Returns true iff *this* call
     *  installed the new global best — the caller fires attribution only then, so a racing loser's
     *  stale value is never reported. */
    private fun updateSharedBound(incumbent: AtomicReference<Incumbent>, objective: Double, sample: Sample): Boolean {
        while (true) {
            val cur = incumbent.load()
            if (objective >= cur.bound) return false
            // One CAS swaps bound + sample together so a reported bound always matches its sample (#81).
            if (incumbent.compareAndSet(cur, Incumbent(objective, sample))) return true
        }
    }

    /**
     * Streaming branch-and-bound: a lazy [Sequence] of every *strict* global improvement, in arrival
     * order, so the consumer sees a monotonically-improving sequence (the anytime/credit entry point).
     * Iterating drives the workers in parallel; the shared bound is exposed to bound-pruning workers
     * exactly as in [minimize]. Each element carries the producing worker and the elapsed time at the
     * moment it was found. Stop early by flipping [cancellation] (then abandoning the iterator).
     */
    fun improvementsAttributed(cancellation: Cancellation = Cancellation.Never): Sequence<AttributedImprovement> {
        val start = TimeSource.Monotonic.markNow()
        val incumbent = AtomicReference(Incumbent(Double.POSITIVE_INFINITY, null))
        fun readBound(): Double = incumbent.load().bound
        val token: Cancellation = { streamStop.load() || cancellation() }
        return parallelStream(
            workers.map { worker ->
                { emit: (AttributedImprovement) -> Unit ->
                    for (r in worker.improvements(::readBound, token)) {
                        if (r is MinimizeResult.WithSample && r.objectiveValue < readBound()) {
                            updateSharedBound(incumbent, r.objectiveValue, r.sample)
                            emit(AttributedImprovement(worker.label, start.elapsedNow(), r))
                        }
                    }
                }
            },
        )
    }

    /** [improvementsAttributed] without the per-worker attribution — just the improving results. */
    fun improvements(cancellation: Cancellation = Cancellation.Never): Sequence<MinimizeResult> =
        improvementsAttributed(cancellation).map { it.result }

    /**
     * Stream samples across all workers as a lazy [Sequence], fanning in as they are produced. Each
     * worker runs to its own budget or until [cancellation]; stop early by flipping [cancellation].
     */
    fun samples(cancellation: Cancellation = Cancellation.Never): Sequence<Sample> {
        val token: Cancellation = { streamStop.load() || cancellation() }
        return parallelStream(
            workers.map { worker -> { emit: (Sample) -> Unit -> for (s in worker.samples(token)) emit(s) } },
        )
    }

    override fun close() {
        // Stop any in-flight streaming producers before tearing down their sessions.
        streamStop.store(true)
        workers.forEach { runCatching { it.close() } }
    }
}

/** Immutable (bound, sample) pair published as one [AtomicReference] cell so the shared bound and
 *  the best sample are swapped together in a single CAS — they can never desync under a race (#81). */
private class Incumbent(val bound: Double, val sample: Sample?)

/** Strategy knobs for [Portfolio]. Affects `solve` only; `samples` always fans in from every worker
 *  and `minimize` always shares the global bound (race honoured via cancellation on Optimal). */
sealed interface PortfolioStrategy {
    /** First worker to produce a definitive answer wins; others are cancelled. Default. */
    data object RaceFirstFeasible : PortfolioStrategy

    /** Run every worker to its own budget without cross-worker cancellation, then reduce. */
    data object Exhaustive : PortfolioStrategy
}
