package com.eignex.klause.solver.backtrack

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.SolverParams
import com.eignex.klause.solver.backtrack.lp.LpAutoConfig
import com.eignex.klause.solver.backtrack.lp.LpConfig
import com.eignex.klause.solver.backtrack.lp.LpEmphasis
import com.eignex.klause.solver.backtrack.lp.LpPlan
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
import com.eignex.klause.solver.backtrack.selector.Vsids
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
 *    [Vsids] for conflict-driven search; alternatives include [SmallestDomain]
 *    (first-fail), [InputOrder], and [RandomVariable].
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
    val variableSelector: VariableSelector = Vsids(),
    val valueSelector: ValueSelector = IndomainRandom,
    val minHammingDistance: Int = 0,
    val recentWindow: Int = 0,
    /**
     * Luby restart base. When non-null, the search pops back to root after
     * `lubyN(restartIdx) * lubyRestartBase` decisions in the current run and starts
     * fresh from level 0. Phase-saving (see [phaseSaving]) and the B&B bound (in
     * [com.eignex.klause.solver.Optimizer.minimize]) survive the restart, so each
     * restart resumes with strictly more information than the previous run. Disabled
     * by default; set a unit (for example `256`) to enable Luby restarts.
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
     * [lubyRestartBase]. Disabled by default.
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
     * dropped. `null` (default) disables forgetting so clauses accumulate indefinitely.
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
     * where useful clauses are otherwise forgotten. Disabled by default.
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
     * The resolved per-technique LP-relaxation plan; see [LpPlan]. [LpAutoConfig.resolve] OR-es the
     * structurally-applicable techniques onto this from [lpConfig]'s emphasis. The raw default is the
     * all-off [LpPlan] (no LP unless a field is set or [lpConfig] resolves one on).
     */
    val lpPlan: LpPlan = LpPlan(),
    /**
     * Subgradient Lagrangian bounding for structured globals (#23). When true and the objective is a
     * [com.eignex.klause.solver.objective.LinearObjective], a node also computes a Lagrangian bound from an
     * AllDifferent global (its variables solved exactly as a min-cost assignment, with the linear
     * constraints over them dualized) and prunes when that bound — rounded up — reaches the incumbent.
     * Independent of [LpPlan.bounding]; off by default; a no-op when no eligible AllDifferent exists.
     */
    val lagrangian: Boolean = false,
    /** Subgradient ascent iterations per node for [lagrangian] / [LpPlan.knapsackLagrangian]; more
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
     * [energeticEvery] pruning checks, mirroring [LpPlan.boundEvery]. The scan is O(windows² · tasks)
     * per Cumulative with no incremental state, so on task-heavy models it dominates a node's
     * cost; a cadence trades missed prunes for throughput. `1` (default) checks at every pruned
     * node. [LpAutoConfig] derives a size-aware cadence from the task counts when *it* enables
     * the check. Must be positive.
     */
    val energeticEvery: Int = 1,
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
        private fun merge(a: Assumptions, b: Assumptions): Assumptions = a.mergedWith(b)
    }
}
