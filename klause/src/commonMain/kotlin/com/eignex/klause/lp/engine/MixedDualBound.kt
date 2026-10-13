package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.magnitudeBitLength

internal fun nodeDualCertificate(
    model: LpModel,
    duals: DoubleArray,
    cancellation: Cancellation = Cancellation.Never,
    observer: LpCertificationObserver? = null,
): LpDeductionCertificate? {
    if (!model.hasContinuous) return integerCertify(model, duals, observer = observer)
    val meter = MixedCertificateMeter(cancellation)
    val certificate = try {
        meter.poll()
        val state = model.exactState ?: throw LpProjectionStop()
        meter.reserve(model.numVars.toLong() * 16L + model.m * 8L)
        integerCertifyUnchecked(model, duals, DEFAULT_SCALE_BITS, allowMixed = true, scan = meter::step)
            ?: mixedCertificate(model, state, duals, meter)
    } catch (_: LpProjectionStop) {
        null
    }
    observer?.observe(LpCertifier.INTEGER, certificate != null, LpCertifierCost.Metered(meter.work))
    return certificate.takeUnless { cancellation() }
}

private fun mixedCertificate(
    model: LpModel,
    state: LpExactState,
    duals: DoubleArray,
    meter: MixedCertificateMeter,
): LpDeductionCertificate? {
    val rounded = roundDuals(model, duals) ?: return null
    val scale = meter.read(state.model.objective.scale.value)
    val y = List(model.m) { row ->
        meter.read(BigFraction.ofLong(rounded.mult[row]) * BigFraction.ofLong(rounded.scale).reciprocal())
    }.toMutableList()
    for (row in y.indices) {
        val slack = model.slackCol(row)
        val cost = meter.read(model.exactCost(slack))
        val bounds = model.exactBounds(slack)
        if ((bounds.lower == null && y[row] < cost) || (bounds.upper == null && y[row] > cost)) y[row] = cost
    }
    var value = meter.read(model.exactConstant())
    for (row in y.indices) value = meter.add(value, meter.multiply(y[row], model.exactRhs(row)))
    val reduced = List(model.numVars) { column ->
        var cost = meter.read(model.exactCost(column))
        model.forEachRationalColumn(column) { row, coefficient ->
            cost = meter.add(cost, meter.multiply(y[row], coefficient).negated())
        }
        val bounds = model.exactBounds(column)
        val endpoint = when {
            cost.signum() > 0 -> bounds.lower ?: return null
            cost.signum() < 0 -> bounds.upper ?: return null
            else -> null
        }
        if (endpoint != null) value = meter.add(value, meter.multiply(cost, endpoint.number.value))
        meter.multiply(cost, scale.reciprocal())
    }
    value = meter.add(meter.multiply(value, scale.reciprocal()), state.model.objective.externalConstant.value)
    meter.poll()
    return MixedDualBound(state, y.toList(), reduced, value)
}

internal class MixedDualBound(
    private val authority: LpExactState,
    private val multipliers: List<BigFraction>,
    private val reduced: List<BigFraction>,
    private val bound: BigFraction,
) : LpDeductionCertificate {
    override fun belongsTo(model: LpModel, observer: LpCertificationObserver?): Boolean = model.exactState === authority
    override fun exactMultipliers(): List<BigFraction> = multipliers.toList()
    override fun exactReducedCost(col: Int): BigFraction = reduced[col]
    override fun exactObjectiveBound(): BigFraction = bound
    override fun dualNonzeroRow(row: Int): Boolean = !multipliers[row].isZero
    override fun reducedCostSign(col: Int): Int = reduced[col].signum()
    override fun objectiveBoundCeil(extraConstant: Long): Long? = (bound + BigFraction.ofLong(extraConstant)).ceilLong()
    override fun improvingGapNonNegative(improvingMax: Long, sourceConstant: Long): Boolean =
        gap(improvingMax, sourceConstant) >= BigFraction.ZERO

    override fun fixStepsAtCutoff(col: Int, cutoff: BigFraction, sourceConstant: Long): Long? =
        if (authority.model.column(col).integral) super.fixStepsAtCutoff(col, cutoff, sourceConstant) else null

    override fun fixSteps(col: Int, improvingMax: Long, sourceConstant: Long): Long? {
        if (!authority.model.column(col).integral || reduced[col].isZero) return null
        val cost = if (reduced[col].signum() < 0) reduced[col].negated() else reduced[col]
        return (gap(improvingMax, sourceConstant) * cost.reciprocal()).floorLong()
    }

    private fun gap(improvingMax: Long, sourceConstant: Long): BigFraction =
        BigFraction.ofLong(improvingMax) - BigFraction.ofLong(sourceConstant) - bound
}

private class MixedCertificateMeter(cancellation: Cancellation) {
    private val meter = LpProjectionMeter(250_000L, 16L * 1024L * 1024L, cancellation)
    val work: Long get() = meter.work
    fun poll() = meter.poll()
    fun step() = meter.reserve(1L, 0L, matrix = true)
    fun reserve(bytes: Long) = meter.reserve(bytes / 8L + 1L, bytes, matrix = false)

    fun read(value: BigFraction): BigFraction {
        val bits = maxOf(value.num.magnitudeBitLength(), value.den.magnitudeBitLength())
        if (bits > 128) throw LpProjectionStop()
        val words = (bits.toLong() + 63L) / 64L
        meter.reserve(1L + words * words, 64L + (bits.toLong() + 7L) / 4L, matrix = false)
        return value
    }

    fun add(left: BigFraction, right: BigFraction): BigFraction = read(read(left) + read(right))
    fun multiply(left: BigFraction, right: BigFraction): BigFraction = read(read(left) * read(right))
}
