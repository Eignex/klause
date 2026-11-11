package com.eignex.klause.lp.engine

import com.eignex.klause.util.Cancellation
import kotlin.math.abs

internal class LpNumericalRecession(private val model: LpModel, val direction: DoubleArray) {
    fun belongsTo(candidate: LpModel): Boolean = candidate === model
}

internal data class LpFloatingRecession(val accepted: Boolean, val work: Long, val stop: LpFloatingBoundStop?)

@Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod")
internal fun floatingRecession(
    model: LpModel,
    direction: DoubleArray,
    workLimit: Long,
    cancellation: Cancellation = Cancellation.Never,
): LpFloatingRecession {
    require(workLimit >= 0L)
    var work = 0L
    var stop: LpFloatingBoundStop? = null
    fun visit(units: Long = 1L): Boolean {
        if (cancellation()) stop = LpFloatingBoundStop.CANCELLED
        else if (units > workLimit - work) stop = LpFloatingBoundStop.WORK
        if (stop != null) return false
        work += units
        return true
    }
    fun refuse(reason: LpFloatingBoundStop) = LpFloatingRecession(false, work, stop ?: reason)
    if (!visit()) return refuse(LpFloatingBoundStop.WORK)
    if (direction.size != model.n) return refuse(LpFloatingBoundStop.INPUT)
    val storage = 2L * model.m + model.n
    if (storage > 262_144L || !visit(storage)) return refuse(LpFloatingBoundStop.WORK)
    val logical = DoubleArray(model.m)
    val magnitude = DoubleArray(model.m)
    var slope = 0.0
    var slopeMagnitude = 0.0
    for (column in direction.indices) {
        if (!visit()) return refuse(LpFloatingBoundStop.WORK)
        val value = direction[column]
        if (!value.isFinite()) return refuse(LpFloatingBoundStop.NON_FINITE)
        if (!model.keepsRecessionSides(column, value, abs(value))) return refuse(LpFloatingBoundStop.INPUT)
        val term = model.costD(column) * value
        slope += term
        slopeMagnitude += abs(term)
        model.forEachInColumnD(column) { row, coefficient ->
            if (!visit()) return refuse(LpFloatingBoundStop.WORK)
            val product = coefficient * value
            logical[row] -= product
            magnitude[row] += abs(product)
        }
    }
    for (row in logical.indices) {
        if (!visit()) return refuse(LpFloatingBoundStop.WORK)
        val value = logical[row]
        if (!value.isFinite() || !magnitude[row].isFinite()) return refuse(LpFloatingBoundStop.NON_FINITE)
        val column = model.slackCol(row)
        if (!model.keepsRecessionSides(column, value, magnitude[row])) return refuse(LpFloatingBoundStop.INPUT)
        val term = model.costD(column) * value
        slope += term
        slopeMagnitude += abs(term)
    }
    if (!slope.isFinite() || !slopeMagnitude.isFinite()) return refuse(LpFloatingBoundStop.NON_FINITE)
    if (slope >= -FLOAT_EVIDENCE_TOLERANCE * maxOf(1.0, slopeMagnitude)) {
        return refuse(LpFloatingBoundStop.INPUT)
    }
    if (cancellation()) return refuse(LpFloatingBoundStop.CANCELLED)
    return LpFloatingRecession(true, work, null)
}

private fun LpModel.keepsRecessionSides(column: Int, value: Double, magnitude: Double): Boolean {
    val tolerance = FLOAT_EVIDENCE_TOLERANCE * maxOf(1.0, magnitude)
    val lower = hasFiniteLower(column) && (column >= n || !probeClampedLo[column])
    val upper = hasFiniteUpper(column) && (column >= n || !probeClampedHi[column])
    return (!lower || value >= -tolerance) && (!upper || value <= tolerance)
}
