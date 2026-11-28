package com.eignex.klause.lp.engine

import com.eignex.util.numbers.BigFraction
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.nextDown

// Exact Lagrangian bound from candidate duals, projected down to a finite Double. Inputs are read from the
// authoritative Long or binary view: Long models use bounded integer multipliers, Double models binary duals in the
// rational reader. Reconstruction and floating summation cannot authorize it. A negative reduced cost needs a finite
// upper side, and a missing lower side needs a nonpositive reduced cost.
internal fun safeObjectiveLowerBound(
    model: LpModel,
    y: DoubleArray,
    observer: LpCertificationObserver? = null,
): Double? {
    val scans = LpScanCount(model)
    return safeObjectiveLowerBoundUnchecked(model, y, scans).also {
        observer?.observe(LpCertifier.SAFE_OBJECTIVE, it != null, scans.cost())
    }
}

private fun safeObjectiveLowerBoundUnchecked(model: LpModel, y: DoubleArray, scans: LpScanCount): Double? {
    if (y.size != model.m || y.any { !it.isFinite() }) return null
    if (model.exactState != null || !model.hasContinuous) {
        scans.scan()
        val certificate = integerCertify(model, y)
        val numerator = certificate?.objectiveNumerator()
        if (numerator != null && numerator.fitsLong()) {
            return lowerBoundAsDouble(numerator.toLong()) / (1L shl certificate.objectiveScaleBits).toDouble()
        }
    }
    val exact = if (!model.hasContinuous) {
        scans.scan()
        certifyLpBound(model, y)?.value ?: run {
            val multipliers = y.map { checkNotNull(BigFraction.ofDouble(it)) }
            scans.scan(exactBits(multipliers))
            exactLagrangian(model, multipliers)
        }
    } else {
        val multipliers = y.map { checkNotNull(BigFraction.ofDouble(it)) }
        scans.scan(exactBits(multipliers))
        exactLagrangian(model, multipliers)
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

// Integer bound on [objectiveCol]'s max (when [maximize]) or min from a solved [result]; null when unbounded in that
// direction. The objective must be that single column with cost ±1 and no constant. An optimum that only rode to
// the [LP_UNBOUNDED_PROBE] frontier, the stand-in for ±∞ on a [LpBuilder.addFreeVar] side, is reported unbounded,
// never as a bound at the probe magnitude. [clamped] overrides which probe flag guards that rejection: a split
// variable x = x⁺ − x⁻ descends by x⁻ riding its own upper probe, which [objectiveCol]'s flags cannot see.
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

// The source objective must have an integer lattice. Null on unsupported scaling, overflow or missing finite
// support. Shares the probe-frontier rejection of [safeVariableBound].
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

// Orients ⌈L⌉ on the minimized objective ([ceilMin]) to the requested sense, then rejects a bound that only rode the
// probe frontier.
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

// Both sides are valid, so the tighter wins; the exact side is sharper on free columns. Null only when neither is
// available. [clamped] is the split-representation probe override of [safeVariableBound].
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

// ⌈L⌉ from approximate duals [y], evaluated exactly by [integerDualLowerBoundCeil], or with continuous columns by
// [rationalizedDualLowerBoundCeil]. Declines without a verified integer source objective lattice, since a ceiling
// may exceed a continuous optimum. Null on 128-bit overflow or a model that does not rationalize.
internal fun LpModel.exactObjectiveLowerBoundCeil(y: DoubleArray, observer: LpCertificationObserver? = null): Long? =
    if (!hasIntegralObjective()) {
        null
    } else if (exactState != null) {
        integerDualLowerBoundCeil(this, y, observer = observer)
            ?: certifyLpBound(this, y, observer)?.value?.ceilLong()
    } else if (hasContinuous) {
        rationalizedDualLowerBoundCeil(this, y, observer = observer)
    } else {
        integerDualLowerBoundCeil(this, y, observer = observer)
    }

// The larger of [safeObjectiveLowerBound] and [exactObjectiveLowerBoundCeil], which may lose different information.
// Null only when neither side is available.
internal fun tightObjectiveLowerBound(
    model: LpModel,
    y: DoubleArray,
    observer: LpCertificationObserver? = null,
): Double? = tighterLowerBound(
    safeObjectiveLowerBound(model, y, observer),
    model.exactObjectiveLowerBoundCeil(y, observer),
)

// [certificate] was computed over the same [model] and [y], or is null when it declined. It contributes a ceiling
// only when the source objective has an integer lattice.
internal fun tightObjectiveLowerBound(
    model: LpModel,
    y: DoubleArray,
    certificate: IntegerCertificate?,
    observer: LpCertificationObserver? = null,
): Double? = tighterLowerBound(
    safeObjectiveLowerBound(model, y, observer),
    certificate?.takeIf { it.belongsTo(model, observer) && model.hasIntegralObjective() }?.objectiveBoundCeil(0L),
)

private fun tighterLowerBound(safe: Double?, exactCeil: Long?): Double? {
    val exact = exactCeil?.let(::lowerBoundAsDouble)
    return when {
        safe == null -> exact
        exact == null -> safe
        else -> maxOf(safe, exact)
    }
}

// Past 2⁵³ widening rounds to nearest; a lower bound that grew in the conversion would be unsound.
private fun lowerBoundAsDouble(v: Long): Double {
    val d = v.toDouble()
    return if (d == Long.MAX_VALUE.toDouble() || d.toLong() > v) d.nextDown() else d
}
