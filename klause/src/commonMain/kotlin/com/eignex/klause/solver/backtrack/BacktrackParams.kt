package com.eignex.klause.solver.backtrack

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.SolverParams
import com.eignex.klause.solver.backtrack.selector.IndomainMax
import com.eignex.klause.solver.backtrack.selector.IndomainMiddle
import com.eignex.klause.solver.backtrack.selector.IndomainMin
import com.eignex.klause.solver.backtrack.selector.IndomainRandom
import com.eignex.klause.solver.backtrack.selector.IndomainSet
import com.eignex.klause.solver.backtrack.selector.InputOrder
import com.eignex.klause.solver.backtrack.selector.RandomVariable
import com.eignex.klause.solver.backtrack.selector.SmallestDomain
import com.eignex.klause.solver.backtrack.selector.ValueSelector
import com.eignex.klause.solver.backtrack.selector.VariableSelector
import com.eignex.klause.solver.propagation.ClauseExchange
import com.eignex.klause.solver.result.SearchEvent

/**
 * Per-call params for [BacktrackSolver].
 *
 *  - [maxDecisions] — abort after this many decisions are pushed (Unknown). `Long.MAX_VALUE`
 *    by default — let the search run to completion.
 *  - [randomSeed] — seeds the engine RNG that's threaded into [variableSelector] and
 *    [valueSelector]. `null` picks a fresh seed per call.
 *  - [assumptions] — variables pinned for the duration of the call.
 *  - [variableSelector] — picks the next variable to branch on. Defaults to
 *    [RandomVariable] for diverse search; CSP-typical alternatives are [SmallestDomain]
 *    (first-fail) and [InputOrder].
 *  - [valueSelector] — picks the order in which to try values of the chosen variable.
 *    Defaults to [IndomainRandom]; alternatives include [IndomainMin] / [IndomainMax] /
 *    [IndomainMiddle] / [IndomainSet] for hole domains.
 *  - [minHammingDistance] / [recentWindow] — opt-in diversity filter for the
 *    [BacktrackSolver.enumerate] path; only set them when you want to *skip* models that
 *    are within `minHammingDistance` of a previously yielded model. The DFS enumerator
 *    never yields the same model twice on its own, so the default `0 / 0` (no filter) is
 *    correct for all standard use. Ignored by `solve` / `samples`.
 */
data class BacktrackParams(
    val maxDecisions: Long = Long.MAX_VALUE,
    /**
     * Wall-clock-independent operation budget across all backends. For [BacktrackSolver]
     * one instruction = one decision attempt (the same unit [maxDecisions] counts). When
     * both are set, the smaller wins. `null` = no instruction-budget cap; use [maxDecisions]
     * alone. Makes the same params object portable across backends without each one needing
     * to know its primary-budget field name (LS sees flips, Brute sees steps, etc.).
     */
    val maxInstructions: Long? = null,
    val randomSeed: Long? = null,
    val assumptions: Assumptions = Assumptions.None,
    val variableSelector: VariableSelector = RandomVariable,
    val valueSelector: ValueSelector = IndomainRandom,
    val minHammingDistance: Int = 0,
    val recentWindow: Int = 0,
    /**
     * Luby restart base. When non-null, the search pops back to root after
     * `lubyN(restartIdx) * lubyRestartBase` decisions in the current run and starts
     * fresh from level 0. Phase-saving (see [phaseSaving]) and the B&B bound (in
     * [com.eignex.klause.solver.Optimizer.minimize]) survive the restart, so each
     * restart resumes with strictly more information than the previous run. Disabled
     * by default — set to a moderate value (e.g. `100` or `200`) on hard instances
     * where DFS gets stuck on bad subtrees.
     */
    val lubyRestartBase: Long? = null,
    /**
     * Glucose-style adaptive restarts (Audemard-Simon) for the pure-Boolean search path.
     * When true, the engine restarts based on learned-clause quality — a short window of
     * recent LBD running hotter than the long-run average forces a re-pick — with trail-size
     * blocking that suppresses the restart when the solver is driving deep toward a model.
     * See [GlucoseRestart]. Selectable *alongside* [lubyRestartBase] rather than replacing it:
     * when adaptive restarts are on the Luby budget is ignored, so SAT-heavy configs opt into
     * data-driven restarts while the CP optimization path keeps Luby. Disabled by default. On
     * larger random instances near the phase transition (see #117) this usually beats Luby.
     */
    val adaptiveRestart: Boolean = false,
    /**
     * Phase-saving: cache the last value the search committed to for each variable.
     * On a fresh descent (after a backtrack or restart) the cached value is tried
     * first, so the search doesn't lose the work spent narrowing down the right
     * polarity each time. Standard CDCL-derived heuristic; combines naturally with
     * [lubyRestartBase]. Disabled by default to keep the search deterministic given a
     * fixed seed when no restarts are in play.
     */
    val phaseSaving: Boolean = false,
    /**
     * Target phasing and rephasing on top of plain [phaseSaving]. When enabled, the engine
     * tracks the deepest conflict-free Boolean assignment seen so far (the "target" phase,
     * the trail prefix at the most-assigned point before a backtrack) and biases fresh
     * Boolean decisions toward it. A rephasing schedule periodically rotates the polarity
     * source — target, saved, all-true, all-false, random — every [rephaseInterval] conflicts
     * to escape basins the saved phase keeps reproducing. Pure-Boolean: integer value
     * selection is untouched (it still follows plain phase saving when [phaseSaving] is set).
     * Disabled by default, leaving plain phase saving as the baseline behaviour.
     */
    val targetPhasing: Boolean = false,
    /**
     * Conflicts between rephasing rotations when [targetPhasing] is on. Each rotation
     * advances the Boolean polarity source through target → saved → all-true → all-false →
     * random and back. Ignored when [targetPhasing] is false. Must be positive.
     */
    val rephaseInterval: Long = 1000L,
    /**
     * Cap on the learned-clause database size. When non-null, a restart-driven
     * forgetting pass runs on every Luby restart (gated by [lubyRestartBase]): clauses
     * with LBD ≤ [lbdGlueThreshold] are kept regardless ("glue clauses"); among the
     * rest, the lowest-LBD clauses are kept up to the cap and higher-LBD clauses are
     * dropped. `null` (default) disables forgetting — clauses accumulate indefinitely.
     * Set to a few thousand for hard instances where memory growth is a concern.
     */
    val maxLearnedClauses: Int? = null,
    /**
     * Glue threshold for [maxLearnedClauses] — clauses with LBD ≤ this are always
     * retained, since they typically capture cross-cutting "high-leverage" constraints.
     * MiniSAT / Glucose default is 2.
     */
    val lbdGlueThreshold: Int = 2,
    /**
     * Three-tier learned-clause database (#201). When true (and [maxLearnedClauses] is set),
     * the restart-driven reduction replaces the binary glue split with three tiers: a
     * permanent core (LBD ≤ [lbdGlueThreshold]), a mid tier (LBD ≤ [midLbdThreshold]) kept
     * across reductions but demoted when idle, and a local tier deleted aggressively. Clauses
     * that participate in a later conflict (detect it or force a unit) are promoted on the
     * next reduction; mid-tier clauses idle across a reduction are demoted. This gives more
     * selective deletion than the binary glue split and helps proof-search families (#117)
     * where useful clauses are otherwise forgotten. Disabled by default — the binary glue
     * policy stays the baseline.
     */
    val tieredLearnedDb: Boolean = false,
    /**
     * Mid-tier LBD threshold for [tieredLearnedDb]: a freshly learned clause with
     * `lbdGlueThreshold < LBD ≤ midLbdThreshold` starts in the mid tier, higher LBD in the
     * local tier. Glucose's "Tier2" cutoff is 6.
     */
    val midLbdThreshold: Int = 6,
    /**
     * Clause vivification (#203) as an inprocessing pass. When true the engine periodically
     * — at restart boundaries, where the trail is at root and assumptions are clean — walks a
     * bounded slice of the learned-clause database and strengthens each clause by tentatively
     * asserting the negations of its literals under propagation: a remaining literal already
     * falsified is dropped; one forced true (or a conflict) shortens the clause to the
     * literals tried so far. Pure-Boolean; atom-literal clauses are skipped. Disabled by
     * default. One of the highest-value inprocessing techniques on hard UNSAT instances like
     * the pigeonhole family in #117. Only honoured when [assumptions] is empty.
     */
    val vivification: Boolean = false,
    /**
     * Maximum number of learned clauses vivified per restart when [vivification] is on. The
     * pass advances a cursor across the database round-robin so the per-restart cost stays
     * bounded while the whole database is covered over successive restarts. Must be positive.
     */
    val vivifyBatch: Int = 256,
    /**
     * Externally-supplied objective upper bound for branch-and-bound minimisation. When
     * non-null, the [com.eignex.klause.solver.Optimizer.improvements] / `minimize`
     * engines read it at each leaf-attempt and prune the subtree whenever the
     * `LinearObjective` lower bound on the current partial assignment is `≥ supplier()`.
     * Used by the parallel CP portfolio to share the best-known incumbent across workers
     * — each worker keeps pruning against a tightening external bound even when its own
     * local incumbent is worse. `null` (default) disables external bound sharing; the
     * engine still tracks and prunes against its own internal incumbent. Only honoured
     * for [com.eignex.klause.solver.objective.LinearObjective] — arbitrary objective subtypes
     * can't yield a sound LB and silently skip external pruning.
     */
    val objectiveBoundSupplier: (() -> Double)? = null,
    /**
     * The emphasis-driven LP-relaxation selector (#429): an [LpEmphasis] cost ceiling + per-technique
     * overrides (see [LpConfig]), resolved against the problem's structure by [LpAutoConfig.resolve]
     * at `minimize`/`improvements`. `null` (the raw default) uses the explicit per-technique flags
     * below verbatim — no LP unless one is set; the user-facing entry points (`--lp`, the portfolio
     * arms) supply an [LpConfig] so LP is on by default with the emphasis level as the only dial.
     * `LpConfig.AGGRESSIVE` reproduces the old structural "enable everything applicable" auto-config.
     * Resolved flags are OR-ed onto any explicit ones, so an explicit flag is never turned off.
     */
    val lpConfig: LpConfig? = null,
    /**
     * Native LP-relaxation bounding for branch-and-bound minimisation (#20). When true, scheduled
     * nodes solve an exact integer LP relaxation of the live problem and prune when the relaxation
     * is infeasible or its objective bound, rounded up to the next integer, is `≥` the incumbent.
     * This strictly dominates the cheap per-term lower bound (which still runs first as a fast
     * pre-filter) but costs an LP solve per scheduled node. Disabled by default; a no-op for
     * problems with no LP-emittable factors (the relaxation is then empty and never prunes).
     */
    val lpBounding: Boolean = false,
    /**
     * Sparse-LU fallback for [lpBounding]: when the exact `Long` fraction-free dual simplex overflows
     * 64 bits on a node (which previously lost the bound entirely), recover a sound objective lower
     * bound via the float revised simplex + exact BigInt basis-certification pipeline and prune on it.
     * Sound (the certified bound can only under-estimate); off by default, auto-enabled with [lpBounding].
     */
    val lpSparseBound: Boolean = false,
    /**
     * Use the sparse pipeline as the *primary* (bound-only) LP path, skipping the dense Bareiss
     * tableau entirely. Auto-enabled by [LpAutoConfig] for models that exceed the dense-tableau cap
     * ([com.eignex.klause.config.KlauseConfig.lpMaxTableauCells]) but fit the sparse cap — the class
     * the guard otherwise disables LP on (big-M-heavy / bool-dominated COP). Bound-only: no cuts,
     * reduced-cost fixing, or Farkas (those need the exact dense tableau).
     */
    val lpSparsePrimary: Boolean = false,
    /**
     * Frequency policy for [lpBounding]: solve the LP at one in every [lpBoundEvery] pruning checks
     * rather than at every node, since the LP solve dominates a node's cost. `1` solves at every
     * checked node; larger values trade pruning power for throughput. Must be positive.
     */
    val lpBoundEvery: Int = 1,
    /**
     * Depth policy for [lpBounding]: skip the LP solve below this decision depth. The relaxation is
     * tightest and most valuable near the root where bounds are loose; deep nodes are nearly fixed
     * and rarely repay the solve. `Int.MAX_VALUE` (default) applies LP bounding at every depth.
     */
    val lpBoundMaxDepth: Int = Int.MAX_VALUE,
    /**
     * Warm-start each node's LP solve from a recent node's basis instead of re-solving cold. Branch
     * decisions only tighten bounds, which leaves a parent basis dual-feasible, so the child
     * re-optimises in a handful of dual pivots. The constraint matrix is identical across nodes
     * (only bounds change), so the basis transfers directly. Sound either way — a stale or singular
     * basis just falls back to a cold solve; this only changes pivot count, never the result.
     * Enabled by default when [lpBounding] is on.
     */
    val lpWarmStart: Boolean = true,
    /**
     * Float fast-path (#18): when a node has no parent basis to warm-start from, solve the LP first
     * in double precision and hand the resulting basis to the exact solver to certify. The bound
     * stays exact (the exact solver re-optimizes from the float basis); this only trades a cheap
     * float solve for fewer exact pivots, a win on larger LPs. Off by default — on the small dense
     * per-node LPs the float solve does not pay for itself.
     */
    val lpFloatWarmStart: Boolean = false,
    /**
     * LP-guided value ordering (#246): when the per-node LP has solved (requires [lpBounding]), order
     * each branch variable's candidate values by closeness to its fractional LP value
     * (round-toward-LP diving). Pure search-order guidance — it changes which solutions are found
     * first, never the optimum or feasibility — so it is correctness-neutral. Off by default; a no-op
     * for variables with no current LP value.
     */
    val lpBranching: Boolean = false,
    /**
     * LP-rounding primal heuristic (#287): before search, solve the root LP and try to round its
     * fractional point into a feasible assignment by pinning each variable toward its LP value and
     * propagating. A complete conflict-free pass is a feasible incumbent (propagation enforces every
     * factor, so the result is sound by construction); it seeds the branch-and-bound bound so pruning
     * and reduced-cost fixing bite from the first node. A conflict on the single pass abandons the
     * probe (no backtracking). Requires a LinearObjective; off by default; never changes the optimum.
     */
    val lpProbe: Boolean = false,
    /**
     * Cut generation (#22): at a scheduled node, after the LP solve, run separators that add valid
     * linear cuts the fractional LP point violates (AllDifferent Hall-set cuts, Gomory integrality
     * cuts), re-solving to tighten the bound. Requires [lpBounding]; off by default.
     */
    val lpCuts: Boolean = false,
    /** Maximum separation rounds per node for [lpCuts]; each round adds cuts and re-solves. */
    val lpCutRounds: Int = 4,
    /**
     * Separation rounds at the root node (decision level 0) for [lpCuts] (#285). The root relaxation
     * is solved once and bounds the whole tree, so spending more rounds there to drive a strong root
     * cut closure pays off broadly; deeper nodes keep the cheaper [lpCutRounds]. Defaults to a deeper
     * closure than per-node; capped to at least [lpCutRounds].
     */
    val lpRootCutRounds: Int = 16,
    /**
     * Cut staleness tolerance (#565): end separation at a node once a round improves the LP objective
     * bound by less than this fraction of `max(1, |bound|)` — diminishing returns. Cuts that no longer
     * move the bound only cost per-node solve time and starve search (on ghoulomb the aggressive tier
     * added ~15795 cuts over 24s of a 30s budget for zero prunes while the incumbent regressed). The
     * first round whose gain falls below the tolerance ends separation for that node. `0.0` disables
     * the check, separating to [lpCutRounds] / [lpRootCutRounds] as before.
     */
    val lpCutMinGain: Double = 1e-3,
    /**
     * Hard cap on the total cuts added at one node across all separation rounds (#565) — a backstop on
     * the per-node solve cost of a large live cut pool (a round's fresh cuts are trimmed to fit, and
     * reaching the cap ends separation for that node). The staleness check ([lpCutMinGain]) is the
     * primary control; this only bounds the worst case where each round keeps moving the bound.
     */
    val lpMaxCutsPerNode: Int = 2048,
    /**
     * Persistent global cut pool. When true (and [lpCuts]), the structural separators are run
     * once at the root and the cuts they find are cached and re-added to every node's relaxation,
     * instead of being re-separated per node. These cuts are computed from the root (= declared)
     * domains and problem structure, so they are globally valid — a root Hall/cover/assignment/subtour
     * cut stays a valid (if weaker) bound at every tighter descendant. Gives a cheap baseline
     * strengthening at every node on top of per-node separation. Off by default.
     */
    val lpCutPool: Boolean = false,
    /**
     * Include Gomory integrality cuts among the [lpCuts] separators. These come from the simplex
     * tableau and strengthen any fractional LP regardless of problem structure; the exact integer
     * tableau makes them numerically clean. On by default when [lpCuts] is set.
     */
    val lpGomory: Boolean = true,
    /**
     * Include Gomory mixed-integer (MIR) cuts among the [lpCuts] separators. Derived from the same
     * tableau rows as [lpGomory] but with the stronger mixed-integer rounding multiplier, so they
     * dominate the pure-integer cut on rows with fractional nonbasic coefficients. Like [lpGomory]
     * they need no problem structure and stay exact in the integer tableau. On by default when
     * [lpCuts] is set.
     */
    val lpMir: Boolean = true,
    /**
     * Warm-start the dual-simplex re-solves within a node — each cut round resumes from the previous
     * round's optimal basis extended with the new cut rows' slacks (the textbook dual-simplex cut
     * loop), and each [lpFixpoint] re-solve resumes from the last optimal basis over the same rows —
     * instead of cold-starting from the all-slack basis. Adding valid cut rows with their slacks
     * basic keeps the prior basis dual-feasible, so the re-solve converges in a handful of pivots; a
     * basis that fails to load (a singular extension, or determinant overflow during the reload)
     * falls back to a cold start. The optimum is identical either way — this only changes the pivot
     * path. On by default.
     */
    val lpWarmCuts: Boolean = true,
    /**
     * Genuine subtour-elimination cuts for Circuit globals (#22). When true and [lpBounding] holds,
     * each Circuit gets an arc-indicator relaxation (degree + channelling rows) and a max-flow
     * separator adds the directed cutset inequalities that fractional/subtour LP points violate.
     * Adds O(n²) columns per circuit (skipped above a node-count cap), so it is opt-in and off by
     * default; a no-op when no Circuit exists.
     */
    val lpCircuit: Boolean = false,
    /**
     * Linearize constant-array Element globals with a one-hot selector model (#22). When true and
     * [lpBounding] holds, each Element over a constant table gets selector columns and channelling
     * rows — its exact convex hull `result = Σ arr[p]·[idx=p]` — so the LP sees `result`'s dependence
     * on `idx`. Adds O(len) columns per Element (skipped above a length cap), so it is opt-in and off
     * by default; a no-op when no constant-array Element exists. Variable-array Element is deferred.
     */
    val lpElement: Boolean = false,
    /**
     * Linearize Table globals with their convex hull (#22). When true and [lpBounding] holds, each
     * Table gets one selector column per allowed tuple with `Σ y_t = 1` and per-column channels
     * `xs[j] = Σ tuple_t[j]·y_t`, so the LP sees the exact convex hull of the allowed tuples. Adds
     * O(numTuples) columns per Table (skipped above a tuple-count cap), so it is opt-in and off by
     * default; a no-op when no Table exists.
     */
    val lpTable: Boolean = false,
    /**
     * One-hot NValue value hull (#435). When true and [lpBounding] holds, each NValue contributes a
     * per-value "used"-indicator model so the distinct-count target gets an LP bound (a real lower
     * bound under minimisation). Sound by construction; off by default; a no-op when no NValue exists.
     */
    val lpNValue: Boolean = false,
    /**
     * Energetic makespan lower-bound row for the scheduling globals (#430). When true and
     * [lpBounding] holds, each Cumulative / Disjunctive whose makespan variable `M` can be verified
     * (`M ≥ startᵢ + durᵢ` from the actual linear / array-max links) contributes one row
     * `capacity·M ≥ cap·t1 + Σ energy-after(t1)` — the energetic / area objective bound the
     * start-variable LP otherwise lacks for scheduling. Sound by construction (a missing row only
     * loosens; the makespan link is never guessed); off by default; a no-op without a verifiable
     * scheduling makespan. See [com.eignex.klause.solver.lp.CumulativeRelaxation].
     */
    val lpCumulative: Boolean = false,
    /**
     * Time-indexed LP reformulation of the scheduling globals (#453). When true and [lpBounding]
     * holds, each Cumulative / Disjunctive over a bounded horizon gets binary `x_{i,t}` start
     * columns with assignment, start-channel and per-time-point resource rows — the resource–time
     * coupling the start-variable LP lacks, so the LP bound is far tighter than the energetic row
     * ([lpCumulative]) alone. Adds O(n·H) columns, hard-gated on the horizon, so it is opt-in and off
     * by default; a no-op without a bounded-horizon scheduling global.
     */
    val lpCumulativeTimeIndexed: Boolean = false,
    /**
     * Preemptive min-cost-flow feasibility / makespan bound for the scheduling globals (#454). When
     * true, a node is pruned if the tasks' energy cannot be preemptively packed into their release /
     * deadline windows at capacity (an exact max-flow feasibility test, strictly stronger than the
     * pairwise-window [energeticReasoning] scan and horizon-independent — it keys off the O(n)
     * start-bound breakpoints, not the time axis), and the verified makespan variable is lower-bounded
     * by the smallest feasible completion time. Pure relaxation; off by default; a no-op without a
     * scheduling global. See [com.eignex.klause.solver.lp.CumulativeFlowBound].
     */
    val lpCumulativeFlow: Boolean = false,
    /** Frequency policy for [lpCumulativeFlow]: run the max-flow check at one in every this-many
     *  pruning checks, mirroring [energeticEvery]. Must be positive. */
    val lpCumulativeFlowEvery: Int = 1,
    /**
     * Subgradient Lagrangian bounding for structured globals (#23). When true and the objective is a
     * [com.eignex.klause.solver.objective.LinearObjective], a node also computes a Lagrangian bound from an
     * AllDifferent global (its variables solved exactly as a min-cost assignment, with the linear
     * constraints over them dualized) and prunes when that bound — rounded up — reaches the incumbent.
     * Independent of [lpBounding]; off by default; a no-op when no eligible AllDifferent exists.
     */
    val lagrangian: Boolean = false,
    /** Subgradient ascent iterations per node for [lagrangian]; more iterations tighten the bound. */
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
     * [energeticEvery] pruning checks, mirroring [lpBoundEvery]. The scan is O(windows² · tasks)
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
     * infeasibility certificate (requires [lpBounding]) and the energetic over-subscription window
     * (requires [energeticReasoning]). When the certificate resolves to an asserting 1UIP clause the
     * engine backjumps and learns immediately (#280), so this now helps even with restarts off;
     * non-asserting certificates still fall back to restart-time registration. A certificate
     * leaning on a live-big-M reified row cites the bounds that justify the M alongside its column
     * seats; one leaning on a node-local cut is not expressible as a globally valid bound-atom
     * clause and is withheld — the prune itself still happens. Off by default.
     */
    val lpLearn: Boolean = false,
    /**
     * Propagate the LP objective lower bound onto a single-variable objective (#281). When true and
     * [lpBounding] holds, a feasible node LP tightens the objective variable's bound to the rounded LP
     * optimum, with the reduced-cost dual certificate recorded as the reason so the bound is learnable
     * and propagates through the objective-defining constraint to its component variables. When no
     * sound bound-atom reason exists (the certificate leans on a node-local row or an auxiliary
     * column), the bound is still applied as a reason-less, level-local tightening. Off by
     * default; a no-op unless the objective is a single integer variable being minimised.
     */
    val lpObjectiveBound: Boolean = false,
    /**
     * Drive the node LP and propagation to a joint fixpoint (#283). When true (and [lpBounding]),
     * after the LP's domain deductions (objective bound #281, reduced-cost fixing #21/#282) tighten
     * domains and propagate, the LP is re-solved and the deductions re-applied, repeating while a
     * round keeps tightening (capped). This lets a fixing that improves the bound enable further
     * fixings in the same node, instead of waiting for a deeper node. Off by default; a no-op when
     * the deductions never tighten anything. Cut separation is not repeated (it runs once per node).
     */
    val lpFixpoint: Boolean = false,
    /** Cooperative cancellation predicate; see [Cancellation]. */
    val cancellation: Cancellation = Cancellation.Never,
    /**
     * Optional live-event listener; see [SearchEvent]. Called
     * inline on the search thread at coarse points only (restarts, learned-DB sweeps,
     * incumbents). `null` (default) disables observation entirely.
     */
    val onEvent: ((SearchEvent) -> Unit)? = null,
    /**
     * Optional cross-arm learned-clause exchange; see [ClauseExchange]. Invoked at each restart
     * boundary (decision level 0) so a portfolio can import nogoods other arms learned and export
     * this arm's new glue clauses. `null` (default) means no sharing — a standalone solve is
     * unaffected. All arms must be built from the same problem for the shared clauses to be valid.
     */
    val clauseExchange: ClauseExchange? = null,
) : SolverParams {
    override fun withAssumptions(assumptions: Assumptions): BacktrackParams =
        if (assumptions.isEmpty) this else copy(assumptions = merge(this.assumptions, assumptions))

    override fun withCancellation(cancellation: Cancellation): BacktrackParams = copy(cancellation = cancellation)

    private companion object {
        fun merge(a: Assumptions, b: Assumptions): Assumptions = a.mergedWith(b)
    }
}
