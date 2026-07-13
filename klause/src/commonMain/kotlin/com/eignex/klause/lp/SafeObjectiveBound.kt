package com.eignex.klause.lp

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Neumaier–Shcherbina safe lower bound on the minimized objective `cᵀz` from an *approximate* dual
 * vector [y] (e.g. from [RevisedSimplex]). The model is standard-form-with-slacks — `A_full z = rhs`,
 * `0 ≤ z ≤ ub` — so for **any** `y` the Lagrangian `y·rhs + Σ_j min_{z_j∈[0,ub_j]} d_j·z_j` (with
 * `d_j = c_j − (A_fullᵀy)_j`) is a valid lower bound on the optimum. Floating-point error is made
 * rigorous by *worst-casing*: each reduced cost is pushed down by a magnitude-scaled rounding-error
 * term before its box minimum is taken, and the final sum is reduced by its own rounding error. The
 * result can therefore only *under*-estimate the true bound — never over-estimate it — so pruning on
 * `result ≥ incumbent` is sound. The lower-bound-shift constant `c·lo` ([LpModel.objConstant]) is
 * re-added so the bound is on the true objective at branched nodes, not the shifted one. Returns null
 * when the relaxation is unbounded below (a strictly negative reduced cost on a variable with no
 * finite upper bound) or when [y] is non-finite.
 *
 * This is the cheap pruning bound; the integer-multiplier [integerCertify] gives the tight
 * authoritative one. A loose result here only costs a missed prune, never correctness.
 */
internal fun safeObjectiveLowerBound(model: LpModel, y: DoubleArray): Double? {
    val m = model.m
    val n = model.n
    var bound = 0.0
    var sumMag = 0.0 // magnitudes feeding the final summation, for its rounding-error term
    for (i in 0 until m) {
        val yi = y[i]
        if (!yi.isFinite()) return null
        val t = yi * model.rhs[i].toDouble()
        bound += t
        sumMag += abs(t)
    }
    for (j in 0 until model.numVars) {
        var atj = 0.0
        var colMag = 0.0
        var terms = 1
        if (j >= n) {
            atj = y[j - n]
            colMag = abs(atj)
        } else {
            var acc = 0.0
            var mag = 0.0
            var t = terms
            model.forEachInColumn(j) { i, a ->
                val term = y[i] * a.toDouble()
                acc += term
                mag += abs(term)
                t++
            }
            atj = acc
            colMag = mag
            terms = t
        }
        val cj = model.cost[j].toDouble()
        val dj = cj - atj
        // Rigorous lower bound on the true reduced cost: subtract the rounding error of forming dj.
        val djErr = (abs(cj) + colMag) * (terms + 1).toDouble() * EPS
        val worstDj = dj - djErr
        if (worstDj < 0.0) {
            if (!model.hasUpper[j]) return null // unbounded below
            val contrib = worstDj * model.upper[j].toDouble()
            bound += contrib
            sumMag += abs(contrib)
        }
    }
    val sumErr = (m + model.numVars + 2).toDouble() * EPS * sumMag
    // Re-add the lower-bound-shift constant the relaxation folded out (exact; matches DualSimplex).
    val safe = bound - sumErr + model.objConstant.toDouble()
    return if (safe.isFinite()) safe else null
}

/** Conservative per-operation relative rounding bound (`> 2⁻⁵³` unit roundoff), with margin. */
private const val EPS = 2.4e-16

/**
 * A sound finite bound on structural column [objectiveCol]'s optimum — its **max** when [maximize],
 * else its **min** — from an already-solved primal [result], or null when the variable is genuinely
 * unbounded in that direction. The model's objective must be that single column with cost `±1` and no
 * constant (max is set up as minimizing `−x`). Rigorous under float error via [safeObjectiveLowerBound],
 * then floored (max) / ceiled (min) to an integer. **Reject-at-cap:** an optimum that only rode the
 * column to its [LP_UNBOUNDED_PROBE] frontier — the private stand-in for `±∞` on a [LpBuilder.addFreeVar]
 * side — is reported unbounded (null), never as a spurious bound at the probe magnitude.
 */
internal fun LpModel.safeVariableBound(result: FloatLpResult, objectiveCol: Int, maximize: Boolean): Long? {
    val objMin = safeObjectiveLowerBound(this, result.duals) ?: return null
    // cost is −1 on the column for a maximization (we minimize −x), +1 for a minimization.
    val bound = if (maximize) floor(-objMin) else ceil(objMin)
    if (!bound.isFinite() || bound < Long.MIN_VALUE.toDouble() || bound > Long.MAX_VALUE.toDouble()) return null
    val clampedThatSide = if (maximize) probeClampedHi[objectiveCol] else probeClampedLo[objectiveCol]
    // Reject well below the exact cap: a bound this large is "at the frontier" and means unbounded (a
    // real bound worth keeping is tiny next to the probe, which is ~Long.MAX/4).
    val frontier = (LP_UNBOUNDED_PROBE - LP_UNBOUNDED_PROBE / 4).toDouble()
    if (clampedThatSide && abs(bound) >= frontier) return null
    return bound.toLong()
}
