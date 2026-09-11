package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.RationalBasisLimits
import com.eignex.klause.simplex.basis.RationalBasisStats
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import kotlin.time.TimeSource

internal class ExactBasisStop(val reason: ExactBasisDecline) : RuntimeException()

internal class ExactBasisMeter(val limits: ExactBasisLimits, private val cancellation: Cancellation) {
    private val started = TimeSource.Monotonic.markNow()
    private val work = LongArray(ExactBasisPhase.entries.size)
    private val allocation = LongArray(ExactBasisPhase.entries.size)
    var phase = ExactBasisPhase.ASSEMBLY
    var eligible = false
    var builds = 0
    var factoryCalls = 0
    var reuse = 0
    var solves = 0
    var restarts = 0
    var maxBits = 0
    var peakFill = 0
    var verificationChecks = 0

    val token = Cancellation { cancellation() || started.elapsedNow() >= limits.factor.time }

    fun poll() {
        if (cancellation()) throw ExactBasisStop(ExactBasisDecline.CANCELLED)
        if (started.elapsedNow() >= limits.factor.time) throw ExactBasisStop(ExactBasisDecline.TIME)
    }

    fun charge(units: Long = 1L, bytes: Long = 0L) {
        poll()
        if (units > limits.factor.work - work.sum()) throw ExactBasisStop(ExactBasisDecline.WORK)
        if (bytes > limits.factor.allocationBytes - allocation.sum()) throw ExactBasisStop(ExactBasisDecline.MEMORY)
        work[phase.ordinal] += units
        allocation[phase.ordinal] += bytes
    }

    fun fraction(value: BigFraction): BigFraction {
        val bits = maxOf(value.num.bitLength(), value.den.bitLength())
        maxBits = maxOf(maxBits, bits)
        if (bits > limits.factor.bits) throw ExactBasisStop(ExactBasisDecline.BITS)
        charge(bytes = 64L + (bits.toLong() + 7L) / 4L)
        return value
    }

    private fun arithmetic(a: BigFraction, b: BigFraction) {
        fraction(a)
        fraction(b)
        val bits = maxOf(a.num.bitLength(), a.den.bitLength()).toLong() +
            maxOf(b.num.bitLength(), b.den.bitLength()) + 1L
        val limbs = (bits + 63L) / 64L
        charge(limbs * limbs, 1024L + 64L * bits)
    }

    fun add(a: BigFraction, b: BigFraction): BigFraction {
        arithmetic(a, b)
        return fraction(a + b)
    }

    fun subtractProduct(a: BigFraction, b: BigFraction, c: BigFraction): BigFraction {
        arithmetic(b, c)
        val product = fraction(b * c)
        arithmetic(a, product)
        return fraction(a - product)
    }

    fun factorLimits(): RationalBasisLimits {
        poll()
        return limits.factor.copy(
            work = limits.factor.work - work.sum(),
            allocationBytes = limits.factor.allocationBytes - allocation.sum(),
            time = (limits.factor.time - started.elapsedNow()).coerceAtLeast(kotlin.time.Duration.ZERO),
        )
    }

    fun verificationLimits(): ReconstructionLimits {
        poll()
        return limits.verification.copy(
            maxWork = minOf(limits.verification.maxWork, limits.factor.work - work.sum()),
            maxAllocation = minOf(limits.verification.maxAllocation, limits.factor.allocationBytes - allocation.sum()),
        )
    }

    // Nested operations already spent this work, including their failed attempts. Account before polling.
    fun record(stats: RationalBasisStats) {
        work[phase.ordinal] += stats.work
        allocation[phase.ordinal] += stats.allocationBytes
        builds += stats.builds
        restarts += stats.restarts
        maxBits = maxOf(maxBits, stats.maxBits)
        peakFill = maxOf(peakFill, stats.peakFill)
    }

    fun record(metrics: ReconstructionMetrics) {
        work[phase.ordinal] += metrics.work
        allocation[phase.ordinal] += metrics.allocation
        restarts += metrics.vectorRestarts + metrics.verificationRestarts
        maxBits = maxOf(maxBits, metrics.maxBits)
        verificationChecks += metrics.pointChecks + metrics.dualChecks + metrics.rayChecks
    }

    fun stop(reason: ExactBasisDecline): Nothing = throw ExactBasisStop(reason)

    fun stop(reason: ReconstructionDecline): Nothing {
        if (reason == ReconstructionDecline.CANCELLED) poll()
        throw ExactBasisStop(
            when (reason) {
                ReconstructionDecline.CANCELLED -> ExactBasisDecline.CANCELLED
                ReconstructionDecline.ALLOCATION -> ExactBasisDecline.MEMORY
                ReconstructionDecline.WORK -> ExactBasisDecline.WORK
                ReconstructionDecline.BITS -> ExactBasisDecline.BITS
                ReconstructionDecline.DIMENSION -> ExactBasisDecline.DIMENSION
                ReconstructionDecline.CANDIDATE -> ExactBasisDecline.CANDIDATE
                ReconstructionDecline.INVALID_INPUT, ReconstructionDecline.NONFINITE -> ExactBasisDecline.INVALID_INPUT
            },
        )
    }

    fun snapshot(decline: ExactBasisDecline?) = ExactBasisMetrics(
        eligible, factoryCalls, builds, reuse, solves, restarts, verificationChecks, maxBits, peakFill,
        ExactBasisPhase.entries.map { ExactBasisWork(it, work[it.ordinal], allocation[it.ordinal]) }, decline, phase,
    )
}

internal class ExactBasisAuthority(val model: LpModel, basis: Basis, val meter: ExactBasisMeter) {
    val headings: IntArray
    val statuses: Array<VarStatus>
    val matrix: List<List<Pair<Int, BigFraction>>>
    val seats: List<BigFraction>
    val rhs: List<BigFraction>
    val costs: List<BigFraction>
    val origins: List<BigFraction>

    init {
        meter.charge()
        if (model.m > meter.limits.factor.dimension ||
            model.numVars.toLong() > meter.limits.verification.maxCoordinates
        ) {
            throw ExactBasisStop(ExactBasisDecline.DIMENSION)
        }
        if (basis.basicVars.size != model.m || basis.status.size != model.numVars) {
            throw ExactBasisStop(ExactBasisDecline.INVALID_BASIS)
        }
        meter.charge(model.numVars.toLong(), model.numVars.toLong() * 64L)
        headings = basis.basicVars.copyOf()
        statuses = basis.status.copyOf()
        val seen = BooleanArray(model.numVars)
        for (j in headings) {
            meter.charge()
            if (j !in seen.indices || seen[j] || statuses[j] != VarStatus.BASIC) {
                throw ExactBasisStop(ExactBasisDecline.INVALID_BASIS)
            }
            seen[j] = true
        }
        val sourceSize = model.exactState?.model?.keySize ?: model.csc.colVal.size.toLong() * 3L
        meter.charge(sourceSize)
        if (!model.finiteExactInput()) throw ExactBasisStop(ExactBasisDecline.INVALID_INPUT)
        var entries = 0
        matrix = List(model.numVars) { j ->
            val column = ArrayList<Pair<Int, BigFraction>>()
            model.forEachRationalColumn(j) { row, value ->
                if (++entries > meter.limits.verification.maxEntries) throw ExactBasisStop(ExactBasisDecline.DIMENSION)
                meter.charge(bytes = 32L)
                column += row to meter.fraction(value)
            }
            column.toList()
        }
        seats = List(model.numVars) { j ->
            meter.charge()
            val bounds = model.exactBounds(j)
            val seat = when (statuses[j]) {
                VarStatus.BASIC -> if (seen[j]) BigFraction.ZERO else null
                VarStatus.AT_LOWER -> bounds.lower?.number?.value
                VarStatus.AT_UPPER -> bounds.upper?.number?.value
                VarStatus.FIXED -> if (bounds.fixed) bounds.lower?.number?.value else null
                VarStatus.FREE -> if (bounds.lower == null && bounds.upper == null) BigFraction.ZERO else null
            } ?: throw ExactBasisStop(ExactBasisDecline.INVALID_BASIS)
            meter.fraction(seat)
        }
        rhs = List(model.m) { meter.fraction(model.exactRhs(it)) }
        costs = List(model.m) { meter.fraction(model.exactCost(headings[it])) }
        origins = List(model.n) { meter.fraction(model.exactShift(it)) }
        meter.eligible = true
    }

    fun basisMatrix(): List<List<BigFraction>> {
        meter.charge(model.m.toLong() * model.m, model.m.toLong() * model.m * 8L + model.m * 64L)
        val result = List(model.m) { MutableList(model.m) { BigFraction.ZERO } }
        for (slot in headings.indices) {
            for ((row, value) in matrix[headings[slot]]) {
                meter.charge()
                result[row][slot] = value
            }
        }
        return result
    }

    fun primalRhs(): List<BigFraction> {
        meter.charge(bytes = model.m * 32L)
        val result = rhs.toMutableList()
        for (j in seats.indices) {
            meter.charge()
            if (statuses[j] == VarStatus.BASIC || seats[j].isZero) continue
            for ((row, value) in matrix[j]) result[row] = meter.subtractProduct(result[row], value, seats[j])
        }
        return result
    }

    fun sourcePrimal(basics: List<BigFraction>): List<BigFraction> {
        meter.charge(bytes = model.numVars * 32L)
        val point = seats.toMutableList()
        for (i in headings.indices) point[headings[i]] = basics[i]
        return List(model.n) { meter.add(point[it], origins[it]) }
    }
}
