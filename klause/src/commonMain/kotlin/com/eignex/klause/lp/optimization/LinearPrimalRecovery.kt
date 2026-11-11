package com.eignex.klause.lp.optimization

import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.engine.reconstructRational
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.IntIndexedCollection
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.magnitudeBitLength

internal data class LinearPrimalRecoveryLimits(
    val work: Long = 2_000_000L,
    val allocation: Long = 64L * 1024L * 1024L,
    val entries: Long = 65_536L,
)

internal class LinearPrimalRecoveryProgress {
    var work: Long = 0L
    var allocation: Long = 0L
    var pending = false
    val completed = mutableMapOf<Int, List<BigFraction>>()
}

internal fun proposeLinearPrimal(
    coordinates: List<SourceCoordinate>, point: List<BigFraction>, rows: List<ExactRationalInequality>,
    inactive: Set<Int>, budget: SourceLpBudget, cancellation: Cancellation, observe: (String, Long) -> Unit,
): List<BigFraction>? {
    val funding = budget.funding
    if (cancellation() || coordinates.size > 4096 || funding?.paused == true ||
        !budget.context.certificationPolicy.numericalDeductions) return null
    val progress = LinearPrimalRecoveryProgress()
    var stopped = false
    val meter = WorkMeter { units ->
        if (funding != null && units > funding.availableWork) {
            progress.work -= units
            stopped = true
            throw LinearPrimalStop("WorkLimit")
        }
        budget.observe(preparation = units)
    }
    val token = object : Cancellation {
        override fun isCancelled() = stopped || cancellation() || funding?.paused == true
        override fun deadline() = cancellation.deadline()
        override fun workMeter(): WorkMeter = meter
    }
    val limits = LinearPrimalRecoveryLimits(work = minOf(2_000_000L, funding?.availableWork ?: 2_000_000L),
        allocation = minOf(64L * 1024L * 1024L, funding?.remainingAllocation ?: 64L * 1024L * 1024L))
    return recoverLinearPrimal(coordinates, point, rows, inactive, token,
        complete = { _, _, _ -> null }, observe = { stage, count ->
            if (stage == "PrimalRecoveryAllocation") funding?.charge(0L, count)
            observe(stage.replace("PrimalRecovery", "NumericalPoint"), count)
        }, limits = limits, progress = progress, preserveFeasiblePoint = true)
}

// Recovered coordinates remain proposals; the original-source checker owns witness admission.
internal fun recoverLinearPrimal(
    coordinates: List<SourceCoordinate>,
    point: List<BigFraction>,
    rows: List<ExactRationalInequality>,
    inactive: Set<Int>,
    cancellation: Cancellation,
    complete: (List<ExactRationalInequality>, IntIndexedCollection, Cancellation) -> List<BigFraction>?,
    observe: (String, Long) -> Unit,
    limits: LinearPrimalRecoveryLimits = LinearPrimalRecoveryLimits(),
    progress: LinearPrimalRecoveryProgress = LinearPrimalRecoveryProgress(),
    preserveFeasiblePoint: Boolean = false,
): List<BigFraction>? {
    progress.pending = false
    val initialWork = progress.work
    val initialAllocation = progress.allocation
    val meter = LinearPrimalMeter(limits, cancellation, progress)
    observe("PrimalRecoveryAttempts", 1L)
    return try {
        meter.reserve(coordinates.size.toLong() + rows.size, 256L * coordinates.size + 96L * rows.size)
        if (coordinates.size != point.size) return null
        for (id in coordinates.indices) {
            meter.number(point[id])
            if (coordinates[id].kind != SourceCoordinate.Kind.Real && point[id].den != BIG_ONE) {
                observe("PrimalRecoveryFractional", 1L)
                return null
            }
        }
        val active = ArrayList<ExactRationalInequality>()
        val endpoints = BooleanArray(point.size)
        var entries = 0L
        for (id in rows.indices) {
            meter.reserve(1L)
            if (id in inactive) continue
            val row = rows[id]
            entries += row.columns.size + 1L
            if (entries > limits.entries) throw LinearPrimalStop("Entries")
            meter.number(row.rhs)
            for (entry in row.columns.indices) {
                if (row.columns[entry] !in point.indices) return null
                meter.number(row.coefficients[entry])
            }
            if (!row.strict && row.columns.size == 1) {
                val column = row.columns.single()
                if (coordinates[column].kind == SourceCoordinate.Kind.Real &&
                    meter.subtractProduct(row.rhs, row.coefficients.single(), point[column]).isZero) {
                    endpoints[column] = true
                }
            }
            active += row
        }
        if (preserveFeasiblePoint && meter.satisfies(point, active)) {
            observe("PrimalRecoveryOriginal", 1L)
            return point
        }
        val reconstructed = point.mapIndexed { id, value ->
            meter.reserve(1L, 32L)
            if (coordinates[id].kind != SourceCoordinate.Kind.Real || endpoints[id]) value else {
                val floating = value.toDouble()
                val exactFloating = BigFraction.ofDouble(floating)?.let(meter::number)
                if (exactFloating != value) value else {
                    val part = reconstructRational(floating, onStep = { meter.reserve(1L) })
                    part?.let { meter.number(BigFraction.of(bigIntOf(it.numerator), bigIntOf(it.denominator))) }
                        ?: value
                }
            }
        }
        if (meter.satisfies(reconstructed, active)) {
            observe("PrimalRecoveryReconstructed", 1L)
            return reconstructed
        }
        val residual = ArrayList<ExactRationalInequality>()
        val incidence = arrayOfNulls<IntArrayList>(point.size)
        for (row in active) {
            meter.reserve(row.columns.size.toLong() + 1L, 64L + 64L * row.columns.size)
            var rhs = row.rhs
            val columns = IntArrayList()
            val coefficients = ArrayList<BigFraction>()
            for (entry in row.columns.indices) {
                val column = row.columns[entry]
                val coefficient = row.coefficients[entry]
                if (coefficient.isZero) continue
                if (coordinates[column].kind == SourceCoordinate.Kind.Real) {
                    columns.add(column)
                    coefficients += coefficient
                } else {
                    rhs = meter.subtractProduct(rhs, coefficient, point[column])
                }
            }
            if (columns.isEmpty()) {
                if (if (row.strict) rhs <= BigFraction.ZERO else rhs < BigFraction.ZERO) {
                    observe("PrimalRecoveryDiscreteRows", 1L)
                    return null
                }
            } else {
                columns.forEach { column ->
                    val adjacent = incidence[column] ?: IntArrayList(1).also { incidence[column] = it }
                    adjacent.add(residual.size)
                }
                residual += ExactRationalInequality(columns.toIntArray(), coefficients, rhs, row.strict)
            }
        }
        val recovered = reconstructed.toMutableList()
        val seen = BooleanArray(point.size)
        val selected = BooleanArray(residual.size)
        val local = IntArray(point.size) { -1 }
        for (root in point.indices) {
            meter.reserve(1L)
            if (seen[root] || incidence[root] == null) continue
            val members = IntArrayList(1).also { it.add(root) }
            val componentRows = IntArrayList()
            seen[root] = true
            var next = 0
            while (next < members.size) {
                val column = members[next++]
                meter.reserve(1L, 16L)
                val adjacent = incidence[column] ?: continue
                adjacent.forEach { id ->
                    meter.reserve(1L)
                    if (selected[id]) return@forEach
                    selected[id] = true
                    componentRows.add(id)
                    for (neighbor in residual[id].columns) {
                        meter.reserve(1L)
                        if (!seen[neighbor]) {
                            seen[neighbor] = true
                            members.add(neighbor)
                        }
                    }
                }
            }
            meter.reserve(members.size.toLong() * (Int.SIZE_BITS - members.size.countLeadingZeroBits()))
            members.backingData.sort(0, members.size)
            for (index in 0 until members.size) local[members[index]] = index
            val localRows = ArrayList<ExactRationalInequality>(componentRows.size)
            componentRows.forEach { id ->
                val row = residual[id]
                meter.reserve(row.columns.size.toLong() + 1L, 64L + 32L * row.columns.size)
                localRows.add(ExactRationalInequality(IntArray(row.columns.size) { local[row.columns[it]] },
                    row.coefficients, row.rhs, row.strict))
            }
            if (meter.satisfies(List(members.size) { recovered[members[it]] }, localRows)) continue
            observe("PrimalRecoveryComponents", 1L)
            val values = progress.completed[root]?.also { observe("PrimalRecoveryComponentReused", 1L) }
                ?: complete(localRows, members, cancellation)
            meter.reserve(1L)
            if (values == null || values.size != members.size) {
                observe("PrimalRecoveryComponentUnavailable", 1L)
                return null
            }
            for (index in 0 until members.size) recovered[members[index]] = meter.number(values[index])
            if (root !in progress.completed) {
                meter.reserve(values.size.toLong(), 32L * values.size)
                progress.completed[root] = values.toList()
            }
        }
        if (meter.satisfies(recovered, active)) {
            observe("PrimalRecoveryResidual", 1L)
            recovered
        } else null
    } catch (stop: LinearPrimalStop) {
        progress.pending = false
        observe("PrimalRecovery${stop.reason}", 1L)
        null
    } finally {
        observe("PrimalRecoveryWork", progress.work - initialWork)
        observe("PrimalRecoveryAllocation", progress.allocation - initialAllocation)
    }
}

private class LinearPrimalStop(val reason: String) : RuntimeException()

internal fun refineLinearPrimal(
    coordinates: List<SourceCoordinate>,
    point: List<BigFraction>,
    rows: List<ExactRationalInequality>,
    inactive: Set<Int>,
    costs: List<BigFraction>,
    cancellation: Cancellation,
    complete: (List<ExactRationalInequality>, List<BigFraction>, Cancellation) -> List<BigFraction>?,
    observe: (String, Long) -> Unit,
): List<BigFraction>? {
    val progress = LinearPrimalRecoveryProgress()
    val meter = LinearPrimalMeter(LinearPrimalRecoveryLimits(), cancellation, progress)
    return try {
        meter.reserve(coordinates.size.toLong())
        if (coordinates.size != point.size || costs.size != point.size) return null
        if (coordinates.indices.none { coordinates[it].kind == SourceCoordinate.Kind.Real && !costs[it].isZero }) {
            return null
        }
        meter.reserve(0L, 64L * coordinates.size)
        val members = IntArrayList()
        for (id in coordinates.indices) if (coordinates[id].kind == SourceCoordinate.Kind.Real) members.add(id)
        val objective = List(members.size) { meter.number(costs[members[it]]) }
        observe("PrimalRefinementAttempts", 1L)
        val local = IntArray(point.size) { -1 }
        for (id in 0 until members.size) local[members[id]] = id
        val residual = ArrayList<ExactRationalInequality>()
        var entries = 0L
        for (id in rows.indices) {
            if (id in inactive) continue
            val row = rows[id]
            entries += row.columns.size + 1L
            if (entries > LinearPrimalRecoveryLimits().entries) throw LinearPrimalStop("Entries")
            meter.reserve(row.columns.size.toLong() + 1L, 64L + 64L * row.columns.size)
            var rhs = meter.number(row.rhs)
            val columns = IntArrayList()
            val coefficients = ArrayList<BigFraction>()
            for (entry in row.columns.indices) {
                val column = row.columns[entry]
                val coefficient = meter.number(row.coefficients[entry])
                if (local[column] >= 0) {
                    columns.add(local[column])
                    coefficients += coefficient
                } else {
                    if (point[column].den != BIG_ONE) return null
                    rhs = meter.subtractProduct(rhs, coefficient, point[column])
                }
            }
            if (columns.isEmpty()) {
                if (if (row.strict) rhs <= BigFraction.ZERO else rhs < BigFraction.ZERO) return null
            } else residual += ExactRationalInequality(columns.toIntArray(), coefficients, rhs, row.strict)
        }
        val refined = complete(residual, objective, cancellation) ?: return null
        if (refined.size != members.size) return null
        var difference = BigFraction.ZERO
        for (id in 0 until members.size) {
            difference = meter.subtractProduct(difference, objective[id], refined[id])
            difference = meter.subtractProduct(difference, objective[id].negated(), point[members[id]])
        }
        if (difference <= BigFraction.ZERO) {
            observe("PrimalRefinementNeutral", 1L)
            return null
        }
        if (!meter.satisfies(refined, residual)) return null
        observe("PrimalRefinementImproved", 1L)
        point.toMutableList().also { values ->
            for (id in 0 until members.size) values[members[id]] = refined[id]
        }
    } catch (stop: LinearPrimalStop) {
        observe("PrimalRefinement${stop.reason}", 1L)
        null
    } finally {
        observe("PrimalRefinementWork", progress.work)
        observe("PrimalRefinementAllocation", progress.allocation)
    }
}

private class LinearPrimalMeter(
    private val limits: LinearPrimalRecoveryLimits,
    private val token: Cancellation,
    private val progress: LinearPrimalRecoveryProgress,
) {

    fun reserve(units: Long, bytes: Long = 0L) {
        val reason = when {
            token() -> "Stopped"
            units > limits.work - progress.work -> "WorkLimit"
            bytes > limits.allocation - progress.allocation -> "AllocationLimit"
            else -> null
        }
        if (reason != null) throw LinearPrimalStop(reason)
        progress.work += units
        progress.allocation += bytes
        token.charge(units)
        if (token()) throw LinearPrimalStop("Stopped")
    }

    fun number(value: BigFraction): BigFraction {
        val bits = maxOf(value.num.magnitudeBitLength(), value.den.magnitudeBitLength())
        reserve(1L + bits / 64L, 32L + bits / 4L)
        if (bits > 4096) throw LinearPrimalStop("ScalarLimit")
        return value
    }

    private fun arithmetic(left: BigFraction, right: BigFraction) {
        val bits = maxOf(left.num.magnitudeBitLength(), left.den.magnitudeBitLength()).toLong() +
            maxOf(right.num.magnitudeBitLength(), right.den.magnitudeBitLength()) + 2L
        val words = (bits + 63L) / 64L
        reserve(words * words + 1L, 64L + bits / 2L)
    }

    fun subtractProduct(value: BigFraction, left: BigFraction, right: BigFraction): BigFraction {
        arithmetic(left, right)
        val product = number(left * right)
        arithmetic(value, product)
        return number(value - product)
    }

    fun satisfies(point: List<BigFraction>, rows: List<ExactRationalInequality>): Boolean {
        for (row in rows) {
            var rhs = row.rhs
            for (entry in row.columns.indices) {
                rhs = subtractProduct(rhs, row.coefficients[entry], point[row.columns[entry]])
            }
            if (if (row.strict) rhs <= BigFraction.ZERO else rhs < BigFraction.ZERO) return false
        }
        reserve(1L)
        return true
    }
}
