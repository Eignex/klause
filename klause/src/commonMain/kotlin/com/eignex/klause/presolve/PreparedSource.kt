package com.eignex.klause.presolve

import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.RetainedFactors
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.PresolveStats
import com.eignex.klause.util.ExecutionControl
import kotlin.time.Duration

// What the source-safe preparation phase produced, before any route or component plan is selected.
// Both lanes plan from [problem], not the input model: a source pass that rewrites factors moves factor
// ownership with it, so a plan selected earlier indexes factors the prepared model no longer has.
// [budget] is carried so every later phase spends the rest of the same allowance instead of restarting it.
internal class PreparedSource(
    val source: Problem,
    // [source] itself when no source pass fired.
    val problem: Problem,
    val infeasible: Boolean,
    // The caller's objective after substitution and namespace extension; the next phase must use it with
    // [problem], and reconstruction restores the original coordinates and objective value.
    val objective: LinearObjective?,
    // In first-fire order.
    val passesFired: List<PresolvePass>,
    // Lifts a witness of [problem] to one of [source] by recovering the eliminated columns.
    val rebuild: SourceRebuilds,
    val budget: PresolveBudget?,
    private val config: PresolveConfig = PresolveConfig.NONE,
    internal var elapsed: Duration = Duration.ZERO,
    val transformObjective: Boolean = false,
) {
    val mapping: SourceMapping = SourceMapping(source, problem, passesFired.transformationGuarantees(), rebuild)

    val changed: Boolean get() = problem !== source

    internal fun retainedFactors(cancellation: ExecutionControl): RetainedFactors? =
        if (infeasible || !rebuild.isEmpty || passesFired.isEmpty()) null
        else RetainedFactors.between(source, problem, cancellation)

    // This phase alone, in the shape every route reports.
    val stats: PresolveStats get() = PresolveStats(
        passes = passesFired.map { it.id },
        constraintsRemoved = source.factors.size - problem.factors.size,
        infeasible = infeasible,
        effort = config.effortStats(budget, elapsed),
    )

    companion object {
        // For an input that has already passed this phase.
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
