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
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.yuck.YuckParams
import com.eignex.klause.yuck.YuckSolver

/**
 * A trusted in-process reference solver used by differential metrics. Both supported
 * backends ([Backend.CHOCO] complete, [Backend.ORTOOLS] CP-SAT) expose the same minimal
 * surface — solve a [Problem] and minimize an [Objective] under a [Budget] — so metrics can
 * be parameterized over which reference they diff klause against.
 */
internal interface Reference {
    val name: String

    /** [search]: annotation-derived klause search params for fixed-track comparisons —
     *  references that can mirror the prescribed search (Choco) apply it; others ignore it. */
    fun solve(problem: Problem, budget: Budget, search: BacktrackParams? = null): SolveResult
    fun minimize(
        problem: Problem,
        objective: Objective,
        budget: Budget,
        search: BacktrackParams? = null,
    ): MinimizeResult

    /** Anytime incumbent stream for the anytime metric. OR-Tools yields each new incumbent
     *  over time; Choco (complete) yields its single optimum. */
    fun improvements(problem: Problem, objective: Objective, budget: Budget): Sequence<MinimizeResult>

    companion object {
        /** The reference backends a metric may diff against. */
        val backends: List<Backend> = listOf(Backend.CHOCO, Backend.ORTOOLS, Backend.YUCK)

        fun of(backend: Backend): Reference = when (backend) {
            Backend.CHOCO -> ChocoReference
            Backend.ORTOOLS -> OrToolsReference
            Backend.YUCK -> YuckReference
            else -> error("$backend is not a reference solver (use $backends)")
        }

        /** Resolve a reference by id ("choco"/"ortools"/"yuck"), e.g. from a system property. */
        fun byId(id: String): Reference = when (id.lowercase()) {
            "choco" -> ChocoReference
            "ortools", "or-tools" -> OrToolsReference
            "yuck" -> YuckReference
            else -> error("unknown reference '$id' (have choco, ortools, yuck)")
        }
    }
}

private object ChocoReference : Reference {
    override val name = "choco"

    // -Dklause.bench.choco.workers=n races n diversified model copies via Choco's
    // ParallelPortfolio — the track-honest reference when klause runs a multi-worker
    // portfolio on the same core budget. Default 1 = the classic sequential reference.
    private val workers = System.getProperty("klause.bench.choco.workers")?.toIntOrNull() ?: 1

    // -Dklause.bench.choco.lcg=true builds the reference with Choco's lazy-clause-generation
    // engine — the Choco CP-SAT competition entry's architecture, the architecture-matched
    // rival for klause on the fixed track.
    private val lcg = System.getProperty("klause.bench.choco.lcg")?.toBoolean() ?: false
    private fun params(b: Budget, search: BacktrackParams? = null) =
        ChocoParams(b.timeoutMillis, workers = workers, fixedSearch = search, lcg = lcg)
    override fun solve(problem: Problem, budget: Budget, search: BacktrackParams?) =
        ChocoSolver(problem).solve(params(budget, search))
    override fun minimize(problem: Problem, objective: Objective, budget: Budget, search: BacktrackParams?) =
        ChocoSolver(problem).minimize(objective, params(budget, search))
    override fun improvements(problem: Problem, objective: Objective, budget: Budget) =
        ChocoSolver(problem).improvements(objective, params(budget))
}

/** Yuck local-search reference (temporary, LS parity sweep). Unlike the complete references it
 *  cannot prove UNSAT or optimality — "not found within budget" maps to `Unknown`, so parity
 *  rows diff feasibility/quality, not completeness. */
private object YuckReference : Reference {
    override val name = "yuck"
    private fun params(b: Budget) = YuckParams(timeoutMillis = b.timeoutMillis)
    override fun solve(problem: Problem, budget: Budget, search: BacktrackParams?) =
        YuckSolver(problem).solve(params(budget))
    override fun minimize(problem: Problem, objective: Objective, budget: Budget, search: BacktrackParams?) =
        YuckSolver(problem).minimize(objective, params(budget))
    override fun improvements(problem: Problem, objective: Objective, budget: Budget) =
        YuckSolver(problem).improvements(objective, params(budget))
}

private object OrToolsReference : Reference {
    override val name = "ortools"
    private fun params(b: Budget) = OrToolsParams(timeoutMillis = b.timeoutMillis)
    override fun solve(problem: Problem, budget: Budget, search: BacktrackParams?) =
        OrToolsSolver(problem).solve(params(budget))
    override fun minimize(problem: Problem, objective: Objective, budget: Budget, search: BacktrackParams?) =
        OrToolsSolver(problem).minimize(objective, params(budget))
    override fun improvements(problem: Problem, objective: Objective, budget: Budget) =
        OrToolsSolver(problem).improvements(objective, params(budget))
}
