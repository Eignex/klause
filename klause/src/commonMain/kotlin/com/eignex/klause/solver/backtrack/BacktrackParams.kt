package com.eignex.klause.solver.backtrack

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.SearchEvent
import com.eignex.klause.solver.SolverParams

/**
 * Per-call params for [BacktrackSolver].
 *
 *  - [maxDecisions] — abort after this many decisions are pushed (Unknown). `Long.MAX_VALUE`
 *    by default — let the search run to completion.
 *  - [randomSeed] — seeds the engine RNG that's threaded into [variableHeuristic] and
 *    [valueHeuristic]. `null` picks a fresh seed per call.
 *  - [assumptions] — variables pinned for the duration of the call.
 *  - [variableHeuristic] — picks the next variable to branch on. Defaults to
 *    [RandomVariable] for diverse search; CSP-typical alternatives are [SmallestDomain]
 *    (first-fail) and [InputOrder].
 *  - [valueHeuristic] — picks the order in which to try values of the chosen variable.
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
    val variableHeuristic: VariableHeuristic = RandomVariable,
    val valueHeuristic: ValueHeuristic = IndomainRandom,
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
     * for [com.eignex.klause.solver.LinearObjective] — arbitrary objective subtypes
     * can't yield a sound LB and silently skip external pruning.
     */
    val objectiveBoundSupplier: (() -> Double)? = null,
    /**
     * Native LP-relaxation bounding for branch-and-bound minimisation (#20). When true and the
     * objective is a [com.eignex.klause.solver.LinearObjective], scheduled nodes solve an exact
     * integer LP relaxation of the live problem and prune when the relaxation is infeasible or its
     * objective bound, rounded up to the next integer, is `≥` the incumbent. This strictly
     * dominates the cheap per-term lower bound (which still runs first as a fast pre-filter) but
     * costs an LP solve per scheduled node. Disabled by default; ignored for non-linear objectives
     * and for problems with no LP-emittable factors (the relaxation is then empty and never prunes).
     */
    val lpBounding: Boolean = false,
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
     * Cut generation (#22): at a scheduled node, after the LP solve, run separators that add valid
     * linear cuts the fractional LP point violates (AllDifferent Hall-set cuts, Gomory integrality
     * cuts), re-solving to tighten the bound. Requires [lpBounding]; off by default.
     */
    val lpCuts: Boolean = false,
    /** Maximum separation rounds per node for [lpCuts]; each round adds cuts and re-solves. */
    val lpCutRounds: Int = 4,
    /**
     * Include Gomory integrality cuts among the [lpCuts] separators. These come from the simplex
     * tableau and strengthen any fractional LP regardless of problem structure; the exact integer
     * tableau makes them numerically clean. On by default when [lpCuts] is set.
     */
    val lpGomory: Boolean = true,
    /**
     * Subgradient Lagrangian bounding for structured globals (#23). When true and the objective is a
     * [com.eignex.klause.solver.LinearObjective], a node also computes a Lagrangian bound from an
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
     * Learn a clause from an infeasible node LP (#247). When true, the LP's Farkas infeasibility
     * certificate is turned into a nogood over absolute variable-bound atoms — a globally valid
     * clause implied by the original constraints — and registered at the next restart (where its
     * literals are no longer all-false), so the dead region is pruned in sibling subtrees. Requires
     * [lpBounding]; takes effect only when restarts are enabled (see [lubyRestartBase] /
     * [adaptiveRestart]). Off by default.
     */
    val lpLearn: Boolean = false,
    /** Cooperative cancellation predicate; see [Cancellation]. */
    val cancellation: Cancellation = Cancellation.Never,
    /**
     * Optional live-event listener; see [SearchEvent]. Called
     * inline on the search thread at coarse points only (restarts, learned-DB sweeps,
     * incumbents). `null` (default) disables observation entirely.
     */
    val onEvent: ((SearchEvent) -> Unit)? = null,
) : SolverParams {
    override fun withAssumptions(assumptions: Assumptions): BacktrackParams =
        if (assumptions.isEmpty) this else copy(assumptions = merge(this.assumptions, assumptions))

    override fun withCancellation(cancellation: Cancellation): BacktrackParams = copy(cancellation = cancellation)

    private companion object {
        fun merge(a: Assumptions, b: Assumptions): Assumptions = a.mergedWith(b)
    }
}
