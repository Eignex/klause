package com.eignex.klause.presolve

import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.RetainedFactors
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.PresolveStats
import com.eignex.klause.util.Cancellation
import kotlin.time.Duration

/**
 * What the one source-safe preparation phase produced, before any route or component plan is selected.
 *
 * Both the bounded and the open lane plan from [problem] rather than from the model handed in: a source
 * pass that rewrites factors moves factor ownership with it, so a plan selected ahead of this phase is
 * indexed by factors the prepared model no longer has.
 *
 * [rebuild] recovers eliminated Boolean and integer columns, so a witness of [problem] becomes one of
 * [source] by running it.
 *
 * [budget] is carried rather than re-derived, so every phase after this one spends what is left of the
 * same allowance instead of restarting it.
 */
internal class PreparedSource(
    /** The model this phase was handed. */
    val source: Problem,
    /** The transformed model, or [source] itself when no source pass fired. */
    val problem: Problem,
    /** Whether preparation refuted the model. */
    val infeasible: Boolean,
    /**
     * The caller's objective after substitution and namespace extension. The next phase must use it
     * with [problem]; reconstruction restores the original coordinates and objective value.
     */
    val objective: LinearObjective?,
    /** Source passes that changed the model, in first-fire order. */
    val passesFired: List<PresolvePass>,
    /** Recovers the columns the phase eliminated, in the order they are recovered. */
    val rebuild: SourceRebuilds,
    /** What remains of the presolve phase's allowance. */
    val budget: PresolveBudget?,
    private val config: PresolveConfig = PresolveConfig.NONE,
    internal var elapsed: Duration = Duration.ZERO,
    val transformObjective: Boolean = false,
) {
    val mapping: SourceMapping = SourceMapping(source, problem, passesFired.transformationGuarantees(), rebuild)

    /** Whether preparation rewrote the model. */
    val changed: Boolean get() = problem !== source

    internal fun retainedFactors(cancellation: Cancellation): RetainedFactors? =
        if (infeasible || !rebuild.isEmpty || passesFired.isEmpty()) null
        else RetainedFactors.between(source, problem, cancellation)

    /** Terse summary of this phase alone, in the shape every route reports. */
    val stats: PresolveStats get() = PresolveStats(
        passes = passesFired.map { it.id },
        constraintsRemoved = source.factors.size - problem.factors.size,
        infeasible = infeasible,
        effort = config.effortStats(budget, elapsed),
    )

    /** Preparations that did not run a pass. */
    companion object {
        /** [problem] as its own preparation, for an input that has already passed this phase. */
        fun unchanged(problem: Problem, budget: PresolveBudget?, transformObjective: Boolean = false): PreparedSource =
            PreparedSource(
                source = problem,
                problem = problem,
                infeasible = false,
                objective = null,
                passesFired = emptyList(),
                rebuild = SourceRebuilds.NONE,
                budget = budget,
                transformObjective = transformObjective,
            )
    }
}
