package com.eignex.klause.backtrack.lp

import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.VarRef
import com.eignex.klause.util.ExecutionControl
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

internal class LpDirectionalHistory(
    private val observe: (String) -> Unit,
    private val maxAge: Long = 256L,
    private val charge: (Long) -> Unit = {},
    private val depthRetention: Boolean = false,
    private val depthCancellation: ExecutionControl = ExecutionControl.Never,
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
        var parentDepth = -1
        var previousParentDepth = -1
        var outcomeParentDepth = -1
        var outcomePreviousParentDepth = -1

        val gain: Double get() = if (count >= RELIABILITY) minOf(latest, previous) else latest

        val stable: Boolean get() = count >= RELIABILITY && maxOf(latest, previous) <= minOf(latest, previous) * 2.0

        fun recordNumeric(gain: Double, node: Long, objective: Double, level: Int) {
            previous = latest
            previousStamp = stamp
            previousParent = parent
            previousParentDepth = parentDepth
            latest = gain
            stamp = node
            parent = objective
            parentDepth = level
            count = minOf(count + 1, RELIABILITY)
        }

        fun recordOutcome(pruned: Boolean, node: Long, objective: Double, level: Int) {
            outcomePrevious = outcomeLatest
            outcomePreviousStamp = outcomeStamp
            outcomePreviousParent = outcomeParent
            outcomePreviousParentDepth = outcomeParentDepth
            outcomeLatest = pruned
            outcomeStamp = node
            outcomeParent = objective
            outcomeParentDepth = level
            outcomeCount = minOf(outcomeCount + 1, RELIABILITY)
        }
    }

    internal class Gains(val variable: VarRef, val down: Direction = Direction(), val up: Direction = Direction()) {
        var indexed = false
    }
    internal class Parent(val objective: Double, val variable: VarRef, val value: Double)
    internal class Pending(val parent: Parent, val up: Boolean, val distance: Double, val depth: Int)
    internal class DepthHistory(val variable: VarRef, val level: Int, val gains: Gains = Gains(variable))

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
    private var depthHistories: Array<DepthHistory?>? = null
    private var depthHistoryCount = 0
    private var depthHistoryRemaining = 128
    private var depthValueStamp = -1L
    private var depthPrunedStamp = -1L

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

    val hasDepthHistory: Boolean get() = depthRetention && depthValueStamp >= 0L && depthPrunedStamp >= 0L &&
        stamp - depthValueStamp <= maxAge && stamp - depthPrunedStamp <= maxAge

    fun isImmediateChild(level: Int): Boolean = level > 0 && depth == level - 1

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
        charge(gains.size.toLong() + retainedCandidates + depthHistoryCount)
        gains.clear()
        depthHistories = null
        depthHistoryCount = 0
        depthValueStamp = -1L
        depthPrunedStamp = -1L
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
        val version = generation
        val node = stamp
        charge(6L)
        if (version != generation) return
        val history = gains.getOrPut(child.parent.variable) { Gains(child.parent.variable) }
        expire(history.down)
        expire(history.up)
        val direction = if (child.up) history.up else history.down
        direction.recordNumeric(normalized, stamp, child.parent.objective, child.depth - 1)
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
        if (depthRetention) recordDepthNumeric(child, normalized, history, version, node)
    }

    private fun compareDepth(entry: DepthHistory, variable: VarRef, level: Int): Int = when {
        (entry.variable is VarRef.Bool) != (variable is VarRef.Bool) -> if (entry.variable is VarRef.Bool) -1 else 1
        entry.variable.varId != variable.varId -> entry.variable.varId.compareTo(variable.varId)
        else -> entry.level.compareTo(level)
    }

    private inline fun depthPosition(
        variable: VarRef,
        level: Int,
        version: Long,
        node: Long,
        crossinline current: () -> Boolean,
    ): Int? {
        val entries = depthHistories ?: return 0
        val count = depthHistoryCount
        var low = 0
        var high = count
        while (low < high) {
            charge(4L)
            if (!current() || version != generation || node != stamp || depthHistories !== entries ||
                depthHistoryCount != count
            ) return null
            val middle = (low + high) ushr 1
            if (compareDepth(checkNotNull(entries[middle]), variable, level) < 0) low = middle + 1
            else high = middle
        }
        return low
    }

    private inline fun retainDepth(
        child: Pending,
        version: Long,
        node: Long,
        numericHistory: Gains?,
        crossinline current: () -> Boolean,
    ): Gains? {
        if (!current() || version != generation || node != stamp) return null
        if (depthHistories == null && depthHistoryRemaining <= 0) return null
        charge(8L)
        if (!current() || version != generation || node != stamp) return null
        val variable = child.parent.variable
        val level = child.depth - 1
        val position = depthPosition(variable, level, version, node, current) ?: return null
        val retained = depthHistories?.getOrNull(position)
        if (retained != null && compareDepth(retained, variable, level) == 0) return retained.gains
        if (numericHistory == null || depthHistoryRemaining <= 0) return null
        charge(16L)
        if (!current() || version != generation || node != stamp) return null
        val opposite = if (child.up) numericHistory.down else numericHistory.up
        val supported = (opposite.count > 0 && opposite.parentDepth == level && seedAge(opposite.stamp, node)) ||
            (opposite.count >= RELIABILITY && opposite.previousParentDepth == level &&
                seedAge(opposite.previousStamp, node))
        if (!supported) {
            observe("DEPTH_RETENTION_ADMISSION_NO_OPPOSITE")
            return null
        }
        depthHistoryRemaining--
        charge(224L + if (depthHistories == null) 128L else 0L)
        if (!current() || version != generation || node != stamp) return null
        val entries = depthHistories ?: arrayOfNulls<DepthHistory>(128).also { depthHistories = it }
        val index = depthPosition(variable, level, version, node, current) ?: return null
        val present = entries.getOrNull(index)
        if (present != null && compareDepth(present, variable, level) == 0) return present.gains
        val count = depthHistoryCount
        charge(64L + count - index)
        if (!current() || version != generation || node != stamp || depthHistories !== entries ||
            depthHistoryCount != count
        ) return null
        if (index < count) {
            entries.copyInto(entries, index + 1, index, count)
        }
        val entry = DepthHistory(variable, level)
        seedDepth(numericHistory.down, entry.gains.down, level, node)
        seedDepth(numericHistory.up, entry.gains.up, level, node)
        noteDepthPruning(entry.gains.down)
        noteDepthPruning(entry.gains.up)
        entries[index] = entry
        depthHistoryCount++
        observe("DEPTH_RETENTION_CONTEXT_RETAINED")
        if (depthHistoryRemaining == 0) observe("DEPTH_RETENTION_CONTEXT_EXHAUSTED")
        return if (current() && version == generation && node == stamp) entry.gains else null
    }

    private fun seedAge(sample: Long, node: Long): Boolean = node - sample in 1L..maxAge

    private fun seedDepth(source: Direction, target: Direction, level: Int, node: Long) {
        if (source.count >= RELIABILITY && source.previousParentDepth == level && seedAge(source.previousStamp, node)) {
            target.recordNumeric(source.previous, source.previousStamp, source.previousParent, level)
        }
        if (source.count > 0 && source.parentDepth == level && seedAge(source.stamp, node)) {
            target.recordNumeric(source.latest, source.stamp, source.parent, level)
        }
        if (source.outcomeCount >= RELIABILITY && source.outcomePreviousParentDepth == level &&
            seedAge(source.outcomePreviousStamp, node)
        ) {
            target.recordOutcome(source.outcomePrevious, source.outcomePreviousStamp, source.outcomePreviousParent, level)
        }
        if (source.outcomeCount > 0 && source.outcomeParentDepth == level && seedAge(source.outcomeStamp, node)) {
            target.recordOutcome(source.outcomeLatest, source.outcomeStamp, source.outcomeParent, level)
        }
    }

    private fun recordDepthNumeric(child: Pending, normalized: Double, ordinary: Gains, version: Long, node: Long) {
        val history = retainDepth(child, version, node, ordinary) { !depthCancellation() } ?: return
        charge(12L)
        if (depthCancellation() || version != generation || node != stamp) return
        val direction = if (child.up) history.up else history.down
        direction.recordNumeric(normalized, node, child.parent.objective, child.depth - 1)
        if (history.down.stable && history.up.stable && (history.down.gain > 0.0 || history.up.gain > 0.0)) {
            depthValueStamp = maxOf(depthValueStamp, minOf(history.down.previousStamp, history.up.previousStamp))
        }
        observe("DEPTH_RETENTION_NUMERIC")
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
        val node = stamp
        charge(15L)
        if (!current() || version != generation) return
        val history = gains.getOrPut(child.parent.variable) { Gains(child.parent.variable) }
        val direction = if (child.up) history.up else history.down
        if (direction.outcomeCount > 0 && stamp - direction.outcomeStamp > maxAge) direction.outcomeCount = 0
        else if (direction.outcomeCount >= RELIABILITY && stamp - direction.outcomePreviousStamp > maxAge) {
            direction.outcomeCount = 1
        }
        direction.recordOutcome(pruned, stamp, child.parent.objective, child.depth - 1)
        if (direction.outcomeCount >= RELIABILITY && (pruned || direction.outcomePrevious)) {
            prunedStamp = maxOf(prunedStamp, direction.outcomePreviousStamp)
            observe("OUTCOME_RATE_READY")
        }
        observe(if (pruned) "OUTCOME_PRUNED" else "OUTCOME_OPEN")
        if (depthRetention) recordDepthOutcome(child, pruned, version, node, current)
    }

    private inline fun recordDepthOutcome(
        child: Pending,
        pruned: Boolean,
        version: Long,
        node: Long,
        crossinline current: () -> Boolean,
    ) {
        val history = retainDepth(child, version, node, numericHistory = null, current) ?: return
        charge(12L)
        if (!current() || version != generation || node != stamp) return
        val direction = if (child.up) history.up else history.down
        direction.recordOutcome(pruned, node, child.parent.objective, child.depth - 1)
        noteDepthPruning(direction)
        observe(if (pruned) "DEPTH_RETENTION_OUTCOME_PRUNED" else "DEPTH_RETENTION_OUTCOME_OPEN")
    }

    private fun noteDepthPruning(direction: Direction) {
        if (direction.outcomeCount >= RELIABILITY && (direction.outcomeLatest || direction.outcomePrevious)) {
            depthPrunedStamp = maxOf(depthPrunedStamp, direction.outcomePreviousStamp)
        }
    }

    fun preferDirection(variable: VarRef, value: Double, cutoff: Double): Boolean? {
        val version = generation
        charge(8L)
        if (version != generation) return null
        val history = reliable(variable, value) ?: return null
        return preferred(history, value, cutoff, version)
    }

    internal inline fun depthPreference(
        variable: VarRef,
        value: Double,
        cutoff: Double,
        crossinline current: () -> Boolean,
    ): Boolean? {
        if (!hasDepthHistory) return null
        val version = generation
        val node = stamp
        charge(8L)
        if (!current() || version != generation || node != stamp) return null
        val index = depthPosition(variable, depth, version, node, current) ?: return null
        val entry = depthHistories?.getOrNull(index)
        if (entry == null || compareDepth(entry, variable, depth) != 0) {
            observe("DEPTH_RETENTION_LOOKUP_NO_CONTEXT")
            return null
        }
        if (!reliableDepth(entry.gains, variable, value)) {
            observe("DEPTH_RETENTION_LOOKUP_NUMERIC_DECLINED")
            return null
        }
        val preference = preferred(entry.gains, value, cutoff, version)
        if (!current() || version != generation || node != stamp) return null
        if (preference == null) observe("DEPTH_RETENTION_LOOKUP_PREFERENCE_DECLINED")
        return preference
    }

    private fun reliableDepth(history: Gains, variable: VarRef, value: Double): Boolean {
        if (!value.isFinite() || (variable is VarRef.Bool && value !in 0.0..1.0)) return false
        if (!history.down.stable || !history.up.stable || stamp - history.down.previousStamp > maxAge ||
            stamp - history.up.previousStamp > maxAge
        ) return false
        val fraction = value - floor(value)
        return fraction > DISTANCE_TOL && fraction < 1.0 - DISTANCE_TOL
    }

    private fun preferred(history: Gains, value: Double, cutoff: Double, version: Long): Boolean? {
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
        charge(32L)
        if (version != generation) return null
        if (depth < 0 || history.down.parentDepth != depth || history.down.previousParentDepth != depth ||
            history.up.parentDepth != depth || history.up.previousParentDepth != depth ||
            history.down.outcomeParentDepth != depth || history.down.outcomePreviousParentDepth != depth ||
            history.up.outcomeParentDepth != depth || history.up.outcomePreviousParentDepth != depth
        ) {
            observe("PASSIVE_VALUE_DEPTH_CONTEXT_DECLINED")
            return null
        }
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
