package com.eignex.klause.solver.backtrack.lp

import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStatsSink

/**
 * Fold the LP relaxation's proven domain tightenings into [problem] permanently (#10) — the
 * relaxation-harvest idea in the one place it pays off in klause. Two harvests share the one root-LP
 * build:
 *  - [LpEngine.shaveVariableBounds] proves, by LP + propagation infeasibility, that some integer
 *    variables cannot reach their declared bounds; their proven bounds intersect into the domains.
 *  - [LpEngine.shaveObjectiveLb] proves a lower bound on a single ascending (minimised) objective
 *    variable; it intersects into that variable's domain.
 *
 * Unlike `BacktrackSolver`'s root shave — which raises bounds only on its own search session, and only
 * for the backtrack backend — this is a `Problem -> Problem` transform run during presolve, so the
 * tightened domains reach **every** backend (local search included) and feed any later presolve pass.
 * Sound: every shaved bound is backed by an infeasibility proof, so intersecting it removes only
 * values no solution can take.
 *
 * Reduced-cost fixing (`applySparseReducedCostFixing`) is deliberately *not* harvested here. It is
 * incumbent-relative — a fixing is valid only for solutions strictly better than a known incumbent, so
 * it needs a finite incumbent (which presolve has not yet found) and it removes feasible-but-non-optimal
 * assignments rather than infeasible ones. A `Problem -> Problem` transform has no channel to retain the
 * incumbent as the answer, so folding such fixings in could discard the optimum itself (when the
 * incumbent is optimal, the fixing bound `objective <= incumbent - 1` makes the problem infeasible). It
 * stays in the search loop, where branch-and-bound keeps the incumbent separately.
 *
 * Gated to the LP relaxation actually being built (`bounding` on) and at least one of variable / objective
 * shaving being enabled; otherwise, or when nothing shaves, [problem] is returned unchanged. Each shave is
 * internally bounded by `SHAVE_MAX_ITERS`, and [cancellation] caps it further.
 */
fun lpHarvest(
    problem: Problem,
    objective: LinearObjective,
    params: BacktrackParams,
    cancellation: Cancellation = Cancellation.Never,
): Problem {
    val engine = LpEngine(problem, objective, params, SolveStatsSink(backend = "lp-harvest"))
    val plan = engine.params.lpPlan
    if (engine.lpRelaxer == null || (!plan.variableShaving && !plan.objectiveShaving)) return problem

    val shaved = if (plan.variableShaving) engine.shaveVariableBounds(cancellation) else emptyList()
    // The objective LB binds only a single ascending (minimised) objective variable; shaveObjectiveLb
    // returns null otherwise, so a maximise / multi-term objective harvests nothing here.
    val obj = if (plan.objectiveShaving) objective.singleIntObjective() else null
    val objLb = obj?.let { o ->
        engine.shaveObjectiveLb(o.varId, o.ascending, cancellation)?.let { lb -> o.varId to lb }
    }
    if (shaved.isEmpty() && objLb == null) return problem

    val domains = problem.intDomains.copyOf()
    for (sb in shaved) {
        // sb.lo/sb.hi lie within the variable's current [min, max] with lo <= hi, so neither narrowing
        // can empty the domain.
        domains[sb.varId] = domains[sb.varId].withMinAtLeast(sb.lo).withMaxAtMost(sb.hi)
    }
    // The proven LB never exceeds any feasible objective value, so raising the variable's min to it
    // cannot empty the domain.
    objLb?.let { (varId, lb) -> domains[varId] = domains[varId].withMinAtLeast(lb) }
    return Problem(
        numBoolVars = problem.numBoolVars,
        numIntVars = problem.numIntVars,
        intDomains = domains,
        factors = problem.factors,
        probeFailedLiterals = problem.probeFailedLiterals,
        probeIntBounds = problem.probeIntBounds,
        probeIntHoles = problem.probeIntHoles,
        probeBudgetPerVar = problem.probeBudgetPerVar,
        probeTotalBudget = problem.probeTotalBudget,
        probeSeed = problem.probeSeed,
    )
}
