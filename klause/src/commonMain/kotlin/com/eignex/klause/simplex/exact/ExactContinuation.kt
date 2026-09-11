package com.eignex.klause.simplex.exact

import com.eignex.klause.util.Cancellation
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.time.TimeSource.Monotonic

internal enum class ContinuationStatus { BASIC, LOWER, UPPER, FIXED, FREE }
internal enum class ContinuationPhase { ADMISSION, INPUT, IMPORT, REPAIR, FEASIBILITY, VERIFY }
internal enum class ContinuationDecline {
    INVALID_INPUT,
    INVALID_BASIS,
    NO_BASIS,
    RESUME_KEY,
    DIMENSION,
    WORK,
    ALLOCATION,
    BITS,
    TIME,
    CANCELLED,
    IMPORT_LIMIT,
    PIVOTS,
    CANDIDATE,
}

internal data class ExactContinuationLimits(
    val maxRows: Int = 128,
    val maxColumns: Int = 4096,
    val maxCells: Int = 65536,
    val maxBits: Int = 4096,
    val maxWork: Long = 100_000_000L,
    val maxAllocation: Long = 256L * 1024L * 1024L,
    val maxTimeNs: Long = 5_000_000_000L,
    val maxImportPivots: Int = 128,
    val maxPivots: Int = 10000,
) {
    init {
        require(maxRows >= 0 && maxColumns >= 0 && maxCells >= 0 && maxBits >= 24)
        require(maxWork >= 0 && maxAllocation >= 0 && maxTimeNs >= 0 && maxImportPivots >= 0 && maxPivots >= 0)
    }
}

internal data class ExactContinuationMetrics(
    val eligible: Boolean = false,
    val builds: Int = 0,
    val imports: Int = 0,
    val pivots: Int = 0,
    val repairs: Int = 0,
    val restarts: Int = 0,
    val work: Long = 0,
    val allocation: Long = 0,
    val elapsedNs: Long = 0,
    val phase: ContinuationPhase = ContinuationPhase.INPUT,
    val decline: ContinuationDecline? = null,
    val retainedPivots: Int = 0,
    val importPosition: Int = 0,
    val resumed: Boolean = false,
    val invalidated: Boolean = false,
    val checks: Int = 0,
    val success: Boolean = false,
    val workByPhase: Map<ContinuationPhase, Long> = emptyMap(),
    val allocationByPhase: Map<ContinuationPhase, Long> = emptyMap(),
)

// Logical columns are implicit units. All lists belong to the immutable import checkpoint.
internal class ExactContinuationInput(
    columns: List<List<Pair<Int, BigFraction>>>,
    rhs: List<BigFraction>,
    lower: List<BigFraction?>,
    upper: List<BigFraction?>,
    headings: List<Int>,
    statuses: List<ContinuationStatus>,
) {
    val columns = columns.map { it.toList() }
    val rhs = rhs.toList()
    val lower = lower.toList()
    val upper = upper.toList()
    val headings = headings.toList()
    val statuses = statuses.toList()
    val n = columns.size
    val m = rhs.size
    val total = n + m
}

internal class ExactContinuationResult(
    val values: List<BigFraction>?,
    val ray: List<BigFraction>?,
    val headings: List<Int>?,
    val statuses: List<ContinuationStatus>?,
    val metrics: ExactContinuationMetrics,
)

internal class ContinuationStop(val reason: ContinuationDecline) : RuntimeException()
private class ContinuationOverflow : RuntimeException()

internal class ContinuationBudget(val limits: ExactContinuationLimits, val token: Cancellation) {
    private val started = Monotonic.markNow()
    var work = 0L
        private set
    var allocation = 0L
        private set
    var phase = ContinuationPhase.INPUT
    val workByPhase = mutableMapOf<ContinuationPhase, Long>()
    val allocationByPhase = mutableMapOf<ContinuationPhase, Long>()
    val elapsedNs get() = started.elapsedNow().inWholeNanoseconds

    @Suppress("ThrowsCount")
    fun step(units: Long = 1L, bytes: Long = 0L) {
        if (token()) throw ContinuationStop(ContinuationDecline.CANCELLED)
        if (elapsedNs >= limits.maxTimeNs) throw ContinuationStop(ContinuationDecline.TIME)
        if (units > limits.maxWork - work) throw ContinuationStop(ContinuationDecline.WORK)
        if (bytes > limits.maxAllocation - allocation) throw ContinuationStop(ContinuationDecline.ALLOCATION)
        work += units
        allocation += bytes
        workByPhase[phase] = (workByPhase[phase] ?: 0L) + units
        allocationByPhase[phase] = (allocationByPhase[phase] ?: 0L) + bytes
    }

    fun fraction(value: BigFraction): BigFraction {
        step()
        val bits = maxOf(value.num.bitLength(), value.den.bitLength())
        if (bits > limits.maxBits) throw ContinuationStop(ContinuationDecline.BITS)
        step(bytes = 64L + (bits.toLong() + 7L) / 4L)
        return value
    }

    fun preflight(a: BigFraction, b: BigFraction) {
        val bits = maxOf(a.num.bitLength(), a.den.bitLength()).toLong() +
            maxOf(b.num.bitLength(), b.den.bitLength()) + 2L
        if (bits > limits.maxBits * 2L + 2L) throw ContinuationStop(ContinuationDecline.BITS)
        step(bits, 128L + bits / 2L)
    }
}

// Totals belong to one authority, including abandoned fixed-width operations. Tokens are invocation-local.
internal class ExactContinuation(private val input: ExactContinuationInput) {
    private var lane: ContinuationLane? = null
    private var big = false
    private var work = 0L
    private var allocation = 0L
    private var elapsed = 0L
    private var imports = 0
    private var pivots = 0
    private var attempts = 0
    private var eligible = false

    val usedWork get() = work
    val usedAllocation get() = allocation
    val usedTimeNs get() = elapsed

    fun account(work: Long, allocation: Long, elapsedNs: Long) {
        this.work += work
        this.allocation += allocation
        elapsed += elapsedNs
    }

    fun resume(
        limits: ExactContinuationLimits = ExactContinuationLimits(),
        cancellation: Cancellation = Cancellation.Never,
    ): ExactContinuationResult {
        val budget = ContinuationBudget(
            limits.copy(
                maxWork = (limits.maxWork - work).coerceAtLeast(0),
                maxAllocation = (limits.maxAllocation - allocation).coerceAtLeast(0),
                maxTimeNs = (limits.maxTimeNs - elapsed).coerceAtLeast(0),
            ),
            cancellation,
        )
        val oldImports = imports
        val oldPivots = pivots
        var builds = 0
        var restarts = 0
        var repairs = 0
        var stop: ContinuationDecline? = null
        var result: LaneResult? = null
        try {
            validate(budget)
            eligible = true
            while (result == null) {
                try {
                    val current = lane ?: run {
                        builds++
                        (
                            if (big) {
                                RationalContinuationLane(input, BigFracOps, budget)
                            } else {
                                RationalContinuationLane(input, Frac128Ops(), budget)
                            }
                            ).also { lane = it }
                    }
                    result = current.advance(
                        budget,
                        beforeImport = {
                            if (imports >= limits.maxImportPivots) {
                                throw ContinuationStop(
                                    ContinuationDecline.IMPORT_LIMIT,
                                )
                            }
                        },
                        imported = { imports++ },
                        beforePivot = {
                            if (pivots >= limits.maxPivots) throw ContinuationStop(ContinuationDecline.PIVOTS)
                        },
                        pivoted = { pivots++ },
                        repaired = { repairs++ },
                    )
                } catch (_: ContinuationOverflow) {
                    check(!big)
                    big = true
                    lane = null
                    restarts++
                }
            }
        } catch (declined: ContinuationStop) {
            stop = declined.reason
        }
        account(budget.work, budget.allocation, budget.elapsedNs)
        val metrics = ExactContinuationMetrics(
            eligible, builds, imports - oldImports, pivots - oldPivots, repairs, restarts,
            budget.work, budget.allocation, budget.elapsedNs, budget.phase, stop,
            lane?.pivots ?: 0, lane?.position ?: 0, attempts++ > 0,
            workByPhase = budget.workByPhase.toMap(), allocationByPhase = budget.allocationByPhase.toMap(),
        )
        return ExactContinuationResult(result?.values, result?.ray, result?.headings, result?.statuses, metrics)
    }

    @Suppress("ThrowsCount")
    private fun validate(budget: ContinuationBudget) {
        var oversized = false
        fun visit(value: BigFraction): BigFraction {
            if (value.num.bitLength() > 127 || value.den.bitLength() > 127) oversized = true
            return budget.fraction(value)
        }
        budget.step()
        val limits = budget.limits
        if (input.m > limits.maxRows || input.total > limits.maxColumns ||
            input.m.toLong() * (input.total.toLong() + input.m + 1L) > limits.maxCells
        ) {
            throw ContinuationStop(ContinuationDecline.DIMENSION)
        }
        if (input.lower.size != input.total || input.upper.size != input.total ||
            input.headings.size != input.m || input.statuses.size != input.total
        ) {
            throw ContinuationStop(ContinuationDecline.INVALID_INPUT)
        }
        val seen = BooleanArray(input.total)
        for (column in input.headings) {
            budget.step()
            if (column !in seen.indices || seen[column]) throw ContinuationStop(ContinuationDecline.INVALID_BASIS)
            seen[column] = true
        }
        for (j in 0 until input.total) {
            budget.step()
            val lower = input.lower[j]?.let(::visit)
            val upper = input.upper[j]?.let(::visit)
            if (lower != null && upper != null && lower > upper) {
                throw ContinuationStop(
                    ContinuationDecline.INVALID_INPUT,
                )
            }
            val valid = when (input.statuses[j]) {
                ContinuationStatus.BASIC -> seen[j]
                ContinuationStatus.LOWER -> !seen[j] && lower != null
                ContinuationStatus.UPPER -> !seen[j] && upper != null
                ContinuationStatus.FIXED -> !seen[j] && lower != null && lower == upper
                ContinuationStatus.FREE -> !seen[j] && lower == null && upper == null
            }
            if (!valid) throw ContinuationStop(ContinuationDecline.INVALID_BASIS)
        }
        for (column in input.columns) {
            var previous = -1
            for ((row, value) in column) {
                budget.step()
                if (row !in 0 until input.m || row <= previous) {
                    throw ContinuationStop(
                        ContinuationDecline.INVALID_INPUT,
                    )
                }
                visit(value)
                previous = row
            }
        }
        input.rhs.forEach(::visit)
        if (lane == null && oversized) big = true
    }
}

private class LaneResult(
    val values: List<BigFraction>? = null,
    val ray: List<BigFraction>? = null,
    val headings: List<Int>? = null,
    val statuses: List<ContinuationStatus>? = null,
)

private interface ContinuationLane {
    val pivots: Int
    val position: Int
    fun advance(
        budget: ContinuationBudget,
        beforeImport: () -> Unit,
        imported: () -> Unit,
        beforePivot: () -> Unit,
        pivoted: () -> Unit,
        repaired: () -> Unit,
    ): LaneResult
}

private class RationalContinuationLane<F>(
    private val input: ExactContinuationInput,
    private val ops: FracOps<F>,
    budget: ContinuationBudget,
) : ContinuationLane {
    private val width = input.total + input.m + 1
    private var table: List<MutableList<F>>
    private var headings = MutableList(input.m) { input.n + it }
    private var seats: MutableList<F>
    private var statuses = MutableList(input.total) { j -> canonical(j) }
    private val lower: List<F?>
    private val upper: List<F?>
    override var position = 0
        private set
    override var pivots = 0
        private set
    private var ordered = false
    private var scalarPeak = 0

    init {
        budget.step(input.m.toLong() * width, input.m.toLong() * width * 8L + input.total * 48L)
        lower = input.lower.map { it?.let { value -> fromBig(value, budget) } }
        upper = input.upper.map { it?.let { value -> fromBig(value, budget) } }
        table = List(input.m) { row ->
            MutableList(width) { j ->
                when (j) {
                    input.n + row, input.total + row -> ops.one
                    width - 1 -> fromBig(input.rhs[row], budget)
                    else -> ops.zero
                }
            }
        }
        for (j in input.columns.indices) {
            for ((row, value) in input.columns[j]) table[row][j] = fromBig(value, budget)
        }
        seats = MutableList(input.total) { j ->
            val status = input.statuses[j].takeUnless { it == ContinuationStatus.BASIC } ?: canonical(j)
            statuses[j] = if (j >= input.n) ContinuationStatus.BASIC else status
            endpoint(j, status)
        }
    }

    override fun advance(
        budget: ContinuationBudget,
        beforeImport: () -> Unit,
        imported: () -> Unit,
        beforePivot: () -> Unit,
        pivoted: () -> Unit,
        repaired: () -> Unit,
    ): LaneResult {
        budget.step()
        if (scalarPeak > budget.limits.maxBits) throw ContinuationStop(ContinuationDecline.BITS)
        budget.phase = ContinuationPhase.IMPORT
        while (position < input.m) {
            findConflict(budget)?.let { return it }
            val target = input.headings[position]
            if (target !in headings) {
                val row = headings.indices.firstOrNull {
                    budget.step()
                    headings[it] !in input.headings && !ops.isZero(table[it][target])
                }
                if (row == null) {
                    budget.phase = ContinuationPhase.REPAIR
                    repaired()
                } else {
                    beforeImport()
                    val leave = headings[row]
                    val seat = input.statuses[leave].takeUnless { it == ContinuationStatus.BASIC } ?: canonical(leave)
                    pivot(row, target, seat, budget)
                    imported()
                }
            }
            position++
        }
        findConflict(budget)?.let { return it }
        if (!ordered) {
            budget.phase = ContinuationPhase.REPAIR
            budget.step(input.m.toLong(), input.m * 32L)
            val remaining = headings.filter { it !in input.headings }.sorted().iterator()
            val order = input.headings.map { if (it in headings) it else remaining.next() }
            table = order.map { table[headings.indexOf(it)] }
            headings = order.toMutableList()
            ordered = true
        }
        budget.phase = ContinuationPhase.FEASIBILITY
        while (true) {
            val values = values(budget)
            val row = headings.indices.filter { violation(it, values, budget) != 0 }.minByOrNull { headings[it] }
            if (row == null) {
                return LaneResult(
                    values.map { toBig(it, budget) },
                    headings = headings.toList(),
                    statuses = statuses.toList(),
                )
            }
            val direction = violation(row, values, budget)
            val enter = entering(row, direction, budget)
            if (enter == null) return conflict(row, direction, budget)
            beforePivot()
            pivot(row, enter, if (direction > 0) ContinuationStatus.LOWER else ContinuationStatus.UPPER, budget)
            pivots++
            pivoted()
        }
    }

    private fun findConflict(budget: ContinuationBudget): LaneResult? {
        val values = values(budget)
        for (row in headings.indices) {
            budget.step()
            val direction = violation(row, values, budget)
            if (direction != 0 && entering(row, direction, budget) == null) return conflict(row, direction, budget)
        }
        return null
    }

    private fun violation(row: Int, values: List<F>, budget: ContinuationBudget): Int {
        val column = headings[row]
        val value = values[column]
        if (lower[column]?.let { compare(value, it, budget) < 0 } == true) return 1
        if (upper[column]?.let { compare(value, it, budget) > 0 } == true) return -1
        return 0
    }

    private fun compare(a: F, b: F, budget: ContinuationBudget): Int {
        preflight(a, b, budget)
        val result = ops.compare(a, b)
        if (ops.overflowed()) throw ContinuationOverflow()
        return result
    }

    private fun entering(row: Int, direction: Int, budget: ContinuationBudget): Int? {
        for (j in 0 until input.total) {
            budget.step()
            if (statuses[j] == ContinuationStatus.BASIC) continue
            val sign = ops.signum(table[row][j])
            if (sign == 0) continue
            val increasing = -sign == direction
            val movable = if (increasing) {
                upper[j]?.let { compare(seats[j], it, budget) < 0 } ?: true
            } else {
                lower[j]?.let { compare(seats[j], it, budget) > 0 } ?: true
            }
            if (movable) return j
        }
        return null
    }

    private fun values(budget: ContinuationBudget): List<F> {
        budget.step(bytes = input.total * 8L)
        val result = seats.toMutableList()
        for (row in headings.indices) {
            var value = table[row][width - 1]
            for (j in 0 until input.total) {
                budget.step()
                if (statuses[j] != ContinuationStatus.BASIC && !ops.isZero(seats[j])) {
                    value = subtract(value, multiply(table[row][j], seats[j], budget), budget)
                }
            }
            result[headings[row]] = value
        }
        return result
    }

    private fun conflict(row: Int, direction: Int, budget: ContinuationBudget): LaneResult = LaneResult(
        ray = List(input.m) { i ->
            val value = table[row][input.total + i]
            toBig(if (direction > 0) multiply(ops.minusOne, value, budget) else value, budget)
        },
    )

    // Publish only a complete pivot. Resource failure leaves the previous basis and tableau usable.
    private fun pivot(row: Int, enter: Int, seat: ContinuationStatus, budget: ContinuationBudget) {
        budget.step(input.m.toLong() * width, input.m.toLong() * width * 8L)
        val next = table.map { it.toMutableList() }
        val inverse = reciprocal(table[row][enter], budget)
        for (j in 0 until width) next[row][j] = multiply(table[row][j], inverse, budget)
        for (i in headings.indices) {
            if (i == row || ops.isZero(table[i][enter])) continue
            for (j in 0 until width) {
                next[i][j] = subtract(table[i][j], multiply(table[i][enter], next[row][j], budget), budget)
            }
        }
        budget.step()
        val leaving = headings[row]
        seats[leaving] = endpoint(leaving, seat)
        statuses[leaving] = seat
        statuses[enter] = ContinuationStatus.BASIC
        headings[row] = enter
        table = next
    }

    private fun canonical(j: Int): ContinuationStatus = when {
        input.lower[j] != null && input.lower[j] == input.upper[j] -> ContinuationStatus.FIXED
        input.lower[j] != null -> ContinuationStatus.LOWER
        input.upper[j] != null -> ContinuationStatus.UPPER
        else -> ContinuationStatus.FREE
    }

    private fun endpoint(j: Int, status: ContinuationStatus): F = when (status) {
        ContinuationStatus.LOWER, ContinuationStatus.FIXED -> requireNotNull(lower[j])
        ContinuationStatus.UPPER -> requireNotNull(upper[j])
        else -> ops.zero
    }

    private fun checked(value: F, budget: ContinuationBudget): F {
        if (ops.overflowed()) throw ContinuationOverflow()
        if (value is BigFraction) {
            budget.fraction(value)
            scalarPeak = maxOf(scalarPeak, value.num.bitLength(), value.den.bitLength())
        } else {
            value as Frac128
            val negative = value.nHi < 0L
            val low = if (negative) 0uL - value.nLo.toULong() else value.nLo.toULong()
            val high = if (negative) value.nHi.toULong().inv() + if (low == 0uL) 1uL else 0uL else value.nHi.toULong()
            fun bits(hi: ULong, lo: ULong) = if (hi == 0uL) {
                64 - lo.countLeadingZeroBits()
            } else {
                128 - hi.countLeadingZeroBits()
            }
            val size = maxOf(bits(high, low), bits(value.dHi.toULong(), value.dLo.toULong()))
            if (size > budget.limits.maxBits) {
                throw ContinuationStop(ContinuationDecline.BITS)
            }
            budget.step(bytes = 48L)
            scalarPeak = maxOf(scalarPeak, size)
        }
        return value
    }

    private fun preflight(a: F, b: F, budget: ContinuationBudget) {
        if (a is BigFraction && b is BigFraction) budget.preflight(a, b) else budget.step()
    }

    private fun multiply(a: F, b: F, budget: ContinuationBudget): F {
        preflight(a, b, budget)
        return checked(ops.times(a, b), budget)
    }

    private fun subtract(a: F, b: F, budget: ContinuationBudget): F {
        preflight(a, b, budget)
        return checked(ops.minus(a, b), budget)
    }

    private fun reciprocal(a: F, budget: ContinuationBudget): F {
        preflight(a, a, budget)
        return checked(ops.reciprocal(a), budget)
    }

    @Suppress("UNCHECKED_CAST")
    private fun fromBig(value: BigFraction, budget: ContinuationBudget): F {
        budget.fraction(value)
        scalarPeak = maxOf(scalarPeak, value.num.bitLength(), value.den.bitLength())
        if (ops === BigFracOps) return value as F
        val negative = value.signum() < 0
        if (value.num.bitLength() > 127 || value.den.bitLength() > 127) throw ContinuationOverflow()
        val magnitude = value.num.abs()
        val low = magnitude.longValue(exactRequired = false)
        val high = (magnitude shr 64).longValue(exactRequired = false)
        return Frac128(
            if (negative) high.inv() + if (low == 0L) 1L else 0L else high,
            if (negative) -low else low,
            (value.den shr 64).longValue(),
            value.den.longValue(exactRequired = false),
        ) as F
    }

    private fun toBig(value: F, budget: ContinuationBudget): BigFraction {
        if (value is BigFraction) return budget.fraction(value)
        value as Frac128
        val negative = value.nHi < 0
        val low = if (negative) 0uL - value.nLo.toULong() else value.nLo.toULong()
        val high = if (negative) value.nHi.toULong().inv() + if (value.nLo == 0L) 1uL else 0uL else value.nHi.toULong()
        budget.step(256, 256)
        val magnitude = (BigInteger.fromULong(high) shl 64) + BigInteger.fromULong(low)
        return budget.fraction(
            BigFraction.of(
                if (negative) -magnitude else magnitude,
                (BigInteger.fromLong(value.dHi) shl 64) + BigInteger.fromULong(value.dLo.toULong()),
            ),
        )
    }
}
