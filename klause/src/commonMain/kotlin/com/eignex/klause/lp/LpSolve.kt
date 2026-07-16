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
): CertifiedLpResult {
    val solver = newLpSolver(model, cancellation)
    val result = solver.solve(warm)
        ?: run {
            // A dual-unbounded termination is only a *candidate* infeasibility — confirm it with an exact
            // Farkas certificate. Any other failure (non-convergence / singular) is indeterminate.
            val floatRay = solver.infeasibleRay
            val ray = if (floatRay != null) integerFarkasRay(model, floatRay) else null
            return if (ray != null) {
                CertifiedLpResult(
                    LpVerdict.INFEASIBLE,
                    float = null,
                    certificate = null,
                    farkasRay = ray,
                    model = null,
                )
            } else {
                CertifiedLpResult(
                    LpVerdict.INDETERMINATE,
                    float = null,
                    certificate = null,
                    farkasRay = null,
                    model = null,
                )
            }
        }
    // A float optimum that cannot be certified exactly (a 128-bit overflow, or a real coefficient the
    // integer certifier declines) is INDETERMINATE — the float point is not a proof.
    val certificate = integerCertify(model, result.duals)
    return CertifiedLpResult(
        if (certificate != null) LpVerdict.OPTIMAL else LpVerdict.INDETERMINATE,
        float = result,
        certificate = certificate,
        farkasRay = null,
        model = model,
    )
}
