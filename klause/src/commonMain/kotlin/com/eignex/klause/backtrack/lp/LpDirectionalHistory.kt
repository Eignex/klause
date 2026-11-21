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
    internal class Direction {
        var count = 0
        var latest = 0.0
        var previous = 0.0
        var stamp = 0L
        var previousStamp = 0L
        var parent = 0.0
        var previousParent = 0.0
        var outcomeCount = 0
        var outcomeLatest = false
        var outcomePrevious = false
        var outcomeStamp = 0L
        var outcomePreviousStamp = 0L
        var outcomeParent = 0.0
        var outcomePreviousParent = 0.0

        val gain: Double get() = if (count >= RELIABILITY) minOf(latest, previous) else latest

        val stable: Boolean get() = count >= RELIABILITY && maxOf(latest, previous) <= minOf(latest, previous) * 2.0
    }

    internal class Gains(val variable: VarRef, val down: Direction = Direction(), val up: Direction = Direction()) {
        var indexed = false
        val hasNumericPair: Boolean get() = down.count >= RELIABILITY && up.count >= RELIABILITY
    }
    internal class Parent(val objective: Double, val variable: VarRef, val value: Double)
    internal class Pending(val parent: Parent, val up: Boolean, val distance: Double, val depth: Int)

    private val gains = HashMap<VarRef, Gains>()
    private var candidates: Array<VarRef?>? = null
    private var candidateCount = 0
    private var generation = 0L
    private var parents: Array<Parent?>? = null
    private var parentCount = 0
    private var parentLimit = 0
    private var pending: Pending? = null
    private var pendingOutcome: Pending? = null
    private var stamp = 0L
    private var depth = 0
    private var objective = Double.NaN
    private var reliableStamp = -1L
    private var valueStamp = -1L
    private var collectionStamp = -1L
    private var prunedStamp = -1L
    private var observed = false
    private var calibrationRemaining = 128

    fun beginNode(level: Int) {
        stamp++
        depth = level
        objective = Double.NaN
        if (pending?.depth != level) pending = null
        if (pendingOutcome?.depth != level) pendingOutcome = null
    }

    val retainedEntries: Int get() = gains.size + parentCount

    val hasReliableHistory: Boolean get() = reliableStamp >= 0L && stamp - reliableStamp <= maxAge

    val hasValueHistory: Boolean get() = valueStamp >= 0L && stamp - valueStamp <= maxAge

    val hasCollectionHistory: Boolean get() = collectionStamp >= 0L && stamp - collectionStamp <= maxAge

    val hasPruningHistory: Boolean get() = prunedStamp >= 0L && stamp - prunedStamp <= maxAge

    val hasObservations: Boolean get() = observed

    fun restart() {
        charge(3L)
        trimParents(0)
        pending = null
        pendingOutcome = null
        objective = Double.NaN
        observe("RESTART")
        if (gains.isNotEmpty()) {
            charge(1L)
            observe("RESTART_RETAINED")
        }
    }

    fun clear(report: Boolean = true) {
        generation++
        val retainedCandidates = candidateCount
        candidates = null
        candidateCount = 0
        charge(gains.size.toLong() + retainedCandidates)
        gains.clear()
        trimParents(0)
        pending = null
        pendingOutcome = null
        objective = Double.NaN
        reliableStamp = -1L
        valueStamp = -1L
        collectionStamp = -1L
        prunedStamp = -1L
        observed = false
        if (report) observe("RESET")
    }

    internal inline fun record(
        value: Double,
        cutoff: Double = Double.NaN,
        crossinline current: () -> Boolean = { true },
    ) {
        objective = value
        val child = pending ?: return
        pending = null
        val gain = value - child.parent.objective
        val normalized = gain / child.distance
        if (!gain.isFinite() || gain < 0.0 || !normalized.isFinite()) {
            observe("UNUSABLE")
            return
        }
        val version = generation
        charge(4L)
        if (version != generation) return
        val history = gains.getOrPut(child.parent.variable) { Gains(child.parent.variable) }
        expire(history.down)
        expire(history.up)
        if (!observeNextGain(history, child, gain, cutoff, version, current)) return
        recordGain(history, child, normalized)
    }

    private fun recordGain(history: Gains, child: Pending, normalized: Double) {
        val direction = if (child.up) history.up else history.down
        direction.previous = direction.latest
        direction.previousStamp = direction.stamp
        direction.previousParent = direction.parent
        direction.parent = child.parent.objective
        direction.latest = normalized
        direction.count = minOf(direction.count + 1, RELIABILITY)
        direction.stamp = stamp
        observed = true
        if (history.down.count != history.up.count) {
            val majority = if (history.down.count > history.up.count) history.down else history.up
            val minority = if (majority === history.down) history.up else history.down
            if (majority.stable && majority.gain > 0.0) {
                charge(1L)
                val minorityStamp = if (minority.count > 0) minority.stamp else majority.previousStamp
                collectionStamp = maxOf(collectionStamp, minOf(majority.previousStamp, minorityStamp))
            }
        }
        if (history.down.count >= RELIABILITY && history.up.count >= RELIABILITY) {
            if (history.down.stable && history.up.stable) {
                val pairStamp = minOf(history.down.previousStamp, history.up.previousStamp)
                if (history.down.gain > 0.0 || history.up.gain > 0.0) valueStamp = maxOf(valueStamp, pairStamp)
                if (history.down.gain > 0.0 && history.up.gain > 0.0) {
                    reliableStamp = maxOf(reliableStamp, pairStamp)
                    index(history)
                }
            } else observe("UNSTABLE")
        }
        observe(if (child.up) "UP" else "DOWN")
    }

    private inline fun observeNextGain(
        history: Gains,
        child: Pending,
        gain: Double,
        cutoff: Double,
        version: Long,
        crossinline current: () -> Boolean,
    ): Boolean {
        if (cutoff.isNaN() || calibrationRemaining <= 0 || !history.hasNumericPair) return true
        charge(8L)
        if (version != generation || !current()) return false
        if (!history.down.stable || !history.up.stable ||
            (history.down.gain <= 0.0 && history.up.gain <= 0.0)
        ) return true
        calibrationRemaining--
        charge(32L)
        if (version != generation || !current()) return false
        if (!reportCalibration("CHECK", version, current)) return false
        val gap = cutoff - child.parent.objective
        val downLatest = (cutoff - history.down.parent) / gap
        val downPrevious = (cutoff - history.down.previousParent) / gap
        val upLatest = (cutoff - history.up.parent) / gap
        val upPrevious = (cutoff - history.up.previousParent) / gap
        val direction = if (child.up) history.up else history.down
        val lower = direction.gain * child.distance
        val upper = maxOf(direction.latest, direction.previous) * child.distance
        val result = when {
            !gap.isFinite() || gap <= 0.0 || !lower.isFinite() || !upper.isFinite() ||
                (lower == 0.0 && direction.gain > 0.0) -> "UNKNOWN"
            !downLatest.isFinite() || !downPrevious.isFinite() ||
                !upLatest.isFinite() || !upPrevious.isFinite() ||
                downLatest !in 0.5..2.0 || downPrevious !in 0.5..2.0 ||
                upLatest !in 0.5..2.0 || upPrevious !in 0.5..2.0 -> "CONTEXT_MISMATCH"
            else -> {
                val tolerance = 1e-9 * maxOf(1.0, abs(gain), abs(lower), abs(upper))
                when {
                    gain + tolerance >= lower && gain - tolerance <= upper -> "WITHIN_RANGE"
                    gain + tolerance < 0.5 * lower -> "LOW_OUTLIER"
                    gain - tolerance > 2.0 * upper -> "HIGH_OUTLIER"
                    else -> "NEAR_RANGE"
                }
            }
        }
        if (!reportCalibration(result, version, current)) return false
        if (result != "UNKNOWN" && result != "CONTEXT_MISMATCH") {
            val gapResult = when {
                lower >= gap && gain < gap -> "GAP_OVERPREDICTED"
                lower >= gap -> "GAP_MET"
                gain >= gap -> "GAP_UNPREDICTED"
                else -> "GAP_NOT_MET"
            }
            if (!reportCalibration(gapResult, version, current)) return false
        }
        return calibrationRemaining != 0 || reportCalibration("EXHAUSTED", version, current)
    }

    private inline fun reportCalibration(
        event: String,
        version: Long,
        crossinline current: () -> Boolean,
    ): Boolean {
        observe("PASSIVE_NUMERIC_NEXT_" + event)
        return version == generation && current()
    }

    private fun index(history: Gains) {
        if (history.indexed || candidateCount >= MAX_CANDIDATES) return
        val version = generation
        var entries = candidates
        if (entries == null || candidateCount == entries.size) {
            val capacity = minOf(MAX_CANDIDATES, maxOf(16, candidateCount * 2))
            charge(capacity.toLong() + candidateCount)
            if (generation != version) return
            val replacement = arrayOfNulls<VarRef>(capacity)
            entries?.copyInto(replacement, endIndex = candidateCount)
            entries = replacement
            candidates = replacement
        }
        charge(1L)
        if (generation != version) return
        entries[candidateCount++] = history.variable
        history.indexed = true
    }

    internal inline fun forEachCandidate(crossinline current: () -> Boolean, crossinline visit: (VarRef) -> Unit) {
        val entries = candidates ?: return
        val count = candidateCount
        val version = generation
        for (index in 0 until count) {
            charge(4L)
            if (version != generation || !current()) return
            visit(checkNotNull(entries[index]))
        }
    }

    fun hasPositiveGap(cutoff: Double): Boolean {
        val gap = cutoff - objective
        return gap.isFinite() && gap > 0.0
    }

    fun hasReliablePair(variable: VarRef, value: Double): Boolean {
        val version = generation
        charge(8L)
        return version == generation && reliable(variable, value) != null && version == generation
    }

    fun minimumPrediction(variable: VarRef, value: Double, cutoff: Double, upper: Boolean = false): Double {
        val version = generation
        charge(24L)
        if (version != generation) return Double.NaN
        val gap = cutoff - objective
        if (!gap.isFinite() || gap <= 0.0) return Double.NaN
        val history = reliable(variable, value) ?: return Double.NaN
        charge(24L)
        if (version != generation) return Double.NaN
        val downLatest = (cutoff - history.down.parent) / gap
        val downPrevious = (cutoff - history.down.previousParent) / gap
        val upLatest = (cutoff - history.up.parent) / gap
        val upPrevious = (cutoff - history.up.previousParent) / gap
        if (!downLatest.isFinite() || !downPrevious.isFinite() ||
            !upLatest.isFinite() || !upPrevious.isFinite() ||
            downLatest !in 0.5..2.0 || downPrevious !in 0.5..2.0 ||
            upLatest !in 0.5..2.0 || upPrevious !in 0.5..2.0
        ) return Double.NaN
        val fraction = value - floor(value)
        val down = history.down.gain * fraction
        val up = history.up.gain * (1.0 - fraction)
        if (!down.isFinite() || !up.isFinite() ||
            (down == 0.0 && history.down.gain > 0.0) || (up == 0.0 && history.up.gain > 0.0)
        ) return Double.NaN
        if (upper) {
            charge(8L)
            if (version != generation) return Double.NaN
            val downUpper = maxOf(history.down.latest, history.down.previous) * fraction
            val upUpper = maxOf(history.up.latest, history.up.previous) * (1.0 - fraction)
            if (!downUpper.isFinite() || !upUpper.isFinite()) return Double.NaN
            return minOf(downUpper, upUpper)
        }
        return if (version == generation) minOf(down, up) else Double.NaN
    }

    fun branch(variable: VarRef, value: Double) {
        val version = generation
        charge(8L)
        if (version != generation) return
        trimParents(depth)
        if (version != generation) return
        if (depth !in 0 until MAX_DEPTH || !objective.isFinite() || !value.isFinite()) return
        val fraction = value - floor(value)
        if (fraction <= DISTANCE_TOL || fraction >= 1.0 - DISTANCE_TOL) return
        var records = parents
        if (records == null || depth >= records.size) {
            val retained = records?.size ?: 0
            val capacity = minOf(MAX_DEPTH, maxOf(16, depth + 1, retained * 2))
            charge(capacity.toLong() + retained)
            if (version != generation) return
            val replacement = arrayOfNulls<Parent>(capacity)
            records?.copyInto(replacement)
            records = replacement
            parents = replacement
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
        pendingOutcome = null
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
        pendingOutcome = pending
    }

    fun infeasible() {
        if (pending != null) observe("INFEASIBLE")
        pending = null
    }

    internal inline fun finishNode(pruned: Boolean? = null, crossinline current: () -> Boolean = { true }) {
        if (pruned != null && current()) recordOutcome(pruned, current)
        pendingOutcome = null
        if (pending != null) observe("MISSING")
        pending = null
    }

    private inline fun recordOutcome(pruned: Boolean, crossinline current: () -> Boolean) {
        val child = pendingOutcome ?: return
        pendingOutcome = null
        val version = generation
        charge(13L)
        if (!current() || version != generation) return
        val history = gains.getOrPut(child.parent.variable) { Gains(child.parent.variable) }
        val direction = if (child.up) history.up else history.down
        if (direction.outcomeCount > 0 && stamp - direction.outcomeStamp > maxAge) direction.outcomeCount = 0
        else if (direction.outcomeCount >= RELIABILITY && stamp - direction.outcomePreviousStamp > maxAge) {
            direction.outcomeCount = 1
        }
        direction.outcomePrevious = direction.outcomeLatest
        direction.outcomePreviousStamp = direction.outcomeStamp
        direction.outcomePreviousParent = direction.outcomeParent
        direction.outcomeLatest = pruned
        direction.outcomeParent = child.parent.objective
        direction.outcomeStamp = stamp
        direction.outcomeCount = minOf(RELIABILITY, direction.outcomeCount + 1)
        if (direction.outcomeCount >= RELIABILITY && (pruned || direction.outcomePrevious)) {
            prunedStamp = maxOf(prunedStamp, direction.outcomePreviousStamp)
            observe("OUTCOME_RATE_READY")
        }
        observe(if (pruned) "OUTCOME_PRUNED" else "OUTCOME_OPEN")
    }

    fun preferDirection(variable: VarRef, value: Double, cutoff: Double): Boolean? {
        val version = generation
        charge(8L)
        if (version != generation) return null
        val history = reliable(variable, value) ?: return null
        if (history.down.gain <= 0.0 && history.up.gain <= 0.0) return null
        charge(8L)
        if (version != generation) return null
        val down = outcomeRate(history.down)
        val up = outcomeRate(history.up)
        if (!down.isFinite() || !up.isFinite()) return null
        charge(16L)
        if (version != generation) return null
        val gap = cutoff - objective
        if (!gap.isFinite() || gap <= 0.0) return null
        val fraction = value - floor(value)
        val downGain = history.down.gain * fraction
        val upGain = history.up.gain * (1.0 - fraction)
        if (!downGain.isFinite() || !upGain.isFinite() ||
            (downGain == 0.0 && history.down.gain > 0.0) ||
            (upGain == 0.0 && history.up.gain > 0.0)
        ) return null
        charge(8L)
        if (version != generation) return null
        val prune = maxOf(downGain, upGain) >= gap
        if (down == up && downGain == upGain) return null
        val preferUp = when {
            down == up -> if (prune) upGain > downGain else upGain < downGain
            prune -> up > down
            else -> up < down
        }
        val preferredGain = if (preferUp) upGain else downGain
        val otherGain = if (preferUp) downGain else upGain
        if ((prune && preferredGain < gap) || (!prune && preferredGain > otherGain)) return null
        if (!prune) {
            charge(8L)
            if (version != generation) return null
            val direction = if (preferUp) history.up else history.down
            val movement = if (preferUp) 1.0 - fraction else fraction
            val upper = maxOf(direction.latest, direction.previous) * movement
            if (!upper.isFinite() || upper >= otherGain) return null
        }
        charge(24L)
        if (version != generation) return null
        val downLatest = (cutoff - history.down.outcomeParent) / gap
        val downPrevious = (cutoff - history.down.outcomePreviousParent) / gap
        val upLatest = (cutoff - history.up.outcomeParent) / gap
        val upPrevious = (cutoff - history.up.outcomePreviousParent) / gap
        if (!downLatest.isFinite() || !downPrevious.isFinite() ||
            !upLatest.isFinite() || !upPrevious.isFinite() ||
            downLatest !in 0.5..2.0 || downPrevious !in 0.5..2.0 ||
            upLatest !in 0.5..2.0 || upPrevious !in 0.5..2.0
        ) return null
        charge(24L)
        if (version != generation) return null
        val numericDownLatest = (cutoff - history.down.parent) / gap
        val numericDownPrevious = (cutoff - history.down.previousParent) / gap
        val numericUpLatest = (cutoff - history.up.parent) / gap
        val numericUpPrevious = (cutoff - history.up.previousParent) / gap
        if (!numericDownLatest.isFinite() || !numericDownPrevious.isFinite() ||
            !numericUpLatest.isFinite() || !numericUpPrevious.isFinite() ||
            numericDownLatest !in 0.5..2.0 || numericDownPrevious !in 0.5..2.0 ||
            numericUpLatest !in 0.5..2.0 || numericUpPrevious !in 0.5..2.0
        ) return null
        return preferUp
    }

    fun prunedDirections(variable: VarRef, value: Double): Double {
        val history = reliable(variable, value) ?: return Double.NaN
        val version = generation
        charge(8L)
        if (version != generation) return Double.NaN
        val down = outcomeEstimate(history.down)
        val up = outcomeEstimate(history.up)
        return down + up
    }

    private fun outcomeRate(direction: Direction): Double {
        if (direction.outcomeCount < RELIABILITY || stamp - direction.outcomePreviousStamp > maxAge) return Double.NaN
        return (if (direction.outcomeLatest) 0.5 else 0.0) + (if (direction.outcomePrevious) 0.5 else 0.0)
    }

    private fun outcomeEstimate(direction: Direction): Double =
        if (direction.outcomeCount < RELIABILITY || stamp - direction.outcomePreviousStamp > maxAge ||
            direction.outcomeLatest != direction.outcomePrevious
        ) Double.NaN else if (direction.outcomeLatest) 1.0 else 0.0

    fun score(variable: VarRef, value: Double): Double = estimate(variable, value)
        .takeIf { it > 0.0 } ?: Double.NaN

    fun estimate(variable: VarRef, value: Double): Double {
        val history = reliable(variable, value) ?: return Double.NaN
        val downGain = history.down.gain
        val upGain = history.up.gain
        if (downGain == 0.0 || upGain == 0.0) return 0.0
        charge(2L)
        val fraction = value - floor(value)
        val downRoot = sqrt(downGain) * sqrt(fraction)
        val upRoot = sqrt(upGain) * sqrt(1.0 - fraction)
        val score = downRoot * upRoot
        return score.takeIf { it.isFinite() && it > 0.0 } ?: Double.NaN
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
        if (direction.count > 0 && stamp - direction.stamp > maxAge) {
            direction.count = 0
            direction.latest = 0.0
            direction.previous = 0.0
            observe("EXPIRED")
        } else if (direction.count >= RELIABILITY && stamp - direction.previousStamp > maxAge) {
            direction.count = 1
            direction.previous = 0.0
            observe("EXPIRED")
        }
    }

    private companion object {
        const val MAX_DEPTH = 128
        const val MAX_CANDIDATES = 8192
        const val RELIABILITY = 2
        const val DISTANCE_TOL = 1e-6
    }
}
