package com.eignex.klause.backtrack.lp

import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.VarRef
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

internal class LpDirectionalHistory(
    private val observe: (String) -> Unit,
    private val maxAge: Long = 256L,
    private val charge: (Long) -> Unit = {},
) {
    private class Direction(private val charge: (Long) -> Unit) {
        private val values = DoubleArray(WINDOW)
        private val stamps = LongArray(WINDOW)
        private var first = 0
        var count = 0
            private set
        var gain = 0.0
            private set
        var stable = false
            private set

        val stamp: Long get() = if (count == 0) 0L else stamps[(first + count - 1) % WINDOW]
        val pairStamp: Long get() = if (count < RELIABILITY) 0L else stamps[(first + count - RELIABILITY) % WINDOW]

        fun add(value: Double, stamp: Long) {
            charge(5L)
            val index = (first + count) % WINDOW
            if (count == WINDOW) first = (first + 1) % WINDOW else count++
            values[index] = value
            stamps[index] = stamp
            summarize()
        }

        fun expire(stamp: Long, maxAge: Long): Boolean {
            var expired = false
            while (count > 0) {
                charge(1L)
                if (stamp - stamps[first] <= maxAge) break
                values[first] = 0.0
                stamps[first] = 0L
                first = (first + 1) % WINDOW
                count--
                expired = true
            }
            if (expired) summarize()
            return expired
        }

        private fun summarize() {
            charge(5L + 3L * count)
            var maximum = 0.0
            repeat(count) { maximum = maxOf(maximum, values[(first + it) % WINDOW]) }
            if (count < RELIABILITY || maximum == 0.0) {
                gain = maximum
                stable = count >= RELIABILITY
                return
            }

            // Scaling keeps finite samples from overflowing the sums and squared deviations.
            var sum = 0.0
            repeat(count) { sum += values[(first + it) % WINDOW] / maximum }
            val mean = sum / count
            var squared = 0.0
            repeat(count) {
                val residual = values[(first + it) % WINDOW] / maximum - mean
                squared += residual * residual
            }
            val error = sqrt(squared / (count.toDouble() * (count - 1)))
            stable = error <= 0.5 * mean
            gain = maximum * maxOf(0.0, mean - error)
        }
    }

    private class Gains(charge: (Long) -> Unit) {
        val down = Direction(charge)
        val up = Direction(charge)
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
            observe("UNUSABLE")
            return
        }
        val history = gains.getOrPut(child.parent.variable) {
            charge(4L * WINDOW)
            Gains(charge)
        }
        expire(history.down)
        expire(history.up)
        val direction = if (child.up) history.up else history.down
        direction.add(normalized, stamp)
        observed = true
        if (history.down.count > 0 && history.up.count > 0 && history.down.count != history.up.count &&
            minOf(history.down.count, history.up.count) < RELIABILITY
        ) {
            val majority = if (history.down.count > history.up.count) history.down else history.up
            val minority = if (majority === history.down) history.up else history.down
            if (majority.stable && majority.gain > 0.0) {
                collectionStamp = maxOf(collectionStamp, minOf(majority.pairStamp, minority.stamp))
            }
        }
        if (history.down.count >= RELIABILITY && history.up.count >= RELIABILITY) {
            if (history.down.stable && history.up.stable) {
                val pairStamp = minOf(history.down.pairStamp, history.up.pairStamp)
                if (history.down.gain > 0.0 || history.up.gain > 0.0) valueStamp = maxOf(valueStamp, pairStamp)
                if (history.down.gain > 0.0 && history.up.gain > 0.0) reliableStamp = maxOf(reliableStamp, pairStamp)
            } else observe("UNSTABLE")
        }
        observe(if (child.up) "UP" else "DOWN")
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

    fun preferGainDirection(variable: VarRef, value: Double, cutoff: Double = Double.POSITIVE_INFINITY): Boolean? {
        val history = reliable(variable, value) ?: return null
        val fraction = value - floor(value)
        val down = history.down.gain * fraction
        val up = history.up.gain * (1.0 - fraction)
        if (!down.isFinite() || !up.isFinite() || abs(down - up) <= 1e-9 * maxOf(1.0, down, up)) return null
        val gap = cutoff - objective
        if (gap.isNaN() || gap <= 0.0) return null
        val preferHighGain = maxOf(down, up) >= gap
        return if (preferHighGain) up > down else up < down
    }

    private fun reliable(variable: VarRef, value: Double): Gains? {
        if (!value.isFinite() || (variable is VarRef.Bool && value !in 0.0..1.0)) return null
        val history = gains[variable] ?: return null
        expire(history.down)
        expire(history.up)
        if (!history.down.stable || !history.up.stable) return null
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
        if (history.down.count >= RELIABILITY && history.up.count >= RELIABILITY) return null
        if (history.down.count == history.up.count) return null
        val sampledDirection = if (history.up.count < history.down.count) history.down else history.up
        if (!sampledDirection.stable) return null
        if (sampledDirection.gain <= 0.0) {
            observe("PASSIVE_COLLECTION_ZERO_GAIN")
            return null
        }
        return history.up.count < history.down.count
    }

    private fun expire(direction: Direction) {
        if (direction.expire(stamp, maxAge)) observe("EXPIRED")
    }

    private companion object {
        const val MAX_DEPTH = 128
        const val RELIABILITY = 2
        const val WINDOW = 8
        const val DISTANCE_TOL = 1e-6
    }
}
