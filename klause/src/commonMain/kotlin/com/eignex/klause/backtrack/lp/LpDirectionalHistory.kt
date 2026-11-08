package com.eignex.klause.backtrack.lp

import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.VarRef
import kotlin.math.floor
import kotlin.math.sqrt

internal class LpDirectionalHistory(
    private val observe: (String) -> Unit,
    private val maxAge: Long = 256L,
) {
    private class Direction {
        var count = 0
        var mean = 0.0
        var stamp = 0L
    }

    private class Gains(val down: Direction = Direction(), val up: Direction = Direction())
    private class Parent(val objective: Double, val variable: VarRef, val value: Double)
    private class Pending(val parent: Parent, val up: Boolean, val distance: Double, val depth: Int)

    private val gains = HashMap<VarRef, Gains>()
    private val parents = HashMap<Int, Parent>()
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

    val retainedEntries: Int get() = gains.size + parents.size

    val retainedObservations: Int get() = parents.size

    val hasTwoSidedSamples: Boolean get() = upSamples >= RELIABILITY && downSamples >= RELIABILITY

    fun clear(report: Boolean = true) {
        gains.clear()
        parents.clear()
        pending = null
        objective = Double.NaN
        upSamples = 0
        downSamples = 0
        if (report) observe("RESET")
    }

    fun discardObservations() {
        parents.clear()
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
        direction.stamp = stamp
        if (child.up) upSamples = minOf(upSamples + 1, RELIABILITY)
        else downSamples = minOf(downSamples + 1, RELIABILITY)
        observe(if (child.up) "UP" else "DOWN")
    }

    fun branch(variable: VarRef, value: Double) {
        parents.keys.removeAll { it >= depth }
        if (depth >= MAX_DEPTH || !objective.isFinite() || !value.isFinite()) return
        val fraction = value - floor(value)
        if (fraction <= DISTANCE_TOL || fraction >= 1.0 - DISTANCE_TOL) return
        parents[depth] = Parent(objective, variable, value)
    }

    fun commit(decision: SearchDecision, level: Int) {
        parents.keys.removeAll { it >= level }
        pending = null
        val parent = parents[level - 1] ?: return
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

    fun score(variable: VarRef, value: Double): Double = score(variable, value, balanced = false)

    fun balancedScore(variable: VarRef, value: Double): Double = score(variable, value, balanced = true)

    private fun score(variable: VarRef, value: Double, balanced: Boolean): Double {
        if (!value.isFinite()) return Double.NaN
        val history = gains[variable] ?: return Double.NaN
        expire(history.down)
        expire(history.up)
        if (history.down.count < RELIABILITY || history.up.count < RELIABILITY) return Double.NaN
        val fraction = value - floor(value)
        if (fraction <= DISTANCE_TOL || fraction >= 1.0 - DISTANCE_TOL) return Double.NaN
        val down = history.down.mean * fraction
        val up = history.up.mean * (1.0 - fraction)
        val score = if (balanced) sqrt(down) * sqrt(up) else minOf(down, up) + maxOf(down, up) / 6.0
        return score.takeIf { it.isFinite() && it > 0.0 } ?: Double.NaN
    }

    fun preferUpForIncumbent(variable: VarRef, value: Double): Boolean? {
        if (!value.isFinite()) return null
        val history = gains[variable] ?: return null
        expire(history.down)
        expire(history.up)
        if (history.down.count < RELIABILITY || history.up.count < RELIABILITY) return null
        val fraction = value - floor(value)
        if (fraction <= DISTANCE_TOL || fraction >= 1.0 - DISTANCE_TOL) return null
        val down = history.down.mean * fraction
        val up = history.up.mean * (1.0 - fraction)
        if (!down.isFinite() || !up.isFinite() || down <= 0.0 || up <= 0.0 || down == up) return null
        return up < down
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
