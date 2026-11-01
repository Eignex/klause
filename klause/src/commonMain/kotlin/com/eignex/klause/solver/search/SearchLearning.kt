package com.eignex.klause.solver.search

import com.eignex.klause.solver.result.GLUE_LBD
import com.eignex.klause.solver.result.OpenTheoryClauseStats
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.SharingMemoryObservation
import kotlin.time.TimeSource

internal class SearchLearning(
    private val learnedDb: SearchLearnedDbParams,
    private val chargeWork: (Long) -> Unit = {},
) {
    val lifecycle = LearnedLifecycle()
    private var deliveryWork: Long? = null
    private var learned = SharedLearnedStore(::chargeOrdinary)
    private var reasons = LearnedIndex<SharedLearnedStore.Row>()
    private var firstAttach: SharedLearnedStore.Row? = null
    private var lastAttach: SharedLearnedStore.Row? = null
    private var firstRecheck: SharedLearnedStore.Row? = null
    private var lastRecheck: SharedLearnedStore.Row? = null
    private var pendingCount = 0
    private var recheckCount = 0
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
    private var mutationEpoch = 0L
    private var capActive = false
    private var capCursor = Long.MAX_VALUE
    private var capBoundary = -1
    private var capRemaining = 0
    private var capUseEpoch = 0L
    private var capDropped = false
    private var memoryActive = false
    private var memoryCursor = -1L
    private var memoryBoundary = -1
    private var memoryEpoch = 0L
    private var memoryDropped = false

    private fun chargeOrdinary(work: Long) {
        val pending = deliveryWork
        if (pending == null) chargeWork(work)
        else deliveryWork = if (work > Long.MAX_VALUE - pending) Long.MAX_VALUE else pending + work
    }

    private fun <T> deliver(action: () -> T): T {
        if (deliveryWork != null) return action()
        deliveryWork = 0L
        try {
            return action()
        } finally {
            val work = checkNotNull(deliveryWork)
            deliveryWork = null
            // A completed mandatory consequence must advance before its cooperative work debt stops the slice.
            chargeWork(work)
        }
    }
    val size: Int get() = learned.size
    val hasReasons: Boolean get() = reasons.size > 0
    internal var ownershipRevision: Long = 0L
        private set
    internal val storeOwnershipRevision: Long get() = learned.ownershipRevision
    internal val watchPosition: Long get() = learned.watchVisits

    fun explanationOf(index: Int): SearchExplanation = learned.explanationOf(index)
    fun onConflict(asserting: Boolean) { if (asserting) assertingConflicts++ else nonAssertingConflicts++ }

    fun onRestart(cancelled: () -> Boolean = { false }, charge: () -> Unit = {}) {
        val budget = SharedReclamationBudget(learnedDb.reclamationWork, learnedDb.reclamationScratchBytes,
            cancelled, charge)
        val started = TimeSource.Monotonic.markNow()
        try {
            learned.finishRetirement(budget)
            val cap = learnedDb.maxClauses ?: return
            if (!capActive) {
                if (size <= cap) return
                budget.pay(16L, learned.scratchBytes)
                capActive = true
                capCursor = Long.MAX_VALUE
                capBoundary = learned.lastIndex
                capRemaining = size - cap
                capUseEpoch = learned.beginUseWindow()
                capDropped = false
            }
            while (capRemaining > 0) {
                budget.pay(learned.readWork, learned.scratchBytes)
                val row = learned.next(capCursor, ranked = true)
                if (row == null) { capActive = false; return }
                budget.pay(16L, learned.scratchBytes)
                if (row.index <= capBoundary && !protected(row) && row.usedEpoch < capUseEpoch) {
                    retire(row, budget)
                    if (!capDropped) { reductions++; capDropped = true }
                    capRemaining--
                }
                capCursor = row.rank
                learned.finishRetirement(budget)
            }
            capActive = false
        } catch (_: SharedReclamationBudget.Stopped) {
            // Completed row retirements keep stable handles; later calls finish their physical release.
        } finally {
            restarts++
            reductionNanos += started.elapsedNow().inWholeNanoseconds
        }
    }

    fun onRetraction(trailSize: Int) {
        if (propagatedTrail > trailSize) propagatedTrail = trailSize
        unitsPending = learned.unitCount > 0
    }

    fun onRootReset() {
        chargeOrdinary(reasons.readWork + 16L)
        reasons.forEach { _, row ->
            chargeOrdinary(16L)
            row.reasonCount--
            queueRecheck(row)
        }
        reasons = LearnedIndex()
        ownershipRevision++
        mutationEpoch++
        propagatedTrail = 0
        unitsPending = learned.unitCount > 0
    }

    fun snapshot(): OpenTheoryClauseStats = OpenTheoryClauseStats(
        learnedClauses, relearnedClauses, restarts, reductions, droppedClauses,
        size.toLong(), peakLearnedClauses, releasedWatchVisits + learned.watchVisits,
        assertingConflicts, nonAssertingConflicts, reductionNanos, lifecycle.snapshot(),
    )

    internal fun release() {
        ownershipRevision++
        mutationEpoch++
        releasedWatchVisits += learned.watchVisits
        learned = SharedLearnedStore(::chargeOrdinary)
        reasons = LearnedIndex()
        firstAttach = null
        lastAttach = null
        firstRecheck = null
        lastRecheck = null
        pendingCount = 0
        recheckCount = 0
        propagatedTrail = 0
        unitsPending = false
        capActive = false
        memoryActive = false
        chargeOrdinary(96L)
    }

    private fun protected(row: SharedLearnedStore.Row): Boolean =
        !row.retired && (row.literals?.size == 1 || row.lbd <= learnedDb.glueLbd ||
            row.reasonCount > 0 || row.stages and 4 != 0)

    internal fun retainedMemory(sample: SharingMemoryObservation) {
        val protected = learned.memory(sample, ::protected)
        reasons.memory(sample) { true }
        sample.ownedReferences(this, 6L, protected || pendingCount > 0)
        sample.record(mapOf(
            "sharedLearnedStageCapacityBytes" to (learned.ownedRows.toLong() * Int.SIZE_BYTES),
            "sharedLearnedReasonIndexBytes" to reasons.size * 12L,
            "sharedLearnedPendingEntries" to (pendingCount + recheckCount).toLong(),
        ))
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
        val budget = SharedReclamationBudget(workAllowance, scratchAllowanceBytes, cancelled, charge)
        val before = ownedBytes() + protectedReasonBytes
        fun result(status: SharedLearningReclamationStatus, complete: Boolean = false,
            continuation: Boolean = false): SharedLearningReclamation {
            val after = ownedBytes() + protectedReasonBytes
            return SharedLearningReclamation(status, before, after,
                if (complete) after else null, budget.work, budget.scratch, continuation)
        }
        return try {
            budget.pay(1L)
            learned.finishRetirement(budget)
            if (ownedBytes() + protectedReasonBytes <= softLimitBytes) {
                memoryActive = false
                result(if (before <= softLimitBytes) SharedLearningReclamationStatus.WithinLimit
                    else SharedLearningReclamationStatus.Reclaimed)
            } else {
                if (!memoryActive) {
                    budget.pay(16L, learned.scratchBytes)
                    memoryActive = true
                    memoryCursor = -1L
                    memoryBoundary = learned.lastIndex
                    memoryEpoch = mutationEpoch
                    memoryDropped = false
                    capActive = false
                }
                while (true) {
                    budget.pay(learned.readWork, learned.scratchBytes)
                    val row = learned.next(memoryCursor)
                    if (row == null || row.index > memoryBoundary) {
                        budget.pay(1L)
                        memoryActive = false
                        if (mutationEpoch != memoryEpoch) {
                            return result(SharedLearningReclamationStatus.Deferred, continuation = true)
                        }
                        return result(SharedLearningReclamationStatus.ProtectedFloor, complete = true)
                    }
                    budget.pay(16L, learned.scratchBytes)
                    if (!protected(row)) {
                        retire(row, budget)
                        if (!memoryDropped) { reductions++; memoryDropped = true }
                    }
                    memoryCursor = row.index.toLong()
                    learned.finishRetirement(budget)
                    if (ownedBytes() + protectedReasonBytes <= softLimitBytes) {
                        memoryActive = false
                        return result(SharedLearningReclamationStatus.Reclaimed)
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                result(SharedLearningReclamationStatus.Deferred)
            }
        } catch (stopped: SharedReclamationBudget.Stopped) {
            result(if (stopped.cancelled) SharedLearningReclamationStatus.Cancelled
                else SharedLearningReclamationStatus.Deferred,
                continuation = budget.canResume && (memoryActive || learned.hasRetirement))
        }
    }

    private fun ownedBytes(): Long = learned.ownedBytes + reasons.ownedBytes + 48L

    private fun retire(row: SharedLearnedStore.Row, budget: SharedReclamationBudget) {
        learned.retire(row, budget)
        removeRecheck(row)
        lifecycle.note(LearnedKind.Clause, LearnedStage.Retired)
        droppedClauses++
        ownershipRevision++
    }

    fun learn(literals: IntArray, host: ClauseWatchHost) {
        lifecycle.note(LearnedKind.Clause, LearnedStage.Constructed)
        if (literals.isEmpty()) return
        val levels = HashSet<Int>(literals.size)
        for (literal in literals) { chargeOrdinary(1L); levels.add(host.levelOf(literal)) }
        chargeOrdinary(literals.size.toLong() + 32L)
        val row = learned.add(literals.copyOf(), levels.size)
        if (row == null) { relearnedClauses++; return }
        row.stages = row.stages or 4
        row.attachPrevious = lastAttach
        lastAttach?.attachNext = row
        if (firstAttach == null) firstAttach = row
        lastAttach = row
        pendingCount++
        lifecycle.note(LearnedKind.Clause, LearnedStage.Admitted)
        learnedClauses++
        if (row.lbd <= GLUE_LBD) glueClauses++
        peakLearnedClauses = maxOf(peakLearnedClauses, size.toLong())
        ownershipRevision++
        mutationEpoch++
    }

    fun propagate(host: ClauseWatchHost, boolTrail: IntArrayList): ComponentResult {
        while (firstAttach != null) {
            val row = checkNotNull(firstAttach)
            val result = deliver {
                if (row.stages and 8 == 0) {
                    chargeOrdinary(16L)
                    row.stages = row.stages or 8
                    lifecycle.note(LearnedKind.Clause, LearnedStage.Asserted)
                }
                if (row.attached) learned.recheck(row, host) else learned.attach(row, host)
            }
            if (result is ComponentResult.Indeterminate) return result
            chargeOrdinary(16L)
            firstAttach = row.attachNext
            firstAttach?.attachPrevious = null
            if (lastAttach === row) lastAttach = null
            row.attachNext = null
            row.attachPrevious = null
            row.stages = row.stages and 4.inv()
            pendingCount--
            ownershipRevision++
            mutationEpoch++
            if (result !is ComponentResult.Consistent) return result
        }
        while (firstRecheck != null) {
            val row = checkNotNull(firstRecheck)
            val result = deliver { learned.recheck(row, host) }
            if (result is ComponentResult.Indeterminate) return result
            chargeOrdinary(16L)
            removeRecheck(row)
            if (result !is ComponentResult.Consistent) return result
        }
        if (unitsPending) {
            unitsPending = false
            val result = deliver { learned.propagateUnits(host) }
            if (result !is ComponentResult.Consistent) { unitsPending = true; return result }
        }
        while (propagatedTrail < boolTrail.size) {
            val result = deliver { learned.propagate(boolTrail[propagatedTrail++] xor 1, host) }
            if (result !is ComponentResult.Consistent) { propagatedTrail--; return result }
        }
        return ComponentResult.Consistent
    }

    fun implied(index: Int, literal: Int) {
        chargeOrdinary(reasons.mutationWork + reasons.readWork + 16L)
        val variable = (literal ushr 1).toLong()
        reasons[variable]?.let { it.reasonCount-- }
        val row = learned[index]
        row.reasonCount++
        reasons.put(variable, row)
        ownershipRevision++
        mutationEpoch++
        consumed(index)
    }

    fun unassigned(variable: Int) {
        chargeOrdinary(reasons.readWork)
        val row = reasons[variable.toLong()] ?: return
        chargeOrdinary(reasons.mutationWork + 16L)
        reasons.remove(variable.toLong())
        row.reasonCount--
        queueRecheck(row)
        ownershipRevision++
        mutationEpoch++
    }

    private fun queueRecheck(row: SharedLearnedStore.Row) {
        if (row.stages and 2 != 0 || row.retired) return
        row.stages = row.stages or 2
        row.recheckPrevious = lastRecheck
        lastRecheck?.recheckNext = row
        if (firstRecheck == null) firstRecheck = row
        lastRecheck = row
        recheckCount++
        ownershipRevision++
    }

    private fun removeRecheck(row: SharedLearnedStore.Row) {
        if (row.stages and 2 == 0) return
        row.recheckPrevious?.recheckNext = row.recheckNext
        row.recheckNext?.recheckPrevious = row.recheckPrevious
        if (firstRecheck === row) firstRecheck = row.recheckNext
        if (lastRecheck === row) lastRecheck = row.recheckPrevious
        row.recheckPrevious = null
        row.recheckNext = null
        row.stages = row.stages and 2.inv()
        recheckCount--
        ownershipRevision++
    }

    fun consumed(index: Int) {
        val row = learned[index]
        if (row.stages and 1 != 0) return
        chargeOrdinary(16L)
        row.stages = row.stages or 1
        lifecycle.note(LearnedKind.Clause, LearnedStage.Consumed)
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
    val continuationPending: Boolean = false,
)
