package com.eignex.klause.backtrack.lp

import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.VarRef
import kotlin.math.floor

internal class LpDirectionalHistory(
    private val observe: (String) -> Unit,
    private val maxAge: Long = 256L,
    private val charge: (Long) -> Unit = {},
    private val useClassPriors: Boolean = true,
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
    private var integerPrior: Gains? = null
    private var booleanPrior: Gains? = null
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
        if (pending?.depth != level) pending = null
    }

    val retainedEntries: Int get() = gains.size + parents.size +
        (if (integerPrior != null) 1 else 0) + (if (booleanPrior != null) 1 else 0)

    val mayHaveScores: Boolean get() = (reliableStamp >= 0L && stamp - reliableStamp <= maxAge) ||
        recent(integerPrior) || recent(booleanPrior)

    fun clear(report: Boolean = true) {
        gains.clear()
        integerPrior = null
        booleanPrior = null
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
            return
        }
        val history = gains.getOrPut(child.parent.variable, ::Gains)
        update(if (child.up) history.up else history.down, normalized)
        if (recent(history)) reliableStamp = stamp
        if (useClassPriors) {
            charge(1L)
            val prior = when (child.parent.variable) {
                is VarRef.Bool -> booleanPrior ?: Gains().also { booleanPrior = it }
                is VarRef.IntVar -> integerPrior ?: Gains().also { integerPrior = it }
            }
            update(if (child.up) prior.up else prior.down, normalized, prior = true)
        }
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

    fun score(variable: VarRef, value: Double): Double {
        if (!value.isFinite()) return Double.NaN
        val fraction = value - floor(value)
        if (fraction <= DISTANCE_TOL || fraction >= 1.0 - DISTANCE_TOL) return Double.NaN
        val local = gains[variable] ?: return Double.NaN
        val prior = if (reliable(local) == null) {
            if (!useClassPriors || local.down.count + local.up.count == 0) return Double.NaN
            charge(1L)
            reliable(if (variable is VarRef.Bool) booleanPrior else integerPrior, prior = true)
                ?: return Double.NaN
        } else null
        val down = (if (prior == null) local.down.mean else estimate(local.down, prior.down)) * fraction
        val up = (if (prior == null) local.up.mean else estimate(local.up, prior.up)) * (1.0 - fraction)
        val score = minOf(down, up) + maxOf(down, up) / 6.0
        return score.takeIf { it.isFinite() && it > 0.0 } ?: Double.NaN
    }

    fun usesPrior(variable: VarRef): Boolean = !recent(gains[variable])

    private fun estimate(local: Direction, prior: Direction): Double {
        val weight = minOf(local.count, RELIABILITY).toDouble() / RELIABILITY
        return local.mean * weight + prior.mean * (1.0 - weight)
    }

    private fun recent(history: Gains?): Boolean = history != null &&
        history.down.count >= RELIABILITY && history.up.count >= RELIABILITY &&
        stamp - history.down.stamp <= maxAge && stamp - history.up.stamp <= maxAge

    private fun reliable(history: Gains?, prior: Boolean = false): Gains? {
        history ?: return null
        expire(history.down, prior)
        expire(history.up, prior)
        return history.takeIf { it.down.count >= RELIABILITY && it.up.count >= RELIABILITY }
    }

    private fun update(direction: Direction, value: Double, prior: Boolean = false) {
        expire(direction, prior)
        direction.count = minOf(direction.count + 1, 8)
        direction.mean += (value - direction.mean) / direction.count
        direction.stamp = stamp
    }

    private fun expire(direction: Direction, prior: Boolean = false) {
        if (direction.count > 0 && stamp - direction.stamp > maxAge) {
            direction.count = 0
            direction.mean = 0.0
            observe(if (prior) "PRIOR_EXPIRED" else "EXPIRED")
        }
    }

    private companion object {
        const val MAX_DEPTH = 128
        const val RELIABILITY = 2
        const val DISTANCE_TOL = 1e-6
    }
}
