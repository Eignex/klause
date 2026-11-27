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
    private class DepthFormation(var selected: Boolean = false, var preferred: Boolean = false)

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
    private var depthJournal: LpDepthObservationJournal? = null
    private var depthFormations: HashMap<Long, DepthFormation>? = null
    private var depthFormationRemaining = 64
    var depthLookupEligible = false
        private set
    var depthGuardDeclined = false
        private set

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

    val hasDepthHistory: Boolean get() = (depthJournal?.size ?: 0) >= 4

    fun clearDepthLookup() {
        depthLookupEligible = false
        depthGuardDeclined = false
    }

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
        val formed = depthFormations
        candidates = null
        candidateCount = 0
        charge(gains.size.toLong() + retainedCandidates + (formed?.size ?: 0) +
            if (depthJournal == null) 0L else LpDepthObservationJournal.CLEAR_WORK)
        gains.clear()
        depthJournal?.clear()
        depthFormations = null
        clearDepthLookup()
        trimParents(0)
        pending = null
        pendingOutcome = null
        objective = Double.NaN
        reliableStamp = -1L
        valueStamp = -1L
        collectionStamp = -1L
        prunedStamp = -1L
        observed = false
        if (formed != null) {
            for (formation in formed.values) {
                if (!formation.selected) observe("DEPTH_RETENTION_FORMATION_CLEARED_UNSELECTED")
                else if (!formation.preferred) observe("DEPTH_RETENTION_FORMATION_CLEARED_WITHOUT_PREFERENCE")
            }
        }
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
        if (depthRetention) {
            recordDepth(child, normalized, null, version, node) { !depthCancellation() }
        }
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

    private fun recordDepth(
        child: Pending,
        gain: Double,
        pruned: Boolean?,
        version: Long,
        node: Long,
        current: () -> Boolean,
    ) {
        if (!current() || version != generation || node != stamp) return
        val journal = depthJournal ?: run {
            charge(LpDepthObservationJournal.indexWork(maxAge))
            if (!current() || version != generation || node != stamp) return
            LpDepthObservationJournal(charge, observe, maxAge).also { depthJournal = it }
        }
        fun live(): Boolean = current() && version == generation && node == stamp
        val recorded = journal.record(child, node, gain, pruned, ::live)
        if (recorded && pruned != null) observeDepthFormation(journal, child, node, ::live)
    }

    private fun observeDepthFormation(
        journal: LpDepthObservationJournal,
        child: Pending,
        node: Long,
        current: () -> Boolean,
    ) {
        if (depthFormationRemaining == 0) return
        charge(8L)
        if (!current()) return
        val variable = child.parent.variable
        val level = child.depth - 1
        val key = depthContextKey(variable, level)
        if (depthFormations?.containsKey(key) == true || !journal.mayHavePairs(variable, level)) return
        depthFormationRemaining--
        val history = journal.restore(variable, level, node, includeCurrent = true, current) ?: return
        observe("DEPTH_RETENTION_FORMATION_CHECK")
        if (!current()) return
        if (depthFormationRemaining == 0) {
            observe("DEPTH_RETENTION_FORMATION_EXHAUSTED")
            if (!current()) return
        }
        val decline = formationDecline(history)
        if (decline != null) {
            observe("DEPTH_RETENTION_FORMATION_$decline")
            return
        }
        charge(if (depthFormations == null) 80L else 32L)
        if (!current()) return
        val formed = depthFormations ?: HashMap<Long, DepthFormation>().also { depthFormations = it }
        formed[key] = DepthFormation()
        observe("DEPTH_RETENTION_FORMATION_COMPLETE")
    }

    private fun formationDecline(history: Gains): String? = when {
        history.down.count < RELIABILITY || history.up.count < RELIABILITY -> "NUMERIC_SHORT"
        !history.down.stable || !history.up.stable || (history.down.gain <= 0.0 && history.up.gain <= 0.0) ->
            "NUMERIC_UNRELIABLE"
        history.down.outcomeCount < RELIABILITY || history.up.outcomeCount < RELIABILITY -> "OUTCOME_SHORT"
        else -> null
    }

    private fun depthContextKey(variable: VarRef, level: Int): Long =
        (variable.varId.toLong() shl 8) or (level.toLong() shl 1) or if (variable is VarRef.Bool) 1L else 0L

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

    fun branch(variable: VarRef, value: Double, pointCurrent: (() -> Boolean)? = null) {
        val version = generation
        val node = stamp
        observeFormationSelection(variable, value, pointCurrent, version, node)
        if (version != generation || node != stamp) return
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

    private fun observeFormationSelection(
        variable: VarRef,
        value: Double,
        pointCurrent: (() -> Boolean)?,
        version: Long,
        node: Long,
    ) {
        val formed = depthFormations ?: return
        if (depth !in 0 until MAX_DEPTH || depthCancellation()) return
        fun current(): Boolean = !depthCancellation() && version == generation && node == stamp
        charge(8L)
        if (!current()) return
        val formation = formed[depthContextKey(variable, depth)] ?: return
        if (formation.selected) return
        formation.selected = true
        observe("DEPTH_RETENTION_FORMATION_SELECTED")
        if (!current()) return
        if (pointCurrent?.invoke() == false) {
            observe("DEPTH_RETENTION_FORMATION_SELECTED_STALE_POINT")
            return
        }
        if (!objective.isFinite() || !fractionalDepthPoint(variable, value)) {
            observe("DEPTH_RETENTION_FORMATION_SELECTED_UNUSABLE_POINT")
            return
        }
        val journal = depthJournal ?: return
        val restored = journal.restore(variable, depth, node) {
            current() && pointCurrent?.invoke() != false
        } ?: return
        observe(if (formationDecline(restored) == null) "DEPTH_RETENTION_FORMATION_SELECTED_READY"
        else "DEPTH_RETENTION_FORMATION_SELECTED_INCOMPLETE")
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
        if (depthRetention) recordDepth(child, Double.NaN, pruned, version, node) { current() }
    }

    fun preferDirection(variable: VarRef, value: Double, cutoff: Double): Boolean? {
        val version = generation
        val node = stamp
        charge(8L)
        if (version != generation) return null
        val history = reliable(variable, value) ?: return null
        val preference = preferred(history, value, cutoff, version, rememberDepthDecline = depthRetention)
        if (depthRetention && preference == null && version == generation && node == stamp) {
            charge(1L)
            if (!depthCancellation() && version == generation && node == stamp) depthLookupEligible = true
        }
        return preference
    }

    internal fun depthPreference(
        variable: VarRef,
        value: Double,
        cutoff: Double,
        current: () -> Boolean,
    ): Boolean? {
        if (!hasDepthHistory) return null
        val version = generation
        val node = stamp
        charge(8L)
        if (!current() || version != generation || node != stamp) return null
        val journal = depthJournal ?: return null
        val restored = journal.restore(variable, depth, node) {
            current() && version == generation && node == stamp
        } ?: return null
        if (restored.down.count == 0 && restored.up.count == 0 &&
            restored.down.outcomeCount == 0 && restored.up.outcomeCount == 0
        ) {
            observe("DEPTH_RETENTION_LOOKUP_NO_CONTEXT")
            return null
        }
        if (!reliableDepth(restored, variable, value)) {
            observe("DEPTH_RETENTION_LOOKUP_NUMERIC_DECLINED")
            if (restored.down.count < RELIABILITY || restored.up.count < RELIABILITY) {
                observe("DEPTH_RETENTION_LOOKUP_NUMERIC_SHORT")
            }
            return null
        }
        val preference = preferred(restored, value, cutoff, version)
        if (!current() || version != generation || node != stamp) return null
        if (preference == null) {
            observe("DEPTH_RETENTION_LOOKUP_PREFERENCE_DECLINED")
            if (restored.down.outcomeCount < RELIABILITY || restored.up.outcomeCount < RELIABILITY) {
                observe("DEPTH_RETENTION_LOOKUP_OUTCOME_SHORT")
            }
        }
        if (preference != null) {
            val formed = depthFormations
            if (formed != null) {
                charge(4L)
                if (!current() || version != generation || node != stamp) return null
                val formation = formed[depthContextKey(variable, depth)]
                if (formation != null && !formation.preferred) {
                    formation.preferred = true
                    observe("DEPTH_RETENTION_FORMATION_REVISITED")
                    if (!current() || version != generation || node != stamp) return null
                }
            }
        }
        return preference
    }

    fun hasCompleteDepthEvidence(variable: VarRef, current: () -> Boolean): Boolean {
        val journal = depthJournal ?: return false
        val version = generation
        val node = stamp
        charge(8L)
        return current() && version == generation && node == stamp && journal.mayHavePairs(variable, depth)
    }

    private fun reliableDepth(history: Gains, variable: VarRef, value: Double): Boolean {
        if (!history.down.stable || !history.up.stable || stamp - history.down.previousStamp > maxAge ||
            stamp - history.up.previousStamp > maxAge
        ) return false
        return fractionalDepthPoint(variable, value)
    }

    private fun fractionalDepthPoint(variable: VarRef, value: Double): Boolean {
        if (!value.isFinite() || (variable is VarRef.Bool && value !in 0.0..1.0)) return false
        val fraction = value - floor(value)
        return fraction > DISTANCE_TOL && fraction < 1.0 - DISTANCE_TOL
    }

    private fun preferred(
        history: Gains,
        value: Double,
        cutoff: Double,
        version: Long,
        rememberDepthDecline: Boolean = false,
    ): Boolean? {
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
            if (rememberDepthDecline) depthGuardDeclined = true
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
