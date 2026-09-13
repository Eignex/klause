package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.RationalBasisBuild
import com.eignex.klause.simplex.basis.RationalBasisFactors
import com.eignex.klause.simplex.basis.RationalBasisLimits
import com.eignex.klause.simplex.basis.RationalBasisSolve
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.BigRationalConflict
import com.eignex.klause.util.Cancellation
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
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
    val time: Duration = 2.seconds,
) {
    init {
        require(maxRounds in 0..64 && maxAuxiliaries in 0..2)
        require(maxCoordinates >= 0 && maxEntries >= 0 && maxBits >= 24)
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
)

// Spending belongs to the source owner, not to a seed, temporary model, or returned proof.
internal class LpRefinementCache {
    internal var work = 0L
    internal var allocation = 0L
    internal var pivots = 0
    internal var elapsed = Duration.ZERO
    internal var attempted: LpExactState? = null
    internal var lastLimits: LpRefinementLimits? = null
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
) {
    fun effectiveLimits(): LpRefinementLimits = limits.copy(
        maxWork = if (sourceWorkLimit > 0L) {
            minOf(limits.maxWork, (sourceWorkLimit - preparationWork - sourceSolve.workOps).coerceAtLeast(0L))
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
    val metrics: LpRefinementMetrics,
)

private class RefinementStop(val reason: LpRefinementDecline) : RuntimeException()

private class RefinementMeter(
    val limits: LpRefinementLimits,
    private val cache: LpRefinementCache,
    private val cancellation: Cancellation,
) {
    private val started = TimeSource.Monotonic.markNow()
    private val initialWork = cache.work
    private val initialAllocation = cache.allocation
    private val initialPivots = cache.pivots
    private var directWork = 0L
    private var directAllocation = 0L
    var metrics = LpRefinementMetrics()
    val token = Cancellation { cancellation() || cache.elapsed + started.elapsedNow() >= limits.time }
    val remainingWork: Long get() = (limits.maxWork - cache.work).coerceAtLeast(0L)
    val remainingAllocation: Long get() = (limits.maxAllocation - cache.allocation).coerceAtLeast(0L)
    val remainingPivots: Int get() = (limits.maxPivots - cache.pivots).coerceAtLeast(0)

    fun poll() {
        if (cancellation()) stop(LpRefinementDecline.CANCELLED)
        if (cache.elapsed + started.elapsedNow() >= limits.time) stop(LpRefinementDecline.TIME)
    }

    fun charge(work: Long = 1L, bytes: Long = 0L) {
        poll()
        if (work > remainingWork) stop(LpRefinementDecline.WORK)
        if (bytes > remainingAllocation) stop(LpRefinementDecline.ALLOCATION)
        cache.work += work
        cache.allocation += bytes
    }

    fun prior(work: Long, allocation: Long, elapsed: Duration) {
        cache.elapsed += elapsed
        directWork = work
        directAllocation = allocation
        completed(work, allocation)
    }

    fun completed(work: Long, bytes: Long = 0L, pivots: Int = 0) {
        cache.work = addSaturated(cache.work, work)
        cache.allocation = addSaturated(cache.allocation, bytes)
        cache.pivots = (cache.pivots.toLong() + pivots).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun number(value: BigFraction): BigFraction {
        val bits = maxOf(value.num.bitLength(), value.den.bitLength())
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

    private fun operands(a: BigFraction, b: BigFraction) {
        number(a)
        number(b)
        val bits = maxOf(a.num.bitLength(), a.den.bitLength()).toLong() +
            maxOf(b.num.bitLength(), b.den.bitLength()) + 1L
        val limbs = (bits + 63L) / 64L
        charge(limbs * limbs, 128L + 4L * bits)
    }

    fun proofLimits(): ReconstructionLimits = ReconstructionLimits(
        maxCoordinates = limits.maxCoordinates,
        maxEntries = limits.maxEntries,
        maxBits = limits.maxBits,
        maxWork = minOf(250_000L, remainingWork),
        maxAllocation = minOf(16L * 1024 * 1024, remainingAllocation),
    )

    fun basisLimits(): ExactBasisLimits = ExactBasisLimits(
        RationalBasisLimits(
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
            reconstructionWork = addSaturated(metrics.reconstructionWork, checked.metrics.work),
        )
    }

    fun record(checked: ExactBasisMetrics) {
        completed(checked.work, checked.allocation)
        metrics = metrics.copy(
            luWork = addSaturated(metrics.luWork, checked.work),
            luFactories = metrics.luFactories + checked.factoryCalls,
            luBuilds = metrics.luBuilds + checked.builds,
            luReuse = metrics.luReuse + checked.reuse,
            luSolves = metrics.luSolves + checked.solves,
        )
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

private fun addSaturated(a: Long, b: Long): Long = if (b > Long.MAX_VALUE - a) Long.MAX_VALUE else a + b

private class RefinementAuthority(val state: LpExactState, val meter: RefinementMeter) {
    val source = state.model
    val model: LpModel
    val matrix: List<List<Pair<Int, BigFraction>>>
    val rhs: List<BigFraction>
    val costs: List<BigFraction>
    val bounds: List<ExactLpBounds>

    init {
        if (source.numVars > meter.limits.maxCoordinates || source.keySize >
            meter.limits.maxEntries * 3L + source.numVars * 16L + source.m * 16L
        ) {
            meter.stop(
                LpRefinementDecline.DIMENSION,
            )
        }
        meter.charge(source.numVars.toLong() + source.m, source.numVars * 96L + source.m * 64L)
        var entries = 0
        matrix = List(source.numVars) { j ->
            val column = if (j < source.n) {
                source.entries(j).map { it.row to meter.number(it.number.value) }
            } else {
                listOf(j - source.n to BigFraction.ONE)
            }
            entries += column.size
            if (entries > meter.limits.maxEntries) meter.stop(LpRefinementDecline.DIMENSION)
            meter.charge(column.size.toLong(), column.size * 32L)
            column
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
        model = state.toWorkingModel() ?: meter.stop(LpRefinementDecline.PROJECTION)
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
        val sp = meter.number(BigFraction.of(BigInteger.ONE shl exponent, BigInteger.ONE))
        val sd = meter.number(BigFraction.of(BigInteger.ONE shl dualExponent, BigInteger.ONE))
        val columns = List(source.numVars) { j ->
            fun side(value: ExactLpSide?): ExactLpSide? = value?.let {
                ExactLpSide(ExactLpNumber.of(meter.multiply(sp, meter.add(it.number.value, x[j].negated()))))
            }
            ExactLpColumn(ExactLpBounds(side(bounds[j].lower), side(bounds[j].upper)), integral = false)
        }
        val working = ExactLpModel(
            List(source.n) { source.entries(it) },
            residual.map { ExactLpNumber.of(meter.multiply(sp, it)) },
            columns,
            List(source.m) { source.row(it).copy(strict = false) },
            ExactLpObjective(reduced.map { ExactLpNumber.of(meter.multiply(sd, it)) }),
        )
        return ResidualModel(refinementWorking(state, working, meter), sp, sd, exponent, dualExponent)
    }
}

private fun largerFraction(a: BigFraction, b: BigFraction): BigFraction = if (a > b) a else b

private fun BigFraction.absolute(): BigFraction = if (signum() < 0) negated() else this

private fun scaleExponent(violation: BigFraction, previous: Int, meter: RefinementMeter): Int {
    if (violation.isZero) return previous
    meter.number(violation)
    var exponent = (violation.den.bitLength() - violation.num.bitLength()).coerceIn(0, minOf(512, previous + 80))
    while (exponent > 0 && meter.multiply(
            violation,
            BigFraction.of(
                BigInteger.ONE shl exponent,
                BigInteger.ONE,
            ),
        ) > BigFraction.ONE
    ) {
        exponent--
    }
    return exponent
}

private fun refinementWorking(source: LpExactState, model: ExactLpModel, meter: RefinementMeter): LpWorkingModel {
    meter.charge(model.keySize, 128L + model.keySize * 8L)
    return LpWorkingModel(source, model)
}

private class ResidualModel(
    val working: LpWorkingModel,
    val primalScale: BigFraction,
    val dualScale: BigFraction,
    val primalExponent: Int,
    val dualExponent: Int,
)

private class RefinedCandidate(
    var point: ExactLpWitness? = null,
    var bound: CertifiedLpBound? = null,
    var dual: List<BigFraction>? = null,
    var conflict: BigRationalConflict? = null,
    var support: LpExactSupport? = null,
    var basis: Basis? = null,
    var usedBasis: Boolean = false,
    var luAttempted: Boolean = false,
    var pointUsesBasis: Boolean = false,
    var boundUsesBasis: Boolean = false,
    var conflictUsesBasis: Boolean = false,
    var dualUsesBasis: Boolean = false,
) {
    val attained: Boolean get() = point != null && bound?.value == point?.objective
    fun acceptPoint(value: ExactLpWitness, basis: Boolean) {
        val current = point
        if (current == null || value.objective < current.objective ||
            (value.objective == current.objective && pointUsesBasis && !basis)
        ) {
            point = value
            pointUsesBasis = basis
        }
    }

    fun acceptBound(value: CertifiedLpBound, basis: Boolean) {
        val current = bound
        if (current == null || value.value > current.value ||
            (value.value == current.value && boundUsesBasis && !basis)
        ) {
            bound = value
            boundUsesBasis = basis
        }
    }

    fun accept(checked: ReconstructedCertificate, basis: Boolean = false, pointBasis: Boolean = basis) {
        checked.witness?.let { acceptPoint(it, pointBasis) }
        checked.bound?.let { acceptBound(it, basis) }
        if (checked.conflict != null) {
            conflict = checked.conflict
            support = checked.conflictSupport
            conflictUsesBasis = basis
        }
        val success = checked.witness != null || checked.bound != null || checked.conflict != null
        usedBasis = usedBasis || (basis && success) || (pointBasis && checked.witness != null)
    }
}

private class RefinementRun(
    private val request: LpRefinementRequest,
    private val meter: RefinementMeter,
    private val sourceModel: LpModel,
    private val sourceFactors: ExactBasisCache,
) {
    private val source by lazy { RefinementAuthority(requireNotNull(sourceModel.exactState), meter) }
    val candidate = RefinedCandidate()
    var unboundedness: ExactLpUnboundedness? = null
    var unboundednessUsesBasis = false

    fun run(
        primal: DoubleArray?,
        duals: DoubleArray?,
        basis: Basis?,
        known: ExactLpWitness?,
        needPoint: Boolean,
        hint: DoubleArray?,
        reconstructInitial: Boolean,
        preferBasis: Boolean,
    ) {
        candidate.basis = basis
        if (preferBasis && basis != null && sourceModel.m <= meter.basisLimits().factor.dimension) {
            checkBasis(sourceModel, basis, sourceFactors, candidate)
            if (candidate.attained || candidate.conflict != null) return
        }
        if (known != null) candidate.accept(check(source, known.primal, null, null, false))
        if (primal != null && duals != null && meter.limits.maxRounds > 0) {
            improve(source, primal, duals, basis, null, candidate, sourceFactors, reconstructInitial)
        }
        if (candidate.attained || candidate.conflict != null) return
        // A finite lower bound rules out recession but says nothing about source feasibility.
        var direction = if (candidate.bound == null) hint?.let { reconstructedDirection(it) } else null
        var directionUsesBasis = false
        if (direction == null && candidate.bound == null && basis != null) {
            direction = directRay(source, basis, sourceFactors)
            directionUsesBasis = direction != null
            candidate.usedBasis = candidate.usedBasis || directionUsesBasis
        }
        if (candidate.point == null && needPoint && meter.limits.maxAuxiliaries > 0) {
            feasibility()
        }
        if (candidate.conflict != null || candidate.bound != null) return
        if (direction == null && candidate.point != null && meter.metrics.auxiliaries < meter.limits.maxAuxiliaries) {
            recession()?.let {
                direction = it.first
                directionUsesBasis = it.second
            }
        }
        val point = candidate.point
        val ray = direction
        if (point != null && ray != null) {
            unboundedness = exactRecession(source, point, ray, meter)
            if (unboundedness != null) {
                unboundednessUsesBasis = candidate.pointUsesBasis || directionUsesBasis
                meter.metrics = meter.metrics.copy(rays = meter.metrics.rays + 1)
            }
        }
    }

    private fun check(
        a: RefinementAuthority,
        x: List<BigFraction>?,
        y: List<BigFraction>?,
        basis: Basis?,
        reconstruct: Boolean,
    ): ReconstructedCertificate {
        meter.poll()
        val checked = if (reconstruct) {
            reconstructRationalCertificate(
                a.model,
                x,
                y,
                basis,
                cancellation = meter.token,
                limits = meter.proofLimits(),
            )
        } else {
            verifyRationalCertificate(
                a.model,
                x,
                y,
                basis = basis,
                cancellation = meter.token,
                limits = meter.proofLimits(),
            )
        }
        meter.record(checked)
        return checked
    }

    private fun improve(
        a: RefinementAuthority,
        primal: DoubleArray,
        duals: DoubleArray,
        proposed: Basis?,
        anchor: LpWorkingScope?,
        output: RefinedCandidate,
        factors: ExactBasisCache,
        reconstructInitial: Boolean = true,
    ) {
        if (duals.size != a.source.m) meter.stop(LpRefinementDecline.CANDIDATE)
        val pointUsesBasis = output.point != null && output.pointUsesBasis
        val knownPoint = output.point?.let { a.fullPoint(it.primal) }
        var x = knownPoint ?: a.seed(primal, proposed)
        var y = duals.map { meter.number(BigFraction.ofDouble(it) ?: meter.stop(LpRefinementDecline.PROJECTION)) }
        var basis = proposed?.takeIf(a::validBasis)
        output.basis = basis
        output.accept(
            check(a, if (knownPoint == null) a.sourcePoint(x) else null, y, basis, reconstructInitial),
            pointBasis = pointUsesBasis,
        )
        output.dual = y
        output.dualUsesBasis = false
        if (output.attained || output.conflict != null || meter.limits.maxRounds == 0) return
        var residual = a.residual(x, y, basis, 0, 0)
        var nextReconstruction = 1
        var stalls = 0
        withChild(residual.working, anchor) { scope, firstAllowance ->
            for (round in 1..(meter.limits.maxRounds - meter.metrics.rounds).coerceAtLeast(0)) {
                meter.poll()
                if (round > 1 && !scope.replaceState(residual.working)) meter.stop(LpRefinementDecline.ADOPTION)
                val attempt = numerical(scope, if (round == 1) basis else null, firstAllowance, round == 1)
                val float = attempt?.second ?: meter.stop(LpRefinementDecline.NUMERICAL)
                val correction = RefinementAuthority(scope.state, meter)
                val dx = correction.seed(float.primal, float.basis)
                x = x.indices.map { meter.add(x[it], meter.multiply(dx[it], residual.primalScale.reciprocal())) }
                y = y.indices.map {
                    meter.add(
                        y[it],
                        meter.multiply(
                            meter.number(
                                BigFraction.ofDouble(float.duals[it])
                                    ?: meter.stop(LpRefinementDecline.PROJECTION),
                            ),
                            residual.dualScale.reciprocal(),
                        ),
                    )
                }
                basis = float.basis
                output.basis = basis
                output.dual = y
                output.dualUsesBasis = false
                meter.metrics = meter.metrics.copy(rounds = meter.metrics.rounds + 1)
                if (attempt.first.lastPivots == 0) {
                    stalls++
                    meter.metrics = meter.metrics.copy(stalls = meter.metrics.stalls + 1)
                } else {
                    stalls = 0
                }
                val reconstruct = round >= nextReconstruction
                if (knownPoint != null) meter.charge(x.size.toLong())
                val primalCandidate = if (x == knownPoint) null else a.sourcePoint(x)
                output.accept(check(a, primalCandidate, y, basis, reconstruct), pointBasis = pointUsesBasis)
                if (reconstruct) nextReconstruction = nextReconstructionRound(round)
                if (output.attained || output.conflict != null) break
                if (stalls >= 2) {
                    if (!output.luAttempted) exactCandidate(a, requireNotNull(basis), factors, output)
                    break
                }
                residual = a.residual(x, y, basis, residual.primalExponent, residual.dualExponent)
            }
        }
    }

    private fun numerical(
        scope: LpWorkingScope,
        warm: Basis?,
        firstAllowance: LpFloatAllowance,
        first: Boolean,
    ): Pair<LpSolver, FloatLpResult?>? {
        val before = scope.metrics
        val allowance = if (first) firstAllowance else meter.allowance(false)
        // prepareLogicals performs no simplex pivots; reserve its full ceiling conservatively.
        if (first) meter.completed(0L, pivots = firstAllowance.iterations)
        return try {
            scope.solveFloat(warm, if (warm != null) null else allowance)
        } finally {
            val after = scope.metrics
            val preparation = after.owners.preparationWork - before.owners.preparationWork
            val work = after.solves.workOps - before.solves.workOps
            val pivots = after.solves.pivots - before.solves.pivots
            meter.completed(addSaturated(preparation, work), pivots = pivots)
            meter.metrics = meter.metrics.copy(
                floatWork = addSaturated(meter.metrics.floatWork, work),
                preparationWork = addSaturated(meter.metrics.preparationWork, preparation),
                floatFactors = meter.metrics.floatFactors + floatFactorCount(
                    after.solves,
                ) - floatFactorCount(before.solves),
                observedPivots = meter.metrics.observedPivots + pivots,
            )
        }
    }

    private fun <T> withChild(
        working: LpWorkingModel,
        anchor: LpWorkingScope?,
        block: (LpWorkingScope, LpFloatAllowance) -> T,
    ): T {
        val allowance = meter.allowance(true)
        return if (anchor == null) {
            request.source.withWorkingModel(working, meter.token, allowance) { block(it, allowance) }
        } else {
            anchor.withWorkingModel(working, allowance) { block(it, allowance) }
        }
    }

    private fun exactCandidate(
        a: RefinementAuthority,
        basis: Basis,
        cache: ExactBasisCache,
        output: RefinedCandidate,
    ) {
        checkBasis(a.model, basis, cache, output)
        if (a === source && output.attained) return
        // The public bound package deliberately contains no raw BTRAN vector.
        exactVectors(a, basis, cache, allowBuild = false) { authority, factors, budget ->
            budget.phase = ExactBasisPhase.DUAL
            output.dual = exactSolve(factors, authority.costs, true, budget)
            output.dualUsesBasis = true
        }
    }

    private fun checkBasis(model: LpModel, basis: Basis, cache: ExactBasisCache, output: RefinedCandidate) {
        output.luAttempted = true
        val checked = verifyExactBasis(
            model,
            basis,
            cache = cache,
            cancellation = meter.token,
            limits = meter.basisLimits(),
        )
        meter.record(checked.metrics)
        checked.witness?.let { output.acceptPoint(it, true) }
        checked.bound?.let { output.acceptBound(it, true) }
        output.usedBasis = checked.witness != null || checked.bound != null || output.usedBasis
    }

    private fun exactVectors(
        a: RefinementAuthority,
        basis: Basis,
        cache: ExactBasisCache,
        allowBuild: Boolean = true,
        block: (ExactBasisAuthority, RationalBasisFactors, ExactBasisMeter) -> Unit,
    ) {
        val budget = ExactBasisMeter(meter.basisLimits(), meter.token)
        var reason: ExactBasisDecline? = null
        try {
            val authority = ExactBasisAuthority(a.model, basis, budget)
            val cached = cache.find(authority)
            if (cached == null && !allowBuild) return
            val factors = if (cached != null) {
                budget.reuse++
                cached
            } else {
                val matrix = authority.basisMatrix()
                budget.phase = ExactBasisPhase.FACTOR
                val order = cache.proposedOrder(authority)
                budget.factoryCalls++
                val build = RationalBasisFactors.factor(matrix, order, budget.factorLimits(), budget.token)
                budget.record(build.stats)
                when (build) {
                    is RationalBasisBuild.Ready -> build.factors.also { cache.install(authority, it) }
                    is RationalBasisBuild.Singular -> budget.stop(ExactBasisDecline.SINGULAR)
                    is RationalBasisBuild.Declined -> budget.stop(ExactBasisDecline.valueOf(build.reason.name))
                }
            }
            block(authority, factors, budget)
        } catch (stop: ExactBasisStop) {
            reason = stop.reason
        } finally {
            meter.record(budget.snapshot(reason))
        }
    }

    private fun reconstructedDirection(hint: DoubleArray): List<BigFraction>? {
        if (hint.size != source.source.n || hint.any { !it.isFinite() }) return null
        val zero = ExactLpSide(ExactLpNumber.of(0L))
        meter.charge(source.source.keySize, source.source.keySize * 8L)
        val cone = ExactLpModel(
            List(source.source.n) { source.source.entries(it) },
            List(source.source.m) { ExactLpNumber.of(0L) },
            List(source.source.numVars) { j ->
                ExactLpColumn(
                    ExactLpBounds(
                        if (source.bounds[j].lower != null) zero else null,
                        if (source.bounds[j].upper != null) zero else null,
                    ),
                    integral = false,
                )
            },
            List(source.source.m) { source.source.row(it).copy(strict = false) },
            ExactLpObjective(source.costs.map { ExactLpNumber.of(it) }),
        )
        val authority = RefinementAuthority(LpExactState(cone), meter)
        val checked = reconstructCertificate(
            authority.model,
            primal = hint,
            cancellation = meter.token,
            limits = meter.proofLimits(),
        )
        meter.record(checked)
        val point = checked.witness ?: return null
        return authority.fullPoint(point.primal).takeIf { validDirection(source, it, meter) }
    }

    private fun directRay(a: RefinementAuthority, basis: Basis, cache: ExactBasisCache): List<BigFraction>? {
        if (!a.validBasis(basis)) return null
        var attempts = 0
        for (j in basis.status.indices) {
            if (basis.status[j] == VarStatus.BASIC) continue
            val signs = when {
                a.bounds[j].lower != null && a.bounds[j].upper != null -> emptyList()
                a.bounds[j].lower != null -> listOf(BigFraction.ONE)
                a.bounds[j].upper != null -> listOf(BigFraction.MINUS_ONE)
                else -> listOf(BigFraction.ONE, BigFraction.MINUS_ONE)
            }
            for (sign in signs) {
                if (++attempts > 8) return null
                var candidate: List<BigFraction>? = null
                exactVectors(a, basis, cache) { authority, factors, budget ->
                    budget.phase = ExactBasisPhase.RAY
                    val rhs = MutableList(a.source.m) { BigFraction.ZERO }
                    for ((row, coefficient) in authority.matrix[j]) {
                        rhs[row] = budget.subtractProduct(rhs[row], coefficient, sign)
                    }
                    val basics = exactSolve(factors, rhs, false, budget)
                    val ray = MutableList(a.source.numVars) { BigFraction.ZERO }
                    ray[j] = sign
                    for (i in authority.headings.indices) ray[authority.headings[i]] = basics[i]
                    candidate = ray
                }
                // FTRAN spending must be debited before source checking consumes the remainder.
                val ray = candidate ?: return null
                if (validDirection(a, ray, meter)) return ray
            }
        }
        return null
    }

    private fun feasibility() {
        val shifts = source.bounds.map { b ->
            when {
                b.lower != null && b.lower.number.value.signum() > 0 -> b.lower.number.value
                b.upper != null && b.upper.number.value.signum() < 0 -> b.upper.number.value
                else -> BigFraction.ZERO
            }
        }
        val activity = source.activity(shifts)
        val h = source.rhs.indices.map { meter.add(source.rhs[it], activity[it].negated()) }
        val auxiliary = auxiliaryModel(source, shifts, h, recession = false)
        val solved = auxiliary(auxiliary)
        val point = solved.point
        if (point != null && point.primal[source.source.numVars] == BigFraction.ONE) {
            val x = source.bounds.indices.map { meter.add(shifts[it], point.primal[it]) }
            val checked = check(source, source.sourcePoint(x), null, null, false)
            candidate.accept(checked, solved.pointUsesBasis)
        }
        if (candidate.point != null) return
        solved.dual?.let { y ->
            val checked = verifyRationalCertificate(
                source.model,
                ray = y,
                cancellation = meter.token,
                limits = meter.proofLimits(),
            )
            meter.record(checked)
            candidate.accept(checked, solved.dualUsesBasis)
        }
    }

    private fun recession(): Pair<List<BigFraction>, Boolean>? {
        val auxiliary = auxiliaryModel(
            source,
            List(source.source.numVars) { BigFraction.ZERO },
            source.rhs.map { BigFraction.ZERO },
            recession = true,
        )
        val solved = auxiliary(auxiliary)
        val point = solved.point ?: return null
        if (point.primal[source.source.numVars] != BigFraction.ONE) return null
        val ray = point.primal.take(source.source.numVars)
        if (!validDirection(source, ray, meter)) return null
        candidate.usedBasis = candidate.usedBasis || solved.pointUsesBasis
        return ray to solved.pointUsesBasis
    }

    private fun auxiliary(model: ExactLpModel): RefinedCandidate {
        meter.metrics = meter.metrics.copy(auxiliaries = meter.metrics.auxiliaries + 1)
        val output = RefinedCandidate()
        withChild(refinementWorking(source.state, model, meter), null) { scope, allowance ->
            val attempt = numerical(scope, null, allowance, true)
            val result = attempt?.second ?: return@withChild
            val a = RefinementAuthority(scope.state, meter)
            val factors = ExactBasisCache()
            improve(a, result.primal, result.duals, result.basis, scope, output, factors)
            if (!output.attained && !output.luAttempted) exactCandidate(a, result.basis, factors, output)
            if (output.dual != null && output.bound?.value?.let { it > BigFraction.MINUS_ONE } == true) {
                // Exact BTRAN can annihilate source free columns that an IEEE dual misses.
                if (!output.luAttempted) exactCandidate(a, output.basis ?: result.basis, factors, output)
            }
        }
        return output
    }
}

private fun floatFactorCount(metrics: LpSolveMetrics): Int = metrics.initialRefactorizations +
    metrics.warmStartRefactorizations + metrics.singularRecoveryRefactorizations + metrics.updateLimitRefactorizations +
    metrics.backendRequestedRefactorizations + metrics.reconcileRecoveryRefactorizations +
    metrics.numericalRecoveryRefactorizations + metrics.primalRefactorizations

private fun exactSolve(
    factors: RationalBasisFactors,
    rhs: List<BigFraction>,
    transpose: Boolean,
    meter: ExactBasisMeter,
): List<BigFraction> {
    meter.solves++
    val solved = factors.solve(rhs, transpose, meter.factorLimits(), meter.token)
    meter.record(solved.stats)
    return when (solved) {
        is RationalBasisSolve.Solved -> solved.values
        is RationalBasisSolve.Declined -> meter.stop(ExactBasisDecline.valueOf(solved.reason.name))
    }
}

private fun auxiliaryModel(
    a: RefinementAuthority,
    shifts: List<BigFraction>,
    h: List<BigFraction>,
    recession: Boolean,
): ExactLpModel {
    val meter = a.meter
    val count = a.source.numVars
    val rows = a.source.m + if (recession) 1 else 0
    if (count.toLong() + 1 + rows > meter.limits.maxCoordinates) meter.stop(LpRefinementDecline.DIMENSION)
    meter.charge(count.toLong() + rows, 128L * (count + 1L + rows))
    val matrix = List(count + 1) { j ->
        val entries = ArrayList<ExactLpEntry>()
        if (j < count) {
            for ((row, coefficient) in a.matrix[j]) entries += ExactLpEntry(row, ExactLpNumber.of(coefficient))
            if (recession && !a.costs[j].isZero) entries += ExactLpEntry(a.source.m, ExactLpNumber.of(a.costs[j]))
        } else if (recession) {
            entries += ExactLpEntry(a.source.m, ExactLpNumber.of(1L))
        } else {
            for (row in h.indices) if (!h[row].isZero) entries += ExactLpEntry(row, ExactLpNumber.of(h[row].negated()))
        }
        meter.charge(entries.size.toLong(), entries.size * 32L)
        entries
    }
    if (matrix.sumOf { it.size.toLong() } + rows > meter.limits.maxEntries) meter.stop(LpRefinementDecline.DIMENSION)
    val zero = ExactLpSide(ExactLpNumber.of(0L))
    val columns = List(count + 1 + rows) { j ->
        val bounds = if (j < count) {
            val b = a.bounds[j]
            fun side(value: ExactLpSide?): ExactLpSide? = value?.let {
                if (recession) zero else ExactLpSide(ExactLpNumber.of(meter.add(it.number.value, shifts[j].negated())))
            }
            ExactLpBounds(side(b.lower), side(b.upper))
        } else if (j == count) {
            ExactLpBounds(zero, ExactLpSide(ExactLpNumber.of(1L)))
        } else {
            ExactLpBounds(zero, zero)
        }
        ExactLpColumn(bounds, integral = false)
    }
    return ExactLpModel(
        matrix,
        List(rows) { ExactLpNumber.of(0L) },
        columns,
        List(rows) { ExactLpRow() },
        ExactLpObjective(List(columns.size) { ExactLpNumber.of(if (it == count) -1L else 0L) }),
    )
}

private fun validDirection(a: RefinementAuthority, ray: List<BigFraction>, meter: RefinementMeter): Boolean {
    if (ray.size != a.source.numVars) return false
    var improvement = BigFraction.ZERO
    for (j in ray.indices) {
        val value = meter.number(ray[j])
        if (a.bounds[j].lower != null && value.signum() < 0) return false
        if (a.bounds[j].upper != null && value.signum() > 0) return false
        improvement = meter.add(improvement, meter.multiply(a.costs[j], value))
    }
    return improvement.signum() < 0 && a.activity(ray).all { it.isZero }
}

private fun exactRecession(
    a: RefinementAuthority,
    witness: ExactLpWitness,
    ray: List<BigFraction>,
    meter: RefinementMeter,
): ExactLpUnboundedness? {
    val checked = verifyRationalCertificate(
        a.model,
        sourcePrimal = witness.primal,
        cancellation = meter.token,
        limits = meter.proofLimits(),
    )
    meter.record(checked)
    val point = checked.witness ?: return null
    val full = a.fullPoint(point.primal)
    var denominator = BigInteger.ONE
    for (j in full.indices) {
        if (a.source.column(j).integral) {
            val coordinate = if (j < a.source.n) point.primal[j] else full[j]
            if (coordinate.den != BigInteger.ONE) return null
            meter.number(ray[j])
            meter.number(BigFraction.of(denominator, BigInteger.ONE))
            meter.charge()
            denominator = denominator / denominator.gcd(ray[j].den) * ray[j].den
            meter.number(BigFraction.of(denominator, BigInteger.ONE))
        }
    }
    val scale = meter.number(BigFraction.of(denominator, BigInteger.ONE))
    val integralRay = ray.map { meter.multiply(it, scale) }
    if (!validDirection(a, integralRay, meter)) return null
    if (integralRay.indices.any { a.source.column(it).integral && integralRay[it].den != BigInteger.ONE }) return null
    return ExactLpUnboundedness(point, integralRay.take(a.source.n))
}

@Suppress("TooGenericExceptionCaught")
internal fun refineLp(
    model: LpModel,
    request: LpRefinementRequest,
    primal: DoubleArray? = null,
    duals: DoubleArray? = null,
    basis: Basis? = null,
    witness: ExactLpWitness? = null,
    cancellation: Cancellation = Cancellation.Never,
    directWork: Long = 0L,
    directAllocation: Long = 0L,
    needPoint: Boolean = true,
    direction: DoubleArray? = null,
    directElapsed: Duration = Duration.ZERO,
    reconstructInitial: Boolean = true,
    preferBasis: Boolean = false,
    preferredBasisCache: ExactBasisCache? = null,
): LpRefinementResult {
    val state = model.exactState
    val limits = request.effectiveLimits()
    val cache = request.cache
    val meter = RefinementMeter(limits, cache, cancellation)
    var run: RefinementRun? = null
    var reason: LpRefinementDecline? = LpRefinementDecline.FAILURE
    var metrics: LpRefinementMetrics
    try {
        if (state == null || request.source.state !== state) meter.stop(LpRefinementDecline.AUTHORITY)
        if (limits.maxRounds == 0 && limits.maxAuxiliaries == 0) meter.stop(LpRefinementDecline.DISABLED)
        if (cache.attempted === state && limits == cache.lastLimits) meter.stop(LpRefinementDecline.REPEATED)
        cache.attempted = state
        cache.lastLimits = limits
        meter.prior(directWork, directAllocation, directElapsed)
        meter.charge()
        meter.metrics = meter.metrics.copy(eligible = true)
        val factors = preferredBasisCache?.takeIf { preferBasis && basis != null } ?: ExactBasisCache()
        val current = RefinementRun(request, meter, model, factors)
        run = current
        current.run(primal, duals, basis, witness, needPoint, direction, reconstructInitial, preferBasis)
        meter.poll()
        reason = if (current.candidate.attained || current.candidate.conflict != null ||
            current.unboundedness != null
        ) {
            null
        } else {
            LpRefinementDecline.CANDIDATE
        }
    } catch (stop: RefinementStop) {
        reason = stop.reason
    } finally {
        metrics = meter.finish(reason)
    }
    val candidate = run?.candidate
    metrics = metrics.copy(
        witnesses = if (candidate?.point != null) 1 else 0,
        bounds = if (candidate?.bound != null) 1 else 0,
        conflicts = if (candidate?.conflict != null) 1 else 0,
    )
    cache.lastMetrics = metrics
    return LpRefinementResult(
        candidate?.point,
        candidate?.bound,
        candidate?.conflict,
        candidate?.support,
        run?.unboundedness,
        candidate?.usedBasis == true,
        candidate?.pointUsesBasis == true,
        candidate?.boundUsesBasis == true,
        candidate?.conflictUsesBasis == true,
        run?.unboundednessUsesBasis == true,
        candidate?.luAttempted == true,
        metrics,
    )
}
