package com.eignex.klause.backtrack.lp

import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.VarRef
import kotlin.math.floor
import kotlin.math.sqrt

internal class LpDirectionalHistory(
    private val observe: (String) -> Unit,
    private val maxAge: Long = 256L,
    private val calibratedNomination: Boolean = false,
    private val charge: (Long) -> Unit = {},
) {
    private class Direction {
        var count = 0
        var mean = 0.0
        var gain = 0.0
        var previousGain = 0.0
        var previousStamp = 0L
        var stamp = 0L
    }

    private class Gains(val down: Direction = Direction(), val up: Direction = Direction()) {
        var calibration = 1.0
        var calibrationStamp = 0L
    }
    private class Parent(val objective: Double, val variable: VarRef, val value: Double)
    private class Pending(val parent: Parent, val up: Boolean, val distance: Double, val depth: Int)

    private val gains = HashMap<VarRef, Gains>()
    private var parents: Array<Parent?>? = null
    private var parentCount = 0
    private var parentLimit = 0
    private var pending: Pending? = null
    private var stamp = 0L
    private var depth = 0
    private var objective = Double.NaN
    private var upSamples = 0
    private var downSamples = 0

    fun beginNode(level: Int) {
        stamp++
        depth = level
        objective = Double.NaN
        if (pending?.depth != level) pending = null
    }

    val retainedEntries: Int get() = gains.size + parentCount

    val retainedObservations: Int get() = parentCount

    val hasObservations: Boolean get() = upSamples > 0 || downSamples > 0

    val hasTwoSidedSamples: Boolean get() = upSamples >= RELIABILITY && downSamples >= RELIABILITY

    fun clear(report: Boolean = true) {
        gains.clear()
        trimParents(0)
        pending = null
        objective = Double.NaN
        upSamples = 0
        downSamples = 0
        if (report) observe("RESET")
    }

    fun discardObservations() {
        trimParents(0)
        pending = null
        objective = Double.NaN
        observe("PASSIVE_SOURCE_OBSERVATIONS_DISCARDED")
    }

    fun record(value: Double) {
        objective = value
        val child = pending ?: return
        pending = null
        val gain = value - child.parent.objective
        val normalized = gain / child.distance
        if (!gain.isFinite() || gain < 0.0 || !normalized.isFinite()) {
            observe("UNUSABLE")
            return
        }
        val history = gains.getOrPut(child.parent.variable, ::Gains)
        val direction = if (child.up) history.up else history.down
        expire(direction)
        direction.count = minOf(direction.count + 1, 8)
        direction.mean += (normalized - direction.mean) / direction.count
        direction.previousGain = direction.gain
        direction.gain = normalized
        direction.previousStamp = direction.stamp
        direction.stamp = stamp
        if (child.up) upSamples = minOf(upSamples + 1, RELIABILITY)
        else downSamples = minOf(downSamples + 1, RELIABILITY)
        observe(if (child.up) "UP" else "DOWN")
    }

    fun branch(variable: VarRef, value: Double) {
        trimParents(depth)
        if (depth !in 0 until MAX_DEPTH || !objective.isFinite() || !value.isFinite()) return
        val fraction = value - floor(value)
        if (fraction <= DISTANCE_TOL || fraction >= 1.0 - DISTANCE_TOL) return
        val records = parents ?: arrayOfNulls<Parent>(MAX_DEPTH).also { parents = it }
        records[depth] = Parent(objective, variable, value)
        parentCount++
        parentLimit = depth + 1
    }

    private fun trimParents(firstRemoved: Int) {
        if (firstRemoved >= parentLimit) return
        val records = parents ?: return
        val start = firstRemoved.coerceAtLeast(0)
        for (index in start until parentLimit) {
            if (records[index] != null) {
                records[index] = null
                parentCount--
            }
        }
        parentLimit = start
    }

    fun commit(decision: SearchDecision, level: Int) {
        trimParents(level)
        pending = null
        val parent = parents?.getOrNull(level - 1) ?: return
        val floor = floor(parent.value)
        val up = when (decision) {
            is SearchDecision.Bool -> {
                if (parent.variable != VarRef.Bool(decision.literal ushr 1)) return
                decision.literal and 1 == 0
            }
            is SearchDecision.IntAtMost -> {
                if (parent.variable != VarRef.IntVar(decision.variable) || decision.upper.toDouble() != floor) return
                false
            }
            is SearchDecision.IntAtLeast -> {
                if (parent.variable != VarRef.IntVar(decision.variable) ||
                    decision.lower.toDouble() != floor + 1.0
                ) return
                true
            }
            else -> return
        }
        val distance = if (up) floor + 1.0 - parent.value else parent.value - floor
        if (distance <= DISTANCE_TOL || distance >= 1.0 - DISTANCE_TOL) return
        pending = Pending(parent, up, distance, level)
    }

    fun infeasible() {
        if (pending != null) observe("INFEASIBLE")
        pending = null
    }

    fun finishNode() {
        if (pending != null) observe("MISSING")
        pending = null
    }

    fun score(variable: VarRef, value: Double): Double {
        if (!value.isFinite()) return Double.NaN
        val history = gains[variable] ?: return Double.NaN
        expire(history.down)
        expire(history.up)
        if (history.down.count < RELIABILITY || history.up.count < RELIABILITY) return Double.NaN
        val fraction = value - floor(value)
        if (fraction <= DISTANCE_TOL || fraction >= 1.0 - DISTANCE_TOL) return Double.NaN
        val down = history.down.mean * fraction
        val up = history.up.mean * (1.0 - fraction)
        val score = minOf(down, up) + maxOf(down, up) / 6.0
        return score.takeIf { it.isFinite() && it > 0.0 } ?: Double.NaN
    }

    fun balancedScore(variable: VarRef, value: Double): Double {
        if (!value.isFinite()) return Double.NaN
        val history = gains[variable] ?: return Double.NaN
        expire(history.down)
        expire(history.up)
        if (history.down.count < RELIABILITY || history.up.count < RELIABILITY) return Double.NaN
        val fraction = value - floor(value)
        if (fraction <= DISTANCE_TOL || fraction >= 1.0 - DISTANCE_TOL) return Double.NaN
        val down = minOf(history.down.gain, history.down.previousGain) * fraction
        val up = minOf(history.up.gain, history.up.previousGain) * (1.0 - fraction)
        if (down <= 0.0 || up <= 0.0) return Double.NaN
        val score = sqrt(down) * sqrt(up)
        if (!score.isFinite() || score <= 0.0) return Double.NaN
        if (stamp - history.down.previousStamp > maxAge || stamp - history.up.previousStamp > maxAge) {
            observe("PASSIVE_HISTORY_PAIR_STALE")
            return Double.NaN
        }
        if (!calibratedNomination || history.calibration == 1.0) return score
        charge(2L)
        observe("CURRENT_CALIBRATION_LOOKUP")
        if (stamp < history.calibrationStamp || stamp - history.calibrationStamp > maxAge) {
            history.calibration = 1.0
            observe("CURRENT_CALIBRATION_EXPIRED")
            return score
        }
        observe("CURRENT_CALIBRATION_ACTIVE")
        val discounted = score * history.calibration
        if (discounted > 0.0) return discounted
        observe("CURRENT_CALIBRATION_ZERO")
        return Double.NaN
    }

    fun calibrateNomination(variable: VarRef, learned: Double, measured: Double): Boolean {
        if (!calibratedNomination || !learned.isFinite() || learned <= 0.0 ||
            !measured.isFinite() || measured < 0.0 || measured >= learned) return false
        val history = gains[variable] ?: return false
        val previous = if (stamp >= history.calibrationStamp && stamp - history.calibrationStamp <= maxAge)
            history.calibration else 1.0
        val discount = previous * (measured / learned)
        if (discount >= previous) return false
        history.calibration = discount
        history.calibrationStamp = stamp
        return true
    }

    val observedVariables: Set<VarRef> get() = gains.keys

    fun needsObservations(variable: VarRef, value: Double): Boolean {
        if (!value.isFinite() || (variable is VarRef.Bool && value !in 0.0..1.0)) return false
        val history = gains[variable] ?: return false
        expire(history.down)
        expire(history.up)
        val fraction = value - floor(value)
        if (fraction <= DISTANCE_TOL || fraction >= 1.0 - DISTANCE_TOL) return false
        if (maxOf(history.down.count, history.up.count) < 1 ||
            history.down.count >= RELIABILITY && history.up.count >= RELIABILITY) return false
        return history.down.mean > 0.0 || history.up.mean > 0.0
    }

    private fun expire(direction: Direction) {
        if (direction.count > 0 && stamp - direction.stamp > maxAge) {
            direction.count = 0
            direction.mean = 0.0
            observe("EXPIRED")
        }
    }

    private companion object {
        const val MAX_DEPTH = 128
        const val RELIABILITY = 2
        const val DISTANCE_TOL = 1e-6
    }
}
