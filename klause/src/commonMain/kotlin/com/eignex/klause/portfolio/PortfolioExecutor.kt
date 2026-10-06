package com.eignex.klause.portfolio

import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.Cancellation
import kotlin.time.Duration

/**
 * The **blocking** interface of a portfolio executor, implemented by [Portfolio] on as many lanes as
 * [PortfolioScenario.cores] gives it. Coroutine-free: `solve` and `minimize` are plain blocking calls.
 */
interface PortfolioExecutor : AutoCloseable {
    /** Solve (satisfaction), honouring [cancellation]. */
    fun solve(cancellation: Cancellation = Cancellation.Never): SolveResult

    /**
     * Branch-and-bound minimisation, honouring [cancellation]. When [onImprovement] is set it fires
     * once per **strict global improvement**, tagged with the producing worker — the attribution
     * entry point for anytime telemetry / per-arm credit. The callback is serialised under the
     * same lock as the install, so the consumer never sees concurrent invocations and receives the improvements
     * in the order the shared incumbent installed them: the objectives a consumer scores against the one before
     * them never regress. [AttributedImprovement.elapsed] carries no
     * such ordering; see it.
     */
    fun minimize(
        cancellation: Cancellation = Cancellation.Never,
        onImprovement: ((AttributedImprovement) -> Unit)? = null,
    ): MinimizeResult
}

/** One strict global improvement, tagged with the producing worker's label and the elapsed time
 *  since the minimisation started. Emitted by [PortfolioExecutor.minimize]'s `onImprovement`. */
data class AttributedImprovement(
    /** [PortfolioWorker.label] of the worker that produced this incumbent. */
    val workerLabel: String,
    /** [PortfolioWorker.armId] of the producing worker — its composed-arm identity; replicas of the
     *  same arm share it, so a credit consumer pools their rewards. */
    val armId: Int,
    /** Time since the minimisation started, read by the producing worker after its own install rather than
     *  with it. Two publishers racing between their compare-and-set and this read can take their readings
     *  the other way round, so this does not increase along the install-ordered sequence. */
    val elapsed: Duration,
    /** The strict global improvement itself (always a [MinimizeResult.WithSample]). */
    val result: MinimizeResult,
)

/**
 * Checks a result an arm claims against the model before a [Portfolio] accepts it, so one faulty arm configuration
 * cannot hand the run a wrong answer.
 */
fun interface WitnessCheck {
    /**
     * Null when [sample] satisfies the model and, when [objective] is given, scores exactly that; otherwise the
     * reason it does not.
     */
    fun refute(sample: Sample, objective: Double?): String?
}

/** An arm a [Portfolio] quarantined: it claimed a result the model refutes, so the run stopped scheduling it. */
data class ArmFault(
    /** [PortfolioWorker.label] of the faulty arm. */
    val workerLabel: String,
    /** [PortfolioWorker.armId] of the faulty arm. */
    val armId: Int,
    /** What the arm claimed and why the model refutes it. */
    val reason: String,
)
