package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.lowering.flatzinc.FlatZincSearchHints
import com.eignex.klause.solver.objective.IncrementalObjective
import com.eignex.klause.solver.objective.LinearObjective

/** Source-independent finite model data consumed by the solver pipeline. */
class FiniteSolveShape(
    /** Finite-domain problem to solve, or null until an open source model is materialized. */
    val problem: Problem?,
    /** Whether the request optimizes an objective. */
    val optimize: Boolean,
    /** Whether the source objective is a maximization. */
    val maximize: Boolean,
    /** Per-move objective view for local-search workers. */
    val localSearchObjective: IncrementalObjective?,
    /** Canonical objective minimized by complete engines. */
    val linearObjective: LinearObjective?,
    /** Objective integer column when the objective is one column. */
    val objectiveIntVar: Int?,
    /** Source-level definitional sweep for local-search workers. */
    val definitionalSweep: DefinitionalSweep?,
    /** Source-provided search hints, decoded by the pipeline for the fixed route. */
    val searchHints: FlatZincSearchHints? = null,
) {
    /** Finite problem, rejecting an open source model before materialization. */
    val finiteProblem: Problem get() = requireNotNull(problem) { "open model was not materialized" }
}
