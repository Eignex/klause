package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.RationalBasisLimits
import com.eignex.klause.simplex.exact.BigRationalConflict
import com.eignex.klause.simplex.exact.ContinuationDecline
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.PollStride
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.compareTo
import com.eignex.util.numbers.div
import com.eignex.util.numbers.magnitudeBitLength
import com.eignex.util.numbers.saturatedAdd
import com.eignex.util.numbers.shl
import com.eignex.util.numbers.times
import kotlin.time.Duration
import kotlin.time.TimeSource

internal data class LpRefinementLimits(
    val maxRounds: Int = 6,
    val maxAuxiliaries: Int = 2,
    val maxCoordinates: Int = 4096,
    val maxEntries: Int = 65536,
    val maxBits: Int = 4096,
    val maxWork: Long = 1_000_000L,
    val maxAllocation: Long = 32L * 1024 * 1024,
    val maxPivots: Int = 256,
    val maxBasisDimension: Int = 128,
    val maxBasisFill: Int = 16384,
    // Unlimited by default: a clock limit makes whether a certificate stands depend on machine speed and load,
    // which changes the search tree. The deterministic limits bound the work; a caller can still set a time.
    val time: Duration = Duration.INFINITE,
) {
    init {
        require(maxRounds >= 0 && maxAuxiliaries in 0..2)
        require(maxCoordinates >= 0 && maxEntries >= 0 && maxBits >= 24)
        require(maxBasisDimension >= 0 && maxBasisFill >= 0)
        require(maxWork >= 0L && maxAllocation >= 0L && maxPivots >= 0 && time >= Duration.ZERO)
    }
}

internal enum class LpRefinementDecline {
    DISABLED,
    AUTHORITY,
    DIMENSION,
    BITS,
    WORK,
    ALLOCATION,
    PIVOTS,
    ROUNDS,
    TIME,
    CANCELLED,
    PROJECTION,
    ADOPTION,
    NUMERICAL,
    STALL,
    CANDIDATE,
    REPEATED,
    FAILURE,
}

internal data class LpRefinementMetrics(
    val eligible: Boolean = false,
    val strictAttempts: Int = 0,
    val strictWitnesses: Int = 0,
    val strictConflicts: Int = 0,
    val strictWork: Long = 0L,
    val strictElapsed: Duration = Duration.ZERO,
    val rounds: Int = 0,
    val stalls: Int = 0,
    val auxiliaries: Int = 0,
    val reconstructions: Int = 0,
    val witnesses: Int = 0,
    val bounds: Int = 0,
    val conflicts: Int = 0,
    val rays: Int = 0,
    val work: Long = 0L,
    val allocation: Long = 0L,
    val floatWork: Long = 0L,
    val preparationWork: Long = 0L,
    val reconstructionWork: Long = 0L,
    val luWork: Long = 0L,
    val pivots: Int = 0,
    val floatFactors: Int = 0,
    val observedPivots: Int = 0,
    val luFactories: Int = 0,
    val luBuilds: Int = 0,
    val luReuse: Int = 0,
    val luSolves: Int = 0,
    val elapsed: Duration = Duration.ZERO,
    val decline: LpRefinementDecline? = null,
) {
    // Sums both calls' counters and keeps the later call's decline.
    fun plus(later: LpRefinementMetrics): LpRefinementMetrics = LpRefinementMetrics(
        eligible = eligible || later.eligible,
        strictAttempts = strictAttempts + later.strictAttempts,
        strictWitnesses = strictWitnesses + later.strictWitnesses,
        strictConflicts = strictConflicts + later.strictConflicts,
        strictWork = strictWork + later.strictWork,
        strictElapsed = strictElapsed + later.strictElapsed,
        rounds = rounds + later.rounds,
        stalls = stalls + later.stalls,
        auxiliaries = auxiliaries + later.auxiliaries,
        reconstructions = reconstructions + later.reconstructions,
        witnesses = witnesses + later.witnesses,
        bounds = bounds + later.bounds,
        conflicts = conflicts + later.conflicts,
        rays = rays + later.rays,
        work = work + later.work,
        allocation = allocation + later.allocation,
        floatWork = floatWork + later.floatWork,
        preparationWork = preparationWork + later.preparationWork,
        reconstructionWork = reconstructionWork + later.reconstructionWork,
        luWork = luWork + later.luWork,
        pivots = pivots + later.pivots,
        floatFactors = floatFactors + later.floatFactors,
        observedPivots = observedPivots + later.observedPivots,
        luFactories = luFactories + later.luFactories,
        luBuilds = luBuilds + later.luBuilds,
        luReuse = luReuse + later.luReuse,
        luSolves = luSolves + later.luSolves,
        elapsed = elapsed + later.elapsed,
        decline = later.decline,
    )
}

// Spending belongs to the source owner, not to a seed, temporary model, or returned proof.
internal class LpRefinementCache {
    internal var work = 0L
    internal var allocation = 0L
    internal var pivots = 0
    internal var elapsed = Duration.ZERO
    internal var attempted: LpExactState? = null
    internal var lastLimits: LpRefinementLimits? = null
    internal var lastClaim: LpClaim? = null

    // An attempt seeded by a known exact point differs from one without: that point is the only start an optimality
    // pass has when the float solve refused the model. One of each per state and limits keeps the spending bounded.
    internal var attemptedUnseeded = false
    internal var attemptedSeeded = false
    var lastMetrics = LpRefinementMetrics()
        internal set
}

internal class LpRefinementRequest(
    val source: LpScopedSolver,
    val cache: LpRefinementCache,
    val limits: LpRefinementLimits,
    private val sourceWorkLimit: Long = 0L,
    private val sourceIterationLimit: Int = 0,
    private val preparationWork: Long = 0L,
    private val sourceSolve: LpSolveMetrics = LpSolveMetrics(),
    val onBasisVerification: ((ExactBasisMetrics) -> Unit)? = null,
) {
    val pointCache: LpRefinementCache get() = source.pointRecoveryCache

    private fun remainingSourceWork(additionalSourceWork: Long): Long =
        ((sourceWorkLimit - preparationWork).coerceAtLeast(0L) - sourceSolve.workOps).coerceAtLeast(0L)
            .let { (it - additionalSourceWork).coerceAtLeast(0L) }

    fun effectiveLimits(additionalSourceWork: Long = 0L): LpRefinementLimits = limits.copy(
        maxWork = if (sourceWorkLimit > 0L) {
            minOf(limits.maxWork, remainingSourceWork(additionalSourceWork))
        } else {
            limits.maxWork
        },
        maxPivots = if (sourceIterationLimit > 0) {
            minOf(limits.maxPivots, (sourceIterationLimit - sourceSolve.pivots).coerceAtLeast(0))
        } else {
            limits.maxPivots
        },
    )
}

internal class LpRefinementResult(
    val witness: ExactLpWitness?,
    val bound: CertifiedLpBound?,
    val conflict: BigRationalConflict?,
    val support: LpExactSupport?,
    val unboundedness: ExactLpUnboundedness?,
    val usedBasis: Boolean,
    val witnessUsesBasis: Boolean,
    val boundUsesBasis: Boolean,
    val conflictUsesBasis: Boolean,
    val unboundednessUsesBasis: Boolean,
    val sourceLuAttempted: Boolean,
    val sourceSingularBasis: Basis?,
    val metrics: LpRefinementMetrics,
)

internal class RefinementStop(val reason: LpRefinementDecline) : RuntimeException()

internal class RefinementMeter(
    val limits: LpRefinementLimits,
    private val cache: LpRefinementCache,
    private val cancellation: ExecutionControl,
    private val onBasisVerification: ((ExactBasisMetrics) -> Unit)? = null,
    private val perAttempt: Boolean = false,
    val diagnostic: LpRefinementPath? = null,
) {
    private val started = TimeSource.Monotonic.markNow()
    private val initialWork = cache.work
    private val initialAllocation = cache.allocation
    private val initialPivots = cache.pivots
    private var directWork = 0L
    private var directAllocation = 0L
    private val stride = PollStride()
    var metrics = LpRefinementMetrics()

    // A finite time limit is a deadline, so a phase handed this token can budget a share of what is left of it.
    val token = if (limits.time.isInfinite()) {
        cancellation
    } else {
        cancellation or ExecutionControl.until(started + (limits.time - elapsedForLimit()))
    }
    val spentWork: Long get() = cache.work
    val remainingWork: Long get() = minOf(
        limits.maxWork - if (perAttempt) cache.work - initialWork else cache.work,
        Long.MAX_VALUE - cache.work,
    ).coerceAtLeast(0L)
    val remainingAllocation: Long get() = minOf(
        limits.maxAllocation - if (perAttempt) cache.allocation - initialAllocation else cache.allocation,
        Long.MAX_VALUE - cache.allocation,
    ).coerceAtLeast(0L)
    val remainingPivots: Int get() = (limits.maxPivots - cache.pivots).coerceAtLeast(0)

    fun diagnosticBudget(): LpRefinementBudget = when {
        remainingWork == 0L && remainingAllocation == 0L -> LpRefinementBudget.BOTH_EMPTY
        remainingWork == 0L -> LpRefinementBudget.WORK_EMPTY
        remainingAllocation == 0L -> LpRefinementBudget.ALLOCATION_EMPTY
        else -> LpRefinementBudget.AVAILABLE
    }

    fun poll() {
        if (cancellation()) stop(LpRefinementDecline.CANCELLED)
        if (elapsedForLimit() >= limits.time) stop(LpRefinementDecline.TIME)
    }

    private fun elapsedForLimit(): Duration = started.elapsedNow() +
        if (perAttempt) Duration.ZERO else cache.elapsed

    fun charge(work: Long = 1L, bytes: Long = 0L) {
        if ( stride.due(work)) poll()
        if (work > remainingWork) stop(LpRefinementDecline.WORK)
        if (bytes > remainingAllocation) stop(LpRefinementDecline.ALLOCATION)
        cache.work = saturatedAdd(cache.work, work)
        cache.allocation = saturatedAdd(cache.allocation, bytes)
    }

    fun prior(work: Long, allocation: Long, elapsed: Duration) {
        cache.elapsed += elapsed
        directWork = work
        directAllocation = allocation
        completed(work, allocation)
    }

    fun completed(work: Long, bytes: Long = 0L, pivots: Int = 0) {
        cache.work = saturatedAdd(cache.work, work)
        cache.allocation = saturatedAdd(cache.allocation, bytes)
        cache.pivots = (cache.pivots.toLong() + pivots).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun number(value: BigFraction): BigFraction {
        val bits = maxOf(value.num.magnitudeBitLength(), value.den.magnitudeBitLength())
        if (bits > limits.maxBits) stop(LpRefinementDecline.BITS)
        charge(bytes = 64L + (bits.toLong() + 7L) / 4L)
        return value
    }

    fun multiply(a: BigFraction, b: BigFraction): BigFraction {
        operands(a, b)
        return number(
            when {
                a.isZero || b.isZero -> BigFraction.ZERO
                a == BigFraction.ONE -> b
                b == BigFraction.ONE -> a
                else -> a * b
            },
        )
    }

    fun add(a: BigFraction, b: BigFraction): BigFraction {
        operands(a, b)
        return number(
            when {
                a.isZero -> b
                b.isZero -> a
                else -> a + b
            },
        )
    }

    fun divide(a: BigFraction, b: BigFraction): BigFraction {
        operands(a, b)
        return number(a * b.reciprocal())
    }

    fun pointVisit(value: BigFraction) {
        if (maxOf(value.num.magnitudeBitLength(), value.den.magnitudeBitLength()) > limits.maxBits) {
            stop(LpRefinementDecline.BITS)
        }
        charge()
    }

    fun pointCompare(a: BigFraction, b: BigFraction): Int {
        pointVisit(a)
        pointVisit(b)
        if (a.den == b.den) return a.num.compareTo(b.num)
        if (a.signum() != b.signum()) return a.signum().compareTo(b.signum())
        val bits = maxOf(
            a.num.magnitudeBitLength() + b.den.magnitudeBitLength(),
            b.num.magnitudeBitLength() + a.den.magnitudeBitLength(),
        )
        if (bits > limits.maxBits) stop(LpRefinementDecline.BITS)
        val limbs = (bits.toLong() + 63L) / 64L
        charge(limbs * limbs, 128L + 4L * bits)
        return a.compareTo(b)
    }

    fun pointAdd(a: BigFraction, b: BigFraction): BigFraction = when {
        a.isZero -> {
            pointVisit(b)
            b
        }

        b.isZero -> {
            pointVisit(a)
            a
        }

        else -> add(a, b)
    }

    fun pointMultiply(a: BigFraction, b: BigFraction): BigFraction = when {
        a.isZero || b.isZero -> {
            pointVisit(a)
            pointVisit(b)
            BigFraction.ZERO
        }

        a == BigFraction.ONE -> {
            pointVisit(b)
            b
        }

        b == BigFraction.ONE -> {
            pointVisit(a)
            a
        }

        else -> multiply(a, b)
    }

    private fun operands(a: BigFraction, b: BigFraction) {
        number(a)
        number(b)
        val bits = maxOf(a.num.magnitudeBitLength(), a.den.magnitudeBitLength()).toLong() +
            maxOf(b.num.magnitudeBitLength(), b.den.magnitudeBitLength()) + 1L
        if (bits > limits.maxBits) stop(LpRefinementDecline.BITS)
        val limbs = (bits + 63L) / 64L
        charge(limbs * limbs, 128L + 4L * bits)
    }

    fun proofLimits(): ReconstructionLimits = ReconstructionLimits(
        maxCoordinates = limits.maxCoordinates,
        maxEntries = limits.maxEntries,
        maxBits = limits.maxBits,
        maxBasisDimension = limits.maxBasisDimension,
        maxBasisFill = limits.maxBasisFill,
        maxWork = remainingWork,
        maxAllocation = remainingAllocation,
    )

    fun basisLimits(): ExactBasisLimits = ExactBasisLimits(
        RationalBasisLimits(
            dimension = limits.maxBasisDimension,
            fill = limits.maxBasisFill,
            work = remainingWork,
            allocationBytes = remainingAllocation,
            bits = limits.maxBits,
            time = (limits.time - cache.elapsed - started.elapsedNow()).coerceAtLeast(Duration.ZERO),
        ),
        proofLimits(),
    )

    fun allowance(first: Boolean): LpFloatAllowance {
        poll()
        val divisor = if (first) 2 else 1
        val work = remainingWork / divisor
        val pivots = remainingPivots / divisor
        if (work <= 0L) stop(LpRefinementDecline.WORK)
        if (pivots <= 0) stop(LpRefinementDecline.PIVOTS)
        return LpFloatAllowance(work, pivots)
    }

    fun record(checked: ReconstructedCertificate) {
        completed(checked.metrics.work, checked.metrics.allocation)
        metrics = metrics.copy(
            reconstructions = metrics.reconstructions + 1,
            reconstructionWork = saturatedAdd(metrics.reconstructionWork, checked.metrics.work),
        )
    }

    fun record(checked: ExactBasisMetrics) {
        completed(checked.work, checked.allocation)
        metrics = metrics.copy(
            luWork = saturatedAdd(metrics.luWork, checked.work),
            luFactories = metrics.luFactories + checked.factoryCalls,
            luBuilds = metrics.luBuilds + checked.builds,
            luReuse = metrics.luReuse + checked.reuse,
            luSolves = metrics.luSolves + checked.solves,
        )
        onBasisVerification?.invoke(checked)
    }

    fun finish(reason: LpRefinementDecline?): LpRefinementMetrics {
        val elapsed = started.elapsedNow()
        cache.elapsed += elapsed
        return metrics.copy(
            work = (cache.work - initialWork - directWork).coerceAtLeast(0L),
            allocation = (cache.allocation - initialAllocation - directAllocation).coerceAtLeast(0L),
            pivots = cache.pivots - initialPivots,
            elapsed = elapsed,
            decline = reason,
        ).also { cache.lastMetrics = it }
    }

    fun stop(reason: LpRefinementDecline): Nothing = throw RefinementStop(reason)
}

internal class RefinementAuthority(val state: LpExactState, val meter: RefinementMeter) {
    val source = state.model
    val model: LpModel
    val matrix: List<List<Pair<Int, BigFraction>>>
    val rhs: List<BigFraction>
    val costs: List<BigFraction>
    val bounds: List<ExactLpBounds>

    init {
        val previousStage = meter.diagnostic?.stage
        meter.diagnostic?.stage = LpRefinementStage.SOURCE_AUTHORITY
        if (source.numVars > meter.limits.maxCoordinates) meter.stop(LpRefinementDecline.DIMENSION)
        meter.charge(source.m.toLong(), source.m * 32L)
        if (source.keySize > meter.limits.maxEntries * 3L + source.numVars * 16L + source.m * 16L) {
            meter.stop(LpRefinementDecline.DIMENSION)
        }
        var entries = source.m.toLong()
        for (j in 0 until source.n) {
            meter.charge()
            entries += source.columnEntries(j).size
            if (entries > meter.limits.maxEntries) meter.stop(LpRefinementDecline.DIMENSION)
        }
        if (entries > meter.limits.maxEntries) meter.stop(LpRefinementDecline.DIMENSION)
        meter.charge(source.numVars.toLong() + source.m, source.numVars * 96L + source.m * 64L)
        meter.charge(entries, entries * 32L)
        matrix = List(source.numVars) { j ->
            if (j < source.n) {
                source.columnEntries(j).map { it.row to meter.number(it.number.value) }
            } else {
                listOf(j - source.n to BigFraction.ONE)
            }
        }
        rhs = List(source.m) { meter.number(source.rhs(it).value) }
        costs = List(source.numVars) { meter.number(source.objective.cost(it).value) }
        bounds = List(source.numVars) { j ->
            source.column(j).bounds.also { b ->
                b.lower?.let { meter.number(it.number.value) }
                b.upper?.let { meter.number(it.number.value) }
                meter.number(source.column(j).origin.value)
            }
        }
        meter.number(source.objective.constant.value)
        meter.number(source.objective.scale.value)
        meter.number(source.objective.externalConstant.value)
        val projection = LpProjectionMeter(meter.remainingWork, meter.remainingAllocation, meter.token)
        model = try {
            state.ownerWorkingModel(projection) ?: run {
                meter.poll()
                meter.stop(when (projection.decline) {
                    ContinuationDecline.WORK -> LpRefinementDecline.WORK
                    ContinuationDecline.ALLOCATION -> LpRefinementDecline.ALLOCATION
                    else -> LpRefinementDecline.PROJECTION
                })
            }
        } finally {
            meter.completed(projection.work, projection.allocation)
        }
        previousStage?.let { meter.diagnostic.stage = it }
    }

    fun activity(x: List<BigFraction>): List<BigFraction> {
        val result = MutableList(source.m) { BigFraction.ZERO }
        for (j in matrix.indices) {
            for ((row, a) in matrix[j]) {
                result[row] = meter.add(result[row], meter.multiply(a, x[j]))
            }
        }
        return result
    }

    fun reduced(y: List<BigFraction>): List<BigFraction> = List(source.numVars) { j ->
        var value = costs[j]
        for ((row, a) in matrix[j]) value = meter.add(value, meter.multiply(a, y[row]).negated())
        value
    }

    fun fullPoint(primal: List<BigFraction>): List<BigFraction> {
        val x = MutableList(source.numVars) { j ->
            if (j < source.n) {
                meter.add(primal[j], source.column(j).origin.value.negated())
            } else {
                BigFraction.ZERO
            }
        }
        val activity = activity(x)
        for (row in rhs.indices) x[source.n + row] = meter.add(rhs[row], activity[row].negated())
        return x
    }

    fun sourcePoint(x: List<BigFraction>): List<BigFraction> = List(source.n) { j ->
        meter.add(x[j], source.column(j).origin.value)
    }

    fun seed(primal: DoubleArray, basis: Basis?): List<BigFraction> {
        if (primal.size != source.n) meter.stop(LpRefinementDecline.CANDIDATE)
        val x = fullPoint(
            primal.map {
                meter.number(
                    BigFraction.ofDouble(it)
                        ?: meter.stop(LpRefinementDecline.PROJECTION),
                )
            },
        ).toMutableList()
        if (basis != null && validBasis(basis)) {
            for (j in x.indices) {
                endpoint(j, basis.status[j])?.let { x[j] = it }
            }
        }
        return x
    }

    fun validBasis(basis: Basis): Boolean {
        meter.charge(source.numVars.toLong())
        if (basis.basicVars.size != source.m || basis.status.size != source.numVars) return false
        val seen = BooleanArray(source.numVars)
        for (j in basis.basicVars) {
            if (j !in seen.indices || seen[j]) return false
            seen[j] = true
        }
        return seen.indices.all { seen[it] == (basis.status[it] == VarStatus.BASIC) }
    }

    fun endpoint(j: Int, status: VarStatus): BigFraction? = when (status) {
        VarStatus.BASIC -> null
        VarStatus.AT_LOWER, VarStatus.FIXED -> bounds[j].lower?.number?.value
        VarStatus.AT_UPPER -> bounds[j].upper?.number?.value
        VarStatus.FREE -> if (bounds[j].lower == null && bounds[j].upper == null) BigFraction.ZERO else null
    }

    fun residual(
        x: List<BigFraction>,
        y: List<BigFraction>,
        basis: Basis?,
        previousPrimal: Int,
        previousDual: Int,
    ): ResidualModel {
        val activity = activity(x)
        val residual = List(rhs.size) { meter.add(rhs[it], activity[it].negated()) }
        val reduced = reduced(y)
        var primalViolation = BigFraction.ZERO
        var dualViolation = BigFraction.ZERO
        for (value in residual) primalViolation = largerFraction(primalViolation, value.absolute())
        for (j in bounds.indices) {
            bounds[j].lower?.let {
                primalViolation = largerFraction(
                    primalViolation,
                    meter.add(it.number.value, x[j].negated()),
                )
            }
            bounds[j].upper?.let {
                primalViolation = largerFraction(
                    primalViolation,
                    meter.add(x[j], it.number.value.negated()),
                )
            }
            val d = reduced[j]
            val defect = when (basis?.status?.getOrNull(j)) {
                VarStatus.AT_LOWER -> d.negated()
                VarStatus.AT_UPPER -> d
                VarStatus.FIXED -> if (bounds[j].fixed) BigFraction.ZERO else d.absolute()
                else -> d.absolute()
            }
            dualViolation = largerFraction(dualViolation, defect)
        }
        val exponent = scaleExponent(primalViolation, previousPrimal, meter)
        val dualExponent = minOf(exponent, scaleExponent(dualViolation, previousDual, meter))
        val sp = meter.number(BigFraction.of(BIG_ONE shl exponent, BIG_ONE))
        val sd = meter.number(BigFraction.of(BIG_ONE shl dualExponent, BIG_ONE))
        meter.charge(source.keySize, 128L + source.keySize * 8L)
        val columns = List(source.numVars) { j ->
            fun side(value: ExactLpSide?): ExactLpSide? = value?.let {
                ExactLpSide(ExactLpNumber.of(meter.multiply(sp, meter.add(it.number.value, x[j].negated()))))
            }
            ExactLpColumn(ExactLpBounds(side(bounds[j].lower), side(bounds[j].upper)), integral = false)
        }
        val working = ExactLpModel(
            List(source.n, source::columnEntries),
            residual.map { ExactLpNumber.of(meter.multiply(sp, it)) },
            columns,
            List(source.m) { source.row(it).copy(strict = false) },
            ExactLpObjective(reduced.map { ExactLpNumber.of(meter.multiply(sd, it)) }),
        )
        return ResidualModel(LpWorkingModel(state, working), sp, sd, exponent, dualExponent)
    }
}

private fun largerFraction(a: BigFraction, b: BigFraction): BigFraction = if (a > b) a else b

private fun BigFraction.absolute(): BigFraction = if (signum() < 0) negated() else this

private fun scaleExponent(violation: BigFraction, previous: Int, meter: RefinementMeter): Int {
    if (violation.isZero) return previous
    meter.number(violation)
    var exponent = (violation.den.magnitudeBitLength() - violation.num.magnitudeBitLength())
        .coerceIn(0, minOf(512, previous + 80))
    while (exponent > 0 && meter.multiply(
            violation,
            BigFraction.of(
                BIG_ONE shl exponent,
                BIG_ONE,
            ),
        ) > BigFraction.ONE
    ) {
        exponent--
    }
    return exponent
}

internal fun refinementWorking(source: LpExactState, model: ExactLpModel, meter: RefinementMeter): LpWorkingModel {
    meter.charge(model.keySize, 128L + model.keySize * 8L)
    return LpWorkingModel(source, model)
}

internal class ResidualModel(
    val working: LpWorkingModel,
    val primalScale: BigFraction,
    val dualScale: BigFraction,
    val primalExponent: Int,
    val dualExponent: Int,
)
