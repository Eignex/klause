package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.RationalBasisLimits
import com.eignex.klause.simplex.basis.RationalBasisStats
import com.eignex.klause.simplex.exact.BigRationalConflict
import com.eignex.klause.simplex.exact.ExactSimplexBound
import com.eignex.klause.simplex.exact.Frac128
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.BigInt
import com.eignex.util.numbers.magnitudeBitLength
import com.eignex.util.numbers.plus
import com.eignex.util.numbers.shl
import com.eignex.util.numbers.shr
import com.eignex.util.numbers.toLong
import kotlin.jvm.JvmField

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

internal enum class LpPointRejection {
    STRICT_COLUMN_ENDPOINT,
    STRICT_LOGICAL_ENDPOINT,
    COLUMN_BOUND,
    LOGICAL_BOUND,
}

internal enum class ReconstructionPhase { INPUT, POINT, DUAL, RAY, SUPPORT, VIOLATION, VECTOR, BASIS, SCHEDULE }

internal data class ReconstructionDualRejection(
    val column: Int, val upper: Boolean, val reduced: BigFraction, val basic: Boolean? = null,
)
internal data class ReconstructionVectorRejection(val commonDenominator: BigInt, val limit: BigInt)
internal data class ReconstructionEntryRejection(val row: Int, val value: BigFraction)
internal data class ReconstructionBasisMetrics(
    val peeledRows: Int = 0,
    val peeledColumns: Int = 0,
    val coreRows: Int = 0,
    val components: Int = 0,
    val largestComponent: Int = 0,
    val solvedComponents: Int = 0,
    val factorWork: Long = 0L,
    val factorAllocation: Long = 0L,
    val openBasicColumns: Int = 0,
    val decline: String? = null,
    val restrictedComponents: Int = 0,
    val restrictedColumns: Int = 0,
    val largestRestrictedColumns: Int = 0,
)

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
    val dualRejection: ReconstructionDualRejection? = null,
    val vectorRejection: ReconstructionVectorRejection? = null,
    val entryRejection: ReconstructionEntryRejection? = null,
    val basisRepair: ReconstructionBasisMetrics? = null,
    val basisWork: Long = 0L,
    val basisDualRejection: ReconstructionDualRejection? = null,
)

internal class ReconstructedCertificate(
    val witness: ExactLpWitness?,
    val bound: CertifiedLpBound?,
    val conflict: BigRationalConflict?,
    val conflictSupport: LpExactSupport?,
    val nonbasicStatusesMatch: Boolean,
    val complementary: Boolean,
    val metrics: ReconstructionMetrics,
    val pointRejection: LpPointRejection? = null,
    val boundUsesBasis: Boolean = false,
)

internal class ReconstructionOverflow : RuntimeException()
internal class ReconstructionStop(val reason: ReconstructionDecline) : RuntimeException()
internal class ReconstructionLayoutStop(val reason: LogicalBasisLayoutDecline) : RuntimeException()

internal class ReconstructionMeter(
    val limits: ReconstructionLimits = ReconstructionLimits(),
    private val cancellation: ExecutionControl = ExecutionControl.Never,
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
    var dualRejection: ReconstructionDualRejection? = null
    var vectorRejection: ReconstructionVectorRejection? = null
    var entryRejection: ReconstructionEntryRejection? = null
    var basisRepair: ReconstructionBasisMetrics? = null
    var basisWork = 0L
    var basisDualRejection: ReconstructionDualRejection? = null
    val spentWork: Long get() = work

    @JvmField var work = 0L
    private var allocation = 0L
    private var maxBits = 0

    @JvmField val workLimit = limits.maxWork

    @JvmField var admittedUntil = 0L

    @Suppress("NOTHING_TO_INLINE") // The admitted work total also schedules the cold stop path.
    inline fun step(units: Long = 1L) {
        if (units > admittedUntil - work) admit(units)
        work += units
    }

    private fun admit(units: Long) {
        poll()
        if (units > workLimit - work) throw ReconstructionStop(ReconstructionDecline.WORK)
        if (units > admittedUntil - work) admittedUntil =
            work + units
    }

    fun require(condition: Boolean, reason: ReconstructionDecline) {
        if (!condition) throw ReconstructionStop(reason)
    }

    fun poll() {
        if (cancellation()) throw ReconstructionStop(ReconstructionDecline.CANCELLED)
        admittedUntil = work + minOf(4096L, workLimit - work)
    }

    fun storage(bytes: Long) {
        step()
        if (bytes > limits.maxAllocation - allocation) throw ReconstructionStop(ReconstructionDecline.ALLOCATION)
        allocation += bytes
    }

    fun integer(value: BigInt): BigInt {
        step()
        val bits = value.magnitudeBitLength()
        maxBits = maxOf(maxBits, bits)
        if (bits > limits.maxBits) throw ReconstructionStop(ReconstructionDecline.BITS)
        if (bits > 128) {
            admittedUntil -= bits.toLong() / Long.SIZE_BITS
        }
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
        vectorRestarts, verificationRestarts, work, allocation, maxBits, decline, phase, dualRejection, vectorRejection,
        entryRejection, basisRepair, basisWork, basisDualRejection,
    )

    fun basisLimits(): RationalBasisLimits = RationalBasisLimits(
        work = (limits.maxWork - work) / 2L,
        allocationBytes = (limits.maxAllocation - allocation) / 2L,
        fill = minOf(limits.maxEntries, 16384),
        bits = limits.maxBits,
    )

    fun recordBasis(stats: RationalBasisStats) {
        step(stats.work)
        storage(stats.allocationBytes)
        maxBits = maxOf(maxBits, stats.maxBits)
    }
}

internal class ReconstructionAuthority(val model: LpModel, val meter: ReconstructionMeter) {
    val n = model.n
    val m = model.m
    val size = model.numVars
    val matrix: List<List<Pair<Int, BigFraction>>>
    val bounds: List<ExactLpBounds>
    val hasOpenStructuralBounds: Boolean
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
        var openStructuralBounds = false
        bounds = List(size) { j ->
            model.exactBounds(j).also { b ->
                if (j < n && (b.lower == null || b.upper == null)) openStructuralBounds = true
                for (side in listOfNotNull(b.lower, b.upper)) {
                    meter.fraction(side.number.value)
                    chargePremises(side.premises)
                }
            }
        }
        meter.storage(8L)
        hasOpenStructuralBounds = openStructuralBounds
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
