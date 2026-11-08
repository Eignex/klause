package com.eignex.klause.propagation

import com.eignex.klause.util.SharingMemoryObservation
import com.eignex.util.collections.IntArrayList

internal data class NativeCapConfiguration(val tiered: Boolean, val glue: Int, val mid: Int)

internal interface NativeIndexedCapPolicy : LearnedCapPolicy {
    val activeCount: Int
    fun classified(index: Int): Boolean
    fun markClassification(index: Int, keep: Boolean): Boolean
    fun clearClassification(index: Int)
}

// Live Native LBD keys are immutable until remapping. Unread admissions follow the heap and its processed gap.
internal class NativeCapIndex(private val candidates: IntArrayList) {
    private val pool = NativeCapPages()
    private val dirty = NativeCapQueue()
    private val aging = NativeCapQueue()
    private val retries = NativeCapQueue()
    private val processed = NativeCapQueue()
    private var stage = Stage.Reserve
    private var afterSift = Stage.Select
    private var afterMark = Stage.Admissions
    private var afterClean = Stage.Aging
    private var afterPrune = Stage.Clear
    private var heapSize = 0
    private var read = 0
    private var pending = -1
    private var sift = 0
    private var dirtyRemaining = 0
    private var agingRemaining = 0
    private var cleanupRead = 0
    private var repairRead = 0
    private var scan = 0
    private var finalRead = 0
    private var finalWrite = 0
    private var pendingKeep = false
    private var insertPending = false
    private var initialized = false
    private var callback = false
    private var cleanOnly = false
    private var aborting = false
    private var rescanRequested = false
    private var rescanNext = false
    private var captureSuppressed = false
    private var configured = false
    private var tiered = false
    private var glue = 0
    private var mid = 0
    private var revision = 0L
    private var observedRevision = 0L
    private var cleanupRevision = 0L
    private var serial = 0L
    var classifications = 0L
        private set
    var activeBudget: ReclamationPreparation? = null
        private set

    val hasPending: Boolean get() = (stage != Stage.Idle && stage != Stage.Reserve) ||
        revision != observedRevision || (bootstrapping && reservedTrimmingPending)
    val continuation: Long get() = serial
    val continuationHeap: Long get() = (heapSize.toLong() shl 32) or (read.toLong() and 0xFFFFFFFFL)
    val continuationOperation: Long get() = stage.ordinal.toLong()
    val readyForAdmissions: Boolean get() = initialized && !aborting
    val hasIndexedRows: Boolean get() = initialized && stage != Stage.Reserve
    val bootstrapping: Boolean get() = stage == Stage.Reserve
    val retiredHandlesPending: Boolean get() = revision != observedRevision
    val cancelling: Boolean get() = aborting
    val recordsUses: Boolean get() = readyForAdmissions && tiered && !captureSuppressed
    val primitiveBytes: Long get() = SCALAR_BYTES + 48L + pool.pages * 36L
    val modeledBytes: Long get() = primitiveBytes + (REFERENCES + 9L + pool.pages * 2L) * Long.SIZE_BYTES
    val resetWork: Long get() = 64L

    fun ownsClassification(index: Int): Boolean = callback && pending == index

    fun <T> usingBudget(budget: ReclamationPreparation, operation: () -> T): T {
        val previous = activeBudget
        activeBudget = budget
        return try { operation() } finally { activeBudget = previous }
    }

    fun admissionPages(rows: Int): Long = if (readyForAdmissions) {
        ((requiredSlots(rows) - pool.slots).coerceAtLeast(0L) + 7L) / 8L
    } else 0L

    fun reserveAdmission() { pool.reserve() }

    fun reservePages(budget: ReclamationPreparation) {
        while (pool.slots < requiredSlots(candidates.size)) {
            budget.atomic(NATIVE_CAP_PAGE_WORK, NATIVE_CAP_PAGE_BYTES)
            budget.visit(NATIVE_CAP_PAGE_WORK)
            budget.ready()
            budget.visit(0L)
            pool.reserve()
            changed()
        }
    }

    private val reservedTarget: Long get() = if (candidates.size == 0) 0L else requiredSlots(candidates.size)
    private val reservedTrimmingPending: Boolean get() = pool.available > 0L && pool.slots - 8L >= reservedTarget

    fun trimReservations(budget: ReclamationPreparation) {
        while (reservedTrimmingPending) {
            budget.visit(8L)
            budget.visit(0L)
            pool.release()
            changed()
        }
    }

    fun recordUse(index: Int) {
        if (dirty.canPush(pool)) dirty.push(index, pool) else requestRescan()
    }

    fun requestRescan() {
        rescanRequested = true
        rescanNext = true
        captureSuppressed = true
    }

    fun invalidateRetiredHandles() { revision++ }

    fun reset() {
        dirty.reset()
        aging.reset()
        retries.reset()
        processed.reset()
        pool.reset()
        heapSize = 0
        read = 0
        pending = -1
        initialized = false
        aborting = false
        configured = false
        rescanRequested = false
        rescanNext = false
        captureSuppressed = false
        observedRevision = revision
        stage = Stage.Idle
        changed()
    }

    fun advance(
        cap: Int,
        configuration: NativeCapConfiguration,
        budget: ReclamationPreparation,
        policy: NativeIndexedCapPolicy,
    ): Completion {
        if (!configured || tiered != configuration.tiered || glue != configuration.glue || mid != configuration.mid) {
            budget.visit(8L)
            budget.visit(0L)
            if (configured) requestRescan()
            tiered = configuration.tiered
            glue = configuration.glue
            mid = configuration.mid
            configured = true
        }
        if (stage == Stage.Idle) {
            if (!initialized && policy.activeCount <= cap && revision != observedRevision) {
                budget.visit(4L)
                budget.visit(0L)
                observedRevision = revision
                changed()
            }
            if (policy.activeCount <= cap && (!initialized || revision == observedRevision)) {
                return Completion.None
            }
            if (!initialized) transition(Stage.Reserve, budget) else begin(policy.activeCount <= cap, budget)
        }
        while (true) {
            when (stage) {
                Stage.Idle -> return Completion.None
                Stage.Reserve -> {
                    reservePages(budget)
                    budget.visit(8L)
                    budget.visit(0L)
                    initialized = true
                    begin(policy.activeCount <= cap, budget)
                }
                Stage.Clean -> clean(budget, policy)
                Stage.Repair -> {
                    if (repairRead < 0) {
                        if (revision != observedRevision) startClean(afterClean, budget)
                        else transition(afterClean, budget)
                    } else {
                        budget.visit(8L)
                        budget.visit(0L)
                        sift = repairRead--
                        afterSift = Stage.Repair
                        stage = Stage.Down
                        changed()
                    }
                }
                Stage.PruneDirty -> prune(dirty, true, budget, policy)
                Stage.PruneAging -> prune(aging, false, budget, policy)
                Stage.Aging -> take(aging, true, budget)
                Stage.Dirty -> take(dirty, false, budget)
                Stage.Admissions -> {
                    if (read == candidates.size) transition(Stage.Select, budget) else {
                        budget.visit(CAP_ROW_INSPECTION_WORK)
                        budget.visit(0L)
                        pending = candidates[read++]
                        insertPending = true
                        afterMark = Stage.Admissions
                        stage = Stage.Inspect
                        changed()
                    }
                }
                Stage.Scan -> {
                    if (scan == heapSize) {
                        budget.visit(1L)
                        budget.visit(0L)
                        rescanRequested = false
                        stage = Stage.Select
                        changed()
                    } else {
                        budget.visit(CAP_ROW_INSPECTION_WORK)
                        budget.visit(0L)
                        pending = candidates[scan++]
                        insertPending = false
                        afterMark = Stage.Scan
                        stage = Stage.Inspect
                        changed()
                    }
                }
                Stage.Inspect -> {
                    val alive = policy.alive(pending)
                    val classified = alive && policy.classified(pending)
                    budget.visit(0L)
                    policy.visited()
                    if (!alive) {
                        insertPending = false
                        pending = -1
                        stage = afterMark
                    } else if (cleanOnly) {
                        rescanNext = true
                        stage = if (insertPending) Stage.Admit else afterMark
                    } else stage = if (classified) {
                        if (insertPending) Stage.Admit else afterMark
                    } else Stage.Classify
                    changed()
                }
                Stage.Classify -> {
                    budget.visit(0L)
                    if (!policy.prepare(pending)) {
                        pendingKeep = true
                        rescanNext = true
                    } else {
                        callback = true
                        pendingKeep = try { policy.keep(pending) } finally { callback = false }
                        classifications++
                    }
                    stage = Stage.Mark
                    changed()
                }
                Stage.Mark -> {
                    budget.visit(24L)
                    val revisit = policy.markClassification(pending, pendingKeep)
                    processed.push(pending, pool)
                    if (revisit && tiered) aging.push(pending, pool)
                    stage = if (insertPending) Stage.Admit else afterMark
                    changed()
                }
                Stage.Admit -> {
                    budget.visit(4L)
                    budget.visit(0L)
                    candidates[heapSize] = pending
                    sift = heapSize++
                    pending = -1
                    insertPending = false
                    afterSift = Stage.Admissions
                    stage = Stage.Up
                    changed()
                }
                Stage.Up -> up(budget, policy)
                Stage.Down -> down(budget, policy)
                Stage.Select -> {
                    when {
                        revision != observedRevision -> startClean(Stage.Select, budget)
                        read < candidates.size -> transition(Stage.Admissions, budget)
                        rescanRequested -> {
                            budget.visit(4L)
                            budget.visit(0L)
                            scan = 0
                            stage = Stage.Scan
                            changed()
                        }
                        cleanOnly || policy.activeCount <= cap || heapSize == 0 -> transition(Stage.Restore, budget)
                        else -> {
                            budget.visit(8L)
                            val index = candidates[0]
                            val key = policy.lbd(index)
                            budget.visit(0L)
                            if (key <= glue) stage = Stage.Restore else {
                                pending = index
                                stage = Stage.Retire
                            }
                            changed()
                        }
                    }
                }
                Stage.Retire -> {
                    budget.visit(8L)
                    budget.visit(0L)
                    pendingKeep = policy.alive(pending) && !policy.retire(pending)
                    stage = Stage.Remove
                    changed()
                }
                Stage.Remove -> {
                    budget.visit(12L)
                    budget.visit(0L)
                    if (pendingKeep) retries.push(pending, pool)
                    heapSize--
                    if (heapSize > 0) candidates[0] = candidates[heapSize]
                    pending = -1
                    sift = 0
                    afterSift = Stage.Select
                    stage = if (heapSize == 0) Stage.Select else Stage.Down
                    changed()
                }
                Stage.Restore -> {
                    if (retries.size == 0) {
                        budget.visit(4L)
                        budget.visit(0L)
                        dirtyRemaining = dirty.size
                        agingRemaining = aging.size
                        afterPrune = Stage.Clear
                        stage = if (aborting) Stage.Clear else Stage.PruneDirty
                        changed()
                    } else {
                        budget.visit(12L)
                        budget.visit(0L)
                        candidates[heapSize] = retries.pop(pool)
                        sift = heapSize++
                        afterSift = Stage.Restore
                        stage = if (aborting) Stage.Restore else Stage.Up
                        changed()
                    }
                }
                Stage.Clear -> {
                    if (processed.size == 0) {
                        budget.visit(4L)
                        budget.visit(0L)
                        finalRead = read
                        finalWrite = heapSize
                        stage = Stage.Finalize
                        changed()
                    } else {
                        budget.visit(12L)
                        policy.clearClassification(processed.first())
                        processed.pop(pool)
                        changed()
                    }
                }
                Stage.Finalize -> {
                    if (finalRead < candidates.size) {
                        budget.visit(4L)
                        budget.visit(0L)
                        candidates[finalWrite++] = candidates[finalRead++]
                        changed()
                    } else {
                        budget.visit(12L)
                        budget.visit(0L)
                        candidates.truncateTo(finalWrite)
                        read = heapSize
                        stage = Stage.TrimPool
                        changed()
                    }
                }
                Stage.TrimPool -> {
                    val needed = if (candidates.size == 0 && dirty.size == 0 && aging.size == 0) 0L
                        else maxOf(requiredSlots(candidates.size), dirty.size.toLong() + aging.size + 64L)
                    if (pool.available > 0L && pool.slots - 8L >= needed) {
                        budget.visit(8L)
                        budget.visit(0L)
                        pool.release()
                        changed()
                    } else {
                        budget.visit(4L)
                        budget.visit(0L)
                        if (candidates.size == 0 && pool.pages == 0L) initialized = false
                        stage = Stage.Idle
                        changed()
                        return when {
                            aborting -> Completion.Aborted
                            cleanOnly -> Completion.Cleaned
                            else -> Completion.Pass
                        }
                    }
                }
            }
        }
    }

    fun abort(budget: ReclamationPreparation, policy: NativeIndexedCapPolicy): Completion {
        if (aborting && stage == Stage.Idle) return Completion.Aborted
        if (!aborting) {
            budget.visit(8L)
            budget.visit(0L)
            if (insertPending && pending >= 0) read--
            pending = -1
            insertPending = false
            aborting = true
            if (stage != Stage.Finalize && stage != Stage.TrimPool) stage = Stage.Restore
            changed()
        }
        return advance(Int.MAX_VALUE, NativeCapConfiguration(tiered, glue, mid), budget, policy)
    }

    fun sharingMemory(sample: SharingMemoryObservation, protected: Boolean) {
        sample.owned(this, SCALAR_BYTES, protected)
        sample.ownedReferences(this, REFERENCES, protected)
        pool.sharingMemory(sample, protected)
        dirty.sharingMemory(sample, protected)
        aging.sharingMemory(sample, protected)
        retries.sharingMemory(sample, protected)
        processed.sharingMemory(sample, protected)
    }

    private fun begin(cleanupOnly: Boolean, budget: ReclamationPreparation) {
        budget.visit(8L)
        budget.visit(0L)
        cleanOnly = cleanupOnly
        afterPrune = if (cleanupOnly) Stage.Admissions else Stage.Clear
        aborting = false
        dirtyRemaining = dirty.size
        agingRemaining = aging.size
        if (rescanNext) {
            rescanRequested = true
            rescanNext = false
        }
        captureSuppressed = false
        if (revision != observedRevision) startClean(if (cleanupOnly) Stage.PruneDirty else Stage.Aging, budget)
        else stage = if (cleanupOnly) Stage.PruneDirty else Stage.Aging
        changed()
    }

    private fun take(queue: NativeCapQueue, fromAging: Boolean, budget: ReclamationPreparation) {
        val remaining = if (fromAging) agingRemaining else dirtyRemaining
        if (remaining == 0) transition(if (fromAging) Stage.Dirty else Stage.Admissions, budget) else {
            budget.visit(CAP_ROW_INSPECTION_WORK)
            budget.visit(0L)
            pending = queue.pop(pool)
            if (fromAging) agingRemaining-- else dirtyRemaining--
            insertPending = false
            afterMark = if (fromAging) Stage.Aging else Stage.Dirty
            stage = Stage.Inspect
            changed()
        }
    }

    private fun prune(
        queue: NativeCapQueue,
        fromDirty: Boolean,
        budget: ReclamationPreparation,
        policy: NativeIndexedCapPolicy,
    ) {
        val remaining = if (fromDirty) dirtyRemaining else agingRemaining
        if (remaining == 0) transition(if (fromDirty) Stage.PruneAging else afterPrune, budget) else {
            budget.visit(8L)
            val alive = policy.alive(queue.first())
            budget.visit(0L)
            val index = queue.pop(pool)
            if (alive) queue.push(index, pool)
            if (fromDirty) dirtyRemaining-- else agingRemaining--
            changed()
        }
    }

    private fun startClean(next: Stage, budget: ReclamationPreparation) {
        budget.visit(4L)
        budget.visit(0L)
        afterClean = next
        cleanupRead = 0
        cleanupRevision = revision
        stage = Stage.Clean
        changed()
    }

    private fun clean(budget: ReclamationPreparation, policy: NativeIndexedCapPolicy) {
        if (cleanupRevision != revision) {
            budget.visit(4L)
            budget.visit(0L)
            cleanupRead = 0
            cleanupRevision = revision
            changed()
        }
        if (cleanupRead == heapSize) {
            budget.visit(4L)
            budget.visit(0L)
            observedRevision = revision
            repairRead = heapSize / 2 - 1
            stage = Stage.Repair
            changed()
            return
        }
        budget.visit(16L)
        val alive = policy.alive(candidates[cleanupRead])
        budget.visit(0L)
        if (alive) cleanupRead++ else {
            val last = heapSize - 1
            val replacement = candidates[last]
            val moveUp = cleanupRead < last && cleanupRead > 0 &&
                better(candidates[(cleanupRead - 1) / 2], replacement, policy)
            budget.visit(0L)
            heapSize = last
            if (cleanupRead < heapSize) {
                candidates[cleanupRead] = replacement
                sift = cleanupRead
                afterSift = Stage.Clean
                stage = if (moveUp) Stage.Up else Stage.Down
            }
        }
        changed()
    }

    private fun up(budget: ReclamationPreparation, policy: NativeIndexedCapPolicy) {
        if (sift == 0) transition(afterSift, budget) else {
            budget.visit(8L)
            val parent = (sift - 1) / 2
            val move = better(candidates[parent], candidates[sift], policy)
            budget.visit(0L)
            if (move) {
                swap(parent, sift)
                sift = parent
                if (afterSift == Stage.Clean) cleanupRead = minOf(cleanupRead, parent)
            } else stage = afterSift
            changed()
        }
    }

    private fun down(budget: ReclamationPreparation, policy: NativeIndexedCapPolicy) {
        budget.visit(12L)
        var child = sift * 2 + 1
        if (child >= heapSize) transition(afterSift, budget) else {
            if (child + 1 < heapSize && better(candidates[child], candidates[child + 1], policy)) child++
            val move = better(candidates[sift], candidates[child], policy)
            budget.visit(0L)
            if (move) {
                swap(sift, child)
                sift = child
            } else stage = afterSift
            changed()
        }
    }

    private fun better(left: Int, right: Int, policy: NativeIndexedCapPolicy): Boolean {
        val a = policy.lbd(left)
        val b = policy.lbd(right)
        return a < b || (a == b && left < right)
    }

    private fun swap(a: Int, b: Int) {
        val old = candidates[a]
        candidates[a] = candidates[b]
        candidates[b] = old
    }

    private fun transition(next: Stage, budget: ReclamationPreparation) {
        budget.visit(1L)
        budget.visit(0L)
        stage = next
        changed()
    }

    private fun changed() { serial = if (serial == Long.MAX_VALUE) 0L else serial + 1L }

    enum class Completion { None, Pass, Cleaned, Aborted }
    private enum class Stage {
        Idle, Reserve, Clean, Repair, PruneDirty, PruneAging, Aging, Dirty, Admissions, Scan, Inspect,
        Classify, Mark, Admit, Up, Down, Select, Retire, Remove, Restore, Clear, Finalize, TrimPool,
    }

    companion object {
        const val CREATION_WORK = 100L
        const val CREATION_BYTES = 319L
        private const val SCALAR_BYTES = 103L
        private const val REFERENCES = 12L
        private fun requiredSlots(rows: Int): Long = rows.toLong() * 5L + 64L
    }
}
