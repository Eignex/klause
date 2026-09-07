package com.eignex.klause.presolve

import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LP_HARVEST_MAX_RELAXATION_COST
import com.eignex.klause.lp.bounding.LpEngine
import com.eignex.klause.lp.bounding.LpParams
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.lp.bounding.impliedEqualities
import com.eignex.klause.lp.bounding.redundantConstraints
import com.eignex.klause.lp.bounding.rootInfeasible
import com.eignex.klause.lp.bounding.rootLpBoundsNoBake
import com.eignex.klause.lp.bounding.rootLpInfeasibleNoBake
import com.eignex.klause.lp.bounding.rootRelaxationSize
import com.eignex.klause.lp.bounding.shaveObjectiveLb
import com.eignex.klause.lp.bounding.shaveVariableBounds
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.LpHarvestReport
import com.eignex.klause.solver.result.LpRoute
import com.eignex.klause.solver.result.LpStats
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.util.Cancellation

/**
 * Fold the LP relaxation's proven domain tightenings into [problem] permanently — the
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
    problem: BakedProblem,
    objective: LinearObjective,
    plan: LpPlan,
    bakeConfig: BakeConfig = BakeConfig.NONE,
    cancellation: Cancellation = Cancellation.Never,
): Problem = lpHarvestReporting(problem, objective, plan, bakeConfig, cancellation).problem

/** [lpHarvest]'s transformed [problem] paired with its report and LP telemetry. */
class LpHarvestResult(
    /** Problem after the root harvest's sound reductions. */
    val problem: BakedProblem,
    /** Reductions the root harvest contributed. */
    val report: LpHarvestReport,
    /** LP work spent by the root harvest, including a no-op harvest. */
    val stats: LpStats = LpStats(),
)

/** Whether the root LP relaxation is Farkas-certifiably infeasible over [problem]'s declared domains,
 *  built without the [com.eignex.klause.propagation.PropagationSession] bake fixpoint (O(domain span) on
 *  wide integer domains). A true result proves [problem] infeasible over its domains — the pre-bake
 *  short-circuit a presolve pipeline uses to skip the O(span) root bake on a wide, infeasible model. */
fun lpRootInfeasible(
    problem: Problem,
    objective: LinearObjective,
    plan: LpPlan,
    cancellation: Cancellation = Cancellation.Never,
): Boolean = lpRootInfeasibleReporting(problem, objective, plan, cancellation).infeasible

internal class LpRootInfeasibleResult(val infeasible: Boolean, val stats: LpStats)

internal fun lpRootInfeasibleReporting(
    problem: Problem,
    objective: LinearObjective,
    plan: LpPlan,
    cancellation: Cancellation = Cancellation.Never,
): LpRootInfeasibleResult {
    val sink = SolveStatsSink(backend = "lp-root-feasibility", lpProbeRoute = LpRoute.ROOT)
    val infeasible = LpEngine(
        problem,
        objective,
        LpParams(lpPlan = plan, cancellation = cancellation),
        sink,
    ).rootLpInfeasibleNoBake(cancellation)
    return LpRootInfeasibleResult(infeasible, sink.snapshot().lp)
}

/** [problem] with each integer variable's domain tightened by the no-bake root-LP OBBT
 *  ([LpEngine.rootLpBoundsNoBake]): the pre-bake bound-tightening a presolve pipeline runs on a wide model
 *  so the O(span) root bake starts from collapsed domains instead of grinding them down one step per round.
 *  Returns [problem] unchanged when the LP tightens nothing. Solution-set-preserving — only values the LP
 *  proves no integer solution can take are removed. */
fun lpRootBounds(
    problem: Problem,
    objective: LinearObjective,
    plan: LpPlan,
    cancellation: Cancellation = Cancellation.Never,
): Problem = lpRootBoundsReporting(problem, objective, plan, cancellation).problem

internal class LpRootBoundsResult(val problem: Problem, val stats: LpStats)

internal fun lpRootBoundsReporting(
    problem: Problem,
    objective: LinearObjective,
    plan: LpPlan,
    cancellation: Cancellation = Cancellation.Never,
): LpRootBoundsResult {
    val sink = SolveStatsSink(backend = "lp-obbt", lpProbeRoute = LpRoute.ROOT)
    val engine = LpEngine(
        problem,
        objective,
        LpParams(lpPlan = plan, cancellation = cancellation),
        sink,
    )
    val shaved = engine.rootLpBoundsNoBake(cancellation)
    if (shaved.isEmpty()) return LpRootBoundsResult(problem, sink.snapshot().lp)
    val domains = problem.finiteIntDomains()
    for (sb in shaved) domains[sb.varId] = domains[sb.varId].withMinAtLeast(sb.lo).withMaxAtMost(sb.hi)
    return LpRootBoundsResult(
        Problem(
            numBoolVars = problem.numBoolVars,
            numIntVars = problem.numIntVars,
            intDomains = domains,
            factors = problem.factors,
            // See PresolveShared.rebuildProblem: the open-side marks address a namespace presolve keeps.
            packedOpenIntLo = problem.intBounds.openLowerBits,
            packedOpenIntHi = problem.intBounds.openUpperBits,
        ),
        sink.snapshot().lp,
    )
}

/** [lpHarvest] returning, alongside the transformed problem, a breakdown of the LP harvest's own effect
 *  (root infeasibility, bounds shaved, objective floor, constraints removed, equalities added) so a
 *  caller can isolate it from the surrounding combinatorial passes. */
fun lpHarvestReporting(
    problem: BakedProblem,
    objective: LinearObjective,
    plan: LpPlan,
    bakeConfig: BakeConfig = BakeConfig.NONE,
    cancellation: Cancellation = Cancellation.Never,
): LpHarvestResult {
    // The token must reach the simplex itself, not just the probe loops below: one primal phase-1 on a
    // large relaxation runs far past the presolve budget, and the engine polls only the `cancellation`
    // that `LpParams` hands it — every `LpSolver` entry point defaults to `Cancellation.Never`.
    val sink = SolveStatsSink(backend = "lp-harvest", lpProbeRoute = LpRoute.ROOT)
    val engine = LpEngine(
        problem,
        objective,
        LpParams(lpPlan = plan, cancellation = cancellation),
        sink,
    )
    if (engine.lpRelaxer == null) return LpHarvestResult(problem, LpHarvestReport(), sink.snapshot().lp)
    // A certified-infeasible root relaxation proves the whole problem has no solution; fold it in as an
    // explicit contradiction so the problem bakes Unsat and every backend short-circuits.
    if (engine.rootInfeasible(cancellation)) {
        return LpHarvestResult(
            RootBaker.provenInfeasible(problem, bakeConfig),
            LpHarvestReport(rootInfeasible = true),
            sink.snapshot().lp,
        )
    }
    if (!plan.variableShaving && !plan.objectiveShaving) {
        return LpHarvestResult(
            problem,
            LpHarvestReport(),
            sink.snapshot().lp,
        )
    }

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
        return LpHarvestResult(problem, report, sink.snapshot().lp)
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
        return LpHarvestResult(problem, LpHarvestReport(), sink.snapshot().lp)
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

    val domains = problem.rootIntDomains()
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
    val transformed = RootBaker.reseed(
        BakedProblem(
            numBoolVars = problem.numBoolVars,
            numIntVars = problem.numIntVars,
            intDomains = domains,
            factors = factors,
            // The LP-only continuous columns are a namespace the harvest never touches; carrying them
            // through is what keeps the leaf's exact verdict on them after a shave.
            numRealVars = problem.numRealVars,
            realLower = problem.realLower,
            realUpper = problem.realUpper,
            // See PresolveShared.rebuildProblem: the open-side marks address a namespace presolve keeps.
            packedOpenIntLo = problem.intBounds.openLowerBits,
            packedOpenIntHi = problem.intBounds.openUpperBits,
        ),
        bakeConfig,
    )
    return LpHarvestResult(transformed, report, sink.snapshot().lp)
}
