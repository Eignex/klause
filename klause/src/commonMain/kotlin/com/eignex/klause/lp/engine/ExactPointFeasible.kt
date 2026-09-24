package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.time.Duration

internal fun exactPointWitness(
    model: LpModel,
    primal: DoubleArray,
    observer: LpCertificationObserver? = null,
): ExactLpWitness? {
    val point = if (primal.size == model.n && primal.all { it.isFinite() } && model.finiteExactInput()) {
        checkedLpWitness(model, primal.map { checkNotNull(BigFraction.ofDouble(it)) }) ?: run {
            var common = BigInteger.ONE
            val limit = BigInteger.fromLong(MAX_POINT_DENOMINATOR)
            val candidate = primal.map { value ->
                val part = reconstructRational(value, maxDenominator = MAX_POINT_DENOMINATOR) ?: return@run null
                val denominator = BigInteger.fromLong(part.denominator)
                common = common / common.gcd(denominator) * denominator
                if (common > limit) return@run null
                BigFraction.of(BigInteger.fromLong(part.numerator), denominator)
            }
            checkedLpWitness(model, candidate)
        }
    } else {
        null
    }
    observer?.observe(LpCertifier.EXACT_POINT, point != null)
    return point
}

internal data class ExactPointRecovery(
    val witness: ExactLpWitness?,
    val checks: Int,
    val repairs: Int,
    val work: Long,
    val allocation: Long,
    val elapsed: Duration,
    val decline: LpRefinementDecline?,
)

internal fun recoverExactPointWitness(
    model: LpModel,
    primal: DoubleArray,
    request: LpRefinementRequest,
    cancellation: Cancellation,
    observer: LpCertificationObserver? = null,
): ExactPointRecovery {
    val meter = RefinementMeter(request.effectiveLimits(), request.pointCache, cancellation, perAttempt = true)
    var witness: ExactLpWitness? = null
    var checks = 0
    var repairs = 0
    var decline: LpRefinementDecline? = LpRefinementDecline.CANDIDATE
    lateinit var metrics: LpRefinementMetrics
    try {
        if (request.source.state !== model.exactState) meter.stop(LpRefinementDecline.AUTHORITY)
        meter.charge(model.n.toLong() + model.m)
        if (primal.size != model.n || primal.any { !it.isFinite() } || !model.finiteExactInput()) {
            meter.stop(LpRefinementDecline.CANDIDATE)
        }
        val candidate = ArrayList<BigFraction>(model.n)
        meter.charge(model.n.toLong(), model.n.toLong() * 8L)
        for (value in primal) {
            meter.charge(64L, 256L)
            val part = reconstructRational(value, maxDenominator = MAX_POINT_DENOMINATOR)
                ?: meter.stop(LpRefinementDecline.CANDIDATE)
            candidate += meter.number(
                BigFraction.of(
                    BigInteger.fromLong(part.numerator),
                    BigInteger.fromLong(part.denominator),
                ),
            )
        }
        val scan = scanPoint(model, candidate, meter)
        while (scan != null && scan.violations.isNotEmpty() && repairs < MAX_POINT_REPAIRS) {
            val correction = choosePointCorrection(model, scan, candidate, meter) ?: break
            candidate[correction.column] = meter.pointAdd(
                candidate[correction.column],
                correction.delta,
            )
            scan.shifted[correction.column] = meter.pointAdd(
                scan.shifted[correction.column],
                correction.delta,
            )
            model.forEachRationalColumn(correction.column) { row, coefficient ->
                meter.charge()
                val change = meter.pointMultiply(coefficient, correction.delta)
                scan.slacks[row] = meter.pointAdd(scan.slacks[row], meter.number(change.negated()))
            }
            scan.violations.removeAt(0)
            scan.violations.removeAll { violation ->
                meter.charge()
                model.withinExactBounds(
                    model.slackCol(violation.row),
                    scan.slacks[violation.row],
                    meter,
                )
            }
            repairs++
        }
        if (scan != null && scan.violations.isEmpty()) {
            checks++
            witness = checkedLpWitness(model, candidate, meter)
        }
        meter.poll()
        if (witness != null) decline = null
    } catch (stop: RefinementStop) {
        decline = stop.reason
        witness = null
    } finally {
        metrics = meter.finish(decline)
    }
    observer?.observe(LpCertifier.EXACT_POINT, witness != null)
    return ExactPointRecovery(
        witness,
        checks,
        repairs,
        metrics.work,
        metrics.allocation,
        metrics.elapsed,
        decline,
    )
}

private data class PointViolation(val row: Int, val slack: BigFraction, val target: BigFraction)
private data class PointScan(
    val shifted: MutableList<BigFraction>,
    val slacks: MutableList<BigFraction>,
    val violations: MutableList<PointViolation>,
)
private data class PointCorrection(val column: Int, val delta: BigFraction, val incidence: Int)

private fun scanPoint(model: LpModel, candidate: List<BigFraction>, meter: RefinementMeter): PointScan? {
    meter.charge(model.n.toLong() + model.m, 24L * (model.n.toLong() + model.m))
    val shifted = candidate.mapIndexed { j, value ->
        val origin = model.exactShift(j)
        if (origin.isZero) value else meter.pointAdd(value, meter.number(origin.negated()))
    }.toMutableList()
    val activity = MutableList(model.m) { BigFraction.ZERO }
    var entries = 0
    for (j in shifted.indices) {
        meter.charge()
        if (!model.withinExactBounds(j, shifted[j], meter)) return null
        model.forEachRationalColumn(j) { row, coefficient ->
            meter.charge()
            if (++entries > meter.limits.maxEntries) meter.stop(LpRefinementDecline.DIMENSION)
            activity[row] = meter.pointAdd(
                activity[row],
                meter.pointMultiply(coefficient, shifted[j]),
            )
        }
    }
    val slacks = ArrayList<BigFraction>(model.m)
    val violations = ArrayList<PointViolation>()
    for (row in 0 until model.m) {
        meter.charge()
        val slack = meter.pointAdd(model.exactRhs(row), meter.number(activity[row].negated()))
        slacks += slack
        val bounds = model.exactBounds(model.slackCol(row))
        val lower = bounds.lower
        val upper = bounds.upper
        val target = when {
            lower != null && (
                meter.pointCompare(slack, lower.number.value) < 0 ||
                    (lower.strict && slack == lower.number.value)
                ) -> lower

            upper != null && (
                meter.pointCompare(slack, upper.number.value) > 0 ||
                    (upper.strict && slack == upper.number.value)
                ) -> upper

            else -> null
        }
        if (target != null) {
            if (target.strict || model.exactState?.model?.row(row)?.strict == true) return null
            if (violations.size == MAX_POINT_REPAIRS) return null
            meter.charge(bytes = 48L)
            violations += PointViolation(row, slack, target.number.value)
        }
    }
    return PointScan(shifted, slacks, violations)
}

private fun choosePointCorrection(
    model: LpModel,
    scan: PointScan,
    candidate: List<BigFraction>,
    meter: RefinementMeter,
): PointCorrection? {
    val violation = scan.violations.first()
    var best: PointCorrection? = null
    var candidates = 0
    var entries = 0
    for (j in candidate.indices) {
        meter.charge()
        var coefficient: BigFraction? = null
        model.forEachRationalColumn(j) { row, value ->
            meter.charge()
            if (++entries > meter.limits.maxEntries) meter.stop(LpRefinementDecline.DIMENSION)
            if (row == violation.row) coefficient = value
        }
        val a = coefficient ?: continue
        if (a.isZero) continue
        if (++candidates > MAX_POINT_CANDIDATES) return null
        val bounds = model.exactBounds(j)
        val value = scan.shifted[j]
        if (bounds.lower?.let { meter.pointCompare(value, it.number.value) <= 0 } == true ||
            bounds.upper?.let { meter.pointCompare(value, it.number.value) >= 0 } == true
        ) {
            continue
        }
        val delta = meter.divide(
            meter.pointAdd(violation.slack, meter.number(violation.target.negated())),
            a,
        )
        val moved = meter.pointAdd(value, delta)
        if (!model.withinExactBounds(j, moved, meter)) continue
        var incidence = 0
        var admissible = true
        model.forEachRationalColumn(j) { row, valueAtRow ->
            meter.charge()
            incidence++
            val oldSlack = scan.slacks[row]
            val rowBounds = model.exactBounds(model.slackCol(row))
            if (row != violation.row &&
                (
                    oldSlack == rowBounds.lower?.number?.value ||
                        oldSlack == rowBounds.upper?.number?.value
                    )
            ) {
                admissible = false
            }
            if (admissible) {
                val movedSlack = meter.pointAdd(
                    oldSlack,
                    meter.number(meter.pointMultiply(valueAtRow, delta).negated()),
                )
                if (!model.withinExactBounds(model.slackCol(row), movedSlack, meter) ||
                    (model.exactState?.model?.row(row)?.strict == true && movedSlack.isZero)
                ) {
                    admissible = false
                }
            }
        }
        if (admissible && (best == null || incidence < best.incidence)) {
            best = PointCorrection(j, delta, incidence)
        }
    }
    return best
}

internal fun exactPointFeasible(
    model: LpModel,
    primal: DoubleArray,
    observer: LpCertificationObserver? = null,
): Boolean = exactPointWitness(model, primal, observer) != null

private const val MAX_POINT_DENOMINATOR = 1L shl 40
private const val MAX_POINT_REPAIRS = 4
private const val MAX_POINT_CANDIDATES = 32
