package com.eignex.klause.lp

import com.eignex.klause.lp.cut.Cut
import com.eignex.klause.solver.Cancellation

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
internal interface LpSolver {
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
 * Construct the LP engine for the general solve/certify path — the swap point for an alternative engine
 * (or, later, a first-order primal–dual GPU solver); callers depend only on [LpSolver].
 *
 * A separable model first decomposes into its column components ([ComponentLpSolver]) — exact, and
 * each block factorizes at a fraction of the monolithic cost; [componentSplit] (default on, the
 * `lp-component-split` knob) opts out. A **dense** model whose constraint matrix has filled in picks
 * the [DenseSimplex] (koblas dense LU), where the sparse [RevisedSimplex]'s `O(nnz)` bookkeeping buys
 * nothing; everything else stays on the sparse engine. The dense gate is currently limited to
 * LP-only-continuous (real) models — the integer path is byte-identity-sensitive, so extending the
 * dense engine there needs a node/propagation A/B first.
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

/** The single-model engine selection [newLpSolver] and each decomposed block share. */
private fun monolithicLpSolver(model: LpModel, cancellation: Cancellation): LpSolver =
    if (model.hasContinuous && isDense(model)) {
        DenseSimplex(model, cancellation)
    } else {
        RevisedSimplex(model, cancellation)
    }

/** The constraint matrix has filled in enough that the dense engine is worthwhile: a non-trivial matrix
 *  whose structural nonzero density is at least [DENSE_FILL_THRESHOLD]. */
private fun isDense(model: LpModel): Boolean {
    val n = model.n
    val m = model.m
    if (n == 0 || m == 0) return false
    val nnz = (model.doubleView?.colVal?.size ?: model.csc.colVal.size).toLong()
    return nnz.toDouble() >= DENSE_FILL_THRESHOLD * m.toLong() * n.toLong()
}

private const val DENSE_FILL_THRESHOLD = 0.5
