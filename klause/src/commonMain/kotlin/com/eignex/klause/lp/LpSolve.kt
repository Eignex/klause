package com.eignex.klause.lp

import com.eignex.klause.solver.Cancellation

/** The certified verdict of an LP over an [LpModel], independent of any search node. */
internal enum class LpVerdict {
    /** A finite optimum was found (see [CertifiedLpResult.float] / [CertifiedLpResult.certificate]). */
    OPTIMAL,

    /** The relaxation is infeasible, proven by an exact Farkas ray ([CertifiedLpResult.farkasRay]). */
    INFEASIBLE,

    /** The float solve did not settle (non-convergence / singular basis) or a candidate infeasibility
     *  could not be exactly certified, or the 128-bit certification overflowed — the LP is left open. */
    INDETERMINATE,
}

/**
 * The outcome of [solveAndCertify]: a float LP solve plus the exact-arithmetic certification of its
 * result. The float engine only guides; the authoritative facts are the [certificate] (a proven
 * optimum lower bound) and the [farkasRay] (a proven infeasibility) — both in 128-bit integer
 * arithmetic. The cheap Neumaier–Shcherbina [safeLowerBound] is a float pruning heuristic and is
 * computed on demand, not eagerly, so a consumer that only wants the exact verdict pays nothing for it.
 */
internal class CertifiedLpResult internal constructor(
    val verdict: LpVerdict,
    /** The float solve result on an [LpVerdict.OPTIMAL] verdict (primal, duals, basis, objective); null otherwise. */
    val float: FloatLpResult?,
    /** The exact integer certificate of the optimum, when the solve certified; null otherwise. */
    val certificate: IntegerCertificate?,
    /** The exact integer Farkas ray proving infeasibility on an [LpVerdict.INFEASIBLE] verdict; null otherwise. */
    val farkasRay: LongArray?,
    private val model: LpModel?,
    /** Exact structural-column witness from the rational decider (delta-instantiated for strict rows);
     *  preferred over the float primal by consumers that report the point. Null when the float
     *  certificates decided. */
    val exactPrimal: DoubleArray? = null,
    /** On a rational-decider [LpVerdict.INFEASIBLE], the load-bearing original rows (no integer ray
     *  exists for a strictness-carried proof); null otherwise. */
    val infeasibleRows: IntArray? = null,
) {
    /** The exact 128-bit integer lower bound `⌈optimum⌉` on the true objective, or null when the LP was
     *  not certified or the bound does not fit a `Long`. */
    val exactLowerBound: Long? get() = certificate?.objectiveBoundCeil(0L)

    /** The Neumaier–Shcherbina float safe lower bound on the true objective — a cheap pruning heuristic,
     *  looser than [exactLowerBound]; null when the LP was not solved or is unbounded below. Computed on
     *  demand. */
    val safeLowerBound: Double? by lazy {
        val f = float ?: return@lazy null
        val m = model ?: return@lazy null
        safeObjectiveLowerBound(m, f.duals)
    }
}

/**
 * Solve [model] to a certified verdict, session-free: the standalone LP theory-solve that the search
 * path, a leaf feasibility check, or a pure-LP / MPS solve all share. Runs the float [LpSolver], then
 * certifies its result exactly — an [LpVerdict.OPTIMAL] optimum via [integerCertify], or an
 * [LpVerdict.INFEASIBLE] via [integerFarkasRay] on a dual-unbounded termination. A float termination
 * that cannot be exactly certified (non-convergence, singular basis, an unconfirmed infeasibility, or a
 * 128-bit overflow) yields [LpVerdict.INDETERMINATE], never an unsound verdict.
 *
 * [warm] optionally warm-starts from a prior optimal basis of the same model structure; it changes only
 * the pivot path, never the verdict.
 */
internal fun solveAndCertify(
    model: LpModel,
    warm: Basis? = null,
    cancellation: Cancellation = Cancellation.Never,
    componentSplit: Boolean = true,
): CertifiedLpResult {
    val solver = newLpSolver(model, cancellation, componentSplit)
    val result = solver.solve(warm)
        ?: run {
            // A dual-unbounded termination is only a *candidate* infeasibility — confirm it with an exact
            // Farkas certificate. Any other failure (non-convergence / singular) is indeterminate.
            val floatRay = solver.infeasibleRay
            val ray = if (floatRay != null) {
                integerFarkasRay(model, floatRay, basis = solver.infeasibleBasis, basisRow = solver.infeasibleRow)
            } else {
                null
            }
            if (ray != null) {
                return CertifiedLpResult(
                    LpVerdict.INFEASIBLE,
                    float = null,
                    certificate = null,
                    farkasRay = ray,
                    model = null,
                )
            }
            val outcome = rationalOutcome(model, cancellation)
            return CertifiedLpResult(
                when (outcome.feasibility) {
                    RationalFeasibility.FEASIBLE -> LpVerdict.OPTIMAL
                    RationalFeasibility.INFEASIBLE -> LpVerdict.INFEASIBLE
                    RationalFeasibility.UNKNOWN -> LpVerdict.INDETERMINATE
                },
                float = null,
                certificate = null,
                farkasRay = null,
                model = null,
                exactPrimal = outcome.witness,
                infeasibleRows = outcome.rows,
            )
        }
    // A float optimum that cannot be certified exactly (a 128-bit overflow, or a real coefficient the
    // integer certifier declines) is INDETERMINATE — the float point is not a proof. An integer model is
    // certified by the exact dual bound ([integerCertify]); a continuous model has no integer dual bound,
    // so its feasibility is certified by reconstructing the reported basis's point exactly
    // ([exactBasisFeasible]) — enough for a definitive SAT verdict at a leaf.
    val certificate = integerCertify(model, result.duals)
    // Strict rows are relaxed to non-strict in the float model, so the basis/point certificates would
    // bless a boundary point a strict row forbids; those models go straight to the delta-aware
    // rational decider.
    val anyStrict = model.rowStrict.any { it }
    var exactPrimal: DoubleArray? = null
    var infeasibleRows: IntArray? = null
    val verdict = when {
        certificate != null -> LpVerdict.OPTIMAL

        model.hasContinuous && !anyStrict &&
            (exactBasisFeasible(model, result.basis) == true || exactPointFeasible(model, result.primal)) ->
            LpVerdict.OPTIMAL

        // Last resort: decide feasibility outright in exact rational arithmetic. The float point was
        // not certifiable, but the model may still be exactly decidable — FEASIBLE stands in for the
        // basis/point certificates (a definitive SAT, with the exact witness carried out), INFEASIBLE
        // is an exact refutation.
        model.hasContinuous -> {
            val outcome = rationalOutcome(model, cancellation)
            exactPrimal = outcome.witness
            infeasibleRows = outcome.rows
            when (outcome.feasibility) {
                RationalFeasibility.FEASIBLE -> LpVerdict.OPTIMAL
                RationalFeasibility.INFEASIBLE -> LpVerdict.INFEASIBLE
                RationalFeasibility.UNKNOWN -> LpVerdict.INDETERMINATE
            }
        }

        else -> LpVerdict.INDETERMINATE
    }
    return CertifiedLpResult(
        verdict,
        float = result,
        certificate = certificate,
        farkasRay = null,
        model = model,
        exactPrimal = exactPrimal,
        infeasibleRows = infeasibleRows,
    )
}
