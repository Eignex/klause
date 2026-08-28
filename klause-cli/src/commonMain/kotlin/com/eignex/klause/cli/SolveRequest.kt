package com.eignex.klause.cli

import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.lowering.flatzinc.FlatZincSearchHints
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.IncrementalObjective
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.FiniteSolveShape
import com.eignex.klause.solver.pipeline.OpenTheoryAssignment
import com.eignex.klause.solver.pipeline.OpenTheoryRequest

/**
 * A parsed instance, lowered to exactly what [SolveCore] needs — mode-neutral. Both the
 * FlatZinc objective-by-name + lsObjective/linear split and XCSP/SMT's single LinearObjective
 * collapse onto this shape.
 */
internal class Solvable(
    private val finite: FiniteSolveShape?,
    /** Render one solution to the mode's solution text. */
    val render: (Sample) -> String,
    /** Sign-corrected objective value in source-model units. */
    val objectiveValue: ((Sample) -> Long)?,
    /** Sign-corrected objective including continuous-column contributions. */
    val continuousObjectiveValue: ((Sample) -> Double)? = null,
    /** The component set selected once while loading the source model. */
    val pipeline: SolvablePipeline = SolvablePipeline.FiniteCp,
) {
    constructor(
        /** Finite CP problem. */
        problem: Problem?,
        optimize: Boolean,
        /** True when the user goal is maximisation (the objectives below already negate for it). */
        maximize: Boolean,
        /** Per-move gradient view of the objective for the LS workers (null for satisfy / when
         *  none); rides into `LocalSearchParams.lsObjective`. */
        lsObjective: IncrementalObjective?,
        /** The canonical linear objective every optimising backend minimises (null for satisfy). */
        linearObjective: LinearObjective?,
        /** Single objective int var id, when the objective is one variable — enables the
         *  enumerate-over-objective fallback. Null for weighted-sum objectives / satisfy. */
        objVarId: Int?,
        /** FlatZinc definitional-sweep DAG; null for XCSP/SMT. */
        definitionalSweep: DefinitionalSweep?,
        render: (Sample) -> String,
        /** Sign-corrected objective value in original problem units, or null for satisfy. Carries the
         *  discrete terms only, so a model weighting a continuous column also sets [continuousObjectiveValue]
         *  — this one stays exact for the integral formats, whose coefficients can outrun a `Double`. */
        objectiveValue: ((Sample) -> Long)?,
        /** Sign-corrected objective value including the continuous columns' contribution, set only when the
         *  objective weights one: their values are real, so the total has no exact integral form. Null
         *  leaves [objectiveValue] the whole objective. */
        continuousObjectiveValue: ((Sample) -> Double)? = null,
        /** Model-supplied FlatZinc search annotation. */
        searchHints: FlatZincSearchHints? = null,
        /** The component set selected once while loading the source model. */
        pipeline: SolvablePipeline = SolvablePipeline.FiniteCp,
    ) : this(
        finite = FiniteSolveShape(
            problem,
            optimize,
            maximize,
            lsObjective,
            linearObjective,
            objVarId,
            definitionalSweep,
            searchHints,
        ),
        render = render,
        objectiveValue = objectiveValue,
        continuousObjectiveValue = continuousObjectiveValue,
        pipeline = pipeline,
    )

    /** Finite CP problem. */
    val problem: Problem? get() = finite?.problem
    val optimize: Boolean get() = finite?.optimize ?: false
    val maximize: Boolean get() = finite?.maximize ?: false
    val lsObjective: IncrementalObjective? get() = finite?.localSearchObjective
    val linearObjective: LinearObjective? get() = finite?.linearObjective
    val objVarId: Int? get() = finite?.objectiveIntVar
    val definitionalSweep: DefinitionalSweep? get() = finite?.definitionalSweep
    val searchHints: FlatZincSearchHints? get() = finite?.searchHints
    val finiteShape: FiniteSolveShape get() = requireNotNull(finite) { "open model was not materialized" }
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
