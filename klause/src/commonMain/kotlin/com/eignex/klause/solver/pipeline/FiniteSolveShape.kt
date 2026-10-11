package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SearchHints
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.solver.objective.IncrementalObjective
import com.eignex.klause.solver.objective.LinearObjective

/** Source-independent finite model data consumed by the solver pipeline. */
class FiniteSolveShape(
    /** Canonical logical model accepted by deferred finite preparation. */
    val problem: Problem,
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
    /** Neutral variable order, value selection and grouping for the finite route. */
    val searchHints: SearchHints? = null,
    /** Component ownership selected from the canonical source model. */
    val componentPlan: ComponentPlan = problem.componentPlan(preferFinite = true),
) {
    /** Canonical bounded problem consumed by finite orchestration. */
    val finiteProblem: Problem get() = problem
}
