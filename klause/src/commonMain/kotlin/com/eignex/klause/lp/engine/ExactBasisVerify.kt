package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.RationalBasisBuild
import com.eignex.klause.simplex.basis.RationalBasisFactors
import com.eignex.klause.simplex.basis.RationalBasisLimits
import com.eignex.klause.simplex.basis.RationalBasisOrder
import com.eignex.klause.simplex.basis.RationalBasisSolve
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.BigRationalConflict
import com.eignex.klause.util.Cancellation
import com.ionspin.kotlin.bignum.integer.BigInteger

internal data class ExactBasisLimits(
    val factor: RationalBasisLimits = RationalBasisLimits(),
    val verification: ReconstructionLimits = ReconstructionLimits(),
)

internal enum class ExactBasisPhase { ASSEMBLY, FACTOR, PRIMAL, DUAL, RAY, VERIFICATION, PROJECTION }
internal enum class ExactBasisDecline {
    INVALID_INPUT,
    INVALID_BASIS,
    SINGULAR,
    DIMENSION,
    WORK,
    MEMORY,
    FILL,
    BITS,
    TIME,
    CANCELLED,
    CANDIDATE,
    PROJECTION,
}

internal data class ExactBasisWork(val phase: ExactBasisPhase, val work: Long, val allocation: Long)
internal enum class ExactBasisOrderDecline { UNSUPPORTED, UPDATED, STALE, INVALID }
internal data class ExactBasisMetrics(
    val eligible: Boolean,
    val factoryCalls: Int,
    val builds: Int,
    val reuse: Int,
    val solves: Int,
    val restarts: Int,
    val verificationChecks: Int,
    val maxBits: Int,
    val peakFill: Int,
    val operations: List<ExactBasisWork>,
    val decline: ExactBasisDecline?,
    val phase: ExactBasisPhase,
    val orderOffers: Int = 0,
    val orderProposals: Int = 0,
    val orderAttempts: Int = 0,
    val orderFallbacks: Int = 0,
    val orderDecline: ExactBasisOrderDecline? = null,
) {
    val work: Long get() = operations.sumOf { it.work }
    val allocation: Long get() = operations.sumOf { it.allocation }
}

internal class ExactBasisVerification(
    val witness: ExactLpWitness?,
    val bound: CertifiedLpBound?,
    val conflict: BigRationalConflict?,
    val conflictSupport: LpExactSupport?,
    val integerRay: LongArray?,
    val complementary: Boolean,
    val singularRank: Int?,
    val metrics: ExactBasisMetrics,
)

private class CachedExactBasis(
    val n: Int,
    val m: Int,
    val state: LpExactState?,
    val matrix: List<List<Pair<Int, BigFraction>>>,
    val headings: IntArray,
    val factors: RationalBasisFactors,
)

// A cache belongs to one numerical owner. Failed builds spend only their enclosing invocation budget.
internal class ExactBasisCache(private val propose: ((ExactBasisAuthority) -> RationalBasisOrder?)? = null) {
    private var cached: CachedExactBasis? = null

    fun clear() {
        cached = null
    }

    fun proposedOrder(current: ExactBasisAuthority): RationalBasisOrder? {
        val provider = propose ?: return null
        val meter = current.meter
        meter.poll()
        meter.orderOffers++
        val proposed = provider(current)
        meter.poll()
        if (proposed == null) {
            if (meter.orderDecline == null) meter.orderDecline = ExactBasisOrderDecline.UNSUPPORTED
            return null
        }
        val n = current.model.m
        if (proposed.rows.size != n || proposed.columns.size != n) {
            meter.orderDecline = ExactBasisOrderDecline.INVALID
            return null
        }
        meter.charge(6L * n, 256L + 18L * n)
        val rows = proposed.rows.copyOf()
        val columns = proposed.columns.copyOf()
        if (!orderPermutation(rows) || !orderPermutation(columns)) {
            meter.orderDecline = ExactBasisOrderDecline.INVALID
            return null
        }
        meter.orderProposals++
        return RationalBasisOrder(rows, columns)
    }

    fun find(current: ExactBasisAuthority): RationalBasisFactors? {
        val previous = cached ?: return null
        current.meter.charge(current.model.numVars.toLong() + current.matrix.sumOf { it.size.toLong() })
        val oldState = previous.state
        val state = current.model.exactState
        val sameState = if (oldState == null) state == null else state != null && oldState.sameMatrix(state)
        if (!sameState || previous.n != current.model.n || previous.m != current.model.m ||
            !previous.headings.contentEquals(current.headings) || previous.matrix != current.matrix
        ) {
            clear()
            return null
        }
        return previous.factors
    }

    fun install(current: ExactBasisAuthority, completed: RationalBasisFactors) {
        current.meter.charge(bytes = 64L + current.headings.size * 4L)
        cached = CachedExactBasis(
            current.model.n,
            current.model.m,
            current.model.exactState,
            current.matrix,
            current.headings.copyOf(),
            completed,
        )
    }
}

internal fun verifyExactBasis(
    model: LpModel,
    basis: Basis,
    rayRow: Int? = null,
    cache: ExactBasisCache = ExactBasisCache(),
    cancellation: Cancellation = Cancellation.Never,
    limits: ExactBasisLimits = ExactBasisLimits(),
    observer: LpCertificationObserver? = null,
): ExactBasisVerification {
    val meter = ExactBasisMeter(limits, cancellation)
    var witness: ExactLpWitness? = null
    var bound: CertifiedLpBound? = null
    var conflict: BigRationalConflict? = null
    var support: LpExactSupport? = null
    var integerRay: LongArray? = null
    var complementary = false
    var singularRank: Int? = null
    var decline: ExactBasisDecline? = null
    try {
        val authority = ExactBasisAuthority(model, basis, meter)
        if (rayRow != null && rayRow !in 0 until model.m) meter.stop(ExactBasisDecline.INVALID_BASIS)
        val cached = cache.find(authority)
        if (cached != null) meter.reuse++
        val factors = cached ?: run {
            val matrix = authority.basisMatrix()
            meter.phase = ExactBasisPhase.FACTOR
            val proposed = cache.proposedOrder(authority)
            val allowance = meter.factorLimits()
            meter.factoryCalls++
            val build = RationalBasisFactors.factor(matrix, proposed, allowance, meter.token)
            meter.record(build.stats)
            when (build) {
                is RationalBasisBuild.Ready -> build.factors.also { cache.install(authority, it) }

                is RationalBasisBuild.Singular -> {
                    singularRank = build.rank
                    meter.stop(ExactBasisDecline.SINGULAR)
                }

                is RationalBasisBuild.Declined -> {
                    if (build.reason.name == "CANCELLED") meter.poll()
                    meter.stop(ExactBasisDecline.valueOf(build.reason.name))
                }
            }
        }
        if (rayRow == null) {
            meter.phase = ExactBasisPhase.ASSEMBLY
            val rhs = authority.primalRhs()
            meter.phase = ExactBasisPhase.PRIMAL
            val basics = solveExactBasis(factors, rhs, false, meter)
            val primal = authority.sourcePrimal(basics)
            // Publish this complete independent witness before BTRAN can exhaust the remaining budget.
            val point = verifyBasisCandidates(model, authority, primal, null, null, meter)
            witness = point.witness
            point.metrics.decline?.takeUnless { it == ReconstructionDecline.CANDIDATE }?.let { meter.stop(it) }
            meter.phase = ExactBasisPhase.DUAL
            val dual = solveExactBasis(factors, authority.costs, true, meter)
            val checked = verifyBasisCandidates(model, authority, primal, dual, null, meter)
            witness = checked.witness ?: witness
            bound = checked.bound
            complementary = checked.complementary
            checked.metrics.decline?.let { meter.stop(it) }
        } else {
            meter.phase = ExactBasisPhase.RAY
            meter.charge(model.m.toLong(), model.m * 32L)
            val rhs = List(model.m) { if (it == rayRow) BigFraction.ONE else BigFraction.ZERO }
            val ray = solveExactBasis(factors, rhs, true, meter)
            val checked = verifyBasisCandidates(model, authority, null, null, ray, meter)
            conflict = checked.conflict
            support = checked.conflictSupport
            checked.metrics.decline?.let { meter.stop(it) }
            if (conflict != null) integerRay = projectBasisConflict(model.m, conflict, meter)
        }
        meter.poll()
    } catch (stop: ExactBasisStop) {
        decline = stop.reason
    }
    val result = ExactBasisVerification(
        witness,
        bound,
        conflict,
        support,
        integerRay,
        complementary,
        singularRank,
        meter.snapshot(decline),
    )
    observer?.observeBasisVerification(result.metrics)
    observer?.observe(
        if (rayRow == null) LpCertifier.EXACT_BASIS else LpCertifier.EXACT_FARKAS,
        if (rayRow == null) witness != null else conflict != null,
    )
    if (rayRow == null) observer?.observe(LpCertifier.RATIONAL, bound != null)
    return result
}

private fun orderPermutation(order: IntArray): Boolean {
    val seen = BooleanArray(order.size)
    for (value in order) {
        if (value !in seen.indices || seen[value]) return false
        seen[value] = true
    }
    return true
}

private fun solveExactBasis(
    factors: RationalBasisFactors,
    rhs: List<BigFraction>,
    transpose: Boolean,
    meter: ExactBasisMeter,
): List<BigFraction> {
    val allowance = meter.factorLimits()
    meter.solves++
    val result = factors.solve(rhs, transpose, allowance, meter.token)
    meter.record(result.stats)
    return when (result) {
        is RationalBasisSolve.Solved -> result.values

        is RationalBasisSolve.Declined -> {
            if (result.reason.name == "CANCELLED") meter.poll()
            throw ExactBasisStop(ExactBasisDecline.valueOf(result.reason.name))
        }
    }
}

private fun verifyBasisCandidates(
    model: LpModel,
    authority: ExactBasisAuthority,
    primal: List<BigFraction>?,
    dual: List<BigFraction>?,
    ray: List<BigFraction>?,
    meter: ExactBasisMeter,
): ReconstructedCertificate {
    meter.phase = ExactBasisPhase.VERIFICATION
    val result = verifyRationalCertificate(
        model,
        primal,
        dual,
        ray,
        Basis(authority.headings, authority.statuses),
        meter.token,
        meter.verificationLimits(),
    )
    meter.record(result.metrics)
    return result
}

// The Long ray is a live integer-consumer projection; the rational conflict survives projection decline.
private fun projectBasisConflict(rows: Int, conflict: BigRationalConflict, meter: ExactBasisMeter): LongArray {
    meter.phase = ExactBasisPhase.PROJECTION
    var denominator = BigInteger.ONE
    for (value in conflict.multipliers) {
        meter.fraction(BigFraction.of(denominator, BigInteger.ONE))
        meter.fraction(value)
        val bits = denominator.bitLength().toLong() + value.den.bitLength()
        meter.charge((bits + 63L) / 64L, 1024L + bits * 64L)
        denominator = denominator / denominator.gcd(value.den) * value.den
        meter.fraction(BigFraction.of(denominator, BigInteger.ONE))
    }
    meter.charge(rows.toLong(), rows * 16L)
    val result = LongArray(rows)
    val lower = BigInteger.fromLong(Long.MIN_VALUE)
    val upper = BigInteger.fromLong(Long.MAX_VALUE)
    for (i in conflict.rows.indices) {
        val value = conflict.multipliers[i]
        val bits = denominator.bitLength().toLong() + value.num.bitLength()
        meter.charge((bits + 63L) / 64L, 1024L + bits * 64L)
        val scaled = -(denominator / value.den * value.num)
        meter.fraction(BigFraction.of(scaled, BigInteger.ONE))
        if (scaled < lower || scaled > upper) throw ExactBasisStop(ExactBasisDecline.PROJECTION)
        result[conflict.rows[i]] = scaled.longValue(exactRequired = true)
    }
    return result
}
