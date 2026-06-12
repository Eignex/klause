package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.runner.ResolvedProblem
import com.eignex.klause.bench.solver.Backend
import com.eignex.klause.choco.ChocoParams
import com.eignex.klause.choco.ChocoSolver
import com.eignex.klause.ortools.OrToolsParams
import com.eignex.klause.ortools.OrToolsSolver
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.yuck.YuckParams
import com.eignex.klause.yuck.YuckSolver

/** A reference optimisation result with its time-to-best — the solve metric records both the
 *  objective and *when* it was reached, so offline comparison can score better value OR same value
 *  sooner. [timeToBestMs] is null when no incumbent was found; [proven] is true only when the
 *  reference closed the search. */
internal data class RefTimed(val value: Double?, val timeToBestMs: Long?, val proven: Boolean)

/**
 * A trusted reference solver used by the solve metric. For a **MiniZinc** instance the reference
 * runs end-to-end via `minizinc --solver <id>` ([MznReference]) — the faithful competition path,
 * where the solver compiles the model with its own globals library. For every other format (XCSP3
 * / OPB / DIMACS / SMT, which have no `.mzn`) it falls back to the in-process adapter, which
 * re-derives the reference from klause's parsed [Problem].
 */
internal interface Reference {
    val name: String

    /** The reference's registered MiniZinc solver id (`minizinc --solver <id>`), or null when it
     *  has none here — then it always uses the in-process adapter. */
    val mznSolverId: String?

    /** [search]: annotation-derived klause search params for the fixed track (in-process adapters
     *  only; the MiniZinc path honours the model's own annotation). [processors]: parallel width
     *  for the in-process adapters. */
    fun solve(entry: ResolvedProblem, budget: Budget, search: BacktrackParams? = null, processors: Int = 1): SolveResult

    /** Minimise the instance's objective, capturing the best value AND its time-to-best (see
     *  [RefTimed]) so an offline comparison can break value-ties on speed. */
    fun minimizeTimed(
        entry: ResolvedProblem,
        objective: LinearObjective,
        budget: Budget,
        search: BacktrackParams? = null,
        processors: Int = 1,
    ): RefTimed

    /** True when this reference should run [entry] end-to-end via MiniZinc (a MiniZinc instance and
     *  its solver config is registered) rather than the in-process adapter. */
    fun useMzn(entry: ResolvedProblem): Boolean =
        entry.ref.format == Format.MINIZINC && mznSolverId?.let { MznReference.available(it) } == true

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

/** Map a [MznReference.Outcome] feasibility verdict to a [SolveResult]. The reference is trusted,
 *  so a placeholder assignment (sized to [problem]) stands in — the solve metric reads only the
 *  Sat/Unsat/Unknown verdict for satisfaction rows, never the assignment. */
private fun mznSolveResult(o: MznReference.Outcome, problem: Problem): SolveResult = when (o.feasible) {
    true -> SolveResult.Sat(Sample(BooleanArray(problem.numBoolVars), IntArray(problem.numIntVars)))
    false -> SolveResult.Unsat()
    null -> SolveResult.Unknown(TerminationReason.Timeout)
}

private object ChocoReference : Reference {
    override val name = "choco"
    override val mznSolverId = "choco"

    // Choco is always the CP-SAT (lazy-clause-generation) engine — see ChocoModel.build, no toggle —
    // and its parallel width is the track's `processors` (Choco races that many diversified copies via
    // ParallelPortfolio), so the reference matches klause's compute budget without a separate knob.
    private fun params(b: Budget, search: BacktrackParams? = null, processors: Int = 1) =
        ChocoParams(b.timeoutMillis, workers = processors, fixedSearch = search)
    override fun solve(entry: ResolvedProblem, budget: Budget, search: BacktrackParams?, processors: Int) =
        if (useMzn(entry)) {
            mznSolveResult(MznReference.run(entry.ref, mznSolverId, budget, optimize = false), entry.problem)
        } else {
            ChocoSolver(entry.problem).solve(params(budget, search, processors))
        }
    override fun minimizeTimed(
        entry: ResolvedProblem,
        objective: LinearObjective,
        budget: Budget,
        search: BacktrackParams?,
        processors: Int,
    ): RefTimed {
        if (useMzn(entry)) {
            val o = MznReference.run(entry.ref, mznSolverId, budget, optimize = true)
            return RefTimed(o.objective, o.timeToBestMs, o.proven)
        }
        // Choco measures time-to-best internally (precise, at the moment the bound improves).
        val t = ChocoSolver(entry.problem).minimizeTimed(objective, params(budget, search, processors))
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
    override val mznSolverId = "yuck"
    private fun params(b: Budget) = YuckParams(timeoutMillis = b.timeoutMillis)
    override fun solve(entry: ResolvedProblem, budget: Budget, search: BacktrackParams?, processors: Int) =
        if (useMzn(entry)) {
            mznSolveResult(MznReference.run(entry.ref, mznSolverId, budget, optimize = false), entry.problem)
        } else {
            YuckSolver(entry.problem).solve(params(budget))
        }
    override fun minimizeTimed(
        entry: ResolvedProblem,
        objective: LinearObjective,
        budget: Budget,
        search: BacktrackParams?,
        processors: Int,
    ): RefTimed {
        if (useMzn(entry)) {
            val o = MznReference.run(entry.ref, mznSolverId, budget, optimize = true)
            return RefTimed(o.objective, o.timeToBestMs, o.proven)
        }
        // In-process Yuck runs a batch subprocess, so it stamps each incumbent's true emit time
        // internally rather than relying on drain-time stamping (which would collapse to ~0ms).
        val t = YuckSolver(entry.problem).minimizeTimed(objective, params(budget))
        return RefTimed(t.value, t.timeToBestMillis, t.proven)
    }
}

private object OrToolsReference : Reference {
    override val name = "ortools"

    // OR-Tools has no registered MiniZinc solver config here, so it always uses the in-process adapter.
    override val mznSolverId: String? = null
    private fun params(b: Budget) = OrToolsParams(timeoutMillis = b.timeoutMillis)
    override fun solve(entry: ResolvedProblem, budget: Budget, search: BacktrackParams?, processors: Int) =
        OrToolsSolver(entry.problem).solve(params(budget))
    override fun minimizeTimed(
        entry: ResolvedProblem,
        objective: LinearObjective,
        budget: Budget,
        search: BacktrackParams?,
        processors: Int,
    ) = timedFromImprovements(OrToolsSolver(entry.problem).improvements(objective, params(budget)))
}
