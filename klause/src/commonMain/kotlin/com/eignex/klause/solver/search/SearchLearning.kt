package com.eignex.klause.solver.search

import com.eignex.klause.solver.result.GLUE_LBD
import com.eignex.klause.solver.result.OpenTheoryClauseStats
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.MutableIntIntMap
import com.eignex.klause.util.SharingMemoryObservation
import kotlin.time.TimeSource

internal class SearchLearning(private val learnedDb: SearchLearnedDbParams) {
    val lifecycle = LearnedLifecycle()
    private var stages = IntArrayList()
    private var learned = WatchedClauseStore()
    private var pendingAttach = IndexQueue()
    private var rechecks = IndexQueue()
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
    private var releasedWatchVisits = 0L
    val size: Int get() = learned.size
    internal var ownershipRevision: Long = 0L
        private set
    internal val storeOwnershipRevision: Long get() = learned.ownershipRevision
    internal val watchPosition: Long get() = learned.watchVisits

    fun explanationOf(index: Int): SearchExplanation = learned.explanationOf(index)

    fun onConflict(asserting: Boolean) {
        if (asserting) assertingConflicts++ else nonAssertingConflicts++
    }

    fun onRestart(cancelled: () -> Boolean = { false }, charge: () -> Unit = {}) {
        reduceLearnedDb(cancelled, charge)
        restarts++
    }

    fun onRetraction(trailSize: Int) {
        if (propagatedTrail > trailSize) propagatedTrail = trailSize
        unitsPending = learned.units.size > 0
    }

    fun onRootReset() {
        reasons.forEach { _, index -> queueRecheck(index) }
        reasons.clear()
        ownershipRevision++
        propagatedTrail = 0
        unitsPending = learned.units.size > 0
    }

    fun snapshot(): OpenTheoryClauseStats = OpenTheoryClauseStats(
        learnedClauses,
        relearnedClauses,
        restarts,
        reductions,
        droppedClauses,
        learned.size.toLong(),
        peakLearnedClauses,
        releasedWatchVisits + learned.watchVisits,
        assertingConflicts,
        nonAssertingConflicts,
        reductionNanos,
        lifecycle.snapshot(),
    )

    internal fun release() {
        ownershipRevision++
        releasedWatchVisits += learned.watchVisits
        learned = WatchedClauseStore()
        stages = IntArrayList()
        reasons = MutableIntIntMap()
        pendingAttach = IndexQueue()
        rechecks = IndexQueue()
        propagatedTrail = 0
        unitsPending = false
    }

    internal fun retainedMemory(sample: SharingMemoryObservation) {
        val protectedOwners = learned.memory(sample) { index ->
            var protected = learned.literalsAt(index).size == 1 || learned.lbdAt(index) <= learnedDb.glueLbd
            for (slot in reasons.usedInternal.indices) {
                sample.step()
                if (reasons.usedInternal[slot] && reasons.valuesInternal[slot] == index) protected = true
            }
            for (pending in pendingAttach) { sample.step(); if (pending == index) protected = true }
            protected
        }
        sample.record(mapOf(
            "sharedLearnedStageCapacityBytes" to stages.backingData.size.toLong() * Int.SIZE_BYTES,
            "sharedLearnedReasonIndexBytes" to reasons.primitiveCapacityBytes,
            "sharedLearnedPendingEntries" to (pendingAttach.size + rechecks.size).toLong(),
        ))
        sample.owned(stages.backingData, stages.backingData.size.toLong() * Int.SIZE_BYTES, protectedOwners)
        sample.owned(reasons, reasons.primitiveCapacityBytes, protected = true)
        sample.owned(pendingAttach, pendingAttach.capacityBytes, protected = true)
        sample.owned(rechecks, rechecks.capacityBytes, protectedOwners)
    }

    internal fun reclaimMemory(
        softLimitBytes: Long,
        workAllowance: Long,
        scratchAllowanceBytes: Long,
        protectedReasonBytes: Long = 0L,
        cancelled: () -> Boolean = { false },
        charge: () -> Unit = {},
    ): SharedLearningReclamation {
        require(softLimitBytes >= 0L && workAllowance >= 0L && scratchAllowanceBytes >= 0L)
        val workBound = learned.replacementWorkBound + reasons.usedInternal.size * 4L +
            (pendingAttach.size.toLong() + rechecks.size) * 4L + size * 16L
        val scratchBound = learned.replacementScratchBound + reasons.size * 64L +
            (pendingAttach.size.toLong() + rechecks.size) * 32L + size * 16L
        if (cancelled()) return SharedLearningReclamation(SharedLearningReclamationStatus.Cancelled)
        if (workBound > workAllowance || scratchBound > scratchAllowanceBytes) {
            return SharedLearningReclamation(SharedLearningReclamationStatus.Deferred)
        }
        var work = 0L
        fun step() {
            if (cancelled()) throw ReclamationStopped(true)
            if (work >= workAllowance) throw ReclamationStopped(false)
            charge()
            work++
        }
        return try {
            val before = ownedBytes(::step) + protectedReasonBytes
            if (before <= softLimitBytes) {
                SharedLearningReclamation(SharedLearningReclamationStatus.WithinLimit, before, before, work = work)
            } else {
                val keep = BooleanArray(size)
                for (index in keep.indices) {
                    step()
                    keep[index] = learned.literalsAt(index).size == 1 || learned.lbdAt(index) <= learnedDb.glueLbd
                }
                reasons.forEach { _, index -> step(); keep[index] = true }
                for (index in pendingAttach) { step(); keep[index] = true }
                val replacement = learned.prepareRetained(keep, ::step)
                val remap = IntArray(size) { -1 }
                val replacementStages = IntArrayList(replacement.size)
                var target = 0
                for (index in keep.indices) {
                    step()
                    if (keep[index]) { remap[index] = target++; replacementStages.add(stages[index]) }
                }
                val replacementReasons = MutableIntIntMap(reasons.size)
                reasons.forEach { variable, index -> step(); replacementReasons.put(variable, remap[index]) }
                val replacementPending = IndexQueue(pendingAttach.size)
                for (index in pendingAttach) { step(); replacementPending.addLast(remap[index]) }
                val replacementRechecks = IndexQueue(minOf(rechecks.size, replacement.size))
                for (index in rechecks) {
                    step()
                    if (remap[index] >= 0) replacementRechecks.addLast(remap[index])
                }
                val after = replacement.ownedBytes(::step) +
                    replacementStages.backingData.size.toLong() * Int.SIZE_BYTES +
                    replacementReasons.primitiveCapacityBytes +
                    replacementPending.capacityBytes + replacementRechecks.capacityBytes +
                    protectedReasonBytes
                step()
                if (cancelled()) throw ReclamationStopped(true)
                // No callback or cancellation point can observe a partially published index generation.
                val retired = size - replacement.size
                ownershipRevision++
                learned = replacement
                stages = replacementStages
                reasons = replacementReasons
                pendingAttach = replacementPending
                rechecks = replacementRechecks
                unitsPending = learned.units.size > 0
                if (retired > 0) {
                    repeat(retired) { lifecycle.note(LearnedKind.Clause, LearnedStage.Retired) }
                    reductions++
                    droppedClauses += retired
                }
                SharedLearningReclamation(
                    if (after > softLimitBytes) SharedLearningReclamationStatus.ProtectedFloor
                    else SharedLearningReclamationStatus.Reclaimed,
                    before, after, after, work, scratchBound,
                )
            }
        } catch (stopped: ReclamationStopped) {
            SharedLearningReclamation(
                if (stopped.cancelled) SharedLearningReclamationStatus.Cancelled
                else SharedLearningReclamationStatus.Deferred,
                work = work, scratchBytes = scratchBound,
            )
        }
    }

    private fun ownedBytes(step: () -> Unit): Long = learned.ownedBytes(step) +
        stages.backingData.size.toLong() * Int.SIZE_BYTES + reasons.primitiveCapacityBytes +
        pendingAttach.capacityBytes + rechecks.capacityBytes

    private class ReclamationStopped(val cancelled: Boolean) : RuntimeException()

    private class IndexQueue(capacity: Int = 8) : Iterable<Int> {
        private var data = IntArray(capacity.coerceAtLeast(1))
        private var head = 0
        var size = 0
            private set
        val capacityBytes: Long get() = data.size.toLong() * Int.SIZE_BYTES
        fun isNotEmpty(): Boolean = size > 0
        fun addLast(value: Int) {
            if (size == data.size) {
                val replacement = IntArray(data.size * 2)
                for (index in 0 until size) replacement[index] = data[(head + index) % data.size]
                data = replacement
                head = 0
            }
            data[(head + size++) % data.size] = value
        }
        fun removeFirst(): Int {
            check(size > 0)
            val value = data[head]
            head = (head + 1) % data.size
            size--
            return value
        }
        override fun iterator(): Iterator<Int> = object : Iterator<Int> {
            private var position = 0
            override fun hasNext(): Boolean = position < size
            override fun next(): Int {
                if (!hasNext()) throw NoSuchElementException()
                return data[(head + position++) % data.size]
            }
        }
    }

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
            ownershipRevision++
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
    private fun reduceLearnedDb(cancelled: () -> Boolean, charge: () -> Unit) {
        val cap = learnedDb.maxClauses ?: return
        // Reduction renumbers clauses, so it waits until no clause is queued for its first
        // examination. The next propagation drains that queue, so the following restart reduces.
        if (learned.size <= cap || pendingAttach.isNotEmpty()) return
        val workBound = learned.replacementWorkBound + reasons.usedInternal.size * 4L + size * 32L +
            rechecks.size * 4L
        val scratchBound = learned.replacementScratchBound + reasons.size * 64L + size * 32L + rechecks.size * 32L
        if (workBound > learnedDb.reclamationWork || scratchBound > learnedDb.reclamationScratchBytes) return
        var work = 0L
        fun step() {
            if (cancelled()) throw ReclamationStopped(true)
            if (work >= learnedDb.reclamationWork) throw ReclamationStopped(false)
            charge()
            work++
        }
        val started = TimeSource.Monotonic.markNow()
        try {
            reduceOverCap(cap, ::step, cancelled)
        } catch (_: ReclamationStopped) {
            return
        } finally {
            reductionNanos += started.elapsedNow().inWholeNanoseconds
        }
    }

    private fun reduceOverCap(cap: Int, step: () -> Unit, cancelled: () -> Boolean) {
        val droppable = IntArrayList(learned.size)
        val protected = BooleanArray(learned.size)
        reasons.forEach { _, index -> step(); protected[index] = true }
        for (index in 0 until learned.size) {
            step()
            val retained = learned.lbdAt(index) <= learnedDb.glueLbd ||
                learned.usedAt(index) ||
                protected[index] ||
                learned.literalsAt(index).size == 1
            if (!retained) droppable.add(index)
        }
        val survivors = (cap - (learned.size - droppable.size)).coerceAtLeast(0)
        if (droppable.size <= survivors) {
            learned.clearUsed()
            return
        }
        val dropped = selectDroppedClauses(droppable, survivors)
        val replacement = learned.prepareRetained(BooleanArray(learned.size) { !dropped[it] }, step)
        val remap = IntArray(learned.size) { -1 }
        val replacementStages = IntArrayList(replacement.size)
        var target = 0
        for (index in 0 until learned.size) {
            step()
            if (!dropped[index]) {
                remap[index] = target
                replacementStages.add(stages[index])
                target++
            }
        }
        val replacementRechecks = IndexQueue(minOf(rechecks.size, replacement.size))
        for (index in rechecks) {
            step()
            val next = remap[index]
            if (next >= 0) replacementRechecks.addLast(next)
        }
        val retainedReasons = MutableIntIntMap(reasons.size)
        reasons.forEach { variable, index -> step(); retainedReasons.put(variable, remap[index]) }
        replacement.clearUsed()
        step()
        if (cancelled()) throw ReclamationStopped(true)
        ownershipRevision++
        stages = replacementStages
        rechecks = replacementRechecks
        reasons = retainedReasons
        learned = replacement
        repeat(droppable.size - survivors) { lifecycle.note(LearnedKind.Clause, LearnedStage.Retired) }
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
            ownershipRevision++
            lifecycle.note(LearnedKind.Clause, LearnedStage.Asserted)
            val result = learned.attach(index, host)
            if (result !is ComponentResult.Consistent) return result
        }
        while (rechecks.isNotEmpty()) {
            val index = rechecks.removeFirst()
            ownershipRevision++
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
        ownershipRevision++
        consumed(index)
    }

    fun unassigned(variable: Int) {
        val index = reasons.getOrDefault(variable, -1)
        if (index < 0) return
        reasons.remove(variable)
        ownershipRevision++
        queueRecheck(index)
    }

    private fun queueRecheck(index: Int) {
        if (stages[index] and 2 != 0) return
        stages[index] = stages[index] or 2
        rechecks.addLast(index)
        ownershipRevision++
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

internal enum class SharedLearningReclamationStatus { WithinLimit, Reclaimed, ProtectedFloor, Deferred, Cancelled }

internal data class SharedLearningReclamation(
    val status: SharedLearningReclamationStatus,
    val beforeBytes: Long? = null,
    val afterBytes: Long? = null,
    val protectedBytes: Long? = null,
    val work: Long = 0L,
    val scratchBytes: Long = 0L,
)
