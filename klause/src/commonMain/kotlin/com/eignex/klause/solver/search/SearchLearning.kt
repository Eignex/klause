package com.eignex.klause.solver.search

import com.eignex.klause.solver.result.GLUE_LBD
import com.eignex.klause.solver.result.OpenTheoryClauseStats
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.MutableIntIntMap
import kotlin.time.TimeSource

internal class SearchLearning(private val learnedDb: SearchLearnedDbParams) {
    val lifecycle = LearnedLifecycle()
    private val stages = IntArrayList()
    private val learned = WatchedClauseStore()
    private val pendingAttach = ArrayDeque<Int>()
    private val rechecks = ArrayDeque<Int>()
    private var reasons = MutableIntIntMap()
    val hasReasons: Boolean get() = !reasons.isEmpty()
    private var propagatedTrail = 0
    private var unitsPending = false
    private var learnedClauses = 0L
    private var relearnedClauses = 0L
    private var restarts = 0L
    private var reductions = 0L
    private var droppedClauses = 0L
    private var peakLearnedClauses = 0L
    var glueClauses = 0L
        private set
    private var assertingConflicts = 0L
    private var nonAssertingConflicts = 0L
    private var reductionNanos = 0L
    val size: Int get() = learned.size

    fun explanationOf(index: Int): SearchExplanation = learned.explanationOf(index)

    fun onConflict(asserting: Boolean) {
        if (asserting) assertingConflicts++ else nonAssertingConflicts++
    }

    fun onRestart() {
        reduceLearnedDb()
        restarts++
    }

    fun onRetraction(trailSize: Int) {
        if (propagatedTrail > trailSize) propagatedTrail = trailSize
        unitsPending = learned.units.size > 0
    }

    fun onRootReset() {
        reasons.forEach { _, index -> queueRecheck(index) }
        reasons.clear()
        propagatedTrail = 0
        unitsPending = learned.units.size > 0
    }

    fun snapshot(measureMemory: Boolean = false): OpenTheoryClauseStats = OpenTheoryClauseStats(
        learnedClauses,
        relearnedClauses,
        restarts,
        reductions,
        droppedClauses,
        learned.size.toLong(),
        peakLearnedClauses,
        learned.watchVisits,
        assertingConflicts,
        nonAssertingConflicts,
        reductionNanos,
        lifecycle.snapshot() + if (measureMemory) learned.memory() + mapOf(
            "sharedLearnedStageCapacityBytes" to stages.backingData.size.toLong() * Int.SIZE_BYTES,
            "sharedLearnedReasonIndexBytes" to reasons.primitiveCapacityBytes,
            "sharedLearnedPendingEntries" to (pendingAttach.size + rechecks.size).toLong(),
        ) else emptyMap(),
    )

    fun learn(literals: IntArray, host: ClauseWatchHost) {
        lifecycle.note(LearnedKind.Clause, LearnedStage.Constructed)
        if (literals.isEmpty()) return
        val lbd = lbdOf(literals, host)
        val index = learned.add(literals.copyOf(), lbd)
        if (index >= 0) {
            lifecycle.note(LearnedKind.Clause, LearnedStage.Admitted)
            stages.add(0)
            learnedClauses++
            if (lbd <= GLUE_LBD) glueClauses++
            peakLearnedClauses = maxOf(peakLearnedClauses, learned.size.toLong())
            pendingAttach.addLast(index)
        } else {
            relearnedClauses++
        }
    }

    private fun lbdOf(literals: IntArray, host: ClauseWatchHost): Int {
        val levels = HashSet<Int>(literals.size)
        for (literal in literals) levels.add(host.levelOf(literal))
        return levels.size
    }

    // Every learned clause is implied by the root problem, so forgetting one only weakens propagation.
    // Glue clauses, clauses that propagated since the last reduction, and units are retained; the rest are
    // dropped highest-LBD first (Audemard and Simon, "Predicting Learnt Clauses Quality in Modern SAT
    // Solvers", IJCAI 2009, https://www.ijcai.org/Proceedings/09/Papers/074.pdf).
    private fun reduceLearnedDb() {
        val cap = learnedDb.maxClauses ?: return
        // Reduction renumbers clauses, so it waits until no clause is queued for its first
        // examination. The next propagation drains that queue, so the following restart reduces.
        if (learned.size <= cap || pendingAttach.isNotEmpty()) return
        val started = TimeSource.Monotonic.markNow()
        try {
            reduceOverCap(cap)
        } finally {
            reductionNanos += started.elapsedNow().inWholeNanoseconds
        }
    }

    private fun reduceOverCap(cap: Int) {
        val droppable = IntArrayList(learned.size)
        for (index in 0 until learned.size) {
            val retained = learned.lbdAt(index) <= learnedDb.glueLbd ||
                learned.usedAt(index) ||
                learned.literalsAt(index).size == 1
            if (!retained) droppable.add(index)
        }
        val survivors = (cap - (learned.size - droppable.size)).coerceAtLeast(0)
        if (droppable.size <= survivors) {
            learned.clearUsed()
            return
        }
        val dropped = selectDroppedClauses(droppable, survivors)
        val remap = IntArray(learned.size) { -1 }
        var target = 0
        for (index in 0 until learned.size) {
            if (dropped[index]) lifecycle.note(LearnedKind.Clause, LearnedStage.Retired)
            else {
                remap[index] = target
                stages[target++] = stages[index]
            }
        }
        stages.truncateTo(target)
        repeat(rechecks.size) {
            val next = remap[rechecks.removeFirst()]
            if (next >= 0) rechecks.addLast(next)
        }
        val retainedReasons = MutableIntIntMap()
        reasons.forEach { variable, index -> if (remap[index] >= 0) retainedReasons.put(variable, remap[index]) }
        reasons = retainedReasons
        learned.retain { index -> !dropped[index] }
        reductions++
        droppedClauses += droppable.size - survivors
        learned.clearUsed()
        unitsPending = learned.units.size > 0
    }

    private fun selectDroppedClauses(droppable: IntArrayList, survivors: Int): BooleanArray {
        val dropped = BooleanArray(learned.size)
        var maximumLbd = 0
        for (position in 0 until droppable.size) {
            maximumLbd = maxOf(maximumLbd, learned.lbdAt(droppable[position]))
        }
        // A bounded histogram avoids sorting when LBDs occupy a small integer range.
        if (maximumLbd <= MAXIMUM_BUCKETED_LBD &&
            maximumLbd.toLong() <= droppable.size.toLong() * BUCKET_RANGE_FACTOR
        ) {
            markBucketedDrops(droppable, survivors, maximumLbd, dropped)
        } else {
            droppable.sortByIntKey { learned.lbdAt(it) }
            for (position in survivors until droppable.size) dropped[droppable[position]] = true
        }
        return dropped
    }

    private fun markBucketedDrops(droppable: IntArrayList, survivors: Int, maximumLbd: Int, dropped: BooleanArray) {
        val counts = IntArray(maximumLbd + 1)
        for (position in 0 until droppable.size) counts[learned.lbdAt(droppable[position])]++
        var remaining = survivors
        var cutoff = 0
        while (remaining > counts[cutoff]) {
            remaining -= counts[cutoff++]
        }
        // Scanning original indices keeps the same oldest-first tie order as the packed sort.
        for (position in 0 until droppable.size) {
            val index = droppable[position]
            val lbd = learned.lbdAt(index)
            dropped[index] = lbd > cutoff || (lbd == cutoff && remaining-- <= 0)
        }
    }

    fun propagate(host: ClauseWatchHost, boolTrail: IntArrayList): ComponentResult {
        while (pendingAttach.isNotEmpty()) {
            val index = pendingAttach.removeFirst()
            lifecycle.note(LearnedKind.Clause, LearnedStage.Asserted)
            val result = learned.attach(index, host)
            if (result !is ComponentResult.Consistent) return result
        }
        while (rechecks.isNotEmpty()) {
            val index = rechecks.removeFirst()
            stages[index] = stages[index] and 2.inv()
            val result = learned.recheck(index, host)
            if (result !is ComponentResult.Consistent) return result
        }
        if (unitsPending) {
            unitsPending = false
            val result = learned.propagateUnits(host)
            if (result !is ComponentResult.Consistent) {
                // The units after this one stay pending: leaving early must not drop them until the
                // next retraction.
                unitsPending = true
                return result
            }
        }
        while (propagatedTrail < boolTrail.size) {
            val falsified = boolTrail[propagatedTrail++] xor 1
            val result = learned.propagate(falsified, host)
            if (result !is ComponentResult.Consistent) {
                // The watchers of this literal were left partly unvisited, and a backjump does not
                // always rewind past it, so it stays queued rather than being silently skipped.
                propagatedTrail--
                return result
            }
        }
        return ComponentResult.Consistent
    }

    fun implied(index: Int, literal: Int) {
        reasons.put(literal ushr 1, index)
        consumed(index)
    }

    fun unassigned(variable: Int) {
        val index = reasons.getOrDefault(variable, -1)
        if (index < 0) return
        reasons.remove(variable)
        queueRecheck(index)
    }

    private fun queueRecheck(index: Int) {
        if (stages[index] and 2 != 0) return
        stages[index] = stages[index] or 2
        rechecks.addLast(index)
    }

    fun consumed(index: Int) {
        if (stages[index] and 1 != 0) return
        stages[index] = stages[index] or 1
        lifecycle.note(LearnedKind.Clause, LearnedStage.Consumed)
    }

    private companion object {
        const val MAXIMUM_BUCKETED_LBD = 4096
        const val BUCKET_RANGE_FACTOR = 4
    }
}
