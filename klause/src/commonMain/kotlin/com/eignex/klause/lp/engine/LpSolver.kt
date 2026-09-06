package com.eignex.klause.lp.engine

import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.util.Cancellation

/**
 * The engine that solves an [LpModel] to a float optimum and the artifacts an exact certifier needs
 * from it. The seam is deliberately engine-agnostic: everything here is producible by any LP method,
 * simplex or not (e.g. a first-order primal–dual method) — the [FloatLpResult] carries the primal
 * point and the dual vector for the Neumaier–Shcherbina safe bound, and [infeasibleRay] carries the
 * candidate Farkas ray for exact infeasibility certification. Basis warm-starting and cut generation
 * are simplex-specific and live on [TableauCutSolver], not here, so a basis-free engine implements
 * only this interface.
 *
 * All returned values are double-precision guides; the authoritative bound always comes from exactly
 * certifying the result downstream ([integerCertify] / [integerFarkasRay]), never from these.
 */
internal interface LpSolver : AutoCloseable {
    /**
     * Solve the relaxation, optionally warm-started from a prior optimal [warm] basis of the same model
     * structure; null on non-convergence / dual-unbounded / singular basis. The warm basis only changes
     * the pivot path, never the result. (The warm-start handle is a simplex basis today; a basis-free
     * engine ignores it.)
     */
    fun solve(warm: Basis? = null): FloatLpResult?

    /** Solve with the primal pass (phase-1 included), used where a feasible point is wanted even when
     *  the dual pass would not converge; null on failure. */
    fun solvePrimal(warm: Basis? = null): FloatLpResult?

    /** The float candidate Farkas ray at a dual-unbounded termination, for [integerFarkasRay] to round
     *  and certify; null unless the last [solve] returned null on infeasibility. */
    val infeasibleRay: DoubleArray?

    /** The dual-unbounded basis behind [infeasibleRay], for the exact ray solve that a model with an
     *  unbounded column needs. Null on an engine that keeps no basis; certification then falls back to
     *  rounding [infeasibleRay]. */
    val infeasibleBasis: Basis? get() = null

    /** The leaving row of [infeasibleBasis]; `-1` when there is none. */
    val infeasibleRow: Int get() = -1

    /**
     * Pivots the last solve spent, whether or not it returned a [FloatLpResult].
     *
     * A solve that terminates dual-unbounded or non-convergent still spent them, and a caller that
     * counts the solve has to be able to count its cost — reading the count off the result alone drops
     * exactly the solves that prune, which are the ones worth costing. 0 on an engine that keeps no
     * pivot count.
     */
    val lastPivots: Int get() = 0

    /** Basis factorizations the last solve built, on the same terms as [lastPivots]. */
    val lastRefactorizations: Int get() = 0

    /** Whether the last solve started from a prior basis rather than a cold start, on the same terms
     *  as [lastPivots]. */
    val lastWarmStarted: Boolean get() = false

    /**
     * Floating-point operations the last solve charged — a deterministic stand-in for its cost, on the
     * same terms as [lastPivots].
     *
     * Pivots alone do not measure a solve: one pivot on a dense basis outweighs many on a sparse one, and
     * a refactorization outweighs both. A policy that budgets LP effort needs a figure that reflects
     * that and is reproducible run to run, which wall-clock time is not. See [LpWork]. 0 on an engine
     * that keeps no count.
     */
    val lastWorkOps: Long get() = 0L

    /**
     * Factorizations the last solve built that came back singular, on the same terms as [lastPivots].
     *
     * A singular factorization is not a failure the caller sees: the engine falls back to the slack
     * cold start and carries on, discarding the warm basis. So this is the only trace that a warm
     * start was thrown away, and the measurement behind whether the relaxation's columns are badly
     * enough conditioned to want scaling. 0 on an engine that keeps no factorization.
     */
    val lastSingularRefactorizations: Int get() = 0

    /**
     * Pivots the last solve abandoned because the pivot element was numerically too small, on the same
     * terms as [lastPivots].
     *
     * The engine gives up on the solve rather than refactorizing and retrying, so each one is a solve
     * lost outright — and lost without a [FloatLpResult] to record it in. 0 on an engine that does not
     * pivot.
     */
    val lastSmallPivotBails: Int get() = 0

    /** Nonbasic columns with zero reduced cost at the last termination — dual degeneracy. 0 on an engine
     *  that does not measure it. */
    val lastDegenerateColumns: Int get() = 0

    /** Columns the last solve ran over, the denominator [lastDegenerateColumns] is judged against. */
    val lastColumns: Int get() = 0

    /**
     * Release what the engine holds outside the heap.
     *
     * A basis factorization can be a native object, and a search builds an engine per node, so the
     * ones a caller keeps are worth closing rather than leaving to a cleaner. An engine holding
     * nothing external closes as a no-op, which is the default, and closing twice is harmless.
     */
    override fun close() {}
}

/**
 * An [LpSolver] that also exposes tableau cut generation. Gomory/MIR cuts are read off an optimal
 * simplex basis, so only a basis-carrying (simplex) engine can supply them; the cut-separation loop
 * types against this, while the general solve/certify path types against [LpSolver].
 */
internal interface TableauCutSolver : LpSolver {
    /** Gomory (Chvátal) integrality cuts from the last optimal basis, up to [maxCuts]; empty if the
     *  last solve was not optimal. */
    fun gomoryCuts(maxCuts: Int): List<Cut>

    /** Gomory mixed-integer (MIR) cuts from the last optimal basis, up to [maxCuts]. */
    fun mirCuts(maxCuts: Int): List<Cut>
}

/**
 * An [LpSolver] a caller keeps across many solves, re-pointing one instance at a successor model rather
 * than building a fresh engine for it. The basis and its factorization — the expensive half of a solve —
 * carry over, so a model differing from its predecessor only in column bounds or in which rows are
 * enforced is repaired in a few pivots.
 *
 * Kept off [LpSolver] because not every engine can honour it: [ComponentLpSolver] holds one sub-solver
 * per column component, and neither the identity test [rebind] rests on nor a per-row enforcement toggle
 * survives that split. Stating the limitation in the type is better than a no-op implementation that a
 * caller would silently pay a full rebuild for.
 */
internal interface PersistentLpSolver : LpSolver {
    /**
     * Re-point this engine at [next] and [token], keeping the seated basis and its factorization; false
     * when [next] is not a bound-only revision of the current model, which is the caller's signal to
     * build a fresh engine.
     */
    fun rebind(next: LpModel, token: Cancellation): Boolean

    /** Re-solve after a [rebind], continuing from the kept basis and factorization. */
    fun resolveBounds(): FloatLpResult?

    /**
     * Re-solve with per-row enforcement, continuing from the kept basis and factorization. A row with
     * `enforced(i) = false` does not constrain — its equation merely defines a free slack. Only sound
     * for an all-zero objective, under which every basis is dual-feasible.
     */
    fun resolveGated(enforced: BooleanArray): FloatLpResult?
}

/**
 * Construct the LP engine for the general solve/certify path — the swap point for an alternative engine
 * (an interior-point method carries no basis, so it implements [LpSolver] without [TableauCutSolver]);
 * callers depend only on [LpSolver].
 *
 * A separable model first decomposes into its column components ([ComponentLpSolver]) — exact, and
 * each block factorizes at a fraction of the monolithic cost; [componentSplit] (default on, the
 * `lp-component-split` knob) opts out.
 */
internal fun newLpSolver(
    model: LpModel,
    cancellation: Cancellation = Cancellation.Never,
    componentSplit: Boolean = true,
): LpSolver {
    if (componentSplit) {
        componentLpSolverOrNull(model, cancellation, ::monolithicLpSolver)?.let { return it }
    }
    return monolithicLpSolver(model, cancellation)
}

/**
 * Construct a basis-carrying engine, for the callers that read cuts off the optimal tableau or that
 * budget the solve itself — both simplex-specific, so this never decomposes.
 *
 * [iterationLimit] and [workLimit] bound the dual solve, each 0 leaving it to the engine; a truncated
 * dual iterate still carries a valid bound. [trackDegeneracy] turns on the dual-degeneracy measurement
 * an adaptive budget reads back.
 */
internal fun newTableauCutSolver(
    model: LpModel,
    cancellation: Cancellation = Cancellation.Never,
    iterationLimit: Int = 0,
    workLimit: Long = 0L,
    trackDegeneracy: Boolean = false,
): TableauCutSolver = RevisedSimplex(
    model,
    cancellation,
    iterationLimit = iterationLimit,
    workLimit = workLimit,
    trackDegeneracy = trackDegeneracy,
)

/**
 * Construct an engine to keep across solves ([PersistentLpSolver]). Monolithic by construction: a
 * decomposed model has no single basis to carry, which is the whole of what such a caller keeps it for.
 *
 * [refactorUpdateLimit] caps the updates folded into the basis before it is rebuilt. A caller whose
 * pivots accumulate across solves raises it, since the default is sized for one solve's chain. The
 * remaining knobs are [newTableauCutSolver]'s.
 */
internal fun newPersistentLpSolver(
    model: LpModel,
    cancellation: Cancellation = Cancellation.Never,
    refactorUpdateLimit: Int = DEFAULT_REFACTOR_UPDATE_LIMIT,
    iterationLimit: Int = 0,
    workLimit: Long = 0L,
    trackDegeneracy: Boolean = false,
): PersistentLpSolver = RevisedSimplex(
    model,
    cancellation,
    refactorUpdateLimit = refactorUpdateLimit,
    iterationLimit = iterationLimit,
    workLimit = workLimit,
    trackDegeneracy = trackDegeneracy,
)

/** The single-model engine selection [newLpSolver] and each decomposed block share. */
private fun monolithicLpSolver(model: LpModel, cancellation: Cancellation): LpSolver =
    RevisedSimplex(model, cancellation)
