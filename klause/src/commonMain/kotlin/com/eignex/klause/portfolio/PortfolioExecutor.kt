package com.eignex.klause.portfolio

import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.Cancellation
import kotlin.time.Duration

/**
 * The common, **blocking** interface of the two portfolio executors — the parallel `Portfolio`
 * (jvm+native, real threads) and the single-core [SequentialPortfolio] (bandit-scheduled segments).
 * A caller selects one by [PortfolioScenario.cores] and then invokes `solve`/`minimize`
 * identically, regardless of which it got. Coroutine-free: both are plain blocking calls.
 */
interface PortfolioExecutor : AutoCloseable {
    /** Solve (satisfaction), honouring [cancellation]. */
    fun solve(cancellation: Cancellation = Cancellation.Never): SolveResult

    /**
     * Branch-and-bound minimisation, honouring [cancellation]. When [onImprovement] is set it fires
     * once per **strict global improvement**, tagged with the producing worker — the attribution
     * entry point for anytime telemetry / per-arm credit. The callback is serialised: the parallel
     * executor holds a lock across it, the single-core one is inherently sequential, so the consumer
     * never sees concurrent invocations.
     */
    fun minimize(
        cancellation: Cancellation = Cancellation.Never,
        onImprovement: ((AttributedImprovement) -> Unit)? = null,
    ): MinimizeResult
}

/** One strict global improvement, tagged with the producing worker's label and the elapsed time
 *  since the minimisation started. Emitted by [PortfolioExecutor.minimize]'s `onImprovement` and by
 *  the parallel `Portfolio.improvementsAttributed` stream. */
data class AttributedImprovement(
    /** [PortfolioWorker.label] of the worker that produced this incumbent. */
    val workerLabel: String,
    /** [PortfolioWorker.armId] of the producing worker — its composed-arm identity; replicas of the
     *  same arm share it, so a credit consumer pools their rewards. */
    val armId: Int,
    /** Time since the minimisation started. */
    val elapsed: Duration,
    /** The strict global improvement itself (always a [MinimizeResult.WithSample]). */
    val result: MinimizeResult,
)
