package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.PollStride
import com.eignex.klause.util.magnitudeBitLength

internal fun nodeDualCertificate(
    model: LpModel,
    duals: DoubleArray,
    cancellation: Cancellation = Cancellation.Never,
    observer: LpCertificationObserver? = null,
    eliminateContinuousObjective: Boolean = false,
    onAdditionalCertificate: ((LpDeductionCertificate) -> Unit)? = null,
    sharedMeter: MixedCertificateMeter? = null,
): LpDeductionCertificate? {
    if (!model.hasContinuous) return integerCertify(model, duals, observer = observer)
    val meter = sharedMeter ?: MixedCertificateMeter(cancellation)
    val initialWork = meter.work
    val certificate = try {
        meter.poll()
        val state = model.exactState ?: throw LpProjectionStop()
        meter.reserve(model.numVars.toLong() * 16L + model.m * 8L + 128L)
        meter.attempt(observer, "INTEGER") {
            integerCertifyUnchecked(model, duals, DEFAULT_SCALE_BITS, allowMixed = true, scan = meter::step)
        } ?: meter.attempt(observer, "ROUNDED") {
            mixedCertificate(model, state, duals, meter)
        } ?: meter.attempt(observer, "ENDPOINT_REPAIR") {
            repairMixedEndpoints(model, state, duals, meter)
        } ?: meter.attempt(observer, "RECONSTRUCTED") {
            reconstructedMixedCertificate(model, state, duals, meter)
        }
    } catch (_: LpProjectionStop) {
        null
    }
    val integralObjective = model.hasIntegralObjective()
    val rows = if (!integralObjective && (certificate != null || onAdditionalCertificate != null)) {
        try {
            meter.attempt(observer, "OBJECTIVE_INTEGRAL") { integralObjectiveRows(model, meter) }
        } catch (_: LpProjectionStop) {
            null
        }
    } else {
        null
    }
    val objectiveRows = rows ?: if (integralObjective) {
        try {
            meter.reserve(160L)
            IntArray(0)
        } catch (_: LpProjectionStop) {
            null
        }
    } else {
        null
    }
    val fixingCertificate = if ((eliminateContinuousObjective || onAdditionalCertificate != null) &&
        certificate != null && rows != null
    ) {
        try {
            meter.attempt(observer, "OBJECTIVE_ELIMINATION") {
                objectiveEliminationCertificate(model, certificate, rows.single(), meter)
            }
        } catch (_: LpProjectionStop) {
            null
        }
    } else {
        null
    }
    val objectiveCertificate = if (onAdditionalCertificate != null && (rows != null || integralObjective)) {
        try {
            meter.attempt(observer, "OBJECTIVE_EXPRESSION") {
                if (rows != null) objectiveExpressionCertificate(model, rows.single(), meter)
                else objectiveBoxCertificate(model, meter)
            }
        } catch (_: LpProjectionStop) {
            null
        }
    } else {
        null
    }
    val primary = if (onAdditionalCertificate == null) {
        fixingCertificate?.takeIf { it.exactObjectiveBound() >= checkNotNull(certificate).exactObjectiveBound() }
            ?: certificate
    } else {
        certificate
    }
    val alternative = if (fixingCertificate != null && fixingCertificate !== primary) {
        fixingCertificate
    } else if (primary != null && onAdditionalCertificate != null) {
        try {
            meter.attempt(observer, "FINITE_ENDPOINT_CANCEL") {
                finiteEndpointCancellation(model, primary, meter)
            } ?: objectiveCertificate?.let {
                meter.attempt(observer, "BLENDED") { blendedCertificate(model, primary, it, meter) }
            }
        } catch (_: LpProjectionStop) {
            null
        }
    } else {
        null
    }
    val candidates = listOfNotNull(primary, objectiveCertificate, alternative)
    var accepted = candidates.firstOrNull()
    for (candidate in candidates) {
        val selected = accepted ?: continue
        if (candidate === selected) continue
        try {
            meter.attempt(observer, "OBJECTIVE_SELECT") {
                if (meter.less(selected.exactObjectiveBound(), candidate.exactObjectiveBound())) accepted = candidate
                accepted
            }
        } catch (_: LpProjectionStop) {
            continue
        }
    }
    observer?.observe(LpCertifier.INTEGER, accepted != null, LpCertifierCost.Metered(meter.work - initialWork))
    if (cancellation()) return null
    for (candidate in candidates) if (candidate !== accepted) {
        onAdditionalCertificate?.invoke(
            if (objectiveRows != null) IntegralObjectiveCertificate(candidate, objectiveRows) else candidate,
        )
    }
    val selected = accepted ?: return null
    return if (objectiveRows != null) IntegralObjectiveCertificate(selected, objectiveRows) else selected
}

private class IntegralObjectiveCertificate(
    private val certificate: LpDeductionCertificate,
    private val rows: IntArray,
) : LpDeductionCertificate by certificate {
    override fun integralObjectiveRows(): IntArray = rows.copyOf()
}

private fun integralObjectiveRows(model: LpModel, meter: MixedCertificateMeter): IntArray? {
    val state = model.exactState ?: return null
    val source = state.model
    meter.reserve(model.numVars.toLong() * 8L)
    val costs = List(model.numVars) { meter.read(model.exactCost(it)) }
    if ((model.n until model.numVars).any { !costs[it].isZero }) return null
    var objective = -1
    for (column in 0 until model.n) {
        meter.step()
        if (!source.column(column).integral && !costs[column].isZero) {
            if (objective >= 0) return null
            objective = column
        }
    }
    if (objective < 0) return null
    val candidates = ArrayList<Pair<Int, BigFraction>>()
    model.forEachRationalColumn(objective) { row, coefficient ->
        meter.step()
        val slack = model.slackCol(row)
        val bounds = model.exactBounds(slack)
        val lower = bounds.lower
        val upper = bounds.upper
        if (!coefficient.isZero && lower != null && upper != null && !lower.strict && !upper.strict &&
            lower.premises == null && upper.premises == null &&
            lower.number.value == upper.number.value && slack < state.baseModel.numVars &&
            bounds == state.baseModel.column(slack).bounds
        ) {
            if (candidates.size == 8) throw LpProjectionStop()
            meter.reserve(64L)
            candidates.add(row to meter.read(coefficient))
        }
    }
    for ((row, coefficient) in candidates) {
        val multiplier = meter.multiply(costs[objective], coefficient.reciprocal())
        if (integralObjectiveAfterRow(model, costs, row, multiplier, meter)) return intArrayOf(row)
    }
    return null
}

private fun integralObjectiveAfterRow(
    model: LpModel,
    costs: List<BigFraction>,
    row: Int,
    multiplier: BigFraction,
    meter: MixedCertificateMeter,
): Boolean {
    val source = checkNotNull(model.exactState).model
    val scale = meter.read(source.objective.scale.value)
    val fixed = checkNotNull(model.exactBounds(model.slackCol(row)).lower).number.value
    var constant = meter.add(model.exactConstant(), meter.multiply(multiplier,
        meter.add(model.exactRhs(row), fixed.negated())))
    for (column in 0 until model.n) {
        var cost = costs[column]
        model.forEachRationalColumn(column) { r, value ->
            meter.step()
            if (r == row) cost = meter.add(cost, meter.multiply(multiplier, value).negated())
        }
        if (cost.isZero) continue
        if (!source.column(column).integral ||
            ExactLpNumber.of(meter.multiply(cost, scale.reciprocal())).exactLong() == null
        ) return false
        constant = meter.add(constant, meter.multiply(cost, model.exactShift(column)).negated())
    }
    constant = meter.add(meter.multiply(constant, scale.reciprocal()), source.objective.externalConstant.value)
    return ExactLpNumber.of(constant).exactLong() != null
}

private fun objectiveBoxCertificate(model: LpModel, meter: MixedCertificateMeter): LpDeductionCertificate? {
    val state = model.exactState ?: return null
    meter.reserve(model.numVars.toLong() * 16L + model.m * 24L + 512L)
    val zero = DoubleArray(model.m)
    return integerCertifyUnchecked(model, zero, DEFAULT_SCALE_BITS, allowMixed = true, scan = meter::step)
        ?: mixedCertificate(model, state, List(model.m) { BigFraction.ZERO }, meter)
}

private fun blendedCertificate(
    model: LpModel,
    primary: LpDeductionCertificate,
    objective: LpDeductionCertificate,
    meter: MixedCertificateMeter,
): LpDeductionCertificate? {
    val state = model.exactState ?: return null
    meter.reserve(model.m * 8L)
    val half = BigFraction.ofLong(2L).reciprocal()
    var different = false
    val y = List(model.m) { row ->
        val a = meter.multiplier(primary, row)
        val b = meter.multiplier(objective, row)
        if (a != b) different = true
        meter.multiply(meter.add(a, b), half)
    }
    return if (different) mixedCertificate(model, state, y, meter) else null
}

private fun objectiveExpressionCertificate(
    model: LpModel,
    row: Int,
    meter: MixedCertificateMeter,
): LpDeductionCertificate? {
    val state = model.exactState ?: return null
    var objective = -1
    for (column in 0 until model.n) {
        meter.step()
        if (!state.model.column(column).integral && !model.exactCost(column).isZero) {
            if (objective >= 0) return null
            objective = column
        }
    }
    if (objective < 0) return null
    var coefficient = BigFraction.ZERO
    model.forEachRationalColumn(objective) { r, value ->
        meter.step()
        if (r == row) coefficient = meter.read(value)
    }
    if (coefficient.isZero) return null
    val multiplier = meter.multiply(model.exactCost(objective), coefficient.reciprocal())
    integralObjectiveExpressionCertificate(model, row, multiplier, meter)?.let { return it }
    meter.reserve(model.m * 8L)
    val y = MutableList(model.m) { BigFraction.ZERO }
    y[row] = multiplier
    return mixedCertificate(model, state, y, meter)
}

private fun integralObjectiveExpressionCertificate(
    model: LpModel,
    row: Int,
    multiplier: BigFraction,
    meter: MixedCertificateMeter,
): LpDeductionCertificate? {
    if (ExactLpNumber.of(multiplier).exactLong() == null) return null
    meter.reserve(128L)
    val proposed = multiplier.toDouble()
    if (!proposed.isFinite() || BigFraction.ofDouble(proposed) != multiplier) return null
    meter.reserve(model.numVars.toLong() * 16L + model.m * 24L + 512L)
    val duals = DoubleArray(model.m)
    duals[row] = proposed
    val certificate = integerCertifyUnchecked(model, duals, DEFAULT_SCALE_BITS, allowMixed = true, scan = meter::step)
        ?: return null
    return certificate.takeIf { meter.read(it.exactMultiplier(row)) == multiplier }
}

private fun objectiveEliminationCertificate(
    model: LpModel,
    certificate: LpDeductionCertificate,
    row: Int,
    meter: MixedCertificateMeter,
): LpDeductionCertificate? {
    val state = model.exactState ?: return null
    var objective = -1
    for (column in 0 until model.n) {
        meter.step()
        if (!state.model.column(column).integral && !model.exactCost(column).isZero) {
            if (objective >= 0) return null
            objective = column
        }
    }
    if (objective < 0) return null
    meter.reserve(model.m * 8L)
    val y = MutableList(model.m) { meter.multiplier(certificate, it) }
    var residual = meter.read(model.exactCost(objective))
    var definingCoefficient = BigFraction.ZERO
    model.forEachRationalColumn(objective) { r, coefficient ->
        residual = meter.add(residual, meter.multiply(y[r], coefficient).negated())
        if (r == row) definingCoefficient = meter.read(coefficient)
    }
    if (residual.isZero || definingCoefficient.isZero) return null
    y[row] = meter.add(y[row], meter.multiply(residual, definingCoefficient.reciprocal()))
    return mixedCertificate(model, state, y, meter)
}

private fun mixedCertificate(
    model: LpModel,
    state: LpExactState,
    duals: DoubleArray,
    meter: MixedCertificateMeter,
): LpDeductionCertificate? {
    val rounded = roundDuals(model, duals) ?: return null
    val y = List(model.m) { row ->
        meter.read(BigFraction.ofLong(rounded.mult[row]) * BigFraction.ofLong(rounded.scale).reciprocal())
    }
    return mixedCertificate(model, state, y, meter)
}

private fun reconstructedMixedCertificate(
    model: LpModel,
    state: LpExactState,
    duals: DoubleArray,
    meter: MixedCertificateMeter,
): LpDeductionCertificate? {
    if (duals.size != model.m) return null
    meter.reserve(model.m * 8L)
    val y = List(model.m) { row ->
        val rational = reconstructRational(duals[row], onStep = meter::reconstructionStep) ?: return null
        meter.multiply(BigFraction.ofLong(rational.numerator), BigFraction.ofLong(rational.denominator).reciprocal())
    }
    return mixedCertificate(model, state, y, meter)
}

private fun finiteEndpointCancellation(
    model: LpModel,
    certificate: LpDeductionCertificate,
    meter: MixedCertificateMeter,
): LpDeductionCertificate? {
    val state = model.exactState ?: return null
    var first = -1
    for (column in 0 until model.n) {
        meter.step()
        if (state.model.column(column).integral) continue
        val sign = certificate.reducedCostSign(column)
        val bounds = model.exactBounds(column)
        if ((sign > 0 && bounds.lower != null) || (sign < 0 && bounds.upper != null)) {
            first = column
            break
        }
    }
    if (first < 0) return null
    meter.reserve(model.m * 8L + 128L)
    val y = MutableList(model.m) { meter.multiplier(certificate, it) }
    val counts = continuousRowCounts(model, state, meter)
    var attempts = 0
    for (column in first until model.n) {
        meter.step()
        if (state.model.column(column).integral) continue
        var residual = meter.read(model.exactCost(column))
        model.forEachRationalColumn(column) { row, coefficient ->
            meter.step()
            residual = meter.add(residual, meter.multiply(y[row], coefficient).negated())
        }
        if (residual.isZero) continue
        val bounds = model.exactBounds(column)
        val endpoint = if (residual.signum() > 0) bounds.lower else bounds.upper
        if (endpoint == null) continue
        for (entry in state.model.columnEntries(column)) {
            meter.step()
            if (!state.rows.row(entry.row).active || counts[entry.row] != 1 || entry.number.value.isZero) continue
            val inverse = meter.read(meter.read(entry.number.value).reciprocal())
            val proposed = meter.add(y[entry.row], meter.multiply(residual, inverse))
            val slack = model.slackCol(entry.row)
            val cost = meter.read(model.exactCost(slack))
            val sides = model.exactBounds(slack)
            if ((sides.lower == null && proposed < cost) || (sides.upper == null && proposed > cost)) continue
            if (attempts++ == 8) return null
            val previous = y[entry.row]
            y[entry.row] = proposed
            mixedCertificate(model, state, y, meter)?.let { return it }
            y[entry.row] = previous
        }
    }
    return null
}

private fun repairMixedEndpoints(
    model: LpModel,
    state: LpExactState,
    duals: DoubleArray,
    meter: MixedCertificateMeter,
): LpDeductionCertificate? {
    meter.reserve(model.m * 16L)
    val rounded = roundDuals(model, duals) ?: return null
    val inverseScale = meter.read(BigFraction.ofLong(rounded.scale).reciprocal())
    val y = MutableList(model.m) { meter.multiply(BigFraction.ofLong(rounded.mult[it]), inverseScale) }
    clampMixedMultipliers(model, y, meter)
    val continuousCounts = continuousRowCounts(model, state, meter)
    var repaired = false
    for (column in 0 until model.n) {
        meter.step()
        if (state.model.column(column).integral) continue
        val corrected = repairMixedColumn(model, y, continuousCounts, column, meter)
            ?: return meter.decline("MISSING_ENDPOINT")
        repaired = repaired || corrected
    }
    return if (repaired) mixedCertificate(model, state, y, meter) else null
}

private fun continuousRowCounts(model: LpModel, state: LpExactState, meter: MixedCertificateMeter): IntArray {
    meter.reserve(model.m * 4L)
    val counts = IntArray(model.m)
    for (column in 0 until model.n) {
        meter.step()
        if (state.model.column(column).integral) continue
        for (entry in state.model.columnEntries(column)) {
            meter.step()
            if (!entry.number.value.isZero && state.rows.row(entry.row).active) {
                counts[entry.row] = minOf(2, counts[entry.row] + 1)
            }
        }
    }
    return counts
}

private fun repairMixedColumn(
    model: LpModel,
    y: MutableList<BigFraction>,
    continuousCounts: IntArray,
    column: Int,
    meter: MixedCertificateMeter,
): Boolean? {
    val state = checkNotNull(model.exactState)
    var residual = meter.read(model.exactCost(column))
    model.forEachRationalColumn(column) { row, coefficient ->
        meter.step()
        residual = meter.add(residual, meter.multiply(y[row], coefficient).negated())
    }
    val bounds = model.exactBounds(column)
    if (!((residual.signum() > 0 && bounds.lower == null) ||
            (residual.signum() < 0 && bounds.upper == null))) return false
    for (entry in state.model.columnEntries(column)) {
        meter.step()
        if (!state.rows.row(entry.row).active || continuousCounts[entry.row] != 1 ||
            entry.number.value.isZero) continue
        val inverse = meter.read(meter.read(entry.number.value).reciprocal())
        val candidate = meter.add(y[entry.row], meter.multiply(residual, inverse))
        val slack = model.slackCol(entry.row)
        val slackBounds = model.exactBounds(slack)
        val slackCost = meter.read(model.exactCost(slack))
        if ((slackBounds.lower == null && candidate < slackCost) ||
            (slackBounds.upper == null && candidate > slackCost)) continue
        // A singleton continuous row cancels this residual without changing another real reduced cost.
        y[entry.row] = candidate
        return true
    }
    return null
}

private fun clampMixedMultipliers(model: LpModel, y: MutableList<BigFraction>, meter: MixedCertificateMeter) {
    for (row in y.indices) {
        meter.step()
        val slack = model.slackCol(row)
        val cost = meter.read(model.exactCost(slack))
        val bounds = model.exactBounds(slack)
        if ((bounds.lower == null && y[row] < cost) || (bounds.upper == null && y[row] > cost)) y[row] = cost
    }
}

private fun mixedCertificate(
    model: LpModel,
    state: LpExactState,
    multipliers: List<BigFraction>,
    meter: MixedCertificateMeter,
): LpDeductionCertificate? {
    val scale = meter.read(state.model.objective.scale.value)
    meter.reserve(model.m * 8L)
    val y = multipliers.toMutableList()
    clampMixedMultipliers(model, y, meter)
    var value = meter.read(model.exactConstant())
    for (row in y.indices) value = meter.add(value, meter.multiply(y[row], model.exactRhs(row)))
    val reduced = List(model.numVars) { column ->
        var cost = meter.read(model.exactCost(column))
        model.forEachRationalColumn(column) { row, coefficient ->
            cost = meter.add(cost, meter.multiply(y[row], coefficient).negated())
        }
        val bounds = model.exactBounds(column)
        val endpoint = when {
            cost.signum() > 0 -> bounds.lower ?: return meter.decline("MISSING_LOWER")
            cost.signum() < 0 -> bounds.upper ?: return meter.decline("MISSING_UPPER")
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
    override fun usesRationalArithmetic(): Boolean = true
    override fun belongsTo(model: LpModel, observer: LpCertificationObserver?): Boolean = model.exactState === authority
    override fun exactMultipliers(): List<BigFraction> = multipliers.toList()
    override fun exactMultiplier(row: Int): BigFraction = multipliers[row]
    override fun exactReducedCost(col: Int): BigFraction = reduced[col]
    override fun exactObjectiveBound(): BigFraction = bound
    override fun dualNonzeroRow(row: Int): Boolean = !multipliers[row].isZero
    override fun reducedCostSign(col: Int): Int = reduced[col].signum()
    override fun objectiveBoundCeil(extraConstant: Long): Long? = (bound + BigFraction.ofLong(extraConstant)).ceilLong()
    override fun improvingGapNonNegative(improvingMax: Long, sourceConstant: Long): Boolean =
        gap(improvingMax, sourceConstant) >= BigFraction.ZERO

    override fun fixStepsForGap(col: Int, gap: BigFraction): Long? =
        if (authority.model.column(col).integral) super.fixStepsForGap(col, gap) else null

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

internal class MixedCertificateMeter(private val cancellation: Cancellation) {
    private val meter = LpProjectionMeter(250_000L, 16L * 1024L * 1024L)
    private val stride = PollStride()
    private val meteredCancellation = cancellation.workMeter() != null
    private var reason = "UNSUPPORTED"
    val work: Long get() = meter.work
    fun poll() {
        if (cancellation()) {
            reason = "CANCELLED"
            throw LpProjectionStop()
        }
    }
    fun <T : Any> attempt(observer: LpCertificationObserver?, stage: String, body: () -> T?): T? {
        val timer = LpPhaseTimer(observer, LpSolvePhase.NODE_DEDUCTION)
        val initialWork = work
        reason = "UNSUPPORTED"
        return try {
            body().also { timer.finish("${stage}_${if (it != null) "COMPLETE" else reason}", work - initialWork) }
        } catch (stopped: LpProjectionStop) {
            timer.finish("${stage}_$reason", work - initialWork)
            throw stopped
        }
    }
    fun decline(reason: String): Nothing? {
        this.reason = reason
        return null
    }
    fun step() = charge(1L, 0L, matrix = true)
    fun reconstructionStep() = charge(8L, 96L, matrix = false)
    fun reserve(bytes: Long) = charge(bytes / 8L + 1L, bytes, matrix = false)

    private fun charge(work: Long, bytes: Long, matrix: Boolean) {
        if (meteredCancellation || stride.due(work)) poll()
        try {
            meter.reserve(work, bytes, matrix)
        } catch (stopped: LpProjectionStop) {
            reason = "RESOURCE_LIMIT"
            throw stopped
        }
    }

    fun read(value: BigFraction): BigFraction {
        val bits = maxOf(value.num.magnitudeBitLength(), value.den.magnitudeBitLength())
        if (bits > 128) {
            reason = "SCALAR_WIDTH"
            throw LpProjectionStop()
        }
        val words = (bits.toLong() + 63L) / 64L
        charge(1L + words * words, 64L + (bits.toLong() + 7L) / 4L, matrix = false)
        return value
    }

    fun less(left: BigFraction, right: BigFraction): Boolean {
        val a = read(left)
        val b = read(right)
        charge(16L, 128L, matrix = false)
        return a < b
    }

    fun multiplier(certificate: LpDeductionCertificate, row: Int): BigFraction {
        charge(64L, 512L, matrix = false)
        return read(certificate.exactMultiplier(row))
    }

    fun add(left: BigFraction, right: BigFraction): BigFraction = read(read(left) + read(right))
    fun multiply(left: BigFraction, right: BigFraction): BigFraction = read(read(left) * read(right))
}
