package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.nextDown

/**
 * An exact Lagrangian bound from candidate duals, projected downward to a finite Double. Every input
 * coefficient, bound and objective constant is read from the authoritative Long or binary view;
 * Long models use bounded integer multipliers; Double models use binary duals in the rational reader.
 * Reconstruction and floating summation cannot authorize this bound. Negative reduced costs require
 * an actual finite upper side, and a missing lower side requires a nonpositive reduced cost.
 */
internal fun safeObjectiveLowerBound(
    model: LpModel,
    y: DoubleArray,
    observer: LpCertificationObserver? = null,
): Double? = safeObjectiveLowerBoundUnchecked(model, y).also {
    observer?.observe(LpCertifier.SAFE_OBJECTIVE, it != null)
}

private fun safeObjectiveLowerBoundUnchecked(model: LpModel, y: DoubleArray): Double? {
    if (y.size != model.m || y.any { !it.isFinite() }) return null
    if (!model.hasContinuous) {
        val certificate = integerCertify(model, y)
        val numerator = certificate?.objectiveNumerator()
        if (numerator != null && numerator.fitsLong()) {
            return lowerBoundAsDouble(numerator.toLong()) / (1L shl certificate.objectiveScaleBits).toDouble()
        }
    }
    val exact = if (!model.hasContinuous) {
        certifyLpBound(model, y)?.value ?: exactLagrangian(model, y.map { checkNotNull(BigFraction.ofDouble(it)) })
    } else {
        exactLagrangian(model, y.map { checkNotNull(BigFraction.ofDouble(it)) })
    } ?: return null
    return exact.lowerBoundDouble()
}

internal fun BigFraction.lowerBoundDouble(): Double? {
    var candidate = toDouble()
    if (!candidate.isFinite()) return null
    // The quotient conversion itself may round twice. The comparison, not a rounding estimate, accepts it.
    repeat(4) {
        if (checkNotNull(BigFraction.ofDouble(candidate)) <= this) return candidate
        candidate = candidate.nextDown()
        if (!candidate.isFinite()) return null
    }
    return null
}

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
    observer: LpCertificationObserver? = null,
): Long? {
    if (!hasIntegralObjective()) return null
    val objMin = safeObjectiveLowerBound(this, result.duals, observer) ?: return null
    val ceilMin = ceil(objMin)
    if (!ceilMin.isFinite() || ceilMin < Long.MIN_VALUE.toDouble() || ceilMin >= Long.MAX_VALUE.toDouble()) {
        return null
    }
    return orientedVariableBound(ceilMin.toLong(), objectiveCol, maximize, clamped)
}

/**
 * An integer variable bound from the bounded integer-multiplier certificate. The source objective
 * must have an integer lattice. Null on unsupported scaling, overflow or missing finite support.
 * Shares the probe-frontier rejection of [safeVariableBound].
 */
internal fun LpModel.exactVariableBound(
    result: FloatLpResult,
    objectiveCol: Int,
    maximize: Boolean,
    clamped: Boolean? = null,
    observer: LpCertificationObserver? = null,
): Long? {
    val ceilMin = exactObjectiveLowerBoundCeil(result.duals, observer) ?: return null
    return orientedVariableBound(ceilMin, objectiveCol, maximize, clamped)
}

/** The shared tail of [safeVariableBound] and [exactVariableBound]: orient `⌈L⌉` on the minimized
 *  objective ([ceilMin]) to the requested sense, then reject a bound that only rode the probe frontier.
 *  The bound source is the only thing the two entry points differ in. */
private fun LpModel.orientedVariableBound(
    ceilMin: Long,
    objectiveCol: Int,
    maximize: Boolean,
    clamped: Boolean?,
): Long? {
    // cost is −1 on the column for a maximization (we minimize −x), +1 for a minimization; `−⌈L⌉` is
    // exactly the `⌊−L⌋` a maximization wants.
    val bound = if (maximize) {
        if (ceilMin == Long.MIN_VALUE) return null // −Long.MIN_VALUE overflows
        -ceilMin
    } else {
        ceilMin
    }
    val clampedThatSide = clamped ?: if (maximize) probeClampedHi[objectiveCol] else probeClampedLo[objectiveCol]
    // Reject well below the exact cap: a bound this large is "at the frontier" and means unbounded (a
    // real bound worth keeping is tiny next to the probe, which is ~Long.MAX/4).
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
    observer: LpCertificationObserver? = null,
): Long? {
    val safe = safeVariableBound(result, objectiveCol, maximize, clamped, observer)
    val exact = exactVariableBound(result, objectiveCol, maximize, clamped, observer)
    return when {
        safe == null -> exact
        exact == null -> safe
        maximize -> minOf(safe, exact)
        else -> maxOf(safe, exact)
    }
}

/**
 * `⌈L⌉` on the minimized objective from the *approximate* duals [y], evaluated exactly: the
 * integer-multiplier [integerDualLowerBoundCeil], or — on a model with continuous columns — the same
 * bound over its scaled-integer rationalization ([rationalizedDualLowerBoundCeil]), so mixed real models
 * retain supported scaling. The ceiling requires a verified integer source objective lattice;
 * otherwise this helper declines, since a ceiling may exceed a continuous objective's optimum. Null on a
 * 128-bit certification overflow or a
 * model that does not rationalize.
 */
internal fun LpModel.exactObjectiveLowerBoundCeil(y: DoubleArray, observer: LpCertificationObserver? = null): Long? =
    if (!hasIntegralObjective()) {
        null
    } else if (hasContinuous) {
        rationalizedDualLowerBoundCeil(this, y, observer = observer)
    } else {
        integerDualLowerBoundCeil(this, y, observer = observer)
    }

/**
 * The tightest sound lower bound on [model]'s minimized objective from the approximate duals [y]: the
 * larger of the projected [safeObjectiveLowerBound] and the exact [exactObjectiveLowerBoundCeil] — the
 * objective-level twin of [tightVariableBound]. The projection and integer ceiling may lose different
 * information. The ceiling is used only for a verified integer source objective. Null
 * only when neither side is available.
 */
internal fun tightObjectiveLowerBound(
    model: LpModel,
    y: DoubleArray,
    observer: LpCertificationObserver? = null,
): Double? = tighterLowerBound(
    safeObjectiveLowerBound(model, y, observer),
    model.exactObjectiveLowerBoundCeil(y, observer),
)

/**
 * [tightObjectiveLowerBound] with the exact side taken from [certificate] — the certificate the caller
 * already computed over the same [model] and [y], or null when that certification declined. The
 * supplied certificate may contribute an integer ceiling only when the source objective has that lattice.
 */
internal fun tightObjectiveLowerBound(
    model: LpModel,
    y: DoubleArray,
    certificate: IntegerCertificate?,
    observer: LpCertificationObserver? = null,
): Double? = tighterLowerBound(
    safeObjectiveLowerBound(model, y, observer),
    certificate?.takeIf { model.hasIntegralObjective() }?.objectiveBoundCeil(0L),
)

/** The larger of two sound lower bounds on the same objective, either of which may be unavailable. */
private fun tighterLowerBound(safe: Double?, exactCeil: Long?): Double? {
    val exact = exactCeil?.let(::lowerBoundAsDouble)
    return when {
        safe == null -> exact
        exact == null -> safe
        else -> maxOf(safe, exact)
    }
}

/** [v] widened to `Double` without ever rounding **up**: past 2⁵³ the widening rounds to nearest, and a
 *  lower bound that grew in the conversion would no longer be sound. */
private fun lowerBoundAsDouble(v: Long): Double {
    val d = v.toDouble()
    return if (d == Long.MAX_VALUE.toDouble() || d.toLong() > v) d.nextDown() else d
}
