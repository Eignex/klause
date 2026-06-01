package com.eignex.klause.bench.metric

import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.solver.Backend
import com.eignex.klause.choco.ChocoParams
import com.eignex.klause.choco.ChocoSolver
import com.eignex.klause.ortools.OrToolsParams
import com.eignex.klause.ortools.OrToolsSolver
import com.eignex.klause.solver.MinimizeResult
import com.eignex.klause.solver.Objective
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult

/**
 * A trusted in-process reference solver used by differential metrics. Both supported
 * backends ([Backend.CHOCO] complete, [Backend.ORTOOLS] CP-SAT) expose the same minimal
 * surface — solve a [Problem] and minimize an [Objective] under a [Budget] — so metrics can
 * be parameterized over which reference they diff klause against.
 */
interface Reference {
    val name: String
    fun solve(problem: Problem, budget: Budget): SolveResult
    fun minimize(problem: Problem, objective: Objective, budget: Budget): MinimizeResult
    /** Anytime incumbent stream for the anytime metric. OR-Tools yields each new incumbent
     *  over time; Choco (complete) yields its single optimum. */
    fun improvements(problem: Problem, objective: Objective, budget: Budget): Sequence<MinimizeResult>

    companion object {
        /** The reference backends a metric may diff against. */
        val backends: List<Backend> = listOf(Backend.CHOCO, Backend.ORTOOLS)

        fun of(backend: Backend): Reference = when (backend) {
            Backend.CHOCO -> ChocoReference
            Backend.ORTOOLS -> OrToolsReference
            else -> error("$backend is not a reference solver (use ${backends})")
        }

        /** Resolve a reference by id ("choco"/"ortools"), e.g. from a system property. */
        fun byId(id: String): Reference = when (id.lowercase()) {
            "choco" -> ChocoReference
            "ortools", "or-tools" -> OrToolsReference
            else -> error("unknown reference '$id' (have choco, ortools)")
        }
    }
}

private object ChocoReference : Reference {
    override val name = "choco"
    private fun params(b: Budget) = ChocoParams(b.timeoutMillis)
    override fun solve(problem: Problem, budget: Budget) = ChocoSolver(problem).solve(params(budget))
    override fun minimize(problem: Problem, objective: Objective, budget: Budget) =
        ChocoSolver(problem).minimize(objective, params(budget))
    override fun improvements(problem: Problem, objective: Objective, budget: Budget) =
        ChocoSolver(problem).improvements(objective, params(budget))
}

private object OrToolsReference : Reference {
    override val name = "ortools"
    private fun params(b: Budget) = OrToolsParams(timeoutMillis = b.timeoutMillis)
    override fun solve(problem: Problem, budget: Budget) = OrToolsSolver(problem).solve(params(budget))
    override fun minimize(problem: Problem, objective: Objective, budget: Budget) =
        OrToolsSolver(problem).minimize(objective, params(budget))
    override fun improvements(problem: Problem, objective: Objective, budget: Budget) =
        OrToolsSolver(problem).improvements(objective, params(budget))
}
