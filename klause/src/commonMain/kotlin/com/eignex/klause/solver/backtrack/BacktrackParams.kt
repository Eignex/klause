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
