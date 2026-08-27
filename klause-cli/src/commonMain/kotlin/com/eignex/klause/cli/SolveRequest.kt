package com.eignex.klause.cli

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.IncrementalObjective
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.OpenTheoryAssignment
import com.eignex.klause.solver.pipeline.OpenTheoryRequest
import com.eignex.klause.solver.result.PresolveStats

/**
 * A parsed instance, lowered to exactly what [SolveCore] needs — mode-neutral. Both the
 * FlatZinc objective-by-name + lsObjective/linear split and XCSP/SMT's single LinearObjective
 * collapse onto this shape.
 */
internal class Solvable(
    /** Finite CP problem. */
    val problem: Problem?,
    val optimize: Boolean,
    /** True when the user goal is maximisation (the objectives below already negate for it). */
    val maximize: Boolean,
    /** Per-move gradient view of the objective for the LS workers (null for satisfy / when
     *  none); rides into `LocalSearchParams.lsObjective`. */
    val lsObjective: IncrementalObjective?,
    /** The canonical linear objective every optimising backend minimises (null for satisfy). */
    val linearObjective: LinearObjective?,
    /** Single objective int var id, when the objective is one variable — enables the
     *  enumerate-over-objective fallback. Null for weighted-sum objectives / satisfy. */
    val objVarId: Int?,
    /** FlatZinc definitional-sweep DAG; null for XCSP/SMT. */
    val definitionalSweep: DefinitionalSweep?,
    /** Render one solution to the mode's solution text. */
    val render: (Sample) -> String,
    /** Sign-corrected objective value in original problem units, or null for satisfy. Carries the
     *  discrete terms only, so a model weighting a continuous column also sets [continuousObjectiveValue]
     *  — this one stays exact for the integral formats, whose coefficients can outrun a `Double`. */
    val objectiveValue: ((Sample) -> Long)?,
    /** Sign-corrected objective value including the continuous columns' contribution, set only when the
     *  objective weights one: their values are real, so the total has no exact integral form. Null
     *  leaves [objectiveValue] the whole objective. */
    val continuousObjectiveValue: ((Sample) -> Double)? = null,
    /** Model-supplied backtrack search (FlatZinc `solve :: *_search(...)`); the driver uses
     *  it unless `-f` (free search) is set. Null when the mode carries no search annotations
     *  (XCSP/SMT), so the driver falls back to its default CDCL configuration. */
    val annotatedBacktrackParams: BacktrackParams? = null,
    /** Terse presolve summary for `-s`, set by [presolved] (null when presolve was off / a no-op). */
    val presolve: PresolveStats? = null,
    /** The component set selected once while loading the source model. */
    val pipeline: SolvablePipeline = SolvablePipeline.FiniteCp,
) {
    val finiteProblem: Problem get() = requireNotNull(problem) { "open model was not materialized" }
}

/** A concrete solve pipeline, selected before [SolveCore] starts its finite CP loop. */
internal sealed interface SolvablePipeline {
    /** The ordinary finite-domain CP, LP, and search components. */
    data object FiniteCp : SolvablePipeline

    /** A complete open-model theory, selected by the solver-side route builder. */
    data class OpenTheory(
        /** Mode-neutral pipeline request; this wrapper owns only output rendering. */
        val request: OpenTheoryRequest,
        val render: (OpenTheoryAssignment) -> String,
    ) : SolvablePipeline
}
