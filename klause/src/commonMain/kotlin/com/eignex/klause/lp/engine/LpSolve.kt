package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.BigRationalConflict
import com.eignex.klause.simplex.exact.RationalFeasibility
import com.eignex.klause.simplex.exact.rationalOutcome
import com.eignex.klause.util.Cancellation
import com.ionspin.kotlin.bignum.integer.BigInteger

// Bounds and witnesses remain independently available even when neither proves attainment.
internal enum class LpVerdict { CERTIFIED_BOUND, FEASIBLE, ATTAINED_OPTIMUM, INFEASIBLE, UNBOUNDED, INDETERMINATE }

internal class ExactLpWitness(val primal: List<BigFraction>, val objective: BigFraction)

internal class ExactLpUnboundedness(val witness: ExactLpWitness, val direction: List<BigFraction>)

internal class CertifiedLpBound(val value: BigFraction, val certificate: IntegerCertificate? = null)

// Exact evidence uses original coordinates and minimized objective units.
internal class CertifiedLpResult(
    val float: FloatLpResult?,
    val bound: CertifiedLpBound?,
    val witness: ExactLpWitness?,
    val farkasRay: LongArray?,
    val rationalConflict: BigRationalConflict?,
    private val integralObjective: Boolean,
    private val safeBound: () -> Double?,
    val unboundedness: ExactLpUnboundedness? = null,
) {
    val verdict: LpVerdict = when {
        farkasRay != null || rationalConflict != null -> LpVerdict.INFEASIBLE
        unboundedness != null -> LpVerdict.UNBOUNDED
        witness != null && bound?.value == witness.objective -> LpVerdict.ATTAINED_OPTIMUM
        witness != null -> LpVerdict.FEASIBLE
        bound != null -> LpVerdict.CERTIFIED_BOUND
        else -> LpVerdict.INDETERMINATE
    }
    val infeasibleRows: IntArray? get() = rationalConflict?.rows
    val certificate: IntegerCertificate? get() = bound?.certificate
    val lowerBound: BigFraction? get() = bound?.value
    val exactPrimal: List<BigFraction>? get() = witness?.primal

    // This ceiling concerns the integer source objective, not attainment of the continuous relaxation.
    val integerObjectiveLowerBound: Long? get() = if (integralObjective) lowerBound?.ceilLong() else null
    val safeLowerBound: Double? by lazy(safeBound)
}

// Float termination hints never determine the proof strength.
internal fun solveAndCertify(
    model: LpModel,
    warm: Basis? = null,
    cancellation: Cancellation = Cancellation.Never,
    componentSplit: Boolean = true,
    observer: LpCertificationObserver? = null,
    context: LpSolveContext = LpSolveContext.Production,
    counterResults: LpCounterResults? = null,
): CertifiedLpResult = newLpSolver(model, cancellation, componentSplit, context.engineFactory).use { solver ->
    val result = try {
        solver.solve(warm)
    } finally {
        observer?.observeSolve(solver.lastMetrics, solver is ComponentLpSolverCapability)
    }
    certifyLpResult(model, solver, result, cancellation, observer, context.certificationPolicy, counterResults)
}

internal fun certifyLpResult(
    model: LpModel,
    solver: LpSolver,
    result: FloatLpResult?,
    cancellation: Cancellation = Cancellation.Never,
    observer: LpCertificationObserver? = null,
    policy: LpCertificationPolicy = ProductionLpCertificationPolicy,
    counterResults: LpCounterResults? = null,
): CertifiedLpResult {
    if (!model.finiteExactInput()) return CertifiedLpResult(null, null, null, null, null, false, { null })
    val remembered = counterResults?.read(model, policy)
    val component = solver as? ComponentLpSolverCapability
    var bound = result?.let { certifyLpBound(model, it.duals, observer, policy) }
        ?: result?.let { component?.exactBound(observer, policy) }
        ?: remembered?.bound
    var witness = remembered?.witness
    var ray: LongArray? = null
    var conflict: BigRationalConflict? = null
    if (result != null && witness == null) {
        witness = component?.exactWitness(observer, policy)
            ?: policy.acceptNullable(LpCertifier.EXACT_BASIS, exactBasisWitness(model, result.basis, observer))
            ?: policy.acceptNullable(LpCertifier.EXACT_POINT, exactPointWitness(model, result.primal, observer))
    }
    // An independently checked point refutes infeasibility; rejecting a ray candidate does not.
    if (result == null && witness == null) {
        ray = solver.infeasibleRay?.let {
            policy.acceptNullable(
                LpCertifier.EXACT_FARKAS,
                certifyLpFarkas(
                    model, it, basis = solver.infeasibleBasis, basisRow = solver.infeasibleRow, observer = observer,
                ),
            )
        }
    }
    // Retain the migration fallback; this is feasibility recovery, not an optimization solve.
    if (witness == null && ray == null && (result == null || model.hasContinuous)) {
        val outcome = rationalOutcome(model, cancellation)
        observer?.observe(LpCertifier.RATIONAL, outcome.feasibility != RationalFeasibility.UNKNOWN)
        val accepted = policy.acceptNullable(
            LpCertifier.RATIONAL, outcome.takeIf { it.feasibility != RationalFeasibility.UNKNOWN },
        )
        if (accepted?.feasibility == RationalFeasibility.FEASIBLE) {
            witness = accepted.exactWitness?.let { shifted ->
                checkedLpWitness(model, shifted.mapIndexed { j, value -> value + model.exactShift(j) })
            }
        } else if (accepted?.feasibility == RationalFeasibility.INFEASIBLE) {
            conflict = accepted.conflict?.takeIf { checkedLpConflict(model, it) }
        }
    }
    if (result != null && witness != null && bound?.value != witness.objective && !cancellation()) {
        val reconstructed = reconstructedLpBound(model, result.duals, cancellation)
        observer?.observe(LpCertifier.RATIONAL, reconstructed != null)
        val accepted = policy.acceptNullable(LpCertifier.RATIONAL, reconstructed)
        if (accepted != null && (bound == null || accepted > bound.value)) bound = CertifiedLpBound(accepted)
    }
    if (ray != null || conflict != null) bound = null
    val unboundedness = if (bound == null && witness != null) {
        solver.recessionDirection?.let { direction ->
            policy.acceptNullable(LpCertifier.EXACT_POINT, checkedLpUnboundedness(model, witness, direction))
        }
    } else null
    val certified = CertifiedLpResult(
        result, bound, witness, ray, conflict, model.hasIntegralObjective(),
        // Freeze the value before mutable objective/bound arrays can change; evaluation is opt-in.
        safeBound = { null },
    )
    counterResults?.remember(model, certified, policy)
    return CertifiedLpResult(
        result, bound, witness, ray, conflict, model.hasIntegralObjective(),
        safeBound = result?.let {
            val snapshot = LpCapturedModel.capture(model).toModel()
            val duals = it.duals.copyOf()
            val compute: () -> Double? = {
                policy.acceptNullable(LpCertifier.SAFE_OBJECTIVE, safeObjectiveLowerBound(snapshot, duals))
            }
            compute
        } ?: { null },
        unboundedness = unboundedness,
    )
}

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
                (BigInteger.fromLong(numerator.hi) shl 64) + BigInteger.fromULong(numerator.lo.toULong()),
                BigInteger.ONE shl certificate.objectiveScaleBits,
            ),
            certificate,
        )
    }
    // The same integer-multiplier Lagrangian, evaluated against exact IEEE input without decimal guessing.
    val bound = rationalLpBound(model, duals)
    observer?.observe(LpCertifier.INTEGER, bound != null)
    return policy.acceptNullable(LpCertifier.INTEGER, bound?.let { CertifiedLpBound(it) })
}

private fun rationalLpBound(model: LpModel, duals: DoubleArray): BigFraction? {
    if (duals.size != model.m) return null
    val rounded = roundDuals(model, duals) ?: return null
    val scale = BigFraction.ofLong(rounded.scale).reciprocal()
    val y = List(model.m) { row -> BigFraction.ofLong(rounded.mult[row]) * scale }
    return exactLagrangian(model, y)
}

private fun reconstructedLpBound(model: LpModel, duals: DoubleArray, cancellation: Cancellation): BigFraction? {
    if (duals.size != model.m) return null
    val multipliers = ArrayList<BigFraction>(model.m)
    for (dual in duals) {
        if (cancellation()) return null
        val part = reconstructRational(dual) ?: return null
        multipliers += BigFraction.of(BigInteger.fromLong(part.numerator), BigInteger.fromLong(part.denominator))
    }
    return exactLagrangian(model, multipliers, cancellation)
}

private fun exactLagrangian(
    model: LpModel,
    multipliers: List<BigFraction>,
    cancellation: Cancellation = Cancellation.Never,
): BigFraction? {
    val y = List(model.m) { row ->
        val candidate = multipliers[row]
        val slackCost = model.exactCost(model.slackCol(row))
        if (!model.hasFiniteUpper(model.slackCol(row)) && candidate > slackCost) slackCost else candidate
    }
    var value = model.exactConstant()
    for (i in 0 until model.m) value += y[i] * model.exactRhs(i)
    for (j in 0 until model.numVars) {
        if (cancellation()) return null
        var reduced = model.exactCost(j)
        model.forEachRationalColumn(j) { row, a -> reduced -= y[row] * a }
        if (reduced.signum() > 0 && j < model.n && model.probeClampedLo[j]) return null
        if (reduced.signum() < 0) {
            if (!model.hasFiniteUpper(j) || (j < model.n && model.probeClampedHi[j])) return null
            value += reduced * model.exactUpper(j)
        }
    }
    return value
}

internal fun certifyLpFarkas(
    model: LpModel,
    candidate: DoubleArray,
    basis: Basis? = null,
    basisRow: Int = -1,
    onRoute: ((FarkasRoute) -> Unit)? = null,
    observer: LpCertificationObserver? = null,
): LongArray? {
    var route = FarkasRoute.NONE
    val mechanismObserver = observer?.let { target ->
        object : LpCertificationObserver by target {
            override fun observe(certifier: LpCertifier, success: Boolean) {
                if (certifier != LpCertifier.EXACT_FARKAS) target.observe(certifier, success)
            }
        }
    }
    val ray = integerFarkasRay(
        model, candidate, basis = basis, basisRow = basisRow, onRoute = { route = it }, observer = mechanismObserver,
    )?.takeIf { sourceFarkasValid(model, it) }
    observer?.observe(LpCertifier.EXACT_FARKAS, ray != null)
    onRoute?.invoke(if (ray != null) route else FarkasRoute.NONE)
    return ray
}

private fun sourceFarkasValid(model: LpModel, ray: LongArray): Boolean {
    if (!model.hasContinuous && model.probeClampedLo.none { it }) return true
    var surplus = BigFraction.ZERO
    for (i in 0 until model.m) surplus += BigFraction.ofLong(ray[i]) * model.exactRhs(i)
    for (j in 0 until model.numVars) {
        var coefficient = BigFraction.ZERO
        model.forEachRationalColumn(j) { row, value -> coefficient += BigFraction.ofLong(ray[row]) * value }
        if (coefficient.signum() < 0 && j < model.n && model.probeClampedLo[j]) return false
        if (coefficient.signum() > 0) {
            if (!model.hasFiniteUpper(j) || (j < model.n && model.probeClampedHi[j])) return false
            surplus -= coefficient * model.exactUpper(j)
        }
    }
    return surplus.signum() > 0
}

internal fun checkedLpConflict(model: LpModel, conflict: BigRationalConflict): Boolean {
    if (conflict.rows.size != conflict.multipliers.size) return false
    val multipliers = MutableList(model.m) { BigFraction.ZERO }
    var rhs = BigFraction.ZERO
    var strict = BigFraction.ZERO
    for (entry in conflict.rows.indices) {
        val row = conflict.rows[entry]
        if (row !in 0 until model.m) return false
        val multiplier = conflict.multipliers[entry]
        multipliers[row] += multiplier
        rhs += multiplier * model.exactRhs(row)
        if (model.rowStrict[row]) strict -= multiplier
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
            if (!upperCited[j] || !model.hasFiniteUpper(j) || (j < model.n && model.probeClampedHi[j])) return false
            minimum += coefficient * model.exactUpper(j)
        } else if (coefficient.signum() > 0) {
            if (!lowerCited[j] || (j < model.n && model.probeClampedLo[j])) return false
        }
    }
    return rhs < minimum || (rhs == minimum && strict.signum() < 0)
}

internal fun checkedLpWitness(model: LpModel, primal: List<BigFraction>): ExactLpWitness? {
    if (primal.size != model.n || !model.finiteExactInput()) return null
    val shifted = primal.mapIndexed { j, value -> value - model.exactShift(j) }
    val activity = MutableList(model.m) { BigFraction.ZERO }
    for (j in 0 until model.n) {
        if (!model.probeClampedLo[j] && shifted[j].signum() < 0) return null
        if (model.hasFiniteUpper(j) && !model.probeClampedHi[j] && shifted[j] > model.exactUpper(j)) return null
        model.forEachRationalColumn(j) { row, a -> activity[row] += a * shifted[j] }
    }
    var objective = model.exactConstant()
    for (j in 0 until model.n) objective += model.exactCost(j) * shifted[j]
    for (row in 0 until model.m) {
        val slack = model.exactRhs(row) - activity[row]
        if (slack.signum() < 0 || (model.rowStrict[row] && slack.isZero)) return null
        val column = model.slackCol(row)
        if (model.hasFiniteUpper(column) && slack > model.exactUpper(column)) return null
        objective += model.exactCost(column) * slack
    }
    return ExactLpWitness(primal.toList(), objective)
}

internal fun checkedLpUnboundedness(
    model: LpModel,
    witness: ExactLpWitness,
    direction: DoubleArray,
): ExactLpUnboundedness? {
    if (direction.size != model.n || direction.any { !it.isFinite() }) return null
    val point = checkedLpWitness(model, witness.primal) ?: return null
    val ray = direction.map { checkNotNull(BigFraction.ofDouble(it)) }
    val slackRay = MutableList(model.m) { BigFraction.ZERO }
    var improvement = BigFraction.ZERO
    for (j in 0 until model.n) {
        if (!model.probeClampedLo[j] && ray[j].signum() < 0) return null
        if (model.hasFiniteUpper(j) && !model.probeClampedHi[j] && ray[j].signum() > 0) return null
        model.forEachRationalColumn(j) { row, a -> slackRay[row] -= a * ray[j] }
        improvement += model.exactCost(j) * ray[j]
    }
    for (row in 0 until model.m) {
        if (slackRay[row].signum() < 0) return null
        if (model.hasFiniteUpper(model.slackCol(row)) && !slackRay[row].isZero) return null
        improvement += model.exactCost(model.slackCol(row)) * slackRay[row]
    }
    return if (improvement.signum() < 0) ExactLpUnboundedness(point, ray) else null
}

internal fun LpModel.exactShift(j: Int): BigFraction = doubleView?.let { exactDouble(it.loShift[j]) }
    ?: BigFraction.ofLong(loShift[j])

internal fun LpModel.exactCost(j: Int): BigFraction = doubleView?.let { exactDouble(it.cost[j]) }
    ?: BigFraction.ofLong(cost[j])

internal fun LpModel.exactUpper(j: Int): BigFraction = doubleView?.let { exactDouble(it.upper[j]) }
    ?: BigFraction.ofLong(upper[j])

private fun LpModel.exactRhs(i: Int): BigFraction = doubleView?.let { exactDouble(it.rhs[i]) }
    ?: BigFraction.ofLong(rhs[i])

internal fun LpModel.exactConstant(): BigFraction = doubleView?.let { exactDouble(it.objConstant) }
    ?: BigFraction.ofLong(objConstant)

private fun LpModel.finiteExactInput(): Boolean {
    val dv = doubleView ?: return true
    return dv.colVal.all { it.isFinite() } && dv.rhs.all { it.isFinite() } && dv.cost.all { it.isFinite() } &&
        dv.loShift.all { it.isFinite() } && dv.objConstant.isFinite() &&
        dv.upper.indices.all { !dv.hasUpper[it] || dv.upper[it].isFinite() }
}

private fun exactDouble(value: Double): BigFraction = checkNotNull(BigFraction.ofDouble(value))

private inline fun LpModel.forEachRationalColumn(j: Int, action: (Int, BigFraction) -> Unit) {
    if (j >= n) {
        action(j - n, BigFraction.ONE)
    } else {
        val dv = doubleView
        if (dv == null) forEachInColumn(j) { row, a -> action(row, BigFraction.ofLong(a)) }
        else for (p in dv.colPtr[j] until dv.colPtr[j + 1]) action(dv.rowIdx[p], exactDouble(dv.colVal[p]))
    }
}

private fun LpModel.hasIntegralObjective(): Boolean {
    if (exactConstant().den != BigInteger.ONE) return false
    for (j in 0 until numVars) {
        val c = exactCost(j)
        if (c.isZero) continue
        if (j >= n || colContinuous[j] || c.den != BigInteger.ONE) return false
    }
    return true
}

private fun BigFraction.ceilLong(): Long? {
    var ceiling = num / den
    if (num.signum() > 0 && !(num % den).isZero()) ceiling += BigInteger.ONE
    if (ceiling < BigInteger.fromLong(Long.MIN_VALUE) || ceiling > BigInteger.fromLong(Long.MAX_VALUE)) return null
    return ceiling.longValue(exactRequired = true)
}

// A bounded value snapshot prevents sibling, objective and premise changes from reusing counters.
internal class LpCounterResults {
    private var key: ByteArray? = null
    private var evidence: CertifiedLpResult? = null
    private var acceptedBy: LpCertificationPolicy? = null
    var storageDeclined: Boolean = false
        private set

    fun read(model: LpModel, policy: LpCertificationPolicy): CertifiedLpResult? {
        val current = keyOf(model)
        if (policy !== ProductionLpCertificationPolicy || current == null || key?.contentEquals(current) != true || acceptedBy !== policy) {
            key = null
            evidence = null
            return null
        }
        return evidence
    }

    fun remember(model: LpModel, result: CertifiedLpResult, policy: LpCertificationPolicy) {
        if (policy !== ProductionLpCertificationPolicy || (result.witness == null && result.bound == null)) return
        val current = keyOf(model) ?: return
        key = current
        // No float vectors, lazy model views, candidate rejections or infeasibility claims are cached.
        evidence = CertifiedLpResult(null, result.bound, result.witness, null, null, false, { null })
        acceptedBy = policy
    }

    private fun keyOf(model: LpModel): ByteArray? = exactLpStateKey(model).also { storageDeclined = it == null }
}

internal fun exactLpStateKey(model: LpModel): ByteArray? {
    var size = model.n.toLong() * 16 + model.m.toLong() * 16 + model.csc.colVal.size.toLong() * 3
    model.doubleView?.let { size += it.colVal.size.toLong() * 3 }
    for (premises in model.rowPremises) if (premises != null) {
        size += premises.vars.size.toLong() * 3 + premises.boolLits.size
    }
    if (size > MAX_COUNTER_KEY_VALUES) return null
    return LpCapture.capture(model, LpReplaySettings("exact-counter", 0L), emptyList()).encode()
}

private const val MAX_COUNTER_KEY_VALUES = 4096L
