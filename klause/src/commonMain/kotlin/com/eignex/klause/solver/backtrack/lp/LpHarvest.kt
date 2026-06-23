package com.eignex.klause.solver.backtrack.lp

import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStatsSink

/**
 * Fold the LP relaxation's proven domain tightenings into [problem] permanently (#10) — the
 * relaxation-harvest idea in the one place it pays off in klause. [LpEngine.shaveVariableBounds]
 * proves, by LP + propagation infeasibility, that some integer variables cannot reach their declared
 * bounds; this intersects those proven bounds into the integer domains and returns a tightened
 * [Problem].
 *
 * Unlike `BacktrackSolver`'s root shave — which raises bounds only on its own search session, and only
 * for the backtrack backend — this is a `Problem -> Problem` transform run during presolve, so the
 * tightened domains reach **every** backend (local search included) and feed any later presolve pass.
 * Sound: every shaved bound is backed by an infeasibility proof, so intersecting it removes only
 * values no solution can take.
 *
 * Gated to the LP relaxation actually being built (`bounding` on) and variable shaving being enabled;
 * otherwise, or when nothing shaves, [problem] is returned unchanged. The shave itself is internally
 * bounded by `SHAVE_MAX_ITERS`, and [cancellation] caps it further.
 */
fun lpHarvest(
    problem: Problem,
    objective: LinearObjective,
    params: BacktrackParams,
    cancellation: Cancellation = Cancellation.Never,
): Problem {
    val engine = LpEngine(problem, objective, params, SolveStatsSink(backend = "lp-harvest"))
    if (engine.lpRelaxer == null || !engine.params.lpPlan.variableShaving) return problem
    val shaved = engine.shaveVariableBounds(cancellation)
    if (shaved.isEmpty()) return problem
    val domains = problem.intDomains.copyOf()
    for (sb in shaved) {
        // sb.lo/sb.hi lie within the variable's current [min, max] with lo <= hi, so neither narrowing
        // can empty the domain.
        domains[sb.varId] = domains[sb.varId].withMinAtLeast(sb.lo).withMaxAtMost(sb.hi)
    }
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
