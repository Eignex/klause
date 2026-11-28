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
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.LpZeroObjectivePricing
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.PreparedFactorProjections
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.LpHarvestReport
import com.eignex.klause.solver.result.LpRoute
import com.eignex.klause.solver.result.LpStats
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.solver.validation.readingSourceSlack
import com.eignex.klause.util.ExecutionControl

/**
 * Fold the LP relaxation's proven reductions into [problem] permanently, sharing one root-LP build:
 *  - a certified-infeasible root relaxation turns [problem] into a proven contradiction;
 *  - [LpEngine.shaveVariableBounds] proves integer bounds tighter than declared;
 *  - [LpEngine.shaveObjectiveLb] proves a lower bound on a single ascending objective variable;
 *  - [LpEngine.redundantConstraints] are dropped and [LpEngine.impliedEqualities] are appended.
 *
 * As a `Problem -> Problem` transform, the tightened domains reach every backend and later presolve passes.
 * Every shaved bound is backed by an infeasibility proof, so only values no solution can take are removed.
 *
 * Reduced-cost fixing is not harvested: it is valid only relative to an incumbent, and a transform has no
 * channel to retain that incumbent, so folding it in could discard the optimum itself.
 *
 * Probes beyond root infeasibility need variable or objective shaving enabled and a relaxation within
 * `LP_HARVEST_MAX_RELAXATION_COST`; otherwise [problem] is returned unchanged.
 */
fun lpHarvest(
    problem: BakedProblem,
    objective: LinearObjective,
    plan: LpPlan,
    bakeConfig: BakeConfig = BakeConfig.NONE,
    cancellation: ExecutionControl = ExecutionControl.Never,
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

/**
 * Whether the root LP relaxation is Farkas-certifiably infeasible over [problem]'s declared domains, built
 * without the O(domain span) bake fixpoint, so a wide infeasible model can skip the root bake.
 */
fun lpRootInfeasible(
    problem: Problem,
    objective: LinearObjective,
    plan: LpPlan,
    cancellation: ExecutionControl = ExecutionControl.Never,
    zeroObjectivePricing: LpZeroObjectivePricing = LpZeroObjectivePricing.MIN_BOUND_SUPPORT,
    randomSeed: Long? = null,
): Boolean = lpRootInfeasibleReporting(
    problem,
    objective,
    plan,
    cancellation,
    zeroObjectivePricing,
    randomSeed,
).infeasible

internal class LpRootInfeasibleResult(val infeasible: Boolean, val stats: LpStats)

internal fun lpRootInfeasibleReporting(
    problem: Problem,
    objective: LinearObjective,
    plan: LpPlan,
    cancellation: ExecutionControl = ExecutionControl.Never,
    zeroObjectivePricing: LpZeroObjectivePricing = LpZeroObjectivePricing.MIN_BOUND_SUPPORT,
    randomSeed: Long? = null,
    projections: PreparedFactorProjections? = (problem as? BakedProblem)?.factorProjections,
    numericalLp: Boolean = false,
): LpRootInfeasibleResult {
    val sink = SolveStatsSink(backend = "lp-root-feasibility", lpProbeRoute = LpRoute.ROOT)
    val infeasible = LpEngine(
        problem,
        objective,
        LpParams(
            lpPlan = plan,
            cancellation = cancellation,
            zeroObjectivePricing = zeroObjectivePricing,
            randomSeed = randomSeed,
        ),
        sink,
        LpSolveContext.finite(numericalLp).readingSourceSlack(problem),
        projections = projections,
    ).rootLpInfeasibleNoBake(cancellation)
    return LpRootInfeasibleResult(infeasible, sink.snapshot().lp)
}

/**
 * [problem] with integer domains tightened by the no-bake root-LP OBBT ([LpEngine.rootLpBoundsNoBake]), so
 * the O(span) root bake of a wide model starts from collapsed domains. Solution-set preserving; returns
 * [problem] unchanged when the LP tightens nothing.
 */
fun lpRootBounds(
    problem: Problem,
    objective: LinearObjective,
    plan: LpPlan,
    cancellation: ExecutionControl = ExecutionControl.Never,
    zeroObjectivePricing: LpZeroObjectivePricing = LpZeroObjectivePricing.MIN_BOUND_SUPPORT,
    randomSeed: Long? = null,
): Problem = lpRootBoundsReporting(
    problem,
    objective,
    plan,
    cancellation,
    zeroObjectivePricing,
    randomSeed,
).problem

internal class LpRootBoundsResult(val problem: Problem, val stats: LpStats)

internal fun lpRootBoundsReporting(
    problem: Problem,
    objective: LinearObjective,
    plan: LpPlan,
    cancellation: ExecutionControl = ExecutionControl.Never,
    zeroObjectivePricing: LpZeroObjectivePricing = LpZeroObjectivePricing.MIN_BOUND_SUPPORT,
    randomSeed: Long? = null,
    minimumSpan: Long = 0L,
    projections: PreparedFactorProjections? = (problem as? BakedProblem)?.factorProjections,
    numericalLp: Boolean = false,
): LpRootBoundsResult {
    val sink = SolveStatsSink(backend = "lp-obbt", lpProbeRoute = LpRoute.ROOT)
    val engine = LpEngine(
        problem,
        objective,
        LpParams(
            lpPlan = plan,
            cancellation = cancellation,
            zeroObjectivePricing = zeroObjectivePricing,
            randomSeed = randomSeed,
        ),
        sink,
        LpSolveContext.finite(numericalLp).readingSourceSlack(problem),
        projections = projections,
    )
    val shaved = engine.rootLpBoundsNoBake(cancellation, minimumSpan)
    if (shaved.isEmpty()) return LpRootBoundsResult(problem, sink.snapshot().lp)
    val domains = problem.finiteIntDomains()
    for (sb in shaved) domains[sb.varId] = domains[sb.varId].withMinAtLeast(sb.lo).withMaxAtMost(sb.hi)
    return LpRootBoundsResult(
        Problem(
            settings = problem.settings,
            semantics = problem.semantics,
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

/** [lpHarvest] with a breakdown of the harvest's own effect, isolated from the surrounding passes. */
fun lpHarvestReporting(
    problem: BakedProblem,
    objective: LinearObjective,
    plan: LpPlan,
    bakeConfig: BakeConfig = BakeConfig.NONE,
    cancellation: ExecutionControl = ExecutionControl.Never,
    zeroObjectivePricing: LpZeroObjectivePricing = LpZeroObjectivePricing.MIN_BOUND_SUPPORT,
    randomSeed: Long? = null,
    numericalLp: Boolean = false,
): LpHarvestResult {
    // The token must reach the simplex itself, not just the probe loops: one phase-1 on a large relaxation
    // runs far past the presolve budget, and the engine polls only the cancellation `LpParams` hands it.
    val sink = SolveStatsSink(backend = "lp-harvest", lpProbeRoute = LpRoute.ROOT)
    val engine = LpEngine(
        problem,
        objective,
        LpParams(
            lpPlan = plan,
            cancellation = cancellation,
            zeroObjectivePricing = zeroObjectivePricing,
            randomSeed = randomSeed,
        ),
        sink,
        LpSolveContext.finite(numericalLp).readingSourceSlack(problem),
        eagerSourcePreparation = true,
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

    // Each probe re-solves the relaxation, so on a large one the per-candidate cost would eat the search's
    // time budget.
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
    // Both keep the variable space, so no reconstruction is needed here.
    val factors = if (redundant.isEmpty() && equalities.isEmpty()) {
        problem.factors
    } else {
        (problem.factors.filterIndexed { idx, _ -> idx !in redundant } + equalities).toTypedArray()
    }
    val transformed = RootBaker.reseed(
        BakedProblem(
            settings = problem.settings,
            semantics = problem.semantics,
            numBoolVars = problem.numBoolVars,
            numIntVars = problem.numIntVars,
            intDomains = domains,
            factors = factors,
            cancellation = cancellation,
            // The LP-only continuous columns are a namespace the harvest never touches; carrying them
            // through is what keeps the leaf's exact verdict on them after a shave.
            numRealVars = problem.numRealVars,
            realLower = problem.realLower,
            realUpper = problem.realUpper,
            // See PresolveShared.rebuildProblem: the open-side marks address a namespace presolve keeps.
            packedOpenIntLo = problem.intBounds.openLowerBits,
            packedOpenIntHi = problem.intBounds.openUpperBits,
            projectionProviders = problem.projectionProviders,
            projectionSelection = problem.factorProjections?.let { captured ->
                { view -> captured.forEngineView(view) }
            },
        ),
        bakeConfig,
    )
    return LpHarvestResult(transformed, report, sink.snapshot().lp)
}
