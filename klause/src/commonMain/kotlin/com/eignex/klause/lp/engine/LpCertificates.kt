package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigRationalConflict
import com.eignex.klause.simplex.exact.ExactSimplexBound
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.PollStride
import com.eignex.klause.util.WorkMeter
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.compareTo
import com.eignex.util.numbers.magnitudeBitLength
import com.eignex.util.numbers.plus
import com.eignex.util.numbers.shl
import com.eignex.util.numbers.toLongExact
import com.eignex.util.numbers.unaryMinus

internal fun certifyLpBound(
    model: LpModel,
    duals: DoubleArray,
    observer: LpCertificationObserver? = null,
    policy: LpCertificationPolicy = ProductionLpCertificationPolicy,
): CertifiedLpBound? {
    if (duals.size != model.m || !model.finiteExactInput()) return null
    if (!model.hasContinuous && model.probeClampedLo.none { it } && model.probeClampedHi.none { it }) {
        val certificate = policy.acceptNullable(LpCertifier.INTEGER, integerCertify(model, duals, observer = observer))
            ?: return null
        val numerator = certificate.objectiveNumerator()
        return CertifiedLpBound(
            BigFraction.of(
                (bigIntOf(numerator.hi) shl 64) + bigIntOf(numerator.lo.toULong()),
                BIG_ONE shl certificate.objectiveScaleBits,
            ),
            certificate,
        )
    }
    // The same integer-multiplier Lagrangian, evaluated against exact IEEE input without decimal guessing.
    val bound = rationalLpBound(model, duals)
    observer?.observe(LpCertifier.INTEGER, bound != null, LpScanCount(model).apply { scan() }.cost())
    return policy.acceptNullable(LpCertifier.INTEGER, bound)
}

private fun rationalLpBound(model: LpModel, duals: DoubleArray): CertifiedLpBound? {
    if (duals.size != model.m) return null
    val rounded = roundDuals(model, duals) ?: return null
    val scale = BigFraction.ofLong(rounded.scale).reciprocal()
    val y = List(model.m) { row -> BigFraction.ofLong(rounded.mult[row]) * scale }
    val value = exactLagrangian(model, y) ?: return null
    return CertifiedLpBound(value, support = model.exactSupport(y, objective = true), dualMultipliers = y)
}

internal fun LpModel.exactConflictSupport(conflict: BigRationalConflict): LpExactSupport? {
    val y = MutableList(m) { BigFraction.ZERO }
    for (i in conflict.rows.indices) y[conflict.rows[i]] -= conflict.multipliers[i]
    return exactSupport(y, objective = false)
}

private fun LpModel.exactSupport(multipliers: List<BigFraction>, objective: Boolean): LpExactSupport? {
    val state = exactState ?: return null
    val y = if (objective) objectiveMultipliers(multipliers) else multipliers
    val sides = ArrayList<LpExactCitedSide>()
    for (j in 0 until numVars) {
        var coefficient = if (objective) exactCost(j).negated() else BigFraction.ZERO
        forEachRationalColumn(j) { row, a -> coefficient += y[row] * a }
        if (coefficient.isZero) continue
        val upper = coefficient.signum() > 0
        val side = if (upper) exactBounds(j).upper else exactBounds(j).lower
        if (side != null) {
            val witness = state.activeSide(j, upper)?.takeIf { it.side == side }?.witness
            sides += LpExactCitedSide(j, upper, side, witness)
        }
    }
    val rows = (
        y.indices.filter {
            !y[it].isZero
        } + sides.filter { it.column >= n }.map { it.column - n }
        ).distinct().sorted()
    return LpExactSupport(state, rows.map { it to state.model.row(it) }, sides)
}

private fun LpModel.objectiveMultipliers(multipliers: List<BigFraction>): List<BigFraction> {
    var repaired: MutableList<BigFraction>? = null
    for (row in 0 until m) {
        val slack = slackCol(row)
        val candidate = multipliers[row]
        val cost = exactCost(slack)
        if ((!hasFiniteLower(slack) && candidate < cost) || (!hasFiniteUpper(slack) && candidate > cost)) {
            val target = repaired ?: multipliers.toMutableList().also { repaired = it }
            target[row] = cost
        }
    }
    return repaired ?: multipliers
}

internal fun exactLagrangian(
    model: LpModel,
    multipliers: List<BigFraction>,
    cancellation: ExecutionControl = ExecutionControl.Never,
): BigFraction? {
    if (cancellation() || multipliers.size != model.m || !model.finiteExactInput()) return null
    val stride = PollStride()
    val y = model.objectiveMultipliers(multipliers)
    var value = model.exactConstant()
    for (i in 0 until model.m) {
        if (stride.due() && cancellation()) return null
        value += y[i] * model.exactRhs(i)
    }
    for (j in 0 until model.numVars) {
        if (stride.due() && cancellation()) return null
        var reduced = model.exactCost(j)
        var stopped = false
        model.forEachRationalColumn(j) { row, a ->
            if (stride.due() && cancellation()) stopped = true
            if (!stopped) reduced -= y[row] * a
        }
        if (stopped) return null
        if (reduced.signum() > 0) {
            val side = model.exactBounds(j).lower ?: return null
            value += reduced * side.number.value
        }
        if (reduced.signum() < 0) {
            val side = model.exactBounds(j).upper ?: return null
            value += reduced * side.number.value
        }
    }
    return if (cancellation()) null else model.sourceObjective(value)
}

internal fun certifyLpFarkas(
    model: LpModel,
    candidate: DoubleArray,
    basis: Basis? = null,
    basisRow: Int = -1,
    onRoute: ((FarkasRoute) -> Unit)? = null,
    observer: LpCertificationObserver? = null,
    cancellation: ExecutionControl = ExecutionControl.Never,
    accounting: WorkMeter? = null,
): LongArray? {
    return observeLpClaim(LpClaim.CONTRADICTION, observer) { observation ->
        var route = FarkasRoute.NONE
        val scans = LpScanCount(model, accounting)
        val mechanismObserver = observation?.let { target ->
            object : LpCertificationObserver by target {
                override fun observe(certifier: LpCertifier, success: Boolean, cost: LpCertifierCost) {
                    if (certifier != LpCertifier.EXACT_FARKAS) target.observe(certifier, success, cost)
                }
            }
        }
        val ray = integerFarkasRay(
            model,
            candidate,
            basis = basis,
            basisRow = basisRow,
            onRoute = { route = it },
            observer = mechanismObserver,
            cancellation = cancellation,
            scans = scans,
        )?.takeIf {
            scans.scan()
            !cancellation() && sourceFarkasValid(model, it) && !cancellation()
        }
        observation?.observe(LpCertifier.EXACT_FARKAS, ray != null, scans.cost())
        onRoute?.invoke(if (ray != null) route else FarkasRoute.NONE)
        return@observeLpClaim ray
    }
}

internal fun exactStateConflict(model: LpModel, candidate: DoubleArray, scans: LpScanCount): BigRationalConflict? {
    if (candidate.size != model.m || candidate.any { !it.isFinite() }) return null
    val candidates = listOfNotNull(reconstructIntegerVector(candidate), roundDuals(model, candidate)?.mult)
    for (integers in candidates) {
        for (sign in listOf(BigFraction.ONE, BigFraction.MINUS_ONE)) {
            val y = integers.map { BigFraction.ofLong(it) * sign }
            scans.scan()
            val sides = ArrayList<ExactSimplexBound>()
            for (j in 0 until model.numVars) {
                var coefficient = BigFraction.ZERO
                model.forEachRationalColumn(j) { i, a -> coefficient += y[i] * a }
                if (!coefficient.isZero) sides += ExactSimplexBound(j, coefficient.signum() < 0)
            }
            val rows = y.indices.filter { !y[it].isZero }
            val conflict = BigRationalConflict(rows.toIntArray(), rows.map { y[it] }, sides)
            scans.scan()
            if (checkedLpConflict(model, conflict)) return conflict
        }
    }
    return null
}

internal fun sourceFarkasValid(model: LpModel, ray: LongArray): Boolean {
    if (ray.size != model.m || !model.finiteExactInput()) return false
    var surplus = BigFraction.ZERO
    var strict = false
    for (i in 0 until model.m) surplus += BigFraction.ofLong(ray[i]) * model.exactRhs(i)
    for (j in 0 until model.numVars) {
        var coefficient = BigFraction.ZERO
        model.forEachRationalColumn(j) { row, value -> coefficient += BigFraction.ofLong(ray[row]) * value }
        if (coefficient.signum() < 0) {
            val side = model.exactBounds(j).lower ?: return false
            surplus -= coefficient * side.number.value
            strict = strict || side.strict
        }
        if (coefficient.signum() > 0) {
            val side = model.exactBounds(j).upper ?: return false
            surplus -= coefficient * side.number.value
            strict = strict || side.strict
        }
    }
    return surplus.signum() > 0 || (surplus.isZero && strict)
}

internal fun checkedLpConflict(model: LpModel, conflict: BigRationalConflict): Boolean {
    if (!model.finiteExactInput() || conflict.rows.size != conflict.multipliers.size) return false
    val multipliers = MutableList(model.m) { BigFraction.ZERO }
    var rhs = BigFraction.ZERO
    var strict = false
    for (entry in conflict.rows.indices) {
        val row = conflict.rows[entry]
        if (row !in 0 until model.m) return false
        val multiplier = conflict.multipliers[entry]
        multipliers[row] += multiplier
        rhs += multiplier * model.exactRhs(row)
    }
    val lowerCited = BooleanArray(model.numVars)
    val upperCited = BooleanArray(model.numVars)
    for (bound in conflict.bounds) {
        if (bound.column !in 0 until model.numVars) return false
        if (bound.upper) upperCited[bound.column] = true else lowerCited[bound.column] = true
    }
    var minimum = BigFraction.ZERO
    for (j in 0 until model.numVars) {
        var coefficient = BigFraction.ZERO
        model.forEachRationalColumn(j) { row, a -> coefficient += multipliers[row] * a }
        if (coefficient.signum() < 0) {
            val side = model.exactBounds(j).upper ?: return false
            if (!upperCited[j]) return false
            minimum += coefficient * side.number.value
            strict = strict || side.strict
        } else if (coefficient.signum() > 0) {
            val side = model.exactBounds(j).lower ?: return false
            if (!lowerCited[j]) return false
            minimum += coefficient * side.number.value
            strict = strict || side.strict
        }
    }
    return rhs < minimum || (rhs == minimum && strict)
}

internal fun checkedLpWitness(
    model: LpModel,
    primal: List<BigFraction>,
    meter: RefinementMeter? = null,
): ExactLpWitness? {
    if (primal.size != model.n) return null
    meter?.charge(model.n.toLong() + model.m, 16L * (model.n.toLong() + model.m))
    if (!model.finiteExactInput()) return null
    fun add(a: BigFraction, b: BigFraction): BigFraction = meter?.pointAdd(a, b) ?: a + b
    fun multiply(a: BigFraction, b: BigFraction): BigFraction = meter?.pointMultiply(a, b) ?: a * b
    fun subtract(a: BigFraction, b: BigFraction): BigFraction =
        if (b.isZero) a else add(a, meter?.number(b.negated()) ?: b.negated())
    val shifted = primal.mapIndexed { j, value -> subtract(value, model.exactShift(j)) }
    for (j in shifted.indices) {
        meter?.charge()
        if (!model.withinExactBounds(j, shifted[j], meter)) return null
    }
    val activity = MutableList(model.m) { BigFraction.ZERO }
    for (j in 0 until model.n) {
        model.forEachRationalColumn(j) { row, a ->
            meter?.charge()
            activity[row] = add(activity[row], multiply(a, shifted[j]))
        }
    }
    var objective = model.exactConstant()
    for (j in 0 until model.n) {
        meter?.charge()
        objective = add(objective, multiply(model.exactCost(j), shifted[j]))
    }
    for (row in 0 until model.m) {
        meter?.charge()
        val slack = subtract(model.exactRhs(row), activity[row])
        val column = model.slackCol(row)
        if (!model.withinExactBounds(column, slack, meter) ||
            (model.exactRowStrict(row) && slack.isZero)
        ) {
            return null
        }
        objective = add(objective, multiply(model.exactCost(column), slack))
    }
    val source = model.exactState?.model?.objective
    val sourceObjective = if (meter != null && source != null) {
        add(
            multiply(objective, meter.number(meter.number(source.scale.value).reciprocal())),
            source.externalConstant.value,
        )
    } else {
        model.sourceObjective(objective)
    }
    meter?.charge(model.n.toLong(), model.n.toLong() * 8L)
    return ExactLpWitness(primal.toList(), sourceObjective)
}

internal fun checkedLpUnboundedness(
    model: LpModel,
    witness: ExactLpWitness,
    direction: DoubleArray,
): ExactLpUnboundedness? {
    if (direction.size != model.n || direction.any { !it.isFinite() }) return null
    val point = checkedLpWitness(model, witness.primal) ?: return null
    val state = model.exactState
    if (state != null && point.primal.indices.any {
            state.model.column(it).integral && point.primal[it].den != BIG_ONE
        }
    ) {
        return null
    }
    val ray = direction.map { checkNotNull(BigFraction.ofDouble(it)) }
    if (state != null && ray.indices.any {
            state.model.column(
                it,
            ).integral && ray[it].den != BIG_ONE
        }
    ) {
        return null
    }
    val slackRay = MutableList(model.m) { BigFraction.ZERO }
    var improvement = BigFraction.ZERO
    for (j in 0 until model.n) {
        if (model.exactBounds(j).lower != null && ray[j].signum() < 0) return null
        if (model.hasFiniteUpper(j) && !model.probeClampedHi[j] && ray[j].signum() > 0) return null
        model.forEachRationalColumn(j) { row, a -> slackRay[row] -= a * ray[j] }
        improvement += model.exactCost(j) * ray[j]
    }
    for (row in 0 until model.m) {
        if (model.hasFiniteLower(model.slackCol(row)) && slackRay[row].signum() < 0) return null
        if (model.hasFiniteUpper(model.slackCol(row)) && slackRay[row].signum() > 0) return null
        improvement += model.exactCost(model.slackCol(row)) * slackRay[row]
    }
    if (state != null) {
        for (row in slackRay.indices) {
            if (!state.model.column(model.n + row).integral) continue
            if (slackRay[row].den != BIG_ONE) return null
            var slackPoint = model.exactRhs(row)
            for (j in point.primal.indices) {
                model.forEachRationalColumn(j) { i, a ->
                    if (i == row) slackPoint -= a * (point.primal[j] - model.exactShift(j))
                }
            }
            if (slackPoint.den != BIG_ONE) return null
        }
    }
    return if (improvement.signum() < 0) ExactLpUnboundedness(point, ray) else null
}

internal fun LpModel.exactShift(j: Int): BigFraction = exactState?.model?.column(j)?.origin?.value
    ?: doubleView?.let { exactDouble(it.loShift[j]) }
    ?: BigFraction.ofLong(loShift[j])

internal fun LpModel.exactCost(j: Int): BigFraction = exactState?.model?.objective?.cost(j)?.value
    ?: doubleView?.let { exactDouble(it.cost[j]) }
    ?: BigFraction.ofLong(cost[j])

internal fun LpModel.exactUpper(j: Int): BigFraction = exactState?.model?.column(j)?.bounds?.upper?.number?.value
    ?: doubleView?.exactUpper(j)
    ?: BigFraction.ofLong(upper[j])

internal fun LpModel.exactRhs(i: Int): BigFraction = exactState?.model?.rhs(i)?.value
    ?: doubleView?.exactRhs(i)
    ?: BigFraction.ofLong(rhs[i])

internal fun LpModel.exactConstant(): BigFraction = exactState?.model?.objective?.constant?.value
    ?: doubleView?.exactObjConstant()
    ?: BigFraction.ofLong(objConstant)

internal fun LpModel.exactLower(j: Int): BigFraction = exactBounds(j).lower?.number?.value ?: BigFraction.ZERO

internal fun LpModel.exactBounds(j: Int): ExactLpBounds {
    val source = exactState?.model?.column(j)?.bounds
    val bounds = source ?: ExactLpBounds(
        if (j >= n || !probeClampedLo[j]) ExactLpSide(ExactLpNumber.of(0L)) else null,
        if (hasFiniteUpper(j) && (j >= n || !probeClampedHi[j])) ExactLpSide(ExactLpNumber.of(exactUpper(j))) else null,
    )
    return if (j >= n && exactRowStrict(j - n) && bounds.lower?.number?.value?.isZero == true) {
        bounds.copy(lower = bounds.lower.copy(strict = true))
    } else {
        bounds
    }
}

private fun LpModel.exactRowStrict(row: Int): Boolean = exactState?.model?.row(row)?.strict ?: rowStrict[row]

internal fun LpModel.withinExactBounds(j: Int, value: BigFraction, meter: RefinementMeter? = null): Boolean {
    val bounds = exactBounds(j)
    meter?.pointVisit(value)
    bounds.lower?.let {
        if ((meter?.pointCompare(value, it.number.value) ?: value.compareTo(it.number.value)) < 0 ||
            (it.strict && value == it.number.value)
        ) {
            return false
        }
    }
    bounds.upper?.let {
        if ((meter?.pointCompare(value, it.number.value) ?: value.compareTo(it.number.value)) > 0 ||
            (it.strict && value == it.number.value)
        ) {
            return false
        }
    }
    return true
}

internal fun LpModel.sourceObjective(value: BigFraction): BigFraction = exactState?.model?.objective?.let {
    value * it.scale.value.reciprocal() + it.externalConstant.value
} ?: value

internal fun LpModel.finiteExactInput(): Boolean {
    if (exactState != null) return exactState.model.n == n && exactState.model.m == m
    if (n < 0 || m < 0 || n > Int.MAX_VALUE - m) return false
    if (colContinuous.size != n || probeClampedLo.size != n || probeClampedHi.size != n ||
        rowStrict.size != m || rowGlobal.size != m || rowPremises.size != m || tag.size != n || flippedRhs.size != m
    ) {
        return false
    }
    if (rowPremises.any {
            it != null && (
                it.vars.size != it.isUpper.size || it.vars.size != it.thresholds.size ||
                    it.vars.any { variable -> variable < 0 } || it.boolLits.any { literal -> literal < 0 }
                )
        }
    ) {
        return false
    }
    val dv = doubleView
    val pointers = dv?.colPtr ?: csc.colPtr
    val rows = dv?.rowIdx ?: csc.rowIdx
    val count = dv?.colVal?.size ?: csc.colVal.size
    if (pointers.size != n + 1 || pointers[0] != 0 || pointers[n] != count || rows.size != count) return false
    for (j in 0 until n) {
        if (pointers[j] > pointers[j + 1] || pointers[j] < 0 || pointers[j + 1] > count) return false
        var previous = -1
        for (entry in pointers[j] until pointers[j + 1]) {
            if (rows[entry] !in 0 until m || rows[entry] <= previous) return false
            previous = rows[entry]
        }
    }
    if (dv == null) {
        return rhs.size == m && cost.size == numVars && upper.size == numVars &&
            hasUpper.size == numVars && loShift.size == n && upper.indices.all { !hasUpper[it] || upper[it] >= 0L }
    }
    if (dv.inexactCoefficients) return false
    return dv.rhs.size == m && dv.cost.size == numVars && dv.upper.size == numVars &&
        dv.hasUpper.size == numVars && dv.loShift.size == n &&
        dv.colVal.all { it.isFinite() } && dv.rhs.all { it.isFinite() } && dv.cost.all { it.isFinite() } &&
        dv.loShift.all { it.isFinite() } && dv.objConstant.isFinite() &&
        dv.upper.indices.all { !dv.hasUpper[it] || (dv.upper[it].isFinite() && dv.upper[it] >= 0.0) }
}

internal fun exactDouble(value: Double): BigFraction = checkNotNull(BigFraction.ofDouble(value))

internal inline fun LpModel.forEachRationalColumn(j: Int, action: (Int, BigFraction) -> Unit) {
    if (j >= n) {
        action(j - n, BigFraction.ONE)
    } else {
        val source = exactState?.model
        if (source != null) {
            for (entry in source.columnEntries(j)) action(entry.row, entry.number.value)
            return
        }
        val dv = doubleView
        if (dv == null) {
            forEachInColumn(j) { row, a -> action(row, BigFraction.ofLong(a)) }
        } else {
            for (p in dv.colPtr[j] until dv.colPtr[j + 1]) action(dv.rowIdx[p], exactDouble(dv.colVal[p]))
        }
    }
}

internal fun LpModel.hasIntegralObjective(): Boolean {
    if (!finiteExactInput()) return false
    exactState?.model?.let { source ->
        val scale = source.objective.scale.value.reciprocal()
        var constant = source.objective.constant.value * scale + source.objective.externalConstant.value
        for (j in 0 until numVars) {
            val c = source.objective.cost(j).value * scale
            if (c.isZero) continue
            if (j >= n || !source.column(j).integral || c.den != BIG_ONE) return false
            constant -= c * source.column(j).origin.value
        }
        return constant.den == BIG_ONE
    }
    if (doubleView == null) return cost.indices.all { cost[it] == 0L || (it < n && !colContinuous[it]) }
    var sourceConstant = exactConstant()
    for (j in 0 until numVars) {
        val c = exactCost(j)
        if (c.isZero) continue
        if (j >= n || colContinuous[j] || c.den != BIG_ONE) return false
        sourceConstant -= c * exactShift(j)
    }
    return sourceConstant.den == BIG_ONE
}

internal fun BigFraction.ceilLong(): Long? {
    val ceiling = ceilInteger()
    if (ceiling < bigIntOf(Long.MIN_VALUE) || ceiling > bigIntOf(Long.MAX_VALUE)) return null
    return ceiling.toLongExact()
}

internal fun BigFraction.floorLong(): Long? {
    val floor = -negated().ceilInteger()
    if (floor < bigIntOf(Long.MIN_VALUE) || floor > bigIntOf(Long.MAX_VALUE)) return null
    return floor.toLongExact()
}

// A bounded value snapshot prevents sibling, objective and premise changes from reusing counters.
internal class LpCounterResults {
    private var state: LpExactState? = null
    private var key: ByteArray? = null
    private var evidence: CertifiedLpResult? = null
    private var acceptedBy: LpCertificationPolicy? = null
    var storageDeclined: Boolean = false
        private set

    fun declineStorage() {
        storageDeclined = true
        state = null
        key = null
        evidence = null
        acceptedBy = null
    }

    fun read(model: LpModel, policy: LpCertificationPolicy): CertifiedLpResult? {
        val exact = model.exactState
        if (exact != null && state === exact && acceptedBy === policy &&
            policy === ProductionLpCertificationPolicy
        )
            return evidence
        val current = if (exact == null) keyOf(model) else null
        if (policy !== ProductionLpCertificationPolicy || current == null ||
            key?.contentEquals(current) != true || acceptedBy !== policy || state !== model.exactState
        ) {
            state = null
            key = null
            evidence = null
            acceptedBy = null
            return null
        }
        return evidence
    }

    fun remember(model: LpModel, result: CertifiedLpResult, policy: LpCertificationPolicy) {
        if (policy !== ProductionLpCertificationPolicy || (result.witness == null && result.bound == null)) return
        val exact = model.exactState
        val current = if (exact == null) keyOf(model) else null
        if ((exact == null && current == null) || (exact != null && !admitsPackage(model, result))) {
            declineStorage()
            return
        }
        storageDeclined = false
        state = model.exactState
        key = current
        // No float vectors, lazy model views, candidate rejections or infeasibility claims are cached.
        evidence = CertifiedLpResult(null, result.bound, result.witness, null, null, false, { null })
        acceptedBy = policy
    }

    private fun keyOf(model: LpModel): ByteArray? = exactLpStateKey(model).also { storageDeclined = it == null }

    private fun admitsPackage(model: LpModel, result: CertifiedLpResult): Boolean {
        // Immutable state identity already covers all source rows, premises, bounds and objective authority.
        // Reserve the retained vectors and support separately from that shared authority.
        val units = model.m.toLong() * 2L + model.numVars.toLong() * 4L + (result.witness?.primal?.size ?: 0)
        if (units > MAX_COUNTER_KEY_VALUES) return false
        var bytes = units * 8L
        fun admits(value: BigFraction): Boolean {
            bytes += (value.num.magnitudeBitLength().toLong() + value.den.magnitudeBitLength() + 7L) / 8L
            return bytes <= 64L * 1024L
        }
        if (result.witness?.let { point -> !admits(point.objective) || point.primal.any { !admits(it) } } == true) {
            return false
        }
        return result.bound?.let { bound ->
            admits(bound.value) && bound.dualMultipliers?.all(::admits) != false
        } != false
    }
}

internal fun exactLpStateKey(model: LpModel): ByteArray? {
    model.exactState?.let { return LpExactCapture.stateKey(it) }
    var size = model.n.toLong() * 16 + model.m.toLong() * 16 + model.csc.colVal.size.toLong() * 3
    model.doubleView?.let { size += it.colVal.size.toLong() * 3 }
    for (premises in model.rowPremises) {
        if (premises != null) {
            size += premises.vars.size.toLong() * 3 + premises.boolLits.size
        }
    }
    if (size > MAX_COUNTER_KEY_VALUES) return null
    val captured = LpCapturedModel.captureOrNull(model) ?: return null
    return LpCapture(LP_CAPTURE_VERSION, LpReplaySettings("exact-counter", 0L), captured, emptyList()).encode()
}

internal fun exactLpStateKey(model: ExactLpModel): ByteArray? {
    if (model.keySize > MAX_COUNTER_KEY_VALUES) return null
    val state = LpExactState(model)
    if (state.toWorkingModel() == null) return null
    return LpExactCapture.stateKey(state)
}

private const val MAX_COUNTER_KEY_VALUES = 4096L
