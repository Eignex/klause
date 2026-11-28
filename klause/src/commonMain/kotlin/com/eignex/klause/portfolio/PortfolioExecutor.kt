package com.eignex.klause.portfolio

import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.ExecutionControl
import kotlin.time.Duration

/** A blocking portfolio executor, implemented by [Portfolio]. */
interface PortfolioExecutor : AutoCloseable {
    /** Solve (satisfaction), honouring [cancellation]. */
    fun solve(cancellation: ExecutionControl = ExecutionControl.Never): SolveResult

    /**
     * Branch-and-bound minimisation, honouring [cancellation]. [onImprovement] fires once per strict global
     * improvement, tagged with the producing worker. Calls are serialised under the install lock and arrive in
     * install order, so the objectives never regress; [AttributedImprovement.elapsed] carries no such ordering.
     */
    fun minimize(
        cancellation: ExecutionControl = ExecutionControl.Never,
        onImprovement: ((AttributedImprovement) -> Unit)? = null,
    ): MinimizeResult
}

/** One strict global improvement reported by [PortfolioExecutor.minimize], tagged with its producing worker. */
data class AttributedImprovement(
    /** [PortfolioWorker.label] of the worker that produced this incumbent. */
    val workerLabel: String,
    /** [PortfolioWorker.armId] of the producing worker; replicas of the same arm share it. */
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
