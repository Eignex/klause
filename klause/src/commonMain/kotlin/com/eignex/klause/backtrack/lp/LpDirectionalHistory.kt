package com.eignex.klause.backtrack.lp

import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.VarRef
import kotlin.math.floor

internal class LpDirectionalHistory(
    private val observe: (String) -> Unit,
    private val maxAge: Long = 256L,
    private val charge: (Long) -> Unit = {},
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
    private var reliableStamp = -1L
    private var depth = 0
    private var objective = Double.NaN

    fun beginNode(level: Int) {
        stamp++
        depth = level
        objective = Double.NaN
        if (pending?.depth != level) {
            pending?.let { observeDirection("LOST_DEPTH", it) }
            pending = null
        }
    }

    val retainedEntries: Int get() = gains.size + parents.size

    val mayHaveScores: Boolean get() = reliableStamp >= 0L && stamp - reliableStamp <= maxAge

    fun clear(report: Boolean = true) {
        if (report) pending?.let { observeDirection("LOST_SCOPE", it) }
        gains.clear()
        reliableStamp = -1L
        parents.clear()
        pending = null
        objective = Double.NaN
        if (report) observe("RESET")
    }

    fun record(value: Double) {
        objective = value
        val child = pending ?: return
        pending = null
        val gain = value - child.parent.objective
        val normalized = gain / child.distance
        if (!gain.isFinite() || gain < 0.0 || !normalized.isFinite()) {
            observe("UNUSABLE")
            observeDirection("UNUSABLE", child)
            return
        }
        val history = gains.getOrPut(child.parent.variable, ::Gains)
        update(if (child.up) history.up else history.down, normalized)
        if (recent(history)) reliableStamp = stamp
        observe(if (child.up) "UP" else "DOWN")
    }

    fun branch(variable: VarRef, value: Double) {
        parents.keys.removeAll { it >= depth }
        if (depth >= MAX_DEPTH || !objective.isFinite() || !value.isFinite()) return
        if (variable is VarRef.Bool && (value < 0.0 || value > 1.0)) return
        val fraction = value - floor(value)
        if (fraction <= DISTANCE_TOL || fraction >= 1.0 - DISTANCE_TOL) return
        parents[depth] = Parent(objective, variable, value)
    }

    fun commit(decision: SearchDecision, level: Int) {
        parents.keys.removeAll { it >= level }
        pending?.let { observeDirection("LOST_REPLACED", it) }
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
        observeDirection("COMMIT", checkNotNull(pending))
    }

    fun infeasible() {
        pending?.let {
            observe("INFEASIBLE")
            observeDirection("INFEASIBLE", it)
        }
        pending = null
    }

    fun finishNode(reason: String = "UNOBSERVED") {
        pending?.let {
            observe("MISSING")
            observeDirection("MISSING_$reason", it)
        }
        pending = null
    }

    private fun observeDirection(event: String, child: Pending) {
        charge(1L)
        observe("${event}_${if (child.up) "UP" else "DOWN"}")
    }

    fun score(variable: VarRef, value: Double): Double {
        if (!value.isFinite()) return Double.NaN
        if (variable is VarRef.Bool && (value < 0.0 || value > 1.0)) return Double.NaN
        val fraction = value - floor(value)
        if (fraction <= DISTANCE_TOL || fraction >= 1.0 - DISTANCE_TOL) return Double.NaN
        val samples = gains[variable] ?: return Double.NaN
        expire(samples.down)
        expire(samples.up)
        if (!recent(samples)) return Double.NaN
        val down = samples.down.mean * fraction
        val up = samples.up.mean * (1.0 - fraction)
        val score = minOf(down, up) + maxOf(down, up) / 6.0
        return score.takeIf { it.isFinite() && it > 0.0 } ?: Double.NaN
    }

    private fun recent(history: Gains?): Boolean = history != null &&
        history.down.count >= RELIABILITY && history.up.count >= RELIABILITY &&
        stamp - history.down.stamp <= maxAge && stamp - history.up.stamp <= maxAge

    private fun update(direction: Direction, value: Double) {
        expire(direction)
        direction.count = minOf(direction.count + 1, 8)
        direction.mean += (value - direction.mean) / direction.count
        direction.stamp = stamp
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
