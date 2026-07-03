package com.eignex.klause.backtrack.lp
import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver

/**
 * The resolved per-technique LP-relaxation plan for a [BacktrackSolver] call. Grouped off
 * [BacktrackParams] so the params' LP surface is just the intent ([BacktrackParams.lpConfig]) plus
 * this plan. [LpAutoConfig.resolve] OR-es the structurally-applicable techniques onto a base plan;
 * a caller can also set fields here directly to force a technique on regardless of structure.
 */
data class LpPlan(
    /**
     * Native LP-relaxation bounding for branch-and-bound minimisation (#20). When true, scheduled
     * nodes solve an exact integer LP relaxation of the live problem and prune when the relaxation
     * is infeasible or its objective bound, rounded up to the next integer, is `≥` the incumbent.
     * This strictly dominates the cheap per-term lower bound (which still runs first as a fast
     * pre-filter) but costs an LP solve per scheduled node. Disabled by default; a no-op for
     * problems with no LP-emittable factors (the relaxation is then empty and never prunes).
     */
    val bounding: Boolean = false,
    /**
     * Frequency policy for [bounding]: solve the LP at one in every [boundEvery] pruning checks
     * rather than at every node, since the LP solve dominates a node's cost. `1` solves at every
     * checked node; larger values trade pruning power for throughput. Must be positive.
     */
    val boundEvery: Int = 1,
    /**
     * Depth policy for [bounding]: skip the LP solve below this decision depth. The relaxation is
     * tightest and most valuable near the root where bounds are loose; deep nodes are nearly fixed
     * and rarely repay the solve. `Int.MAX_VALUE` (default) applies LP bounding at every depth.
     */
    val boundMaxDepth: Int = Int.MAX_VALUE,
    /**
     * Warm-start each node's LP solve from a recent node's basis instead of re-solving cold. Branch
     * decisions only tighten bounds, which leaves a parent basis dual-feasible, so the child
     * re-optimises in a handful of dual pivots. The constraint matrix is identical across nodes
     * (only bounds change), so the basis transfers directly. Sound either way — a stale or singular
     * basis just falls back to a cold solve; this only changes pivot count, never the result.
     * Enabled by default when [bounding] is on.
     */
    val warmStart: Boolean = true,
    /**
     * LP-guided value ordering (#246): when the per-node LP has solved (requires [bounding]), order
     * each branch variable's candidate values by closeness to its fractional LP value
     * (round-toward-LP diving). Pure search-order guidance — it changes which solutions are found
     * first, never the optimum or feasibility — so it is correctness-neutral. Off by default; a no-op
     * for variables with no current LP value.
     */
    val branching: Boolean = false,
    /**
     * LP-rounding primal heuristic (#287): before search, solve the root LP and try to round its
     * fractional point into a feasible assignment by pinning each variable toward its LP value and
     * propagating. A complete conflict-free pass is a feasible incumbent (propagation enforces every
     * factor, so the result is sound by construction); it seeds the branch-and-bound bound so pruning
     * and reduced-cost fixing bite from the first node. A conflict on the single pass abandons the
     * probe (no backtracking). Requires a LinearObjective; off by default; never changes the optimum.
     */
    val probe: Boolean = false,
    /**
     * Cut generation (#22): at a scheduled node, after the LP solve, run separators that add valid
     * linear cuts the fractional LP point violates (AllDifferent Hall-set cuts, Gomory integrality
     * cuts), re-solving to tighten the bound. Requires [bounding]; off by default.
     */
    val cuts: Boolean = false,
    /**
     * During-search cut separation depth (#41): when [cuts] holds, the structural separators also run
     * at search nodes whose decision level is at most this, not only at the root. Each such node, after
     * its LP solve, separates the violated cuts from its LP point and re-solves for a tighter bound;
     * globally-valid cuts join the persistent pool so descendants inherit them, while node-local cuts
     * tighten only that node's solve (they never leak to siblings, so the bound stays sound). The depth
     * gate keeps the effort root-dominant — the relaxation is loosest and the cuts most valuable near
     * the root. `0` runs the root harvest only (the prior behaviour). Requires [cuts].
     */
    val cutSearchMaxDepth: Int = 16,
    /**
     * Include Gomory integrality cuts among the [cuts] separators. These come from the simplex
     * tableau and strengthen any fractional LP regardless of problem structure; the exact integer
     * tableau makes them numerically clean. On by default when [cuts] is set.
     */
    val gomory: Boolean = true,
    /**
     * Include Gomory mixed-integer (MIR) cuts among the [cuts] separators. Derived from the same
     * tableau rows as [gomory] but with the stronger mixed-integer rounding multiplier, so they
     * dominate the pure-integer cut on rows with fractional nonbasic coefficients. Like [gomory]
     * they need no problem structure and stay exact in the integer tableau. On by default when
     * [cuts] is set.
     */
    val mir: Boolean = true,
    /**
     * Genuine subtour-elimination cuts for Circuit globals (#22). When true and [bounding] holds,
     * each Circuit gets an arc-indicator relaxation (degree + channelling rows) and a max-flow
     * separator adds the directed cutset inequalities that fractional/subtour LP points violate.
     * Adds O(n²) columns per circuit (skipped above a node-count cap), so it is opt-in and off by
     * default; a no-op when no Circuit exists.
     */
    val circuit: Boolean = false,
    /**
     * Linearize constant-array Element globals with a one-hot selector model (#22). When true and
     * [bounding] holds, each Element over a constant table gets selector columns and channelling
     * rows — its exact convex hull `result = Σ arr[p]·[idx=p]` — so the LP sees `result`'s dependence
     * on `idx`. Adds O(len) columns per Element (skipped above a length cap), so it is opt-in and off
     * by default; a no-op when no constant-array Element exists. Variable-array Element is deferred.
     */
    val element: Boolean = false,
    /**
     * Linearize Table globals with their convex hull (#22). When true and [bounding] holds, each
     * Table gets one selector column per allowed tuple with `Σ y_t = 1` and per-column channels
     * `xs[j] = Σ tuple_t[j]·y_t`, so the LP sees the exact convex hull of the allowed tuples. Adds
     * O(numTuples) columns per Table (skipped above a tuple-count cap), so it is opt-in and off by
     * default; a no-op when no Table exists.
     */
    val table: Boolean = false,
    /**
     * One-hot NValue value hull (#435). When true and [bounding] holds, each NValue contributes a
     * per-value "used"-indicator model so the distinct-count target gets an LP bound (a real lower
     * bound under minimisation). Sound by construction; off by default; a no-op when no NValue exists.
     */
    val nValue: Boolean = false,
    /**
     * Regular DFA flow hull (#655). When true and [bounding] holds, each Regular contributes the
     * layer-expanded automaton flow model (arc vars + flow-conservation + channel rows) — the exact
     * convex hull of its accepting strings, so an objective over the sequence gets a tight LP bound.
     * Sound by construction; off by default; a no-op when no Regular exists or the unfolding exceeds
     * the arc cap.
     */
    val regular: Boolean = false,
    /**
     * Mdd layered flow hull (#655). When true and [bounding] holds, each Mdd contributes the layered
     * flow model (arc vars + flow-conservation + value channel + cost channel) — the exact convex hull
     * of its accepting paths, so an objective over the sequence (or a cost-MDD's cost var) gets a tight
     * LP bound. Sound by construction; off by default; a no-op when no Mdd exists or the unfolding
     * exceeds the arc cap.
     */
    val mdd: Boolean = false,
    /**
     * Count-variable GlobalCardinality count hull (#655). When true and [bounding] holds, each
     * count-variable GCC contributes a one-hot selector model (`Σ_v z_iv = 1`, channel
     * `Σ_v v·z_iv = xs(i)`, and `Σ_i z_{i,cover(k)} = counts(k)` per cover value) so a count variable
     * in the objective gets an exact LP bound. Sound by construction; off by default; a no-op when no
     * count-variable GCC exists or the selector count exceeds the cell cap.
     */
    val gccCount: Boolean = false,
    /**
     * Objective-cone / precedence-only sub-relaxation (#571). When true and [bounding] holds, the
     * per-node LP is built over **only** the variables and rows transitively connected to the
     * objective, with every big-M [com.eignex.klause.factor.arithmetic.ReifiedLinear] disjunctive row
     * dropped — for scheduling, the critical-path / longest-path bound (precedence + objective). The
     * point is that this subset has no disjunctive ordering bools, so it always fits the relaxation-size
     * cap where the full relaxation does not; it is a cheaper, looser, always-sound bound (any subset
     * of constraints is a relaxation). Forces the hull / circuit / cut / cumulative LP features off.
     * Off by default.
     */
    val objectiveCone: Boolean = false,
    /**
     * Whether the adaptive LP auto-off (#614) may **re-probe** a disabled per-node LP on exponential
     * backoff to recover subtrees where the relaxation becomes useful again. `true` is the adaptive
     * default; `false` makes a disable irreversible — the static-one-shot behaviour of #562, kept as a
     * toggle for measuring the re-probe's value. No effect when [bounding] is off.
     */
    val autoOffReprobe: Boolean = true,
    /**
     * Wall-clock budget for the one-shot pre-search root LP work (#31): the cut harvest, the root
     * relaxation-bound capture, and the LP-rounding [probe] all share one deadline so a pathological
     * root relaxation cannot consume the whole time budget before branch-and-bound gets its first node
     * (the liner-sf failure mode). The budget is [rootBudgetFraction] of the time remaining when search
     * starts — so search keeps the rest of every slice — capped at [rootBudgetMillis]. Each root step is
     * cooperatively cancelled at the budget and degrades gracefully (a half-harvested cut pool, a `NaN`
     * root bound, no probe seed), never unsoundly. `0.0` disables the cap (unbudgeted, the prior behaviour).
     */
    val rootBudgetFraction: Double = 0.5,
    /** Absolute ceiling in milliseconds on the [rootBudgetFraction] pre-search root-LP budget; also the
     *  sole cap when the time remaining is unknown (the non-pausable one-shot path). */
    val rootBudgetMillis: Long = 30_000,
    /**
     * Energetic makespan lower-bound row for the scheduling globals (#430). When true and
     * [bounding] holds, each Cumulative / Disjunctive whose makespan variable `M` can be verified
     * (`M ≥ startᵢ + durᵢ` from the actual linear / array-max links) contributes one row
     * `capacity·M ≥ cap·t1 + Σ energy-after(t1)` — the energetic / area objective bound the
     * start-variable LP otherwise lacks for scheduling. Sound by construction (a missing row only
     * loosens; the makespan link is never guessed); off by default; a no-op without a verifiable
     * scheduling makespan. See [com.eignex.klause.lp.relaxation.CumulativeRelaxation].
     */
    val cumulative: Boolean = false,
    /**
     * Diffn per-axis cumulative makespan bound (#655). When true and [bounding] holds, each
     * constant-size Diffn is projected onto both axes as a cumulative (capacity = the maximum
     * perpendicular extent) and contributes the same energetic makespan row as [cumulative] — a
     * sound lower bound on a strip-length / extent variable (its `t1 = min-est` case is the area bound
     * `Σ wᵢ·hᵢ ≤ W·H`). Sound by construction; off by default; a no-op unless an axis extent is a
     * verifiable upper bound on every task end. See [com.eignex.klause.lp.relaxation.CumulativeRelaxation].
     */
    val diffn: Boolean = false,
    /**
     * Time-indexed LP reformulation of the scheduling globals (#453). When true and [bounding]
     * holds, each Cumulative / Disjunctive over a bounded horizon gets binary `x_{i,t}` start
     * columns with assignment, start-channel and per-time-point resource rows — the resource–time
     * coupling the start-variable LP lacks, so the LP bound is far tighter than the energetic row
     * ([cumulative]) alone. Adds O(n·H) columns, hard-gated on the horizon, so it is opt-in and off
     * by default; a no-op without a bounded-horizon scheduling global.
     */
    val cumulativeTimeIndexed: Boolean = false,
    /**
     * Preemptive min-cost-flow feasibility / makespan bound for the scheduling globals (#454). When
     * true, a node is pruned if the tasks' energy cannot be preemptively packed into their release /
     * deadline windows at capacity (an exact max-flow feasibility test, strictly stronger than the
     * pairwise-window [energeticReasoning] scan and horizon-independent — it keys off
     * the O(n) start-bound breakpoints, not the time axis), and the verified makespan variable is
     * lower-bounded by the smallest feasible completion time. Pure relaxation; off by default; a no-op
     * without a scheduling global. See [com.eignex.klause.lp.bound.CumulativeFlowBound].
     */
    val cumulativeFlow: Boolean = false,
    /** Frequency policy for [cumulativeFlow]: run the max-flow check at one in every this-many
     *  pruning checks, mirroring [energeticEvery]. Must be positive. */
    val cumulativeFlowEvery: Int = 1,
    /**
     * 0/1 multi-knapsack subgradient Lagrangian bounding (#632). When true and the objective is a
     * [com.eignex.klause.solver.objective.LinearObjective], a node also computes a Lagrangian bound for
     * problems with several `PseudoBoolean` capacity rows: one capacity row is kept and solved **exactly**
     * by 0/1-knapsack DP while the rest are dualized, so the bound captures integrality the monolithic LP
     * relaxes away. Shares [lagrangianIterations]. Independent of [bounding]; off by
     * default; a no-op unless a clean capacity `PseudoBoolean` (positive literals/weights, `≤`) is present.
     */
    val knapsackLagrangian: Boolean = false,
    /**
     * Subgradient Lagrangian bounding for structured globals (#23). When true and the objective is a
     * [com.eignex.klause.solver.objective.LinearObjective], a node also computes a Lagrangian bound from an
     * AllDifferent global (its variables solved exactly as a min-cost assignment, with the linear
     * constraints over them dualized) and prunes when that bound — rounded up — reaches the incumbent.
     * Independent of [bounding]; off by default; a no-op when no eligible AllDifferent exists.
     */
    val lagrangian: Boolean = false,
    /** Subgradient ascent iterations per node for [lagrangian] / [knapsackLagrangian]; more
     *  iterations tighten the bound. */
    val lagrangianIterations: Int = 15,
    /**
     * Energetic-reasoning infeasibility check for Cumulative globals (#22/#23). When true, a node is
     * pruned if some Cumulative is energetically over-subscribed (required mandatory energy in a time
     * window exceeds capacity·width). Pure feasibility test; off by default; a no-op without a
     * Cumulative. Currently applied on the minimization path alongside the other LP bounds.
     */
    val energeticReasoning: Boolean = false,
    /**
     * Frequency policy for [energeticReasoning]: run the window scan at one in every
     * [energeticEvery] pruning checks, mirroring [boundEvery]. The scan is O(windows² · tasks)
     * per Cumulative with no incremental state, so on task-heavy models it dominates a node's
     * cost; a cadence trades missed prunes for throughput. `1` (default) checks at every pruned
     * node. [LpAutoConfig] derives a size-aware cadence from the task counts when *it* enables
     * the check. Must be positive.
     */
    val energeticEvery: Int = 1,
    /**
     * Learn a clause from an infeasible node (#247). When true, an infeasibility proof is turned into
     * a nogood over absolute variable-bound atoms — a globally valid clause implied by the original
     * constraints — and registered at the next restart (where its literals are no longer all-false),
     * so the dead region is pruned in sibling subtrees. Two sources: the node LP's Farkas
     * infeasibility certificate (requires [bounding]) and the energetic over-subscription window
     * (requires [energeticReasoning]). When the certificate resolves to an asserting
     * 1UIP clause the engine backjumps and learns immediately (#280), so this now helps even with
     * restarts off; non-asserting certificates still fall back to restart-time registration. A
     * certificate leaning on a live-big-M reified row cites the bounds that justify the M alongside its
     * column seats; one leaning on a node-local cut is not expressible as a globally valid bound-atom
     * clause and is withheld — the prune itself still happens. Off by default.
     */
    val learn: Boolean = false,
    /**
     * Per-hull pruning: before search, drop each convex-hull technique that adds no strength to the root
     * relaxation bound (solve the root LP with the hull off; keep it only if its removal loosens the
     * bound). The build-time counterpart of the adaptive effort ladder — it sheds a single unhelpful hull
     * while the rest keep running, so a hull that costs per-node build effort without tightening is paid
     * for once at the root rather than at every node. Sound (a hull is a sound relaxation either way, and
     * one is dropped only when the root bound is unchanged). Requires [bounding]; off by default.
     */
    val pruneHulls: Boolean = false,
    /**
     * Objective shaving: before search, probe whether the objective can be proven to exceed its
     * current lower bound — assume `objVar ≤ v` and, if the LP relaxation + propagation prove that
     * infeasible, raise the root lower bound to `v + 1`. Sound (every raise is a proof that lower values
     * are infeasible). Requires [bounding] and a single ascending objective variable. Off by default.
     */
    val objectiveShaving: Boolean = false,
    /**
     * Variable shaving: before search, probe each integer variable's domain bounds — assume
     * `v ≤ lo` (resp. `v ≥ hi`) and, if the LP relaxation + propagation prove that infeasible, tighten
     * the bound inward. Sound (every tightening is a proof that the shaved-off values are infeasible).
     * Requires [bounding]; costs probes per variable, so off by default.
     */
    val variableShaving: Boolean = false,
    /**
     * Best-bound tree-search primal subsolver: before search, explore the branch-and-bound tree
     * best-first (lowest LP bound first) to dive for good incumbents fast. Pure heuristic — returns only
     * propagation-feasible incumbents (re-checked), so it never changes the optimum. Requires [bounding];
     * off by default.
     */
    val lbTreeSearch: Boolean = false,
    /**
     * Add the Anderson big-M tight face of each [com.eignex.klause.factor.arithmetic.ArrayMinMax]
     * on top of the envelope, bounding the extremum from the tight side. Sound relaxation; gated.
     * Off by default.
     */
    val linMaxTightFace: Boolean = false,
    /**
     * Relax each [com.eignex.klause.factor.arithmetic.Product] `result = a·b` with its McCormick
     * envelope (the square case degenerates to secant/tangent). Sound relaxation; gated. Off by default.
     */
    val productMcCormick: Boolean = false,
    /**
     * Separate implied-bound cuts from the probing implication graph: `litVal(A) ≤ litVal(B)` for
     * each violated probing implication not already an explicit binary clause. Sound (valid at every
     * solution); requires [cuts]. Off by default.
     */
    val impliedBoundCuts: Boolean = false,
    /**
     * Separate single-node flow-cover cuts: detect a capacity row `Σ yⱼ ≤ b` whose flows
     * carry variable-upper-bounds `yⱼ ≤ uⱼ·xⱼ` (`xⱼ ∈ {0,1}`) and add the violated Padberg–Van Roy–Wolsey
     * flow-cover inequality. Sound (valid at every integer solution); requires [cuts]. Off by default.
     */
    val flowCoverCuts: Boolean = false,
    /**
     * Boolean RLT relaxation: multiply each small 0/1 knapsack row by its binaries and linearize
     * the products `xₖ·xᵢ` with their McCormick envelope (product columns + rows). Sound — the
     * relaxation excludes no integer solution; adds capped product columns, so it is gated. Requires
     * [bounding]; off by default.
     */
    val booleanRlt: Boolean = false,
)
