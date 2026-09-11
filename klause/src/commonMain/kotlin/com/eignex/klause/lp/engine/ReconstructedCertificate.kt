package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.BigRationalConflict
import com.eignex.klause.simplex.exact.ExactSimplexBound
import com.eignex.klause.simplex.exact.Frac128
import com.eignex.klause.simplex.exact.Frac128Ops
import com.eignex.klause.util.Cancellation
import com.ionspin.kotlin.bignum.integer.BigInteger

internal data class ReconstructionLimits(
    val maxCoordinates: Int = 4096,
    val maxEntries: Int = 65536,
    val maxBits: Int = 4096,
    val maxWork: Long = 250000L,
    val maxAllocation: Long = 16L * 1024L * 1024L,
    val maxAttempts: Int = 4,
) {
    init {
        require(maxCoordinates >= 0 && maxEntries >= 0 && maxBits >= 24)
        require(maxWork >= 0L && maxAllocation >= 0L && maxAttempts in 0..64)
    }
}

internal enum class ReconstructionDecline {
    INVALID_INPUT,
    NONFINITE,
    DIMENSION,
    BITS,
    WORK,
    ALLOCATION,
    CANCELLED,
    CANDIDATE,
}

internal enum class ReconstructionPhase { INPUT, POINT, DUAL, RAY, SUPPORT, VIOLATION, VECTOR, SCHEDULE }

internal data class ReconstructionMetrics(
    val attempts: Int,
    val pointChecks: Int,
    val dualChecks: Int,
    val rayChecks: Int,
    val pointSuccesses: Int,
    val dualSuccesses: Int,
    val raySuccesses: Int,
    val vectorRestarts: Int,
    val verificationRestarts: Int,
    val work: Long,
    val allocation: Long,
    val maxBits: Int,
    val decline: ReconstructionDecline?,
    val phase: ReconstructionPhase,
)

internal class ReconstructedCertificate(
    val witness: ExactLpWitness?,
    val bound: CertifiedLpBound?,
    val conflict: BigRationalConflict?,
    val conflictSupport: LpExactSupport?,
    val nonbasicStatusesMatch: Boolean,
    val complementary: Boolean,
    val metrics: ReconstructionMetrics,
)

internal class ReconstructionOverflow : RuntimeException()
private class ReconstructionStop(val reason: ReconstructionDecline) : RuntimeException()

internal class ReconstructionMeter(
    val limits: ReconstructionLimits = ReconstructionLimits(),
    private val cancellation: Cancellation = Cancellation.Never,
) {
    var phase = ReconstructionPhase.INPUT
    var attempts = 0
    var pointChecks = 0
    var dualChecks = 0
    var rayChecks = 0
    var pointSuccesses = 0
    var dualSuccesses = 0
    var raySuccesses = 0
    var vectorRestarts = 0
    var verificationRestarts = 0
    private var work = 0L
    private var allocation = 0L
    private var maxBits = 0

    fun step(units: Long = 1L) {
        if (cancellation()) throw ReconstructionStop(ReconstructionDecline.CANCELLED)
        if (units > limits.maxWork - work) throw ReconstructionStop(ReconstructionDecline.WORK)
        work += units
    }

    fun storage(bytes: Long) {
        step()
        if (bytes > limits.maxAllocation - allocation) throw ReconstructionStop(ReconstructionDecline.ALLOCATION)
        allocation += bytes
    }

    fun integer(value: BigInteger): BigInteger {
        step()
        val bits = value.bitLength()
        maxBits = maxOf(maxBits, bits)
        if (bits > limits.maxBits) throw ReconstructionStop(ReconstructionDecline.BITS)
        storage(32L + (bits.toLong() + 7L) / 8L)
        return value
    }

    fun fraction(value: BigFraction): BigFraction {
        integer(value.num)
        integer(value.den)
        return value
    }

    fun fixed(value: Frac128): Frac128 {
        val low = if (value.nHi < 0L) 0uL - value.nLo.toULong() else value.nLo.toULong()
        val high = if (value.nHi <
            0L
        ) {
            value.nHi.toULong().inv() + if (value.nLo == 0L) 1uL else 0uL
        } else {
            value.nHi.toULong()
        }
        val numerator = if (high == 0uL) 64 - low.countLeadingZeroBits() else 128 - high.countLeadingZeroBits()
        val denominator = if (value.dHi ==
            0L
        ) {
            64 - value.dLo.countLeadingZeroBits()
        } else {
            128 - value.dHi.countLeadingZeroBits()
        }
        val bits = maxOf(numerator, denominator)
        maxBits = maxOf(maxBits, bits)
        if (bits > limits.maxBits) throw ReconstructionStop(ReconstructionDecline.BITS)
        storage(48L)
        return value
    }

    fun snapshot(decline: ReconstructionDecline?) = ReconstructionMetrics(
        attempts, pointChecks, dualChecks, rayChecks, pointSuccesses, dualSuccesses, raySuccesses,
        vectorRestarts, verificationRestarts, work, allocation, maxBits, decline, phase,
    )
}

private class ReconstructionAuthority(val model: LpModel, val meter: ReconstructionMeter) {
    val n = model.n
    val m = model.m
    val size = model.numVars
    val matrix: List<List<Pair<Int, BigFraction>>>
    val bounds: List<ExactLpBounds>
    val costs: List<BigFraction>
    val rhs: List<BigFraction>
    val origins: List<BigFraction>
    val constant: BigFraction
    val scale: BigFraction
    val external: BigFraction

    init {
        meter.step()
        if (n < 0 || m < 0 || n.toLong() + m > meter.limits.maxCoordinates) {
            throw ReconstructionStop(ReconstructionDecline.DIMENSION)
        }
        meter.storage(size.toLong() * 96L)
        val captureSize = model.exactState?.model?.keySize
            ?: (model.doubleView?.colVal?.size ?: model.csc.colVal.size).toLong() * 3L
        meter.step(captureSize)
        meter.storage(captureSize * 8L)
        if (!model.finiteExactInput()) throw ReconstructionStop(ReconstructionDecline.INVALID_INPUT)
        var entries = 0
        matrix = List(size) { j ->
            meter.step()
            val column = ArrayList<Pair<Int, BigFraction>>()
            model.forEachRationalColumn(j) { row, value ->
                if (++entries > meter.limits.maxEntries) throw ReconstructionStop(ReconstructionDecline.DIMENSION)
                meter.storage(32L)
                column += row to meter.fraction(value)
            }
            column.toList()
        }
        bounds = List(size) { j ->
            model.exactBounds(j).also { b ->
                for (side in listOfNotNull(b.lower, b.upper)) {
                    meter.fraction(side.number.value)
                    chargePremises(side.premises)
                }
            }
        }
        for (i in 0 until m) {
            meter.step()
            chargePremises(model.exactState?.model?.row(i)?.premises)
        }
        costs = List(size) { meter.fraction(model.exactCost(it)) }
        rhs = List(m) { meter.fraction(model.exactRhs(it)) }
        origins = List(n) { meter.fraction(model.exactShift(it)) }
        constant = meter.fraction(model.exactConstant())
        scale = meter.fraction(model.exactState?.model?.objective?.scale?.value ?: BigFraction.ONE)
        external = meter.fraction(model.exactState?.model?.objective?.externalConstant?.value ?: BigFraction.ZERO)
    }

    private fun chargePremises(premises: ExactLpPremises?) {
        if (premises == null) return
        meter.step(premises.size)
        meter.storage(premises.size * 16L)
        for (bound in premises.boundEntries()) meter.fraction(bound.threshold.value)
    }

    fun support(y: List<BigFraction>, selected: List<ExactSimplexBound>): LpExactSupport? {
        meter.phase = ReconstructionPhase.SUPPORT
        val state = model.exactState ?: return null
        meter.storage((m.toLong() + selected.size) * 32L)
        val rows = BooleanArray(m)
        for (i in y.indices) {
            meter.step()
            rows[i] = !y[i].isZero
        }
        val sides = selected.map { cited ->
            meter.step()
            if (cited.column >= n) rows[cited.column - n] = true
            val side = requireNotNull(if (cited.upper) bounds[cited.column].upper else bounds[cited.column].lower)
            val witness = state.activeSide(cited.column, cited.upper)?.takeIf { it.side == side }?.witness
            LpExactCitedSide(cited.column, cited.upper, side, witness)
        }
        return LpExactSupport(state, rows.indices.filter { rows[it] }.map { it to state.model.row(it) }, sides)
    }
}

private class ReconstructedPoint(val witness: ExactLpWitness, val coordinates: List<BigFraction>)
private class ReconstructedDual(
    val value: BigFraction,
    val reduced: List<BigFraction>,
    val selected: List<ExactSimplexBound>,
    val strict: Boolean,
)

private class FixedReconstructionVerifier(
    private val a: ReconstructionAuthority,
    private val meter: ReconstructionMeter,
) {
    private val ops = Frac128Ops()

    private fun checked(value: Frac128): Frac128 {
        if (ops.overflowed()) throw ReconstructionOverflow()
        return meter.fixed(value)
    }

    private fun from(value: BigFraction): Frac128 {
        meter.fraction(value)
        if (value.num.bitLength() > 127 || value.den.bitLength() > 127) throw ReconstructionOverflow()
        val magnitude = value.num.abs()
        val low = magnitude.longValue(exactRequired = false)
        val high = (magnitude shr 64).longValue(exactRequired = false)
        val negative = value.signum() < 0
        return checked(
            Frac128(
                if (negative) high.inv() + if (low == 0L) 1L else 0L else high,
                if (negative) -low else low,
                (value.den shr 64).longValue(exactRequired = true),
                value.den.longValue(exactRequired = false),
            ),
        )
    }

    private fun big(value: Frac128): BigFraction = meter.fraction(
        BigFraction.of(
            (BigInteger.fromLong(value.nHi) shl 64) + BigInteger.fromULong(value.nLo.toULong()),
            (BigInteger.fromLong(value.dHi) shl 64) + BigInteger.fromULong(value.dLo.toULong()),
        ),
    )

    private fun plus(x: Frac128, y: Frac128): Frac128 = checked(ops.plus(x, y))
    private fun minus(x: Frac128, y: Frac128): Frac128 = checked(ops.minus(x, y))
    private fun times(x: Frac128, y: Frac128): Frac128 = checked(ops.times(x, y))
    private fun reciprocal(x: Frac128): Frac128 = checked(ops.reciprocal(x))
    private fun sign(x: Frac128): Int {
        meter.step()
        return ops.signum(x)
    }
    private fun compare(x: Frac128, y: Frac128): Int = sign(minus(x, y))

    fun point(primal: List<BigFraction>): ReconstructedPoint? {
        meter.storage(a.size.toLong() * 32L)
        val x = primal.map { from(it) }.toMutableList()
        val slack = a.rhs.map { from(it) }.toMutableList()
        for (j in 0 until a.n) {
            for ((row, coefficient) in a.matrix[j]) slack[row] = minus(slack[row], times(from(coefficient), x[j]))
        }
        x.addAll(slack)
        var value = from(a.constant)
        for (j in x.indices) {
            val bounds = a.bounds[j]
            bounds.lower?.let {
                val compare = compare(x[j], from(it.number.value))
                if (compare < 0 || (compare == 0 && it.strict)) return null
            }
            bounds.upper?.let {
                val compare = compare(x[j], from(it.number.value))
                if (compare > 0 || (compare == 0 && it.strict)) return null
            }
            value = plus(value, times(from(a.costs[j]), x[j]))
        }
        val sourceObjective = plus(times(value, reciprocal(from(a.scale))), from(a.external))
        val sourcePrimal = List(a.n) { big(plus(x[it], from(a.origins[it]))) }
        return ReconstructedPoint(ExactLpWitness(sourcePrimal, big(sourceObjective)), x.map { big(it) })
    }

    fun dual(multipliers: List<BigFraction>, ray: Boolean): ReconstructedDual? {
        meter.storage((a.m.toLong() + a.size) * 48L)
        val y = multipliers.map { from(it) }
        var value = if (ray) from(BigFraction.ZERO) else from(a.constant)
        for (i in y.indices) value = plus(value, times(y[i], from(a.rhs[i])))
        val reduced = ArrayList<BigFraction>(a.size)
        val selected = ArrayList<ExactSimplexBound>()
        var strict = false
        for (j in 0 until a.size) {
            var d = if (ray) from(BigFraction.ZERO) else from(a.costs[j])
            for ((row, coefficient) in a.matrix[j]) d = minus(d, times(y[row], from(coefficient)))
            val sign = sign(d)
            if (sign != 0) {
                val upper = sign < 0
                val side = (if (upper) a.bounds[j].upper else a.bounds[j].lower) ?: return null
                value = plus(value, times(d, from(side.number.value)))
                selected += ExactSimplexBound(j, upper)
                strict = strict || side.strict
            }
            reduced += big(d)
        }
        if (!ray) value = plus(times(value, reciprocal(from(a.scale))), from(a.external))
        return ReconstructedDual(big(value), reduced, selected.toList(), strict)
    }
}

private class BigReconstructionVerifier(
    private val a: ReconstructionAuthority,
    private val meter: ReconstructionMeter,
) {
    private fun from(value: BigFraction): BigFraction = meter.fraction(value)
    private fun big(value: BigFraction): BigFraction = meter.fraction(value)
    private fun plus(x: BigFraction, y: BigFraction): BigFraction = meter.fraction(x + y)
    private fun minus(x: BigFraction, y: BigFraction): BigFraction = meter.fraction(x - y)
    private fun times(x: BigFraction, y: BigFraction): BigFraction = meter.fraction(x * y)
    private fun reciprocal(x: BigFraction): BigFraction = meter.fraction(x.reciprocal())
    private fun sign(x: BigFraction): Int {
        meter.step()
        return x.signum()
    }
    private fun compare(x: BigFraction, y: BigFraction): Int = sign(minus(x, y))

    fun point(primal: List<BigFraction>): ReconstructedPoint? {
        meter.storage(a.size.toLong() * 32L)
        val x = primal.map { from(it) }.toMutableList()
        val slack = a.rhs.map { from(it) }.toMutableList()
        for (j in 0 until a.n) {
            for ((row, coefficient) in a.matrix[j]) slack[row] = minus(slack[row], times(from(coefficient), x[j]))
        }
        x.addAll(slack)
        var value = from(a.constant)
        for (j in x.indices) {
            val bounds = a.bounds[j]
            bounds.lower?.let {
                val compare = compare(x[j], from(it.number.value))
                if (compare < 0 || (compare == 0 && it.strict)) return null
            }
            bounds.upper?.let {
                val compare = compare(x[j], from(it.number.value))
                if (compare > 0 || (compare == 0 && it.strict)) return null
            }
            value = plus(value, times(from(a.costs[j]), x[j]))
        }
        val sourceObjective = plus(times(value, reciprocal(from(a.scale))), from(a.external))
        val sourcePrimal = List(a.n) { big(plus(x[it], from(a.origins[it]))) }
        return ReconstructedPoint(ExactLpWitness(sourcePrimal, big(sourceObjective)), x.map { big(it) })
    }

    fun dual(multipliers: List<BigFraction>, ray: Boolean): ReconstructedDual? {
        meter.storage((a.m.toLong() + a.size) * 48L)
        val y = multipliers.map { from(it) }
        var value = if (ray) from(BigFraction.ZERO) else from(a.constant)
        for (i in y.indices) value = plus(value, times(y[i], from(a.rhs[i])))
        val reduced = ArrayList<BigFraction>(a.size)
        val selected = ArrayList<ExactSimplexBound>()
        var strict = false
        for (j in 0 until a.size) {
            var d = if (ray) from(BigFraction.ZERO) else from(a.costs[j])
            for ((row, coefficient) in a.matrix[j]) d = minus(d, times(y[row], from(coefficient)))
            val sign = sign(d)
            if (sign != 0) {
                val upper = sign < 0
                val side = (if (upper) a.bounds[j].upper else a.bounds[j].lower) ?: return null
                value = plus(value, times(d, from(side.number.value)))
                selected += ExactSimplexBound(j, upper)
                strict = strict || side.strict
            }
            reduced += big(d)
        }
        if (!ray) value = plus(times(value, reciprocal(from(a.scale))), from(a.external))
        return ReconstructedDual(big(value), reduced, selected.toList(), strict)
    }
}

private class ReconstructionRun(private val a: ReconstructionAuthority, private val meter: ReconstructionMeter) {
    var point: ReconstructedPoint? = null
    var bound: CertifiedLpBound? = null
    var dual: ReconstructedDual? = null
    var conflict: BigRationalConflict? = null
    var conflictSupport: LpExactSupport? = null
    var statusesMatch = false
    var complementary = false

    fun point(candidate: List<BigFraction>, basis: Basis?) {
        meter.phase = ReconstructionPhase.POINT
        meter.pointChecks++
        val checked = try {
            FixedReconstructionVerifier(a, meter).point(candidate)
        } catch (_: ReconstructionOverflow) {
            meter.verificationRestarts++
            BigReconstructionVerifier(a, meter).point(candidate)
        } ?: return
        val matches = statusesMatch(checked.coordinates, basis)
        meter.step()
        meter.pointSuccesses++
        if (point == null || checked.witness.objective < requireNotNull(point).witness.objective) {
            point = checked
            statusesMatch = matches
        }
    }

    fun dual(candidate: List<BigFraction>, ray: Boolean) {
        meter.phase = if (ray) ReconstructionPhase.RAY else ReconstructionPhase.DUAL
        if (ray) meter.rayChecks++ else meter.dualChecks++
        val checked = try {
            FixedReconstructionVerifier(a, meter).dual(candidate, ray)
        } catch (_: ReconstructionOverflow) {
            meter.verificationRestarts++
            BigReconstructionVerifier(a, meter).dual(candidate, ray)
        } ?: return
        if (ray && !(checked.value.signum() > 0 || (checked.value.isZero && checked.strict))) return
        val support = a.support(candidate, checked.selected)
        meter.storage((candidate.size.toLong() + checked.selected.size) * 32L)
        if (ray) {
            val rows = candidate.indices.filter { !candidate[it].isZero }
            // Conflict storage uses RHS below the box minimum, the opposite orientation to this ray.
            val proof = BigRationalConflict(
                rows.toIntArray(),
                rows.map { candidate[it].negated() },
                checked.selected.toList(),
            )
            meter.step()
            conflict = proof
            conflictSupport = support
            meter.raySuccesses++
        } else {
            val proof = CertifiedLpBound(checked.value, support = support)
            meter.step()
            meter.dualSuccesses++
            if (bound == null || proof.value > requireNotNull(bound).value) {
                bound = proof
                dual = checked
            }
        }
    }

    fun attained(): Boolean {
        val x = point ?: return false
        val y = dual ?: return false
        if (x.witness.objective != y.value) return false
        for (side in y.selected) {
            val endpoint = requireNotNull(if (side.upper) a.bounds[side.column].upper else a.bounds[side.column].lower)
            val movement = meter.fraction(x.coordinates[side.column] - endpoint.number.value)
            if (!meter.fraction(y.reduced[side.column] * movement).isZero) return false
        }
        complementary = true
        return true
    }

    fun seated(candidate: List<BigFraction>, basis: Basis?): List<BigFraction>? {
        if (!validHeadings(basis)) return null
        return candidate.mapIndexed { j, value ->
            endpoint(j, requireNotNull(basis).status[j]) ?: run {
                if (basis.status[j] == VarStatus.BASIC) value else return null
            }
        }
    }

    fun reconstructedSeated(candidate: List<BigFraction>, basis: Basis?, denominator: BigInteger): List<BigFraction>? {
        val seated = seated(candidate, basis) ?: return null
        meter.storage(a.n.toLong() * 32L)
        val basics = (0 until a.n).filter { requireNotNull(basis).status[it] == VarStatus.BASIC }
        val parts = reconstructExactVector(basics.map { candidate[it] }, denominator, meter) ?: return null
        val result = seated.toMutableList()
        for (i in basics.indices) result[basics[i]] = parts[i]
        return result.toList()
    }

    private fun validHeadings(basis: Basis?): Boolean {
        meter.step(a.size.toLong())
        if (basis == null || basis.status.size != a.size || basis.basicVars.size != a.m) return false
        meter.storage(a.size.toLong() * 16L)
        if (basis.basicVars.any { it !in 0 until a.size } || basis.basicVars.distinct().size != a.m) return false
        val basic = BooleanArray(a.size)
        for (j in basis.basicVars) basic[j] = true
        return basic.indices.all { basic[it] == (basis.status[it] == VarStatus.BASIC) }
    }

    private fun statusesMatch(coordinates: List<BigFraction>, basis: Basis?): Boolean {
        if (!validHeadings(basis)) return false
        for (j in coordinates.indices) {
            meter.step()
            val status = requireNotNull(basis).status[j]
            if (status != VarStatus.BASIC && endpoint(j, status) != coordinates[j]) return false
        }
        return true
    }

    private fun endpoint(j: Int, status: VarStatus): BigFraction? {
        meter.step()
        val b = a.bounds[j]
        return when (status) {
            VarStatus.AT_LOWER -> b.lower?.number?.value
            VarStatus.AT_UPPER -> b.upper?.number?.value
            VarStatus.FIXED -> if (b.fixed) b.lower?.number?.value else null
            VarStatus.FREE -> if (b.lower == null && b.upper == null) BigFraction.ZERO else null
            VarStatus.BASIC -> null
        }
    }
}

internal fun reconstructCertificate(
    model: LpModel,
    primal: DoubleArray? = null,
    duals: DoubleArray? = null,
    basis: Basis? = null,
    ray: DoubleArray? = null,
    cancellation: Cancellation = Cancellation.Never,
    limits: ReconstructionLimits = ReconstructionLimits(),
): ReconstructedCertificate {
    val meter = ReconstructionMeter(limits, cancellation)
    var run: ReconstructionRun? = null
    var decline: ReconstructionDecline? = null
    try {
        meter.step()
        if ((primal != null && primal.size != model.n) || (duals != null && duals.size != model.m) ||
            (ray != null && ray.size != model.m)
        ) {
            throw ReconstructionStop(ReconstructionDecline.INVALID_INPUT)
        }
        for (vector in listOfNotNull(primal, duals, ray)) {
            meter.step(vector.size.toLong())
            if (vector.any { !it.isFinite() }) throw ReconstructionStop(ReconstructionDecline.NONFINITE)
        }
        val authority = ReconstructionAuthority(model, meter)
        val current = ReconstructionRun(authority, meter)
        run = current
        meter.storage(((primal?.size ?: 0).toLong() + (duals?.size ?: 0) + (ray?.size ?: 0)) * 24L)
        val x = primal?.mapIndexed { j, value ->
            meter.fraction(
                meter.fraction(exactDouble(value)) - authority.origins[j],
            )
        }
        val y = duals?.map { meter.fraction(exactDouble(it)) }
        val rho = ray?.map { meter.fraction(exactDouble(it)) }
        if (x != null) {
            current.point(x, basis)
            current.seated(x, basis)?.takeIf { it != x }?.let { current.point(it, basis) }
        }
        if (y != null) current.dual(y, ray = false)
        if (rho != null && current.point == null) {
            current.dual(rho, ray = true)
            if (current.conflict == null) current.dual(rho.map { meter.fraction(it.negated()) }, ray = true)
        }
        if (!current.attained() && current.conflict == null) {
            val violation = reconstructionViolation(authority, x, y ?: rho, ray = y == null && rho != null, meter)
            var correction = BigFraction.ofLong(2L)
            var previous: BigInteger? = null
            var round = 0
            for (attempt in 0 until limits.maxAttempts) {
                meter.phase = ReconstructionPhase.SCHEDULE
                val denominator = if (violation.isZero) {
                    RECONSTRUCTION_FLOOR
                } else {
                    reconstructionDenominator(
                        violation,
                        correction,
                        meter,
                    )
                }
                if (denominator == previous) break
                previous = denominator
                meter.attempts++
                if (x != null) {
                    reconstructExactVector(x, denominator, meter)?.let { current.point(it, basis) }
                    current.reconstructedSeated(x, basis, denominator)?.let { current.point(it, basis) }
                }
                if (y != null) reconstructExactVector(y, denominator, meter)?.let { current.dual(it, ray = false) }
                if (rho != null && current.point == null) {
                    reconstructExactVector(rho, denominator, meter)?.let {
                        current.dual(it, ray = true)
                        if (current.conflict == null) {
                            current.dual(
                                it.map { value -> meter.fraction(value.negated()) },
                                ray = true,
                            )
                        }
                    }
                }
                if (current.attained() || current.conflict != null) break
                val next = nextReconstructionRound(round)
                repeat(
                    next - round,
                ) {
                    correction = meter.fraction(
                        correction * BigFraction.of(BigInteger.fromInt(11), BigInteger.fromInt(10)),
                    )
                }
                round = next
            }
        }
        if (current.point == null && current.bound == null &&
            current.conflict == null
        ) {
            decline = ReconstructionDecline.CANDIDATE
        }
    } catch (stop: ReconstructionStop) {
        decline = stop.reason
    }
    return ReconstructedCertificate(
        run?.point?.witness,
        run?.bound,
        run?.conflict,
        run?.conflictSupport,
        run?.statusesMatch ?: false,
        run?.complementary ?: false,
        meter.snapshot(decline),
    )
}

private fun reconstructionViolation(
    a: ReconstructionAuthority,
    primal: List<BigFraction>?,
    multipliers: List<BigFraction>?,
    ray: Boolean,
    meter: ReconstructionMeter,
): BigFraction {
    meter.phase = ReconstructionPhase.VIOLATION
    meter.storage(a.size.toLong() * 32L)
    val x = primal?.toMutableList()
    if (x != null) {
        val slack = a.rhs.toMutableList()
        for (j in 0 until a.n) {
            for ((row, coefficient) in a.matrix[j]) {
                slack[row] = meter.fraction(slack[row] - meter.fraction(coefficient * x[j]))
            }
        }
        x.addAll(slack)
    }
    var violation = BigFraction.ZERO
    for (j in 0 until a.size) {
        meter.step()
        val bounds = a.bounds[j]
        if (x != null) {
            bounds.lower?.let { violation = larger(violation, meter.fraction(it.number.value - x[j])) }
            bounds.upper?.let { violation = larger(violation, meter.fraction(x[j] - it.number.value)) }
        }
        if (multipliers != null) {
            var reduced = if (ray) BigFraction.ZERO else a.costs[j]
            for ((row, coefficient) in a.matrix[j]) {
                reduced = meter.fraction(
                    reduced - meter.fraction(multipliers[row] * coefficient),
                )
            }
            if (!reduced.isZero) {
                val side = if (reduced.signum() > 0) bounds.lower else bounds.upper
                val defect = if (side == null) {
                    reduced
                } else if (x != null) {
                    meter.fraction(
                        reduced * meter.fraction(x[j] - side.number.value),
                    )
                } else {
                    BigFraction.ZERO
                }
                violation = larger(violation, if (defect.signum() < 0) defect.negated() else defect)
            }
        }
    }
    return violation
}

private fun larger(a: BigFraction, b: BigFraction): BigFraction = if (a >= b) a else b

internal fun verifyRationalCertificate(
    model: LpModel,
    sourcePrimal: List<BigFraction>? = null,
    duals: List<BigFraction>? = null,
    ray: List<BigFraction>? = null,
    basis: Basis? = null,
    cancellation: Cancellation = Cancellation.Never,
    limits: ReconstructionLimits = ReconstructionLimits(),
): ReconstructedCertificate {
    val meter = ReconstructionMeter(limits, cancellation)
    var run: ReconstructionRun? = null
    var decline: ReconstructionDecline? = null
    try {
        meter.step()
        if ((sourcePrimal != null && sourcePrimal.size != model.n) || (duals != null && duals.size != model.m) ||
            (ray != null && ray.size != model.m)
        ) {
            throw ReconstructionStop(ReconstructionDecline.INVALID_INPUT)
        }
        val authority = ReconstructionAuthority(model, meter)
        val current = ReconstructionRun(authority, meter)
        run = current
        meter.storage(((sourcePrimal?.size ?: 0).toLong() + (duals?.size ?: 0) + (ray?.size ?: 0)) * 24L)
        sourcePrimal?.let { values ->
            current.point(
                values.mapIndexed { j, value -> meter.fraction(meter.fraction(value) - authority.origins[j]) },
                basis,
            )
        }
        duals?.let { current.dual(it.map { value -> meter.fraction(value) }, ray = false) }
        if (ray != null && current.point == null) {
            val candidate = ray.map { meter.fraction(it) }
            current.dual(candidate, ray = true)
            if (current.conflict == null) current.dual(candidate.map { meter.fraction(it.negated()) }, ray = true)
        }
        current.attained()
        if (current.point == null && current.bound == null &&
            current.conflict == null
        ) {
            decline = ReconstructionDecline.CANDIDATE
        }
    } catch (stop: ReconstructionStop) {
        decline = stop.reason
    }
    return ReconstructedCertificate(
        run?.point?.witness,
        run?.bound,
        run?.conflict,
        run?.conflictSupport,
        run?.statusesMatch ?: false,
        run?.complementary ?: false,
        meter.snapshot(decline),
    )
}
