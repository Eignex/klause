package com.eignex.klause.backtrack.lp

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.LpHarvestReport
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
): Problem = lpHarvestReporting(problem, objective, params, cancellation).problem

/** [lpHarvest]'s transformed [problem] paired with the [report] of what the LP harvest contributed. */
class LpHarvestResult(val problem: Problem, val report: LpHarvestReport)

/** [lpHarvest] returning, alongside the transformed problem, a breakdown of the LP harvest's own effect
 *  (root infeasibility, bounds shaved, objective floor, constraints removed, equalities added) so a
 *  caller can isolate it from the surrounding combinatorial passes. */
fun lpHarvestReporting(
    problem: Problem,
    objective: LinearObjective,
    params: BacktrackParams,
    cancellation: Cancellation = Cancellation.Never,
): LpHarvestResult {
    val engine = LpEngine(problem, objective, params, SolveStatsSink(backend = "lp-harvest"))
    if (engine.lpRelaxer == null) return LpHarvestResult(problem, LpHarvestReport())
    // A certified-infeasible root relaxation proves the whole problem has no solution; fold it in as an
    // explicit contradiction so the problem bakes Unsat and every backend short-circuits.
    if (engine.rootInfeasible(cancellation)) {
        return LpHarvestResult(provenInfeasible(problem), LpHarvestReport(rootInfeasible = true))
    }
    val plan = engine.params.lpPlan
    if (!plan.variableShaving && !plan.objectiveShaving) return LpHarvestResult(problem, LpHarvestReport())

    // Self-limit on the built relaxation's size. The shave / redundancy / equality probes each rebuild and
    // re-solve the relaxation (up to SHAVE_MAX_ITERS of each), so on a large relaxation that per-candidate
    // cost dominates the time budget and loses instances the search would otherwise solve. Measure the root
    // relaxation once (cols / rows / nnz) and, above the budget, skip the probe work — the harvest's gains
    // are on small / medium models.
    val size = engine.rootRelaxationSize()
    if (size != null && size.cost > LP_HARVEST_MAX_RELAXATION_COST) {
        val report = LpHarvestReport(
            relaxationCols = size.cols,
            relaxationRows = size.rows,
            relaxationNnz = size.nnz,
            skipped = true,
        )
        return LpHarvestResult(problem, report)
    }

    val shaved = if (plan.variableShaving) engine.shaveVariableBounds(cancellation) else emptyList()
    // The objective LB binds only a single ascending (minimised) objective variable; shaveObjectiveLb
    // returns null otherwise, so a maximise / multi-term objective harvests nothing here.
    val obj = if (plan.objectiveShaving) objective.singleIntObjective() else null
    val objLb = obj?.let { o ->
        engine.shaveObjectiveLb(o.varId, o.ascending, cancellation)?.let { lb -> o.varId to lb }
    }
    // Constraints the LP proves implied by the rest — dropped permanently (solution-set-preserving).
    val redundant = if (plan.variableShaving) engine.redundantConstraints(cancellation).toHashSet() else emptySet()
    // Differences the LP proves pinned to a constant — added as `=` factors for the next presolve round's
    // affine elimination to fold out, shrinking the variable space (this transform only adds the factor).
    val equalities = if (plan.variableShaving) engine.impliedEqualities(cancellation) else emptyList()
    if (shaved.isEmpty() && objLb == null && redundant.isEmpty() && equalities.isEmpty()) {
        return LpHarvestResult(problem, LpHarvestReport())
    }
    val report = LpHarvestReport(
        boundsShaved = shaved.size,
        objectiveLbRaised = objLb != null,
        constraintsRemoved = redundant.size,
        equalitiesAdded = equalities.size,
        relaxationCols = size?.cols ?: 0,
        relaxationRows = size?.rows ?: 0,
        relaxationNnz = size?.nnz ?: 0,
    )

    val domains = problem.intDomains.copyOf()
    for (sb in shaved) {
        // sb.lo/sb.hi lie within the variable's current [min, max] with lo <= hi, so neither narrowing
        // can empty the domain.
        domains[sb.varId] = domains[sb.varId].withMinAtLeast(sb.lo).withMaxAtMost(sb.hi)
    }
    // The proven LB never exceeds any feasible objective value, so raising the variable's min to it
    // cannot empty the domain.
    objLb?.let { (varId, lb) -> domains[varId] = domains[varId].withMinAtLeast(lb) }
    // Dropping redundant rows and appending proven equalities both keep the variable space (the affine
    // pass that consumes an equality runs later and carries its own reconstruct), so none is needed here.
    val factors = if (redundant.isEmpty() && equalities.isEmpty()) {
        problem.factors
    } else {
        (problem.factors.filterIndexed { idx, _ -> idx !in redundant } + equalities).toTypedArray()
    }
    val transformed = Problem(
        numBoolVars = problem.numBoolVars,
        numIntVars = problem.numIntVars,
        intDomains = domains,
        factors = factors,
        probeFailedLiterals = problem.probeFailedLiterals,
        probeIntBounds = problem.probeIntBounds,
        probeIntHoles = problem.probeIntHoles,
        probeBudgetPerVar = problem.probeBudgetPerVar,
        probeTotalBudget = problem.probeTotalBudget,
        probeSeed = problem.probeSeed,
    )
    return LpHarvestResult(transformed, report)
}

/**
 * [problem] with an explicit contradiction appended so its bake propagation reports `Unsat` — used when
 * the LP harvest has *certified* the root relaxation infeasible. Two equalities pinning one variable to
 * consecutive values (or a Boolean forced both ways) are jointly unsatisfiable regardless of domains, so
 * the conflict is witnessed without depending on the LP proof being re-derivable downstream. Sound: an
 * infeasible problem has no solutions, and the relaxation infeasibility certifies there are none.
 */
private fun provenInfeasible(problem: Problem): Problem {
    val factors = ArrayList<Factor>(problem.factors.size + 2)
    factors.addAll(problem.factors)
    when {
        problem.numIntVars > 0 -> {
            val min = problem.intDomains[0].min
            val c = if (min < Int.MAX_VALUE) min else min - 1
            factors.add(Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, c))
            factors.add(Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, c + 1))
        }

        problem.numBoolVars > 0 -> {
            factors.add(Clause(intArrayOf(Lit.make(0, true)))) // b0 = true
            factors.add(Clause(intArrayOf(Lit.make(0, false)))) // b0 = false
        }

        else -> return problem // no variable to pin a contradiction on (a variable-free problem)
    }
    return Problem(
        numBoolVars = problem.numBoolVars,
        numIntVars = problem.numIntVars,
        intDomains = problem.intDomains.copyOf(),
        factors = factors,
        probeFailedLiterals = problem.probeFailedLiterals,
        probeIntBounds = problem.probeIntBounds,
        probeIntHoles = problem.probeIntHoles,
        probeBudgetPerVar = problem.probeBudgetPerVar,
        probeTotalBudget = problem.probeTotalBudget,
        probeSeed = problem.probeSeed,
    )
}
