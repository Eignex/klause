package com.eignex.klause.lp.bounding

import com.eignex.klause.lp.relaxation.LpSourcePreparation

/**
 * The resolved per-technique LP-relaxation plan for a backtracking solve, carried as [LpParams.lpPlan]
 * beside the intent [LpParams.lpConfig]. [LpAutoConfig.resolve] OR-es the structurally-applicable
 * techniques onto a base plan; a caller can also set fields here directly to force a technique on
 * regardless of structure.
 */
data class LpPlan(
    /**
     * Native LP-relaxation bounding for branch-and-bound minimisation. Scheduled nodes solve an exact
     * LP relaxation of the live problem and prune when it is infeasible or its bound, rounded up, is `≥`
     * the incumbent. The cheap per-term lower bound still runs first as a pre-filter.
     */
    val bounding: Boolean = false,
    /**
     * Solve the [bounding] LP at one in every [boundEvery] pruning checks; `1` solves at every checked
     * node. Must be positive.
     */
    val boundEvery: Int = 1,
    /**
     * Skip the [bounding] LP below this decision depth; deep nodes are nearly fixed and rarely repay the
     * solve. `Int.MAX_VALUE` applies it at every depth.
     */
    val boundMaxDepth: Int = Int.MAX_VALUE,
    /**
     * Pivot budget for [bounding]'s dual solve: a node LP that reaches this many pivots returns its
     * current iterate. `0` leaves only the size-derived budget.
     *
     * The dual simplex is dual-feasible at every basis, so a stopped solve still prunes on a valid, looser
     * bound; it cannot claim an optimum, so reduced-cost fixing and tableau cuts stand down on it. The
     * budget applies only after the effort ladder's warmup window passes without a prune, and any prune
     * lifts it permanently ([LpPivotBudget]). Off by default because a prune-based gate cannot tell a
     * model that never finds an incumbent from one whose LP has not started pruning yet.
     */
    val boundMaxPivots: Int = 0,
    /**
     * Adaptive node LP work budget ([LpWorkBudget]): stopping short while progressing raises the next
     * budget, stalling among tied columns lowers it, and a clean optimum resets it to the size-derived
     * value. It budgets work rather than pivots because a pivot costs far more on a dense basis.
     */
    val boundAdaptiveWork: Boolean = true,
    /**
     * Deterministic ceiling on node LP work per explored node, in [com.eignex.klause.lp.engine.LpWork]
     * operations ([LpEffortGovernor]). Exceeding it without a prune demotes the LP to its floor budget;
     * a prune restores it and restarts its evidence window. `0` disables the rule. A work ratio keeps
     * the demotion point reproducible across machines.
     * Ordinary optional LP queries earn this much credit per observed node, with bounded warmup credit.
     * Full query cost spends the credit, and a prune cannot forgive the resulting debt.
     */
    val boundMaxOpsPerNode: Long = 20_000L,
    /**
     * [com.eignex.klause.lp.engine.LpWork] operations the one-shot pre-search root LP may spend on cut
     * harvesting, the root bound and primal probes, or `0` to bound it by the clock alone. Work rather than
     * time, because the root harvest decides which cuts every later node inherits.
     * [rootBudgetFraction] remains the wall-clock backstop.
     */
    val rootMaxWork: Long = 200_000_000L,
    /**
     * Warm-start each node's LP from a recent node's basis. Branching only tightens bounds, so a parent
     * basis stays dual-feasible; a stale or singular basis falls back to a cold solve.
     */
    val warmStart: Boolean = true,
    /**
     * LP-guided branching: steer each branch variable by its fresh fractional LP value, splitting an int
     * var at `x ≤ floor(lp)` and diving an integral value round-toward-LP. Changes search order only.
     * Needs [bounding] for an LP value.
     */
    val branching: Boolean = true,
    /**
     * LP-rounding primal heuristic: before search, round the root LP point into a feasible assignment by
     * pinning each discrete variable toward its LP value and propagating, falling back to peer-target and
     * directed rounding and bounded feasibility pumping under the same root allowance. Publication requires
     * the composed witness check, source-accepted or certified continuous coordinates, and improvement over
     * the incumbent cutoff. Requires a LinearObjective.
     */
    val probe: Boolean = false,
    /**
     * Cut generation: after a scheduled node's LP solve, run separators that add cuts the LP point
     * violates and re-solve. Requires [bounding].
     */
    val cuts: Boolean = false,
    /**
     * When [cuts] holds, also run the structural separators at search nodes up to this decision level.
     * Globally valid cuts join the persistent pool; node-local cuts tighten only that node's solve. `0`
     * runs the root harvest only.
     */
    val cutSearchMaxDepth: Int = 16,
    /** Include Gomory integrality cuts among the [cuts] separators; the exact integer tableau keeps them clean. */
    val gomory: Boolean = true,
    /**
     * Include Gomory mixed-integer (MIR) cuts among the [cuts] separators. They use the same tableau rows as
     * [gomory] with the stronger mixed-integer rounding multiplier.
     */
    val mir: Boolean = true,
    /**
     * Subtour-elimination cuts for Circuit globals: an arc-indicator relaxation per Circuit plus a max-flow
     * separator for the violated directed cutset inequalities. Adds one column per candidate arc, skipped
     * above an arc-count cap. Requires [bounding].
     */
    val circuit: Boolean = false,
    /**
     * One-hot selector hull `result = Σ arr(p)·[idx=p]` for constant-array Element globals. Adds O(len)
     * columns per Element, skipped above a length cap. Requires [bounding].
     */
    val element: Boolean = false,
    /**
     * Convex hull of each Table's allowed tuples: one selector column per tuple with `Σ y_t = 1` and
     * channels `xs(j) = Σ tuple_t(j)·y_t`. Skipped above a tuple-count cap. Requires [bounding].
     */
    val table: Boolean = false,
    /** One-hot value hull per NValue, giving the distinct-count target an LP bound. Requires [bounding]. */
    val nValue: Boolean = false,
    /**
     * Layer-expanded DFA flow hull per Regular, the exact convex hull of its accepting strings. Skipped when
     * the unfolding exceeds the arc cap. Requires [bounding].
     */
    val regular: Boolean = false,
    /**
     * Layered flow hull per Mdd with value and cost channels, the exact convex hull of its accepting paths.
     * Skipped when the unfolding exceeds the arc cap. Requires [bounding].
     */
    val mdd: Boolean = false,
    /**
     * One-hot count hull per count-variable GlobalCardinality (`Σ_v z_iv = 1`, `Σ_v v·z_iv = xs(i)`,
     * `Σ_i z_{i,cover(k)} = counts(k)`). Skipped above the cell cap. Requires [bounding].
     */
    val gccCount: Boolean = false,
    /**
     * Objective-cone sub-relaxation: build the per-node LP over only the variables and rows connected to
     * the objective, dropping every big-M [com.eignex.klause.factor.arithmetic.ReifiedLinear] row. For
     * scheduling this is the precedence bound; it fits the relaxation-size cap where the full relaxation
     * does not. Forces the hull, circuit, cut and cumulative features off. Requires [bounding].
     */
    val objectiveCone: Boolean = false,
    /**
     * Build the per-node relaxation over only the factors touching LP-only real columns. Propagation
     * already enforces the Boolean clause rows, so dropping them loses no pruning at an infinite incumbent.
     * Requires [bounding].
     */
    val realResidual: Boolean = false,
    /** Whether the adaptive LP auto-off may re-probe a disabled per-node LP on exponential backoff. */
    val autoOffReprobe: Boolean = true,
    /**
     * Wall-clock share of the time remaining at search start for the pre-search root LP work (cut harvest,
     * root bound, [probe]), capped at [rootBudgetMillis]. Each root step stops cooperatively at the deadline
     * and degrades soundly. `0.0` disables the cap.
     */
    val rootBudgetFraction: Double = 0.5,
    /** Absolute ceiling in milliseconds on the [rootBudgetFraction] budget; the sole cap when the time
     *  remaining is unknown. */
    val rootBudgetMillis: Long = 30_000,
    /**
     * Share of the total solve budget that optional root and node LP work may spend, converted at a fixed
     * rate to a cumulative [com.eignex.klause.lp.engine.LpWork] allowance ([LpEffortGovernor]) so it never
     * depends on the clock. Once spent, optional node LP stops. `0.0` disables it; no effect when the total
     * budget is unknown.
     */
    val lpWorkBudgetFraction: Double = 0.25,
    /**
     * Energetic makespan row `capacity·M ≥ cap·t1 + Σ energy-after(t1)` for each Cumulative / Disjunctive
     * whose makespan variable `M` is verified from the linear or array-max links. Requires [bounding]. See
     * [com.eignex.klause.lp.relaxation.CumulativeRelaxation].
     */
    val cumulative: Boolean = false,
    /**
     * Projects each constant-size Diffn onto both axes as a cumulative and adds the [cumulative] makespan
     * row. Applies only when an axis extent verifiably bounds every task end. Requires [bounding]. See
     * [com.eignex.klause.lp.relaxation.CumulativeRelaxation].
     */
    val diffn: Boolean = false,
    /**
     * Time-indexed reformulation of each Cumulative / Disjunctive over a bounded horizon: binary `x_{i,t}`
     * start columns with assignment, start-channel and per-time-point resource rows. Adds O(n·H) columns,
     * gated on the horizon. Requires [bounding].
     */
    val cumulativeTimeIndexed: Boolean = false,
    /**
     * Preemptive max-flow feasibility and makespan bound for the scheduling globals: prunes a node when the
     * tasks' energy cannot be packed preemptively into their windows, and lower-bounds the verified
     * makespan. Keys off the O(n) start-bound breakpoints, so it is horizon-independent. See
     * [com.eignex.klause.bound.CumulativeFlowBound].
     */
    val cumulativeFlow: Boolean = false,
    /** Run the [cumulativeFlow] check at one in every this-many pruning checks. Must be positive. */
    val cumulativeFlowEvery: Int = 1,
    /**
     * 0/1 multi-knapsack Lagrangian bound for a
     * [com.eignex.klause.solver.objective.LinearObjective] with several clean `PseudoBoolean` capacity rows:
     * one row is solved exactly by knapsack DP while the rest are dualized. Shares [lagrangianIterations].
     * Independent of [bounding].
     */
    val knapsackLagrangian: Boolean = false,
    /**
     * Solve a separable LP per column component ([com.eignex.klause.lp.engine.ComponentLpSolver]). The
     * stitched optimum, point and duals match the monolithic solve.
     */
    val componentSplit: Boolean = true,
    /**
     * AllDifferent Lagrangian bound for a [com.eignex.klause.solver.objective.LinearObjective]: the
     * AllDifferent solved as a min-cost assignment with its linear constraints dualized, pruning when the
     * rounded-up bound reaches the incumbent. Independent of [bounding].
     */
    val lagrangian: Boolean = false,
    /** Subgradient ascent iterations per node for [lagrangian] / [knapsackLagrangian]. */
    val lagrangianIterations: Int = 15,
    /**
     * Energetic-reasoning infeasibility check: prune a node when some Cumulative needs more mandatory energy
     * in a window than capacity·width.
     */
    val energeticReasoning: Boolean = false,
    /**
     * Run the [energeticReasoning] window scan at one in every [energeticEvery] pruning checks. The scan is
     * O(windows² · tasks) per Cumulative with no incremental state. [LpAutoConfig] derives a size-aware
     * cadence when it enables the check. Must be positive.
     */
    val energeticEvery: Int = 1,
    /**
     * Retain source-feasibility clauses over absolute variable-bound atoms: Farkas conflict nogoods,
     * mixed-row implications guarded by their row and bound premises, and energetic over-subscription
     * nogoods. Row implications register immediately so sibling subtrees reuse them; an asserting 1UIP
     * certificate backjumps and learns at once, other clauses register at restart. A certificate on a
     * live-big-M row cites the bounds justifying its M; one leaning on a node-local cut is withheld
     * while the prune still happens. LP sources require [bounding]; energetic sources require
     * [energeticReasoning].
     */
    val learn: Boolean = false,
    /**
     * Before search, drop each convex-hull technique whose removal leaves the root relaxation bound
     * unchanged, so an unhelpful hull is paid for once at the root. Requires [bounding].
     */
    val pruneHulls: Boolean = false,
    /**
     * Objective shaving: before search, assume `objVar ≤ v` and raise the root lower bound to `v + 1` when
     * the LP relaxation and propagation refute it. Requires [bounding] and a single ascending objective
     * variable.
     */
    val objectiveShaving: Boolean = false,
    /**
     * Variable shaving: before search, assume `v ≤ lo` (resp. `v ≥ hi`) per integer variable and tighten the
     * bound inward when the LP relaxation and propagation refute it. Requires [bounding].
     */
    val variableShaving: Boolean = false,
    /**
     * LP-guided tree-search primal subsolver over finite discrete domains. Returns checked incumbents
     * only; exhaustion and unbounded leaves supply no terminal proof. Requires [bounding].
     */
    val lbTreeSearch: Boolean = false,
    /**
     * Add the big-M tight face of each [com.eignex.klause.factor.arithmetic.ArrayMinMax] on top of the
     * envelope, bounding the extremum from the tight side (Anderson, Huchette, Ma, Tjandraatmadja and
     * Vielma, "Strong mixed-integer programming formulations for trained neural networks", Math. Program.
     * 2020, https://doi.org/10.1007/s10107-020-01474-5).
     */
    val linMaxTightFace: Boolean = false,
    /**
     * Relax each [com.eignex.klause.factor.arithmetic.Product] `result = a·b` with its McCormick
     * envelope (the square case degenerates to secant/tangent) — McCormick, "Computability of global
     * solutions to factorable nonconvex programs: Part I", Math. Program. 1976,
     * https://doi.org/10.1007/BF01580665.
     */
    val productMcCormick: Boolean = false,
    /**
     * Separate implied-bound cuts `litVal(A) ≤ litVal(B)` from the probing implication graph, for each
     * violated implication not already an explicit binary clause. Requires [cuts].
     */
    val impliedBoundCuts: Boolean = false,
    /**
     * Separate single-node flow-cover cuts: detect a capacity row `Σ yⱼ ≤ b` whose flows
     * carry variable-upper-bounds `yⱼ ≤ uⱼ·xⱼ` (`xⱼ ∈ {0,1}`) and add the violated flow-cover inequality
     * (Padberg, Van Roy and Wolsey, "Valid linear inequalities for fixed charge problems", Oper. Res. 1985,
     * https://doi.org/10.1287/opre.33.4.842). Requires [cuts].
     */
    val flowCoverCuts: Boolean = false,
    /**
     * Boolean RLT relaxation: multiply each small 0/1 knapsack row by its binaries and linearize the
     * products `xₖ·xᵢ` with their McCormick envelope. Adds capped product columns. Requires [bounding].
     */
    val booleanRlt: Boolean = false,
    /** Harvest globally valid cuts before search. Node separation remains independent. */
    val rootCutHarvest: Boolean = true,
    /** Enable component-composed node hints, retained root guidance and private rounding. */
    val componentOwnership: Boolean = false,
    /** Source-template retention and admission, independent of the enabled numerical techniques. */
    val sourcePreparation: LpSourcePreparation = LpSourcePreparation.ADMITTED,
    /** Automatically admit selective numerical child reuse independently of root guidance and rounding. */
    val componentReuse: Boolean = true,
) {
    /** Whether Gomory integrality cuts run: [gomory] permits them and [cuts] admits separators. */
    val gomoryEnabled: Boolean get() = cuts && gomory

    /** Whether MIR cuts run, on the same terms as [gomoryEnabled]. */
    val mirEnabled: Boolean get() = cuts && mir
}

internal fun LpPlan.hasDefaultNumericalSettings(): Boolean =
    this == DefaultNumericalPlans.byPreparation[sourcePreparation.ordinal][if (componentReuse) 1 else 0]

private object DefaultNumericalPlans {
    val byPreparation = LpSourcePreparation.entries.map {
        arrayOf(LpPlan(sourcePreparation = it, componentReuse = false),
            LpPlan(sourcePreparation = it, componentReuse = true))
    }
}
