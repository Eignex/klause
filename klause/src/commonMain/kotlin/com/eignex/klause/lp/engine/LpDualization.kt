package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.BigRationalConflict
import com.eignex.klause.util.Cancellation
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.time.TimeSource

internal data class LpDualizationOptions(
    val enabled: Boolean = false,
    val minRowColumnRatio: Int = 10,
    val maxCoordinates: Int = 4096,
    val maxEntries: Int = 65536,
    val maxBits: Int = 4096,
    val constructionWork: Long = 250_000L,
    val solveWork: Long = 250_000L,
    val solvePivots: Int = 1000,
    val proofWork: Long = 250_000L,
) {
    init {
        require(minRowColumnRatio >= 1 && maxCoordinates >= 0 && maxEntries >= 0 && maxBits >= 24)
        require(constructionWork >= 0L && solveWork > 0L && solvePivots > 0 && proofWork >= 0L)
    }

    fun proofLimits() = ReconstructionLimits(
        maxCoordinates = maxCoordinates,
        maxEntries = maxEntries,
        maxBits = maxBits,
        maxWork = proofWork,
    )
}

internal enum class LpDualizationDecline {
    DISABLED,
    NOT_TALL,
    PARENT_BUDGET,
    AUTHORITY,
    STRICT,
    BOUNDS,
    DIMENSION,
    BITS,
    WORK,
    CANCELLED,
    PROJECTION,
    AUXILIARY,
    BASIS,
    POLICY,
}

internal data class LpDualizationMetrics(
    val constructionWork: Long = 0L,
    val solve: LpSolveMetrics = LpSolveMetrics(),
    val postsolveWork: Long = 0L,
    val setupNanos: Long = 0L,
    val cleanupNanos: Long = 0L,
    val eligible: Boolean = false,
    val basisRecovered: Boolean = false,
    val decline: LpDualizationDecline? = null,
) {
    val totalWork: Long get() = constructionWork + solve.workOps + postsolveWork
    val rootMetrics: LpSolveMetrics get() = solve + LpSolveMetrics(workOps = constructionWork + postsolveWork)
}

private class DualizationStop(val reason: LpDualizationDecline) : RuntimeException()

internal class LpDualizationMeter(
    private val limit: Long,
    private val bits: Int,
    private val cancellation: Cancellation,
) {
    var work = 0L
        private set

    fun step(units: Long = 1L) {
        if (cancellation()) throw DualizationStop(LpDualizationDecline.CANCELLED)
        if (units > limit - work) throw DualizationStop(LpDualizationDecline.WORK)
        work += units
    }

    fun completed(units: Long) {
        require(units >= 0L)
        work += units
        step(0L)
    }

    fun number(value: BigFraction): BigFraction {
        step()
        if (value.num.bitLength() > bits || value.den.bitLength() > bits) {
            throw DualizationStop(LpDualizationDecline.BITS)
        }
        return value
    }
}

internal data class LpDualSide(val sourceColumn: Int, val upper: Boolean, val sign: Int)

internal class LpDualization private constructor(
    val source: LpExactState,
    val working: LpWorkingModel,
    private val sides: List<LpDualSide>,
) {
    val model: ExactLpModel get() = working.state.model

    fun sourceDual(
        point: List<BigFraction>,
        meter: LpDualizationMeter,
        homogeneous: Boolean = false,
    ): List<BigFraction>? {
        if (point.size != model.n) return null
        val original = source.model
        val result = MutableList(original.m) {
            meter.number(if (homogeneous) BigFraction.ZERO else original.objective.cost(original.n + it).value)
        }
        for (j in sides.indices) {
            meter.step()
            val side = sides[j]
            if (side.sourceColumn >= original.n) {
                val row = side.sourceColumn - original.n
                result[row] = meter.number(
                    result[row] + meter.number(point[j]) * BigFraction.ofLong(side.sign.toLong()),
                )
            }
        }
        return result
    }

    fun sourcePoint(dual: List<BigFraction>, meter: LpDualizationMeter): List<BigFraction>? {
        if (dual.size != source.model.n) return null
        return dual.mapIndexed { j, value -> meter.number(value.negated() + source.model.column(j).origin.value) }
    }

    fun sourceBasis(auxiliary: Basis, witness: ExactLpWitness, meter: LpDualizationMeter): Basis? {
        val original = source.model
        if (auxiliary.basicVars.size != model.m || auxiliary.status.size != model.numVars ||
            auxiliary.basicVars.distinct().size != model.m || witness.primal.size != original.n
        ) {
            return null
        }
        val values = MutableList(original.numVars) { BigFraction.ZERO }
        for (j in 0 until original.n) values[j] = meter.number(witness.primal[j] - original.column(j).origin.value)
        for (i in 0 until original.m) values[original.n + i] = meter.number(original.rhs(i).value)
        for (j in 0 until original.n) {
            for (entry in original.entries(j)) {
                val column = original.n + entry.row
                values[column] = meter.number(values[column] - entry.number.value * values[j])
            }
        }
        val statuses = Array(original.numVars) { VarStatus.BASIC }
        for (column in auxiliary.basicVars) {
            meter.step()
            if (column !in 0 until model.numVars || auxiliary.status[column] != VarStatus.BASIC) return null
            val side = sides.getOrNull(column)
            val sourceColumn = side?.sourceColumn ?: (column - model.n)
            if (sourceColumn !in values.indices || statuses[sourceColumn] != VarStatus.BASIC) return null
            val bounds = original.column(sourceColumn).bounds
            val value = values[sourceColumn]
            val status = when {
                bounds.fixed && value == bounds.lower?.number?.value -> VarStatus.FIXED
                side?.upper != true && value == bounds.lower?.number?.value -> VarStatus.AT_LOWER
                side?.upper != false && value == bounds.upper?.number?.value -> VarStatus.AT_UPPER
                side == null && bounds.lower == null && bounds.upper == null && value.isZero -> VarStatus.FREE
                else -> return null
            }
            statuses[sourceColumn] = status
        }
        val headings = statuses.indices.filter { statuses[it] == VarStatus.BASIC }.toIntArray()
        return if (headings.size == original.m) Basis(headings, statuses, captureEligible = false) else null
    }

    fun sourceConflict(
        dualDirection: List<BigFraction>,
        token: Cancellation = Cancellation.Never,
        options: LpDualizationOptions = LpDualizationOptions(),
    ): ReconstructedCertificate? = mapped(options, token) { meter ->
        val dualModel = working.state.toWorkingModel() ?: return@mapped null
        if (!recession(dualModel, dualDirection, meter)) return@mapped null
        val ray = sourceDual(dualDirection, meter, homogeneous = true)?.map { meter.number(it.negated()) }
            ?: return@mapped null
        verifyRationalCertificate(
            source.toWorkingModel() ?: return@mapped null,
            ray = ray,
            cancellation = token,
            limits = options.proofLimits().copy(maxWork = maxOf(0L, options.proofWork - meter.work)),
        )
    }

    fun sourceUnboundedness(
        dualConflict: BigRationalConflict,
        sourceWitness: ExactLpWitness,
        token: Cancellation = Cancellation.Never,
        options: LpDualizationOptions = LpDualizationOptions(),
    ): ExactLpUnboundedness? = mapped(options, token) { meter ->
        val rho = conflictVector(dualConflict, model.m, meter) ?: return@mapped null
        val dualModel = working.state.toWorkingModel() ?: return@mapped null
        val checked = verifyRationalCertificate(
            dualModel,
            ray = rho,
            cancellation = token,
            limits = options.proofLimits().copy(maxWork = maxOf(0L, options.proofWork - meter.work)),
        )
        if (checked.conflict == null) return@mapped null
        meter.completed(checked.metrics.work)
        // The destination checks determine orientation; a Farkas API may have accepted the opposite sign.
        val original = source.toWorkingModel() ?: return@mapped null
        exactUnboundedness(original, sourceWitness, rho, meter)
            ?: exactUnboundedness(original, sourceWitness, rho.map { it.negated() }, meter)
    }

    fun dualConflict(
        sourceDirection: List<BigFraction>,
        token: Cancellation = Cancellation.Never,
        options: LpDualizationOptions = LpDualizationOptions(),
    ): ReconstructedCertificate? = mapped(options, token) { meter ->
        if (!recession(source.toWorkingModel() ?: return@mapped null, sourceDirection, meter)) return@mapped null
        verifyRationalCertificate(
            working.state.toWorkingModel() ?: return@mapped null,
            ray = sourceDirection,
            cancellation = token,
            limits = options.proofLimits().copy(maxWork = maxOf(0L, options.proofWork - meter.work)),
        )
    }

    fun dualUnboundedness(
        sourceConflict: BigRationalConflict,
        dualWitness: ExactLpWitness,
        token: Cancellation = Cancellation.Never,
        options: LpDualizationOptions = LpDualizationOptions(),
    ): ExactLpUnboundedness? = mapped(options, token) { meter ->
        val original = source.toWorkingModel() ?: return@mapped null
        val rho = conflictVector(sourceConflict, source.model.m, meter) ?: return@mapped null
        val checked = verifyRationalCertificate(
            original,
            ray = rho,
            cancellation = token,
            limits = options.proofLimits().copy(maxWork = maxOf(0L, options.proofWork - meter.work)),
        )
        val conflict = checked.conflict ?: return@mapped null
        meter.completed(checked.metrics.work)
        val oriented = conflictVector(conflict, source.model.m, meter) ?: return@mapped null
        val coefficients = MutableList(source.model.numVars) { BigFraction.ZERO }
        for (j in coefficients.indices) {
            original.forEachRationalColumn(j) { row, value ->
                coefficients[j] = meter.number(coefficients[j] + oriented[row] * value)
            }
        }
        val direction = sides.mapIndexed { index, side ->
            val value = coefficients[side.sourceColumn]
            val signed = if (side.upper) value.negated() else value
            meter.number(
                if (model.column(
                        index,
                    ).bounds.lower == null
                ) {
                        signed
                    } else if (signed.signum() > 0) {
                        signed
                    } else {
                        BigFraction.ZERO
                    },
            )
        }
        exactUnboundedness(working.state.toWorkingModel() ?: return@mapped null, dualWitness, direction, meter)
    }

    companion object {
        @Suppress("ThrowsCount")
        fun create(source: LpExactState, options: LpDualizationOptions, meter: LpDualizationMeter): LpDualization {
            val original = source.model
            if (original.numVars > options.maxCoordinates) throw DualizationStop(LpDualizationDecline.DIMENSION)
            val rows = List(original.m) { ArrayList<ExactLpEntry>() }
            val costs = MutableList(original.n) { meter.number(original.objective.cost(it).value) }
            var constant = meter.number(original.objective.constant.value)
            var entries = 0L
            for (j in 0 until original.numVars) {
                meter.step()
                val bounds = original.column(j).bounds
                if (!bounds.consistent) throw DualizationStop(LpDualizationDecline.BOUNDS)
                if (bounds.lower?.strict == true || bounds.upper?.strict == true) {
                    throw DualizationStop(
                    LpDualizationDecline.STRICT,
                )
                }
                for (side in listOfNotNull(bounds.lower, bounds.upper)) meter.number(side.number.value)
                meter.number(original.column(j).origin.value)
            }
            for (i in 0 until original.m) {
                if (original.row(i).strict) throw DualizationStop(LpDualizationDecline.STRICT)
                constant = meter.number(
                    constant + meter.number(
                        original.rhs(i).value,
                    ) * meter.number(original.objective.cost(original.n + i).value),
                )
            }
            for (j in 0 until original.n) {
                for (entry in original.entries(j)) {
                    meter.step()
                    if (++entries > options.maxEntries) throw DualizationStop(LpDualizationDecline.DIMENSION)
                    rows[entry.row] += ExactLpEntry(j, ExactLpNumber.of(meter.number(entry.number.value)))
                    costs[j] = meter.number(
                        costs[j] - entry.number.value * original.objective.cost(original.n + entry.row).value,
                    )
                }
            }
            val matrix = ArrayList<List<ExactLpEntry>>()
            val columns = ArrayList<ExactLpColumn>()
            val objective = ArrayList<ExactLpNumber>()
            val sides = ArrayList<LpDualSide>()
            var transformedEntries = 0L
            fun side(column: Int, upper: Boolean, bound: ExactLpSide, free: Boolean) {
                meter.step()
                val structural = column < original.n
                val sign = if (upper == structural) -1L else 1L
                val data = if (structural) {
                    listOf(
                    ExactLpEntry(column, ExactLpNumber.of(1L)),
                )
                } else {
                    rows[column - original.n]
                }
                transformedEntries += data.size
                if (matrix.size.toLong() + 1 + original.n > options.maxCoordinates ||
                    transformedEntries > options.maxEntries
                ) {
                    throw DualizationStop(LpDualizationDecline.DIMENSION)
                }
                matrix += data.map {
                    ExactLpEntry(
                        it.row,
                        ExactLpNumber.of(meter.number(it.number.value * BigFraction.ofLong(sign))),
                    )
                }
                columns += ExactLpColumn(
                    if (free) ExactLpBounds() else ExactLpBounds(ExactLpSide(ExactLpNumber.of(0L))),
                    integral = false,
                )
                val cost = if (structural) {
                    bound.number.value.negated()
                } else {
                    bound.number.value - original.rhs(
                    column - original.n,
                ).value
                }
                objective += ExactLpNumber.of(meter.number(cost * BigFraction.ofLong(sign)))
                sides += LpDualSide(column, upper, sign.toInt())
            }
            for (column in 0 until original.numVars) {
                val bounds = original.column(column).bounds
                if (bounds.fixed) {
                    val upper = column >= original.n
                    side(column, upper, requireNotNull(if (upper) bounds.upper else bounds.lower), true)
                } else {
                    bounds.lower?.let { side(column, false, it, false) }
                    bounds.upper?.let { side(column, true, it, false) }
                }
            }
            meter.step((matrix.size.toLong() + original.n) * 4L + transformedEntries)
            val zero = ExactLpNumber.of(0L)
            val exact = ExactLpModel(
                matrix,
                costs.map(ExactLpNumber::of),
                columns + List(
                    original.n,
                ) { ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero)), integral = false) },
                List(original.n) { ExactLpRow() },
                ExactLpObjective(
                    objective + List(original.n) { zero },
                    ExactLpNumber.of(constant.negated()),
                    ExactLpNumber.of(meter.number(original.objective.scale.value)),
                    ExactLpNumber.of(meter.number(original.objective.externalConstant.value.negated())),
                ),
            )
            return LpDualization(source, LpWorkingModel(source, exact), sides)
        }
    }
}

private fun conflictVector(conflict: BigRationalConflict, size: Int, meter: LpDualizationMeter): List<BigFraction>? {
    if (conflict.rows.size != conflict.multipliers.size) return null
    val result = MutableList(size) { BigFraction.ZERO }
    for (i in conflict.rows.indices) {
        val row = conflict.rows[i]
        if (row !in result.indices) return null
        result[row] = meter.number(result[row] + conflict.multipliers[i])
    }
    return result
}

private fun recession(model: LpModel, direction: List<BigFraction>, meter: LpDualizationMeter): Boolean {
    if (direction.size != model.n) return false
    val all = MutableList(model.numVars) { BigFraction.ZERO }
    for (j in direction.indices) {
        all[j] = meter.number(direction[j])
        model.forEachRationalColumn(
            j,
        ) { row, value -> all[model.n + row] = meter.number(all[model.n + row] - value * all[j]) }
    }
    var improvement = BigFraction.ZERO
    for (j in all.indices) {
        meter.step()
        val bounds = model.exactBounds(j)
        if ((bounds.lower != null && all[j].signum() < 0) || (bounds.upper != null && all[j].signum() > 0)) return false
        improvement = meter.number(improvement + model.exactCost(j) * all[j])
    }
    return improvement.signum() < 0
}

private fun exactUnboundedness(
    model: LpModel,
    witness: ExactLpWitness,
    direction: List<BigFraction>,
    meter: LpDualizationMeter,
): ExactLpUnboundedness? {
    if (!recession(model, direction, meter)) return null
    val original = model.exactState?.model ?: return null
    val point = MutableList(original.numVars) { BigFraction.ZERO }
    val ray = MutableList(original.numVars) { BigFraction.ZERO }
    if (witness.primal.size != original.n) return null
    for (i in 0 until original.m) point[original.n + i] = meter.number(original.rhs(i).value)
    for (j in 0 until original.n) {
        point[j] = meter.number(witness.primal[j] - original.column(j).origin.value)
        ray[j] = meter.number(direction[j])
        for (entry in original.entries(j)) {
            val slack = original.n + entry.row
            point[slack] = meter.number(point[slack] - entry.number.value * point[j])
            ray[slack] = meter.number(ray[slack] - entry.number.value * ray[j])
        }
    }
    var objective = meter.number(original.objective.constant.value)
    for (j in point.indices) {
        meter.step()
        val bounds = original.column(j).bounds
        if (bounds.lower?.let { point[j] < it.number.value || (it.strict && point[j] == it.number.value) } == true ||
            bounds.upper?.let { point[j] > it.number.value || (it.strict && point[j] == it.number.value) } == true
        ) {
            return null
        }
        val exported = meter.number(point[j] + original.column(j).origin.value)
        if (original.column(j).integral && (exported.den != BigInteger.ONE || ray[j].den != BigInteger.ONE)) return null
        objective = meter.number(objective + original.objective.cost(j).value * point[j])
    }
    objective = meter.number(
        objective * original.objective.scale.value.reciprocal() + original.objective.externalConstant.value,
    )
    return ExactLpUnboundedness(ExactLpWitness(witness.primal.toList(), objective), direction.toList())
}

private inline fun <T> mapped(
    options: LpDualizationOptions,
    token: Cancellation,
    block: (LpDualizationMeter) -> T?,
): T? = try {
    block(LpDualizationMeter(options.proofWork, options.maxBits, token)).takeUnless { token() }
} catch (_: DualizationStop) {
    null
}

internal class LpRootDualizationAttempt(private val options: LpDualizationOptions) {
    private var spent = false
    var metrics = LpDualizationMetrics()
        private set
    var sourceState: LpExactState? = null
        private set
    var certificate: ReconstructedCertificate? = null
        private set

    fun decline(reason: LpDualizationDecline) {
        check(!spent) { "root dualization attempt has already spent its allowance" }
        spent = true
        metrics = metrics.copy(decline = reason)
    }

    @Suppress("TooGenericExceptionCaught", "ThrowingExceptionFromFinally", "ThrowsCount")
    fun solve(
        source: LpExactState,
        context: LpSolveContext = LpSolveContext.Production,
        pricing: LpPricingOptions = LpPricingOptions(),
        token: Cancellation = Cancellation.Never,
        parentWork: Long = 0L,
        parentPivots: Int = 0,
        sourcePreparationWork: Long = 0L,
    ): Basis? {
        check(!spent) { "root dualization attempt has already spent its allowance" }
        spent = true
        sourceState = source
        val decline = when {
            !options.enabled -> LpDualizationDecline.DISABLED
            source.model.n == 0 ||
                source.model.m.toLong() < options.minRowColumnRatio.toLong() * source.model.n ->
                LpDualizationDecline.NOT_TALL
            else -> null
        }
        if (decline != null) {
            metrics = metrics.copy(decline = decline)
            return null
        }
        metrics = metrics.copy(eligible = true)
        require(parentWork >= 0L && parentPivots >= 0 && sourcePreparationWork >= 0L)
        val constructionLimit = if (parentWork == 0L) {
            options.constructionWork
        } else {
            minOf(options.constructionWork, maxOf(0L, parentWork / 8L - sourcePreparationWork))
        }
        val solveLimit = if (parentWork == 0L) options.solveWork else minOf(options.solveWork, parentWork / 2L)
        val proofLimit = if (parentWork == 0L) options.proofWork else minOf(options.proofWork, parentWork / 8L)
        val pivotLimit = if (parentPivots == 0) options.solvePivots else minOf(options.solvePivots, parentPivots)
        if (solveLimit <= 0L) {
            metrics = metrics.copy(decline = LpDualizationDecline.PARENT_BUDGET)
            return null
        }
        val construction = LpDualizationMeter(constructionLimit, options.maxBits, token)
        val postsolve = LpDualizationMeter(proofLimit, options.maxBits, token)
        try {
            val transform = LpDualization.create(source, options, construction)
            val setup = TimeSource.Monotonic.markNow()
            val solver: TableauCutSolver
            val original: LpModel
            try {
                construction.step((transform.model.numVars.toLong() + source.model.numVars) * 4L)
                val auxiliary = transform.working.state.toWorkingModel() ?: throw DualizationStop(
                    LpDualizationDecline.PROJECTION,
                )
                original = source.toWorkingModel() ?: throw DualizationStop(LpDualizationDecline.PROJECTION)
                solver = newTableauCutSolver(
                    auxiliary,
                    token,
                    pivotLimit,
                    solveLimit,
                    factory = context.engineFactory,
                    pricing = pricing,
                )
            } finally {
                metrics = metrics.copy(setupNanos = setup.elapsedNow().inWholeNanoseconds)
            }
            var failure: Throwable? = null
            val result = try {
                solver.solve()
            } catch (primary: Throwable) {
                failure = primary
                throw primary
            } finally {
                val cleanupStart = TimeSource.Monotonic.markNow()
                try {
                    try {
                        metrics = metrics.copy(solve = solver.lastMetrics)
                    } catch (measurement: Throwable) {
                        if (failure == null) failure = measurement else failure.addSuppressed(measurement)
                    }
                    try {
                        solver.close()
                    } catch (cleanup: Throwable) {
                        if (failure == null) failure = cleanup else failure.addSuppressed(cleanup)
                    }
                } finally {
                    metrics = metrics.copy(cleanupNanos = cleanupStart.elapsedNow().inWholeNanoseconds)
                }
                failure?.let { throw it }
            } ?: throw DualizationStop(LpDualizationDecline.AUXILIARY)
            postsolve.step()
            if (result.primal.size != transform.model.n || result.duals.size != transform.model.m ||
                result.primal.any { !it.isFinite() } || result.duals.any { !it.isFinite() }
            ) {
                throw DualizationStop(LpDualizationDecline.PROJECTION)
            }
            if (result.exactState !== transform.working.state) throw DualizationStop(LpDualizationDecline.AUTHORITY)
            val y = transform.sourceDual(result.primal.map { postsolve.number(exactDouble(it)) }, postsolve)
                ?: throw DualizationStop(LpDualizationDecline.PROJECTION)
            val x = if (result.optimal) {
                transform.sourcePoint(
                result.duals.map { postsolve.number(exactDouble(it)) },
                postsolve,
            )
            } else {
                null
            }
            val checked = reconstructCertificate(
                original,
                primal = x?.map { it.toDouble() }?.toDoubleArray(),
                duals = y.map { it.toDouble() }.toDoubleArray(),
                cancellation = token,
                limits = options.proofLimits().copy(maxWork = maxOf(0L, proofLimit - postsolve.work)),
            )
            postsolve.completed(checked.metrics.work)
            certificate = checked
            val point = checked.witness ?: throw DualizationStop(LpDualizationDecline.BASIS)
            val basis = transform.sourceBasis(
                result.basis,
                point,
                postsolve,
            ) ?: throw DualizationStop(LpDualizationDecline.BASIS)
            postsolve.step()
            metrics = metrics.copy(basisRecovered = true)
            return basis
        } catch (stop: DualizationStop) {
            metrics = metrics.copy(decline = stop.reason)
            if (stop.reason == LpDualizationDecline.CANCELLED) certificate = null
            return null
        } finally {
            metrics = metrics.copy(constructionWork = construction.work, postsolveWork = postsolve.work)
        }
    }
}
