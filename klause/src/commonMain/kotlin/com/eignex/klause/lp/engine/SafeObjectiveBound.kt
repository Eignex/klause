package com.eignex.klause.lp.engine

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Neumaier and Shcherbina, *Safe Bounds in Linear and Mixed-Integer Linear Programming* (Mathematical
 * Programming, 2004): a floating-point LP bound made rigorous by directed rounding of the dual, so a
 * prune rests on an inequality that holds exactly rather than on the float arithmetic that produced it.
 *
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
    // The Neumaier–Shcherbina bound is sound over real data too, so it reads the double view when the
    // model has continuous columns — an integer model's accessors return the same widened Long values,
    // so the integer path is unchanged.
    val m = model.m
    val n = model.n
    val dual = repairedDual(model, y)
    var bound = 0.0
    var sumMag = 0.0 // magnitudes feeding the final summation, for its rounding-error term
    for (i in 0 until m) {
        val yi = dual[i]
        if (!yi.isFinite()) return null
        val t = yi * model.rhsD(i)
        bound += t
        sumMag += abs(t)
    }
    for (j in 0 until model.numVars) {
        var atj = 0.0
        var colMag = 0.0
        var terms = 1
        if (j >= n) {
            atj = dual[j - n]
            colMag = abs(atj)
        } else {
            var acc = 0.0
            var mag = 0.0
            var t = terms
            model.forEachInColumnD(j) { i, a ->
                val term = dual[i] * a
                acc += term
                mag += abs(term)
                t++
            }
            atj = acc
            colMag = mag
            terms = t
        }
        val cj = model.costD(j)
        val dj = cj - atj
        // Rigorous lower bound on the true reduced cost: subtract the rounding error of forming dj.
        val djErr = (abs(cj) + colMag) * (terms + 1).toDouble() * EPS
        val worstDj = dj - djErr
        if (worstDj < 0.0) {
            if (!model.hasFiniteUpper(j)) return null // unbounded below
            val contrib = worstDj * model.upperD(j)
            bound += contrib
            sumMag += abs(contrib)
        }
    }
    val sumErr = (m + model.numVars + 2).toDouble() * EPS * sumMag
    // Re-add the lower-bound-shift constant the relaxation folded out (exact; matches DualSimplex).
    val safe = bound - sumErr + model.objConstantD
    return if (safe.isFinite()) safe else null
}

/**
 * [y] with each row multiplier moved to where its own slack's reduced cost is zero, for the rows where
 * it had gone the other way; [y] itself when none had.
 *
 * The Lagrangian below is valid for **any** [y], so a multiplier is free to be moved — and moving one is
 * worth far more than the alternative. A slack column carries no upper bound, so `min d_j·z_j` over its
 * box is `−∞` the moment `d_j` is negative, and the whole bound is lost. What sends it negative is not a
 * dual worth respecting: an approximate `y` leaves a multiplier whose exact value is zero sitting at
 * `−1e-12`, while the rounding term this function charges covers only the arithmetic of *forming* the
 * reduced cost — for a slack, a single subtraction, so around `1e-28`. Discarding the bound over that
 * loses every bound on a model whose columns are all open, which is exactly where bounds are wanted.
 *
 * The repair is not a tolerance: the moved multiplier is used for the whole bound, so what comes back is
 * the true Lagrangian of a different, equally valid `y`. A multiplier that was genuinely wrong rather
 * than merely noisy is moved just the same, and only costs the bound some tightness.
 *
 * A structural column with no finite upper cannot be repaired this way — its reduced cost reads every
 * multiplier at once — so one left negative still abandons the bound.
 */
private fun repairedDual(model: LpModel, y: DoubleArray): DoubleArray {
    var repaired: DoubleArray? = null
    for (i in 0 until model.m) {
        val slack = model.n + i
        if (model.hasFiniteUpper(slack)) continue
        // A slack column is the unit column e_i, so its reduced cost is `c_slack − y_i`.
        val cost = model.costD(slack)
        if (cost - y[i] >= 0.0) continue
        val fix = repaired ?: y.copyOf().also { repaired = it }
        fix[i] = cost
    }
    return repaired ?: y
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
 * side — is reported unbounded (null), never as a spurious bound at the probe magnitude. [clamped]
 * overrides which probe flag guards that rejection — a variable represented split (`x = x⁺ − x⁻`)
 * descends by `x⁻` riding *its* upper probe, which [objectiveCol]'s own flags cannot see.
 */
internal fun LpModel.safeVariableBound(
    result: FloatLpResult,
    objectiveCol: Int,
    maximize: Boolean,
    clamped: Boolean? = null,
): Long? {
    val objMin = safeObjectiveLowerBound(this, result.duals) ?: return null
    // cost is −1 on the column for a maximization (we minimize −x), +1 for a minimization.
    val bound = if (maximize) floor(-objMin) else ceil(objMin)
    if (!bound.isFinite() || bound < Long.MIN_VALUE.toDouble() || bound > Long.MAX_VALUE.toDouble()) return null
    val clampedThatSide = clamped ?: if (maximize) probeClampedHi[objectiveCol] else probeClampedLo[objectiveCol]
    // Reject well below the exact cap: a bound this large is "at the frontier" and means unbounded (a
    // real bound worth keeping is tiny next to the probe, which is ~Long.MAX/4).
    val frontier = (LP_UNBOUNDED_PROBE - LP_UNBOUNDED_PROBE / 4).toDouble()
    if (clampedThatSide && abs(bound) >= frontier) return null
    return bound.toLong()
}

/**
 * The integer-exact twin of [safeVariableBound], from the 128-bit [integerDualLowerBoundCeil] instead of
 * the float [safeObjectiveLowerBound]. It is tight where the float bound is loose: the safe bound
 * subtracts a conservative rounding margin from every reduced cost, which for a free column
 * ([LpBuilder.addFreeVar]) is multiplied by the ~`Long.MAX/4` probe upper and swamps the true bound; the
 * exact bound carries no such margin, so a free basic column (true reduced cost 0) contributes nothing.
 * A continuous model certifies over its scaled-integer rationalization
 * ([rationalizedDualLowerBoundCeil]), so mixed real models get the same sharpness.
 * Null on a 128-bit certification overflow (the caller falls back). Same probe-frontier rejection as
 * [safeVariableBound], with the same [clamped] override for split representations.
 */
internal fun LpModel.exactVariableBound(
    result: FloatLpResult,
    objectiveCol: Int,
    maximize: Boolean,
    clamped: Boolean? = null,
): Long? {
    // ⌈L⌉ on the minimized objective (−x when maximizing, x when minimizing), objConstant folded in.
    val ceil = (
        if (hasContinuous) {
            rationalizedDualLowerBoundCeil(this, result.duals)
        } else {
            integerDualLowerBoundCeil(this, result.duals)
        }
        ) ?: return null
    val bound = if (maximize) {
        if (ceil == Long.MIN_VALUE) return null // −Long.MIN_VALUE overflows
        -ceil
    } else {
        ceil
    }
    val clampedThatSide = clamped ?: if (maximize) probeClampedHi[objectiveCol] else probeClampedLo[objectiveCol]
    val frontier = LP_UNBOUNDED_PROBE - LP_UNBOUNDED_PROBE / 4
    if (clampedThatSide && (bound == Long.MIN_VALUE || abs(bound) >= frontier)) return null
    return bound
}

/**
 * The tightest sound bound on [objectiveCol] from an already-solved [result]: the tighter of the exact
 * [exactVariableBound] and the float [safeVariableBound]. Both are valid, so the tighter always wins —
 * the smaller upper bound when [maximize], the larger lower bound otherwise — which never regresses below
 * the float bound yet captures the exact bound's sharpness on free columns. Null only when neither is
 * available (both unbounded / overflow). [clamped] is the split-representation probe override of
 * [safeVariableBound].
 */
internal fun LpModel.tightVariableBound(
    result: FloatLpResult,
    objectiveCol: Int,
    maximize: Boolean,
    clamped: Boolean? = null,
): Long? {
    val safe = safeVariableBound(result, objectiveCol, maximize, clamped)
    val exact = exactVariableBound(result, objectiveCol, maximize, clamped)
    return when {
        safe == null -> exact
        exact == null -> safe
        maximize -> minOf(safe, exact)
        else -> maxOf(safe, exact)
    }
}
