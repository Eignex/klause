package com.eignex.klause.presolve

import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.PresolveStats

/**
 * What the one source-safe preparation phase produced, before any route or component plan is selected.
 *
 * Both the bounded and the open lane plan from [problem] rather than from the model handed in: a source
 * pass that rewrites factors moves factor ownership with it, so a plan selected ahead of this phase is
 * indexed by factors the prepared model no longer has.
 *
 * [rebuild] recovers the Boolean columns the phase eliminated, so a witness of [problem] becomes one of
 * [source] by running it. Integer columns are still never eliminated here — [SourceDelta] states a
 * reconstruction over Boolean columns and nothing else — so the lift a caller needs is this and no more.
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
     * The caller's objective re-fitted to [problem]'s variable space, or null when the caller's own
     * already spans it. Carried for the same reason [PresolveOutcome.objective] is: whoever runs the next
     * phase has to hand it an objective that fits the model this one produced.
     */
    val objective: LinearObjective?,
    /** Source passes that changed the model, in first-fire order. */
    val passesFired: List<PresolvePass>,
    /** Recovers the Boolean columns the phase eliminated, in the order they are recovered. */
    val rebuild: SourceRebuilds,
    /** What remains of the presolve phase's allowance. */
    val budget: PresolveBudget?,
) {
    /** Whether preparation rewrote the model. */
    val changed: Boolean get() = problem !== source

    /** Terse summary of this phase alone, in the shape every route reports. */
    val stats: PresolveStats get() = PresolveStats(
        passes = passesFired.map { it.id },
        constraintsRemoved = source.factors.size - problem.factors.size,
        infeasible = infeasible,
    )

    /** Preparations that did not run a pass. */
    companion object {
        /** [problem] as its own preparation, for an input that has already passed this phase. */
        fun unchanged(problem: Problem, budget: PresolveBudget?): PreparedSource = PreparedSource(
            source = problem,
            problem = problem,
            infeasible = false,
            objective = null,
            passesFired = emptyList(),
            rebuild = SourceRebuilds.NONE,
            budget = budget,
        )
    }
}
