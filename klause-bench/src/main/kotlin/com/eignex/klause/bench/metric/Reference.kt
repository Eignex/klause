package com.eignex.klause.bench.metric

import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.solver.Backend
import com.eignex.klause.choco.ChocoParams
import com.eignex.klause.choco.ChocoSolver
import com.eignex.klause.ortools.OrToolsParams
import com.eignex.klause.ortools.OrToolsSolver
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.yuck.YuckParams
import com.eignex.klause.yuck.YuckSolver

/** A reference optimisation result with its time-to-best — the solve metric records both the
 *  objective and *when* it was reached, so offline comparison can score better value OR same value
 *  sooner. [timeToBestMs] is null when no incumbent was found; [proven] is true only when the
 *  reference closed the search. */
internal data class RefTimed(val value: Double?, val timeToBestMs: Long?, val proven: Boolean)

/**
 * A trusted in-process reference solver used by differential metrics. Both supported
 * backends ([Backend.CHOCO] complete, [Backend.ORTOOLS] CP-SAT) expose the same minimal
 * surface — solve a [Problem] and minimize an [Objective] under a [Budget] — so metrics can
 * be parameterized over which reference they diff klause against.
 */
internal interface Reference {
    val name: String

    /** [search]: annotation-derived klause search params for fixed-track comparisons —
     *  references that can mirror the prescribed search (Choco) apply it; others ignore it.
     *  [processors]: parallel-search width — the same `processors=` that drives klause, so a
     *  track is faithful end-to-end (Choco races that many diversified copies; Yuck/OR-Tools,
     *  single-process, ignore it). */
    fun solve(problem: Problem, budget: Budget, search: BacktrackParams? = null, processors: Int = 1): SolveResult

    /** Minimise [objective], capturing the best value AND its time-to-best (see [RefTimed]) so an
     *  offline comparison can break value-ties on speed. */
    fun minimizeTimed(
        problem: Problem,
        objective: LinearObjective,
        budget: Budget,
        search: BacktrackParams? = null,
        processors: Int = 1,
    ): RefTimed

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

    // Choco is always the CP-SAT (lazy-clause-generation) engine — see ChocoModel.build, no toggle —
    // and its parallel width is the track's `processors` (Choco races that many diversified copies via
    // ParallelPortfolio), so the reference matches klause's compute budget without a separate knob.
    private fun params(b: Budget, search: BacktrackParams? = null, processors: Int = 1) =
        ChocoParams(b.timeoutMillis, workers = processors, fixedSearch = search)
    override fun solve(problem: Problem, budget: Budget, search: BacktrackParams?, processors: Int) =
        ChocoSolver(problem).solve(params(budget, search, processors))
    override fun minimizeTimed(
        problem: Problem,
        objective: LinearObjective,
        budget: Budget,
        search: BacktrackParams?,
        processors: Int,
    ): RefTimed {
        // Choco measures time-to-best internally (precise, at the moment the bound improves).
        val t = ChocoSolver(problem).minimizeTimed(objective, params(budget, search, processors))
        return RefTimed(t.value, t.timeToBestMillis, t.proven)
    }
}

/** Drain an incumbent [stream] (budget-bounded), stamping each new best with the wall-clock elapsed
 *  since the call — the time-to-best source for references without a native timed minimize. */
private fun timedFromImprovements(stream: Sequence<MinimizeResult>): RefTimed {
    val start = System.currentTimeMillis()
    var value: Double? = null
    var ms: Long? = null
    var proven = false
    for (r in stream) {
        val v = r.objectiveValue ?: continue
        value = v
        ms = System.currentTimeMillis() - start
        proven = r is MinimizeResult.Optimal
    }
    return RefTimed(value, ms, proven)
}

/** Yuck local-search reference (temporary, LS baseline sweep). Unlike the complete references it
 *  cannot prove UNSAT or optimality — "not found within budget" maps to `Unknown`, so its rows
 *  carry feasibility/quality, not completeness. */
private object YuckReference : Reference {
    override val name = "yuck"
    private fun params(b: Budget) = YuckParams(timeoutMillis = b.timeoutMillis)
    override fun solve(problem: Problem, budget: Budget, search: BacktrackParams?, processors: Int) =
        YuckSolver(problem).solve(params(budget))
    override fun minimizeTimed(
        problem: Problem,
        objective: LinearObjective,
        budget: Budget,
        search: BacktrackParams?,
        processors: Int,
    ): RefTimed {
        // Yuck runs a batch subprocess, so it stamps each incumbent's true emit time internally
        // rather than relying on drain-time stamping (which would collapse to ~0ms).
        val t = YuckSolver(problem).minimizeTimed(objective, params(budget))
        return RefTimed(t.value, t.timeToBestMillis, t.proven)
    }
}

private object OrToolsReference : Reference {
    override val name = "ortools"
    private fun params(b: Budget) = OrToolsParams(timeoutMillis = b.timeoutMillis)
    override fun solve(problem: Problem, budget: Budget, search: BacktrackParams?, processors: Int) =
        OrToolsSolver(problem).solve(params(budget))
    override fun minimizeTimed(
        problem: Problem,
        objective: LinearObjective,
        budget: Budget,
        search: BacktrackParams?,
        processors: Int,
    ) = timedFromImprovements(OrToolsSolver(problem).improvements(objective, params(budget)))
}
