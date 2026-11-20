package com.eignex.klause.backtrack.lp

import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.VarRef
import kotlin.math.floor
import kotlin.math.sqrt

internal class LpDirectionalHistory(
    private val observe: (String) -> Unit,
    private val maxAge: Long = 256L,
    private val charge: (Long) -> Unit = {},
) {
    private class Direction {
        var count = 0
        var latest = 0.0
        var previous = 0.0
        var stamp = 0L
        var previousStamp = 0L
        var validationStamp = 0L
        var rejectionStamp = 0L

        val gain: Double get() = if (count >= RELIABILITY) minOf(latest, previous) else latest

        val stable: Boolean get() = count >= RELIABILITY && minOf(latest, previous) >= maxOf(latest, previous) * 0.5

        val readiness: Int get() = count + if (stable && rejectionStamp == 0L) 1 else 0
    }

    private class Gains(val down: Direction = Direction(), val up: Direction = Direction())
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
    private var reliableStamp = -1L
    private var valueStamp = -1L
    private var collectionStamp = -1L
    private var observed = false

    fun beginNode(level: Int) {
        stamp++
        depth = level
        objective = Double.NaN
        if (pending?.depth != level) pending = null
    }

    val retainedEntries: Int get() = gains.size + parentCount

    val hasReliableHistory: Boolean get() = reliableStamp >= 0L && stamp - reliableStamp <= maxAge

    val hasValueHistory: Boolean get() = valueStamp >= 0L && stamp - valueStamp <= maxAge

    val hasCollectionHistory: Boolean get() = collectionStamp >= 0L && stamp - collectionStamp <= maxAge

    val hasObservations: Boolean get() = observed

    fun clear(report: Boolean = true) {
        charge(gains.size.toLong())
        gains.clear()
        trimParents(0)
        pending = null
        objective = Double.NaN
        reliableStamp = -1L
        valueStamp = -1L
        collectionStamp = -1L
        observed = false
        if (report) observe("RESET")
    }

    fun record(value: Double) {
        objective = value
        val child = pending ?: return
        pending = null
        val gain = value - child.parent.objective
        val normalized = gain / child.distance
        if (!gain.isFinite() || gain < 0.0 || !normalized.isFinite()) {
            charge(1L)
            gains[child.parent.variable]?.let { history ->
                val direction = if (child.up) history.up else history.down
                charge(3L)
                direction.validationStamp = 0L
                direction.rejectionStamp = stamp
                observe("FORECAST_UNAVAILABLE")
            }
            observe("UNUSABLE")
            return
        }
        val history = gains.getOrPut(child.parent.variable) {
            charge(4L)
            Gains()
        }
        expire(history.down)
        expire(history.up)
        val direction = if (child.up) history.up else history.down
        validateForecast(direction, normalized)
        direction.previous = direction.latest
        direction.previousStamp = direction.stamp
        direction.latest = normalized
        direction.count = minOf(direction.count + 1, RELIABILITY)
        direction.stamp = stamp
        observed = true
        recordCollectionAvailability(history)
        recordAdviceAvailability(history)
        observe(if (child.up) "UP" else "DOWN")
    }

    private fun validateForecast(direction: Direction, normalized: Double) {
        charge(2L)
        val prior = direction.gain
        val predictable = direction.stable
        val validated = predictable && when {
            normalized < prior -> normalized / prior >= 0.5
            normalized > prior -> prior / normalized >= 0.5
            else -> true
        }
        // A validated prediction still depends on both of its earlier input observations.
        direction.validationStamp = if (validated) direction.previousStamp else 0L
        if (predictable) {
            charge(5L)
            direction.rejectionStamp = if (validated) 0L else stamp
            observe(if (validated) "FORECAST_VALIDATED" else "FORECAST_REJECTED")
        }
    }

    private fun recordCollectionAvailability(history: Gains) {
        if (history.down.count > 0 && history.up.count > 0 && history.down.readiness != history.up.readiness) {
            val majority = if (history.down.readiness > history.up.readiness) history.down else history.up
            val minority = if (majority === history.down) history.up else history.down
            if (majority.stable && (history.down.latest > 0.0 || history.up.latest > 0.0)) {
                collectionStamp = maxOf(collectionStamp, minOf(majority.previousStamp, minority.stamp))
            }
        }
    }

    private fun recordAdviceAvailability(history: Gains) {
        if (history.down.count >= RELIABILITY && history.up.count >= RELIABILITY) {
            charge(2L)
            if (history.down.stable && history.up.stable && (history.down.gain > 0.0 || history.up.gain > 0.0)) {
                // Rejection may expire while these numeric observations remain fresh.
                valueStamp = maxOf(valueStamp, minOf(history.down.previousStamp, history.up.previousStamp))
            }
            if (history.down.stable && history.up.stable &&
                history.down.rejectionStamp == 0L && history.up.rejectionStamp == 0L
            ) {
                if (history.down.validationStamp > 0L && history.up.validationStamp > 0L) {
                    if (history.down.gain > 0.0 && history.up.gain > 0.0) {
                        reliableStamp = maxOf(reliableStamp, minOf(history.down.validationStamp, history.up.validationStamp))
                    }
                } else observe("VARIABLE_UNCALIBRATED")
            } else observe(if (history.down.stable && history.up.stable) "FORECAST_SUPPRESSED" else "UNSTABLE")
        }
    }

    fun branch(variable: VarRef, value: Double) {
        charge(8L)
        trimParents(depth)
        if (depth !in 0 until MAX_DEPTH || !objective.isFinite() || !value.isFinite()) return
        val fraction = value - floor(value)
        if (fraction <= DISTANCE_TOL || fraction >= 1.0 - DISTANCE_TOL) return
        val records = parents ?: run {
            charge(MAX_DEPTH.toLong())
            arrayOfNulls<Parent>(MAX_DEPTH).also { parents = it }
        }
        records[depth] = Parent(objective, variable, value)
        parentCount++
        parentLimit = depth + 1
    }

    private fun trimParents(firstRemoved: Int) {
        charge(1L)
        if (firstRemoved >= parentLimit) return
        val records = parents ?: return
        val start = firstRemoved.coerceAtLeast(0)
        charge((parentLimit - start).toLong())
        for (index in start until parentLimit) {
            if (records[index] != null) {
                records[index] = null
                parentCount--
            }
        }
        parentLimit = start
    }

    fun commit(decision: SearchDecision, level: Int) {
        charge(8L)
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

    fun score(variable: VarRef, value: Double): Double = estimate(variable, value)
        .takeIf { it > 0.0 } ?: Double.NaN

    fun estimate(variable: VarRef, value: Double): Double {
        val history = reliable(variable, value) ?: return Double.NaN
        val fraction = value - floor(value)
        val down = history.down.gain * fraction
        val up = history.up.gain * (1.0 - fraction)
        val score = sqrt(down) * sqrt(up)
        return score.takeIf { it.isFinite() } ?: Double.NaN
    }

    fun upperEstimate(variable: VarRef, value: Double): Double {
        val history = reliable(variable, value) ?: return Double.NaN
        charge(6L)
        val fraction = value - floor(value)
        val down = maxOf(history.down.latest, history.down.previous) * fraction
        val up = maxOf(history.up.latest, history.up.previous) * (1.0 - fraction)
        val score = sqrt(down) * sqrt(up)
        return score.takeIf { it.isFinite() } ?: Double.NaN
    }

    fun preferGainDirection(variable: VarRef, value: Double, cutoff: Double = Double.POSITIVE_INFINITY): Boolean? {
        val history = reliable(variable, value, requireValidation = false) ?: return null
        charge(12L)
        val fraction = value - floor(value)
        val down = history.down.gain * fraction
        val up = history.up.gain * (1.0 - fraction)
        val downHigh = maxOf(history.down.latest, history.down.previous) * fraction
        val upHigh = maxOf(history.up.latest, history.up.previous) * (1.0 - fraction)
        if (!downHigh.isFinite() || !upHigh.isFinite()) return null
        val tolerance = 1e-9 * maxOf(1.0, downHigh, upHigh)
        val downFirst = downHigh < up - tolerance
        val upFirst = upHigh < down - tolerance
        if (!downFirst && !upFirst) {
            observe("PASSIVE_VALUE_SUPPORT_OVERLAP")
            return null
        }
        val gap = cutoff - objective
        if (gap.isNaN() || gap <= 0.0) return null
        val preferHighGain = maxOf(down, up) >= gap
        if (!preferHighGain && maxOf(downHigh, upHigh) >= gap) {
            observe("PASSIVE_VALUE_GAP_UNCERTAIN")
            return null
        }
        observe("PASSIVE_VALUE_SUPPORT_SEPARATED")
        return if (preferHighGain) downFirst else upFirst
    }

    private fun reliable(variable: VarRef, value: Double, requireValidation: Boolean = true): Gains? {
        if (!value.isFinite() || (variable is VarRef.Bool && value !in 0.0..1.0)) return null
        val history = gains[variable] ?: return null
        expire(history.down)
        expire(history.up)
        charge(2L)
        if (!history.down.stable || !history.up.stable ||
            history.down.rejectionStamp > 0L || history.up.rejectionStamp > 0L ||
            (requireValidation && (history.down.validationStamp == 0L || history.up.validationStamp == 0L))
        ) return null
        val fraction = value - floor(value)
        if (fraction <= DISTANCE_TOL || fraction >= 1.0 - DISTANCE_TOL) return null
        return history
    }

    fun preferUnderObservedDirection(variable: VarRef, value: Double): Boolean? {
        if (!value.isFinite() || (variable is VarRef.Bool && value !in 0.0..1.0)) return null
        val history = gains[variable] ?: return null
        expire(history.down)
        expire(history.up)
        val fraction = value - floor(value)
        if (fraction <= DISTANCE_TOL || fraction >= 1.0 - DISTANCE_TOL) return null
        if (history.down.count == 0 || history.up.count == 0) return null
        if (history.down.readiness == history.up.readiness) return null
        val sampledDirection = if (history.up.readiness < history.down.readiness) history.down else history.up
        if (!sampledDirection.stable) return null
        if (history.down.latest <= 0.0 && history.up.latest <= 0.0) {
            observe("PASSIVE_COLLECTION_ZERO_GAIN")
            return null
        }
        return history.up.readiness < history.down.readiness
    }

    private fun expire(direction: Direction) {
        if (direction.validationStamp > 0L) charge(1L)
        if (direction.rejectionStamp > 0L) {
            charge(1L)
            if (stamp - direction.rejectionStamp > maxAge) {
                direction.rejectionStamp = 0L
                observe("REJECTION_EXPIRED")
            }
        }
        if (direction.count > 0 && stamp - direction.stamp > maxAge) {
            direction.count = 0
            direction.latest = 0.0
            direction.previous = 0.0
            direction.validationStamp = 0L
            observe("EXPIRED")
        } else if (direction.count >= RELIABILITY && stamp - direction.previousStamp > maxAge) {
            direction.count = 1
            direction.previous = 0.0
            direction.validationStamp = 0L
            observe("EXPIRED")
        } else if (direction.validationStamp > 0L && stamp - direction.validationStamp > maxAge) {
            direction.validationStamp = 0L
            observe("CALIBRATION_EXPIRED")
        }
    }

    private companion object {
        const val MAX_DEPTH = 128
        const val RELIABILITY = 2
        const val DISTANCE_TOL = 1e-6
    }
}
