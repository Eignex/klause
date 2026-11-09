package com.eignex.klause.propagation

import com.eignex.klause.ir.Lit
import com.eignex.klause.solver.search.LearnedKind
import com.eignex.klause.solver.search.LearnedStage
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.OriginCounts
import com.eignex.klause.util.SharingMemoryObservation
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.IntHashSet

/**
 * Two-watched-literal BCP (Moskewicz et al., "Chaff: engineering an efficient SAT solver", DAC 2001,
 * https://doi.org/10.1145/378239.379017) for arena-packed pure-Boolean problems. A trail-pointer literal sweep
 * avoids factor dispatch and order-literal atoms. The general lane's trail, pin machinery,
 * antecedents and [ConflictAnalyzer] remain shared.
 *
 * Watch positions `watchPos(2h)` and `watchPos(2h+1)` index the two watched literals of clause `h`;
 * `watchEntries(L)` lists clauses watching `L`. Watches are rebuilt per session and may drift
 * across backtracking. Base literals stay in the immutable [ClauseArena]; learned clauses use
 * individually owned payload array per learned clause. Relocation moves handles and leaves the
 * arena shareable read-only.
 */
internal class NativeSatState(private val state: PropagationState) {

    private val arena = state.projection.clauseArena
    private val baseCount = arena.clauseCount
    private val numBoolVars = state.problem.numBoolVars
    private val boolTruth = checkNotNull(state.nativeBoolTruth)

    private fun litTrue(lit: Int): Boolean {
        state.work++
        return boolTruth[Lit.variable(lit)].toInt() == 2 - (lit and 1)
    }

    private fun litFalse(lit: Int): Boolean {
        state.work++
        return boolTruth[Lit.variable(lit)].toInt() == 1 + (lit and 1)
    }

    // Learned handles remain stable while each payload can release independently of other rows.
    private val learnedPayloads = NativeLearnedPayloads(state)
    private var learnedCount = 0
    private var peerImports = 0
    private var consumedPeerImports = 0
    private var consumed = 0
    private var permanentCount = 0
    private var retiredCount = 0
    private val capCandidates = IntArrayList(1)
    private val capRanking = LearnedCapRanking(capCandidates)
    private var defaultCapIndex: NativeCapIndex? = null
    private var capCalls = 0L
    private var capWork = 0L
    private var capVisits = 0L
    internal val capWindowPending: Boolean get() = capRanking.hasPending || defaultCapIndex?.hasPending == true
    internal val capTraversalVisits: Long get() = capVisits
    internal val capContinuationCursor: Long
        get() = defaultCapIndex?.takeUnless { it.bootstrapping && capRanking.hasPending }
            ?.continuation ?: capRanking.continuationCursor
    internal val capContinuationHeap: Long
        get() = defaultCapIndex?.takeUnless { it.bootstrapping && capRanking.hasPending }
            ?.continuationHeap ?: capRanking.continuationHeap
    internal val capContinuationOperation: Long
        get() = defaultCapIndex?.takeUnless { it.bootstrapping && capRanking.hasPending }
            ?.continuationOperation ?: capRanking.continuationOperation
    private var capBoundaryDefers = 0L
    private var capPreparationDefers = 0L
    private var capPendingReplayCalls = 0L

    // Per-learned-clause policy columns, parallel to the learned index — the native mirror of
    // LearnedClauseDb's forgetting/three-tier metadata. Recent use shares lifecycle storage;
    // permanent clauses survive every forget.
    private val metadata = NativeLearnedMetadata(state)
    private val learnedUnits = IntArrayList()
    private var unitsPending = false
    private val rechecks = IntArrayList()
    private var recheckHead = 0

    // The origin an imported learned clause came from, parallel to the learned columns; see [OriginCounts].

    /** Uses of imported learned clauses, by origin. */
    val importUses = OriginCounts()

    // Keep learned positions separate so the first learned clause cannot double the base allocation.
    // Single-literal clauses are examined directly after retraction instead of watching a literal.
    private val watchPos = IntArray(2 * baseCount)

    // Pack each handle and blocker into one word. Empty literals own no posting container;
    // page boundaries release retired posting capacity without moving factor handles.
    private val watchSizes = IntArray(2 * numBoolVars)
    private val watchEntries: Array<NativeWatchEntries?> = run {
        for (h in 0 until baseCount) {
            if (arena.length(h) < 2) continue
            watchSizes[arena.lits[arena.start(h)]]++
            watchSizes[arena.lits[arena.start(h) + 1]]++
        }
        Array(watchSizes.size) { lit ->
            if (watchSizes[lit] == 0) null else {
                state.preparationIndexWork++
                NativeWatchEntries(watchSizes[lit])
            }
        }.also { watchSizes.fill(0) }
    }

    // Base unit clauses, pinned once at root during the initial full propagation.
    private val baseUnits = IntArrayList()

    /** Trail cursor: every literal in `boolPinOrder[0, bcpHead)` has been propagated. Reset to the
     *  (truncated) trail size on backtrack, since the surviving prefix is already propagated. */
    var bcpHead = 0

    private var retiredRootSources: IntHashSet? = null

    private fun isRetired(handle: Int): Boolean = if (handle < baseCount) {
        retiredRootSources?.contains(handle) == true
    } else retiredOf(handle - baseCount)

    fun retireRootSource(factor: Int) {
        check(factor in 0 until baseCount)
        val retired = retiredRootSources ?: IntHashSet().also { retiredRootSources = it }
        retired.add(factor)
    }

    private var initialized = false
    private var pendingConflict = false

    init {
        state.preparationIndexWork += metadata.preparationWork
        state.preparationIndexWork += learnedPayloads.preparationWork
        state.preparationIndexWork += 2L
        for (h in 0 until baseCount) {
            val len = arena.length(h)
            if (len == 1) {
                baseUnits.add(h)
                continue
            }
            watchPos[2 * h] = 0
            watchPos[2 * h + 1] = 1
            addWatch(litOf(h, 0), h, litOf(h, 1))
            addWatch(litOf(h, 1), h, litOf(h, 0))
        }
    }

    private fun IntArrayList.growTo(n: Int) {
        while (size < n) add(0)
    }

    private fun clauseLen(h: Int): Int =
        if (h < baseCount) arena.length(h) else learnedPayloads.length(h - baseCount)

    private fun litOf(h: Int, k: Int): Int =
        if (h < baseCount) arena.lits[arena.start(h) + k] else learnedPayloads[h - baseCount][k]

    private fun packWatch(handle: Int, blocker: Int): Long =
        (blocker.toLong() shl 32) or (handle.toLong() and 0xFFFFFFFFL)

    private fun watchPosition(h: Int, slot: Int): Int =
        if (h < baseCount) watchPos[2 * h + slot] else metadata[h - baseCount, NativeLearnedMetadata.WATCH_FIRST + slot]

    private fun setWatchPosition(h: Int, slot: Int, position: Int) {
        if (h < baseCount) watchPos[2 * h + slot] = position
        else metadata[h - baseCount, NativeLearnedMetadata.WATCH_FIRST + slot] = position
    }

    private var watchCleanupLiteral = 0
    private var watchCleanupIndex = 0
    private var watchCleanupScan = 0
    private val watchCleanupReadIndex: Int
        get() = if (watchCleanupScan < 0) watchCleanupScan.inv() else watchCleanupScan
    // An inactive epoch encodes the completed retirement count without retaining another cursor.
    private var watchCleanupEpoch = -1
    private var watchCleanupSparse = false

    private var retiredMetadataCount = 0

    internal fun sweepRetiredMetadata(budget: ReclamationPreparation) {
        if (metadata.hasEmptyReservation) {
            budget.visit(12L, 16L)
            budget.visit(0L)
            metadata.releaseEmptyReservation()
            budget.advanced()
        }
        while (metadata.hasRetiredRows) {
            budget.visit(20L, 16L)
            budget.visit(0L)
            metadata.releaseRetired()
            retiredMetadataCount--
            budget.advanced()
        }
        metadata.sweepDirectory(budget)
        learnedPayloads.sweepDirectory(budget)
        if (retiredCount > 0) trimLearnedCandidates(capCandidates, budget)
    }

    private fun enqueueRetiredMetadata(index: Int) {
        metadata.enqueueRetired(index)
        retiredMetadataCount++
    }

    private fun addWatch(lit: Int, handle: Int, blocker: Int) {
        val entries = watchEntries[lit] ?: run {
            state.work++
            NativeWatchEntries().also { watchEntries[lit] = it }
        }
        state.work += entries.appendWork
        entries.add(packWatch(handle, blocker))
        watchSizes[lit] = entries.size
    }

    private fun cleanupScanAfterRemoval(lit: Int, index: Int, remaining: Int): Int {
        if (lit != watchCleanupLiteral) return watchCleanupScan
        return if (watchCleanupScan < 0 || (index < remaining && index < watchCleanupReadIndex)) {
            minOf(watchCleanupIndex, index)
        } else minOf(watchCleanupReadIndex, remaining)
    }

    private fun watchRemovalWork(lit: Int, index: Int): Long =
        checkNotNull(watchEntries[lit]).removalWork + 4L +
            if (cleanupScanAfterRemoval(lit, index, watchSizes[lit] - 1) != watchCleanupScan) 1L else 0L

    private fun removeWatchAt(lit: Int, idx: Int) {
        val entries = checkNotNull(watchEntries[lit])
        val scan = cleanupScanAfterRemoval(lit, idx, entries.size - 1)
        entries[idx] = entries[entries.size - 1]
        entries.removeLast()
        watchSizes[lit] = entries.size
        if (entries.size == 0) watchEntries[lit] = null
        if (lit == watchCleanupLiteral && idx < watchCleanupIndex) watchCleanupIndex = idx
        if (scan != watchCleanupScan) watchCleanupScan = scan
    }

    private fun nextLiveWatch(lit: Int, start: Int): Int {
        val entries = checkNotNull(watchEntries[lit])
        var index = start
        while (index < watchSizes[lit]) {
            state.work++
            if (!isRetired(entries[index].toInt())) return index
            index++
        }
        return index
    }

    private fun trimRetiredWatchTail(lit: Int, index: Int) {
        if (retiredCount == 0 && retiredRootSources == null) return
        val entries = checkNotNull(watchEntries[lit])
        while (watchSizes[lit] - 1 > index) {
            state.work++
            val last = watchSizes[lit] - 1
            if (!isRetired(entries[last].toInt())) return
            state.work += watchRemovalWork(lit, last)
            removeWatchAt(lit, last)
        }
    }

    internal fun sweepRetiredWatches(budget: ReclamationPreparation) =
        metadata.usingBudget(budget) { sweepWatches(budget) }

    private fun sweepWatches(budget: ReclamationPreparation) {
        if (retiredCount == 0) return
        if (watchCleanupEpoch < 0) {
            if (watchCleanupEpoch.inv() == retiredCount) return
            val index = defaultCapIndex
            val sparse = index?.hasIndexedRows == true && watchCleanupEpoch.inv() >= index.retirementBaseline
            budget.visit(if (watchCleanupSparse == sparse) 4L else 5L)
            budget.visit(0L)
            if (watchCleanupSparse != sparse) watchCleanupSparse = sparse
            watchCleanupEpoch = retiredCount
        }
        while (watchCleanupLiteral < watchEntries.size) {
            val index = defaultCapIndex
            val sparse = watchCleanupSparse && index?.hasIndexedRows == true &&
                index.retirementBaseline <= watchCleanupEpoch
            budget.visit(if (sparse) 5L else 4L)
            budget.visit(0L)
            val entries = watchEntries[watchCleanupLiteral]
            if (entries == null || watchCleanupIndex >= entries.size ||
                (sparse && watchCleanupIndex == 0 && watchCleanupScan == 0 && entries.retirementEpoch == 0)
            ) {
                val clear = entries != null && entries.retirementEpoch != 0 &&
                    entries.retirementEpoch <= watchCleanupEpoch
                budget.visit((if (watchCleanupScan == 0) 0L else 1L) + (if (clear) 1L else 0L))
                budget.visit(0L)
                if (clear) checkNotNull(entries).retirementEpoch = 0
                watchCleanupLiteral++
                watchCleanupIndex = 0
                watchCleanupScan = 0
            } else if (watchCleanupReadIndex > watchCleanupIndex) {
                advanceWatchCleanup(entries, budget)
            } else if (entries[watchCleanupIndex].toInt() >= baseCount &&
                retiredOf(entries[watchCleanupIndex].toInt() - baseCount)) {
                budget.visit(1L)
                budget.visit(0L)
                watchCleanupScan = watchCleanupIndex + 1
            } else watchCleanupIndex++
            budget.advanced()
        }
        budget.visit(4L)
        budget.visit(0L)
        watchCleanupEpoch = watchCleanupEpoch.inv()
        watchCleanupLiteral = 0
        watchCleanupIndex = 0
        watchCleanupScan = 0
    }

    private fun advanceWatchCleanup(entries: NativeWatchEntries, budget: ReclamationPreparation) {
        val read = watchCleanupReadIndex
        if (read >= entries.size) {
            val last = entries.size - 1
            budget.visit(watchRemovalWork(watchCleanupLiteral, last))
            budget.visit(0L)
            removeWatchAt(watchCleanupLiteral, last)
            return
        }
        if (watchCleanupScan >= 0) {
            checkWatchCleanupTail(entries, budget)
            return
        }
        budget.visit(1L)
        val entry = entries[read]
        if (entry.toInt() >= baseCount && retiredOf(entry.toInt() - baseCount)) {
            budget.visit(1L)
            budget.visit(0L)
            watchCleanupScan = if (read + 1 == entries.size) read + 1 else (read + 1).inv()
        } else {
            budget.visit(4L)
            budget.visit(0L)
            val retired = entries[watchCleanupIndex]
            entries[watchCleanupIndex] = entry
            entries[read] = retired
            watchCleanupIndex++
            watchCleanupScan = if (read + 1 == entries.size) read + 1 else (read + 1).inv()
        }
    }

    private fun checkWatchCleanupTail(entries: NativeWatchEntries, budget: ReclamationPreparation) {
        val last = entries.size - 1
        budget.visit(1L)
        val entry = entries[last]
        if (entry.toInt() >= baseCount && retiredOf(entry.toInt() - baseCount)) {
            budget.visit(watchRemovalWork(watchCleanupLiteral, last))
            budget.visit(0L)
            removeWatchAt(watchCleanupLiteral, last)
        } else {
            budget.visit(1L)
            budget.visit(0L)
            // A complemented scan retains the checked live tail while filling this hole.
            watchCleanupScan = watchCleanupScan.inv()
        }
    }

    /** The clause's literals as a fresh array (the seed reason for conflict analysis). */
    private fun clauseLits(h: Int): IntArray {
        val lits = if (h < baseCount) arena.lits else learnedPayloads[h - baseCount]
        val start = if (h < baseCount) arena.start(h) else 0
        return lits.copyOfRange(start, start + clauseLen(h)).also {
            state.factorAttribution?.remember(it, 1 shl FactorFamily.Clause.ordinal)
        }
    }

    /** The clause's literals except index [skip] — the antecedent set recorded for a forced unit,
     *  matching the general clause propagator's reason exactly. Null for a single-literal clause. */
    private fun antecedentsExcept(h: Int, skip: Int): IntArray? {
        val len = clauseLen(h)
        if (len <= 1) return null
        val lits = if (h < baseCount) arena.lits else learnedPayloads[h - baseCount]
        val start = if (h < baseCount) arena.start(h) else 0
        val out = IntArray(len - 1)
        lits.copyInto(out, 0, start, start + skip)
        lits.copyInto(out, skip, start + skip + 1, start + len)
        return out
    }

    private fun recordConflict(h: Int) {
        state.conflictSeedFactors.add(h)
        state.nativeConflictReason = clauseLits(h)
        val levels = IntHashSet()
        val len = clauseLen(h)
        for (k in 0 until len) {
            val lvl = state.boolLevel[Lit.variable(litOf(h, k))]
            if (lvl > 0) levels.add(lvl)
        }
        state.conflictLevels = levels.toIntArray()
    }

    /**
     * Register a learned clause and fire it once against the current assignment: pin its sole
     * non-false literal (the asserting UIP after a backjump), flag a conflict if every literal is
     * already false, or just install watches when two or more literals are still open. The trailing
     * [propagate] call continues BCP from any pin this makes.
     */
    fun addLearned(
        lits: IntArray,
        lbd: Int,
        permanent: Boolean,
        origin: Int = OWN_ORIGIN,
        ownedLiterals: Boolean = false,
    ): Int {
        state.learned.lifecycle.note(LearnedKind.Clause, LearnedStage.Constructed)
        val h = appendLearned(lits, lbd, permanent, origin, ownedLiterals)
        state.learned.lifecycle.note(LearnedKind.Clause, LearnedStage.Admitted)
        state.learned.lifecycle.note(LearnedKind.Clause, LearnedStage.Asserted)
        val len = lits.size
        if (len == 1) {
            learnedUnits.add(h)
            if (litFalse(lits[0])) {
                recordConflict(h)
                pendingConflict = true
            } else if (!litTrue(lits[0])) {
                if (!pinUnit(h, 0)) pendingConflict = true
            }
            return h
        }
        var w0 = -1
        var w1 = -1
        for (k in 0 until len) {
            if (!litFalse(lits[k])) {
                if (w0 < 0) {
                    w0 = k
                } else {
                    w1 = k
                    break
                }
            }
        }
        if (w0 < 0) {
            val first = highestLevelLiteral(h)
            val second = highestLevelLiteral(h, first)
            setWatchPosition(h, 0, first)
            setWatchPosition(h, 1, second)
            addWatch(litOf(h, first), h, litOf(h, second))
            addWatch(litOf(h, second), h, litOf(h, first))
            recordConflict(h)
            pendingConflict = true
            return h
        }
        val second = if (w1 >= 0) {
            w1
        } else {
            highestLevelLiteral(h, w0)
        }
        setWatchPosition(h, 0, w0)
        setWatchPosition(h, 1, second)
        addWatch(litOf(h, w0), h, litOf(h, second))
        addWatch(litOf(h, second), h, litOf(h, w0))
        if (w1 < 0 && !pinUnit(h, w0)) pendingConflict = true // exactly one open literal — assert it
        return h
    }

    // A false watch must retract with the deepest premise: otherwise a backjump can leave it false
    // while an unwatched premise becomes open, and assigning that premise again misses a unit.
    private fun highestLevelLiteral(h: Int, excluded: Int = -1): Int {
        var best = -1
        var level = -1
        for (k in 0 until clauseLen(h)) {
            if (k == excluded) continue
            val candidate = state.boolLevel[Lit.variable(litOf(h, k))]
            if (best < 0 || candidate > level) {
                best = k
                level = candidate
            }
        }
        return best
    }

    private fun appendLearned(
        lits: IntArray, lbd: Int, permanent: Boolean, origin: Int, ownedLiterals: Boolean,
    ): Int {
        val i = learnedCount
        if (!permanent) defaultCapIndex?.let { index ->
            repeat(index.admissionPages(capCandidates.size + 1).toInt()) {
                val budget = index.activeBudget
                if (budget == null) state.work += NATIVE_CAP_PAGE_WORK else {
                    budget.atomic(NATIVE_CAP_PAGE_WORK, NATIVE_CAP_PAGE_BYTES)
                    budget.visit(NATIVE_CAP_PAGE_WORK)
                    budget.ready()
                    budget.visit(0L)
                }
                index.reserveAdmission()
            }
        }
        state.work += learnedPayloads.admissionWork(lits.size, ownedLiterals)
        learnedPayloads.add(lits, ownedLiterals)
        state.work += metadata.admissionWork
        check(state.nextLearnedAdmissionKey < Long.MAX_VALUE)
        metadata.add(lbd, permanent, origin, state.nextLearnedAdmissionKey++)
        state.work++
        if (i == 0) state.work++
        if (permanent) {
            permanentCount++
        } else {
            if (capCandidates.size == capCandidates.backingData.size) state.work += capCandidates.size * 3L
            capCandidates.add(i)
        }
        state.work += 2L
        if (origin >= 0) peerImports++
        learnedCount++
        return baseCount + i
    }

    // Learned-clause policy accessors: the native mirror of the LearnedClauseDb columns.

    internal fun sharingMemory(sample: SharingMemoryObservation, protected: SharingMemoryObservation.Seen<Int>) =
        metadata.usingObservation(sample) { observeSharingMemory(sample, protected) }

    private fun observeSharingMemory(sample: SharingMemoryObservation, protected: SharingMemoryObservation.Seen<Int>) {
        repeat(3) { sample.step() }
        for (i in recheckHead until rechecks.size) {
            sample.step()
            val index = rechecks[i] - baseCount
            if (metadata[index, NativeLearnedMetadata.STAGES] and 1 == 0) sample.retain(protected, index)
        }
        val protectedRows = protected.size > 0 || permanentCount > 0
        capRanking.sharingMemory(sample, protectedRows)
        defaultCapIndex?.sharingMemory(sample, protectedRows)
        sample.ownedReferences(this, 1L, protectedRows)
        state.learned.capRanking.sharingMemory(sample, protectedRows)
        val columns = listOf(learnedUnits, rechecks, capCandidates,
            state.learned.capCandidates)
        var metadataBytes = 56L + capRanking.primitiveBytes + state.learned.capRanking.primitiveBytes +
            learnedPayloads.primitiveBytes + metadata.primitiveBytes + (defaultCapIndex?.primitiveBytes ?: 0L)
        metadata.sharingMemory(sample, protectedRows) { index ->
            metadata[index, NativeLearnedMetadata.PERMANENT] == 1 || sample.contains(protected, index)
        }
        sample.owned(state.learned, 44L, protectedRows)
        state.learned.initializedStore?.let { rows ->
            metadataBytes += rows.primitiveBytes
            rows.sharingMemory(sample, protectedRows) { false }
        }
        for (column in columns) {
            sample.step(); metadataBytes += column.backingData.size.toLong() * Int.SIZE_BYTES
            sample.owned(column.backingData, column.backingData.size.toLong() * Int.SIZE_BYTES, protectedRows)
        }
        learnedPayloads.sharingMemory(sample, protectedRows, permanentCount == learnedCount) { index ->
            metadata[index, NativeLearnedMetadata.PERMANENT] == 1 || sample.contains(protected, index)
        }
        sample.owned(arena.lits, arena.lits.size.toLong() * Int.SIZE_BYTES, true, shared = true)
        sample.owned(arena.starts, arena.starts.size.toLong() * Int.SIZE_BYTES, true, shared = true)
        sample.owned(watchSizes, watchSizes.size.toLong() * Int.SIZE_BYTES, true)
        sample.owned(watchPos, watchPos.size.toLong() * Int.SIZE_BYTES, true)
        sample.owned(baseUnits.backingData, baseUnits.backingData.size.toLong() * Int.SIZE_BYTES, true)
        sample.ownedReferences(watchEntries, watchEntries.size.toLong(), true)
        var watchBytes = 0L
        var watchPostings = 0L
        sample.ownedPool(this, 33L, (if (protectedRows) 12L else 0L) +
            (if (watchCleanupEpoch >= 0) 17L else 0L) +
            (if (metadata.hasRetiredRows) 4L else 0L))
        for (entries in watchEntries) {
            sample.step()
            if (entries == null) continue
            watchPostings += entries.size
            watchBytes += entries.sharingMemory(sample) { entry ->
                val handle = entry.toInt()
                handle < baseCount || permanentCount == learnedCount ||
                    metadata[handle - baseCount, NativeLearnedMetadata.PERMANENT] == 1 ||
                    sample.contains(protected, handle - baseCount)
            }
        }
        sample.record(mapOf(
            "learnedObjects" to learnedCount.toLong(),
            "learnedRetiredSlots" to retiredCount.toLong(),
            "learnedPayloadBytes" to learnedPayloads.capacityBytes,
            "learnedArenaCapacityBytes" to learnedPayloads.capacityBytes,
            "learnedPayloadReferenceCapacity" to learnedPayloads.referenceCapacity,
            "learnedPayloadDirectoryCapacity" to learnedPayloads.directoryCapacity,
            "learnedMetadataCapacityBytes" to metadataBytes,
            "learnedCleanupCursorCapacityBytes" to 21L,
            "learnedCapIndexStateBytes" to capRanking.modeledBytes + state.learned.capRanking.modeledBytes +
                Long.SIZE_BYTES + (defaultCapIndex?.modeledBytes ?: 0L),
            "sessionWatchEntries" to watchPostings,
            "sessionWatchCapacityBytes" to watchBytes +
                (watchSizes.size.toLong() + watchPos.size) * Int.SIZE_BYTES +
                baseUnits.backingData.size.toLong() * Int.SIZE_BYTES,
            "rootRetiredSourceCapacityBytes" to (retiredRootSources?.allocatedBytes ?: 0L),
            "pendingAttachments" to (rechecks.size - recheckHead).toLong(),
            "importUseCapacityBytes" to importUses.allocatedBytes,
            "retainedPeerImports" to peerImports.toLong(),
            "retainedConsumedPeerImports" to consumedPeerImports.toLong(),
            "retainedConsumed" to consumed.toLong(),
            "learnedPayloadCacheReferenceCapacity" to learnedPayloads.cacheReferenceCapacity,
        ))
        retiredRootSources?.let { sample.owned(it, it.allocatedBytes, true) }
        sample.owned(importUses, importUses.allocatedBytes)
    }

    val count: Int get() = learnedCount
    val activeCount: Int get() = learnedCount - retiredCount
    fun retiredOf(i: Int): Boolean = metadata[i, NativeLearnedMetadata.STAGES] and RETIRED != 0
    fun lbdOf(i: Int): Int = metadata[i, NativeLearnedMetadata.LBD]
    fun permanentOf(i: Int): Boolean = metadata[i, NativeLearnedMetadata.PERMANENT] == 1
    fun originOf(i: Int): Int = metadata[i, NativeLearnedMetadata.ORIGIN]
    fun admissionKeyOf(i: Int): Long = metadata.admission(i)
    fun tierOf(i: Int): ClauseTier = ClauseTier.entries[metadata[i, NativeLearnedMetadata.TIER]]
    fun setTierOf(i: Int, tier: ClauseTier) {
        if (retiredOf(i)) return
        noteCapPolicyMutation(i)
        metadata[i, NativeLearnedMetadata.TIER] = tier.ordinal
    }
    fun usedOf(i: Int): Boolean = metadata[i, NativeLearnedMetadata.STAGES] and RECENT_USED != 0
    fun clearUsed(i: Int) {
        if (retiredOf(i)) return
        noteCapPolicyMutation(i)
        metadata[i, NativeLearnedMetadata.STAGES] = metadata[i, NativeLearnedMetadata.STAGES] and RECENT_USED.inv()
    }

    internal fun <T> withOwnershipBudget(budget: ReclamationPreparation, operation: () -> T): T {
        val index = defaultCapIndex
        return if (index == null) metadata.usingBudget(budget) { learnedPayloads.usingBudget(budget, operation) }
        else index.usingBudget(budget) {
            metadata.usingBudget(budget) { learnedPayloads.usingBudget(budget, operation) }
        }
    }

    private fun chargeCapMutation(index: NativeCapIndex, units: Long) {
        val budget = index.activeBudget
        if (budget == null) state.work += units else {
            budget.visit(units)
            budget.visit(0L)
        }
    }

    private fun noteCapPolicyMutation(row: Int) {
        val index = defaultCapIndex ?: return
        if (!index.hasIndexedRows || index.ownsClassification(row)) return
        chargeCapMutation(index, 4L)
        index.requestRescan()
    }

    internal fun maintainCapWindow(
        cap: Int,
        maxWork: Long,
        maxScratchBytes: Long,
        cancelled: () -> Boolean,
        canDrop: (Int) -> Boolean,
        keep: (Int) -> Boolean,
        nativePolicy: NativeCapConfiguration? = null,
    ): Int {
        capCalls++
        if (unitsPending || recheckHead < rechecks.size) capPendingReplayCalls++
        if (maxScratchBytes < 64L || !initialized || pendingConflict ||
            bcpHead != state.boolPinOrder.size || state.runCancelled
        ) {
            capBoundaryDefers++
            return 0
        }
        val before = retiredCount
        val budget = ReclamationPreparation(maxWork, maxScratchBytes, cancelled) {
            state.work += it
            capWork += it
        }
        try {
            budget.visit(0L, 64L)
            withOwnershipBudget(budget) {
                var index = defaultCapIndex
                val stopping = index
                if (stopping != null && (nativePolicy == null || stopping.cancelling)) {
                    val stopped = stopping.usingBudget(budget) {
                        stopping.abort(budget, capPolicy(budget, canDrop, keep, true))
                    }
                    if (stopped == NativeCapIndex.Completion.Aborted) {
                        budget.visit(8L)
                        budget.visit(0L)
                        defaultCapIndex = null
                    }
                } else {
                    if (nativePolicy != null && index == null && !capRanking.hasPending &&
                        budget.supportsAtomic(NativeCapIndex.CREATION_WORK, NativeCapIndex.CREATION_WORK + 4L,
                            NativeCapIndex.CREATION_BYTES + 64L)
                    ) {
                        budget.visit(CAP_ROW_INSPECTION_WORK)
                        budget.visit(0L)
                        budget.atomic(NativeCapIndex.CREATION_WORK, NativeCapIndex.CREATION_BYTES)
                        budget.visit(NativeCapIndex.CREATION_WORK)
                        budget.ready()
                        budget.visit(0L)
                        index = NativeCapIndex(capCandidates)
                        defaultCapIndex = index
                    }
                    val active = index
                    if (active != null && nativePolicy != null) {
                        val preparationOnly = active.bootstrapping && !capRanking.hasPending &&
                            learnedCount - retiredCount <= cap
                        if (preparationOnly && !active.retiredHandlesPending) active.trimReservations(budget)
                        else if (preparationOnly || (active.bootstrapping &&
                            !budget.supportsAtomic(NATIVE_CAP_PAGE_WORK, NATIVE_CAP_PAGE_WORK,
                                NATIVE_CAP_PAGE_BYTES + 64L))) {
                            budget.visit(8L)
                            budget.visit(0L)
                            defaultCapIndex = null
                        } else {
                            var reserved = true
                            if (active.bootstrapping) {
                                try { active.reservePages(budget) }
                                catch (_: ReclamationPreparation.Deferred) {
                                    reserved = false
                                    budget.visit(0L)
                                }
                            }
                            if (!reserved || capRanking.hasPending) {
                                if (capRanking.hasPending || learnedCount - retiredCount > cap) {
                                    capRanking.advance(cap, permanentCount, budget, capPolicy(budget, canDrop, keep))
                                    if (!capRanking.hasPending) active.trimReservations(budget)
                                }
                            } else {
                                val completion = active.usingBudget(budget) {
                                    active.advance(cap, nativePolicy, budget, capPolicy(budget, canDrop, keep, true))
                                }
                                if (completion == NativeCapIndex.Completion.Pass) capRanking.completedIndexedPass()
                            }
                        }
                    }
                    if (defaultCapIndex == null &&
                        (nativePolicy == null || capRanking.hasPending || learnedCount - retiredCount > cap)) {
                        capRanking.advance(cap, permanentCount, budget, capPolicy(budget, canDrop, keep))
                    }
                }
            }
        } catch (_: ReclamationPreparation.Deferred) {
            capPreparationDefers++
            // Heap steps and pending retirement keep their handles across roots.
        }
        return retiredCount - before
    }

    private fun capPolicy(
        budget: ReclamationPreparation,
        mayDrop: (Int) -> Boolean,
        retain: (Int) -> Boolean,
        indexed: Boolean = false,
    ): NativeIndexedCapPolicy = object : NativeIndexedCapPolicy {
        override val activeCount: Int get() = learnedCount - retiredCount
        override val retirementCount: Int get() = retiredCount
        override fun alive(index: Int): Boolean = !retiredOf(index)
        private var prepared = -1
        private var protected = false
        override fun lbd(index: Int): Int = metadata[index, NativeLearnedMetadata.LBD]
        override fun keep(index: Int): Boolean {
            val retained = retain(index)
            return retained || protected
        }
        override fun visited() { capVisits++ }
        override fun prepare(index: Int): Boolean {
            protected = capProtected(index, budget, CAP_ROW_POLICY_WORK)
            prepared = index
            return true
        }
        override fun retire(index: Int): Boolean {
            if (indexed && metadata[index, NativeLearnedMetadata.STAGES] and CAP_KEEP != 0) return false
            if (!mayDrop(index)) return false
            val pinned = if (!indexed && index == prepared) protected else capProtected(index, budget, 0L)
            if (pinned) return false
            val watched = if (indexed && budget.supports(48L, 0L)) retiringWatchLiterals(index, budget) else {
                if (indexed) defaultCapIndex?.prepareUntrackedRetirement(retiredCount + 1, budget)
                -1L
            }
            budget.visit(24L)
            budget.visit(0L)
            markRetiredWatchLists(watched)
            learnedPayloads.release(index)
            metadata[index, NativeLearnedMetadata.STAGES] = metadata[index, NativeLearnedMetadata.STAGES] or RETIRED
            state.learned.lifecycle.note(LearnedKind.Clause, LearnedStage.Retired)
            retiredCount++
            enqueueRetiredMetadata(index)
            return true
        }
        override fun classified(index: Int): Boolean =
            metadata[index, NativeLearnedMetadata.STAGES] and CAP_CLASSIFIED != 0
        override fun markClassification(index: Int, keep: Boolean): Boolean {
            val flags = metadata[index, NativeLearnedMetadata.STAGES]
            val tier = metadata[index, NativeLearnedMetadata.TIER]
            budget.visit(0L)
            metadata[index, NativeLearnedMetadata.STAGES] =
                (flags and CAP_KEEP.inv()) or CAP_CLASSIFIED or (if (keep) CAP_KEEP else 0)
            return tier == ClauseTier.MID.ordinal
        }
        override fun clearClassification(index: Int) {
            if (retiredOf(index)) {
                budget.visit(0L)
                return
            }
            val flags = metadata[index, NativeLearnedMetadata.STAGES]
            budget.visit(0L)
            metadata[index, NativeLearnedMetadata.STAGES] = flags and (CAP_KEEP or CAP_CLASSIFIED).inv()
        }
    }

    private fun capProtected(index: Int, budget: ReclamationPreparation, work: Long): Boolean {
        val length = literalCountOf(index)
        budget.visit(work + 8L)
        budget.visit(0L)
        return length <= 1 || metadata[index, NativeLearnedMetadata.STAGES] and 2 != 0 ||
            metadata[index, NativeLearnedMetadata.BOOL_REASONS] > 0
    }

    private fun retiringWatchLiterals(index: Int, budget: ReclamationPreparation): Long {
        budget.visit(8L)
        val literals = learnedPayloads[index]
        val first = literals[metadata[index, NativeLearnedMetadata.WATCH_FIRST]]
        val second = literals[metadata[index, NativeLearnedMetadata.WATCH_SECOND]]
        return (second.toLong() shl 32) or (first.toLong() and 0xFFFFFFFFL)
    }

    private fun markRetiredWatchLists(watched: Long) {
        if (watched < 0L) return
        watchEntries[watched.toInt()]?.retirementEpoch = retiredCount + 1
        watchEntries[(watched ushr 32).toInt()]?.retirementEpoch = retiredCount + 1
    }

    internal fun noteBoolReason(factor: Int, delta: Int) {
        val index = factor - baseCount
        if (index !in 0 until learnedCount) return
        state.work += 2L
        val count = metadata[index, NativeLearnedMetadata.BOOL_REASONS] + delta
        check(count >= 0)
        metadata[index, NativeLearnedMetadata.BOOL_REASONS] = count
    }

    internal fun retireSelectedLearnedClause(index: Int, budget: ReclamationPreparation): LearnedRetirementResult =
        withOwnershipBudget(budget) { retireSelected(index, budget) }

    private fun retireSelected(index: Int, budget: ReclamationPreparation): LearnedRetirementResult {
        if (!initialized || pendingConflict || bcpHead != state.boolPinOrder.size || state.runCancelled) {
            return LearnedRetirementResult(LearnedRetirementStatus.Deferred)
        }
        if (index !in 0 until learnedCount) return LearnedRetirementResult(LearnedRetirementStatus.AlreadyGone,
            payloadReleased = true, physicalReleased = true)
        if (retiredOf(index)) return LearnedRetirementResult(LearnedRetirementStatus.AlreadyGone,
            payloadReleased = true)
        budget.visit(16L)
        if (metadata[index, NativeLearnedMetadata.PERMANENT] == 1 || literalCountOf(index) <= 1 ||
            metadata[index, NativeLearnedMetadata.BOOL_REASONS] > 0 ||
            metadata[index, NativeLearnedMetadata.STAGES] and 2 != 0
        ) return LearnedRetirementResult(LearnedRetirementStatus.ProtectedFloor)
        val owner = defaultCapIndex
        if (owner?.hasIndexedRows == true) owner.prepareUntrackedRetirement(retiredCount + 1, budget)
        budget.visit(40L + (if (defaultCapIndex == null) 0L else 1L))
        budget.visit(0L)
        learnedPayloads.release(index)
        metadata[index, NativeLearnedMetadata.STAGES] = metadata[index, NativeLearnedMetadata.STAGES] or RETIRED
        state.learned.lifecycle.note(LearnedKind.Clause, LearnedStage.Retired)
        retiredCount++
        enqueueRetiredMetadata(index)
        capRanking.invalidateRetiredHandles()
        val capIndex = defaultCapIndex
        if (capIndex?.hasIndexedRows == false) defaultCapIndex = null
        else capIndex?.invalidateRetiredHandles()
        return LearnedRetirementResult(LearnedRetirementStatus.Retired, payloadReleased = true)
    }

    internal fun capWindowStatistics(): Map<String, Long> = if (capCalls == 0L) emptyMap() else mapOf(
        "learnedCapNativeBackend" to 1L,
        "learnedMetadataPendingRows" to retiredMetadataCount.toLong(),
        "learnedCapCalls" to capCalls,
        "learnedCapWork" to capWork,
        "learnedCapVisits" to capVisits,
        "learnedCapBoundaryDefers" to capBoundaryDefers,
        "learnedCapPreparationDefers" to capPreparationDefers,
        "learnedCapPendingReplayCalls" to capPendingReplayCalls,
        "learnedCapRankedPasses" to capRanking.passes,
        "learnedCapPermanentClauses" to permanentCount.toLong(),
        "learnedCapRetainedSlots" to learnedCount.toLong(),
        "learnedCapRetiredSlots" to retiredCount.toLong(),
        "learnedCapActiveClauses" to activeCount.toLong(),
    )

    /** Literals of learned clause [i] as a fresh array, for glue export and introspection. */
    fun literalsOf(i: Int): IntArray = clauseLits(baseCount + i)

    fun literalCountOf(i: Int): Int = clauseLen(baseCount + i)

    fun literalOf(i: Int, k: Int): Int = litOf(baseCount + i, k)

    fun variableOf(i: Int, k: Int): Int = Lit.variable(litOf(baseCount + i, k))

    fun forEachBinaryPartner(lit: Int, action: (Int) -> Unit) {
        val entries = watchEntries[lit] ?: return
        for (i in 0 until watchSizes[lit]) {
            state.work++
            val h = entries[i].toInt()
            if (isRetired(h) || clauseLen(h) != 2) continue
            val first = litOf(h, 0)
            action(if (first == lit) litOf(h, 1) else first)
        }
    }

    /** Mark learned clause forced by factor id [fid] (if it is one) as reused since the last
     *  reduction — drives three-tier promotion, mirroring `PropagationState.noteLearnedUse`. */
    fun markUsed(fid: Int) {
        val i = fid - baseCount
        if (i !in 0 until learnedCount) return
        state.factorAttribution?.used(1 shl FactorFamily.Clause.ordinal)
        if (metadata[i, NativeLearnedMetadata.STAGES] and 1 == 0) {
            metadata[i, NativeLearnedMetadata.STAGES] = metadata[i, NativeLearnedMetadata.STAGES] or 1
            state.work += 3L
            consumed++
            if (metadata[i, NativeLearnedMetadata.ORIGIN] >= 0) consumedPeerImports++
            state.learned.lifecycle.note(LearnedKind.Clause, LearnedStage.Consumed)
            state.factorAttribution?.note(1 shl FactorFamily.Clause.ordinal, FactorAttribution.Metric.Consumed)
        }
        if (!usedOf(i)) {
            val index = defaultCapIndex
            if (index?.recordsUses == true && !retiredOf(i) && metadata[i, NativeLearnedMetadata.PERMANENT] == 0) {
                chargeCapMutation(index, 8L)
                index.recordUse(i)
            }
            importUses.note(metadata[i, NativeLearnedMetadata.ORIGIN])
        }
        metadata[i, NativeLearnedMetadata.STAGES] = metadata[i, NativeLearnedMetadata.STAGES] or RECENT_USED
    }

    internal fun prepareReclamation(
        budget: ReclamationPreparation,
        retained: BooleanArray,
        keep: (Int, Int) -> Boolean,
    ) = withOwnershipBudget(budget) { prepareRetained(budget, retained, keep) }

    private fun prepareRetained(
        budget: ReclamationPreparation,
        retained: BooleanArray,
        keep: (Int, Int) -> Boolean,
    ) {
        for (i in 0 until learnedCount) {
            budget.visit()
            retained[i] = retained[i] || (!retiredOf(i) &&
                (metadata[i, NativeLearnedMetadata.PERMANENT] == 1 || keep(i, metadata[i, NativeLearnedMetadata.LBD])))
        }
        for (i in recheckHead until rechecks.size) {
            budget.visit()
            val index = rechecks[i] - baseCount
            if (!retiredOf(index) && metadata[index, NativeLearnedMetadata.STAGES] and 1 == 0) retained[index] = true
        }
        learnedPayloads.prepareRemap(budget)
        metadata.prepareRemap(budget)
        budget.atomic(1L)
        defaultCapIndex?.let { budget.atomic(it.resetWork + learnedCount.toLong() * 8L) }
        for (column in listOf(learnedUnits, rechecks, capCandidates)) {
            budget.atomic(column.backingData.size.toLong(), column.backingData.size.toLong() * Int.SIZE_BYTES)
        }
        for (entries in watchEntries) {
            budget.visit()
            if (entries == null) continue
            budget.atomic(entries.primitiveBytes + entries.referenceSlots,
                entries.primitiveBytes + entries.referenceSlots * Long.SIZE_BYTES)
        }
        budget.ready()
    }

    fun forget(retained: BooleanArray) {
        // old learned index -> new learned index, or -1 if dropped.
        val remap = IntArray(learnedCount)
        state.work += learnedCount
        var survivors = 0
        for (i in 0 until learnedCount) {
            state.work++
            remap[i] = if (retained[i]) survivors++ else -1
        }
        if (survivors == learnedCount) return
        state.work += state.selectedRetirements.remapWork
        state.selectedRetirements.remap(remap)

        for (i in 0 until learnedCount) if (remap[i] < 0) {
            state.work += 8L
            if (!retiredOf(i)) state.learned.lifecycle.note(LearnedKind.Clause, LearnedStage.Retired)
            state.work += 24L
            learnedPayloads.release(i)
        }
        state.remapReasons(state.boolReason, remap, baseCount)
        var queued = 0
        for (i in recheckHead until rechecks.size) {
            state.work++
            val next = remap[rechecks[i] - baseCount]
            if (next >= 0) rechecks[queued++] = baseCount + next
        }
        rechecks.truncateTo(queued)
        recheckHead = 0

        for (lit in watchEntries.indices) {
            state.work++
            val entries = watchEntries[lit] ?: continue
            state.work++
            entries.retirementEpoch = 1
            var w = 0
            for (r in 0 until watchSizes[lit]) {
                state.work++
                val entry = entries[r]
                val h = entry.toInt()
                val newH = if (h < baseCount) h else remap[h - baseCount].let { if (it < 0) -1 else baseCount + it }
                if (newH < 0) continue
                entries[w++] = packWatch(newH, (entry ushr 32).toInt())
            }
            watchSizes[lit] = w
            state.work += entries.truncationWork(w)
            entries.truncateTo(w)
            if (w == 0) watchEntries[lit] = null
        }

        watchCleanupLiteral = 0
        watchCleanupIndex = 0
        watchCleanupScan = 0
        watchCleanupEpoch = -1
        state.work++
        watchCleanupSparse = false

        // A survivor's destination precedes its source, so moving references cannot overwrite unread rows.
        permanentCount = 0
        peerImports = 0
        consumedPeerImports = 0
        consumed = 0
        retiredCount = 0
        state.work += metadata.resetRetirements()
        retiredMetadataCount = 0
        capCandidates.clear()
        capRanking.reset()
        defaultCapIndex?.let {
            state.work += it.resetWork
            it.reset()
        }
        for (i in 0 until learnedCount) {
            state.work++
            val target = remap[i]
            if (target < 0) continue
            state.work += learnedPayloads.move(i, target)
            state.work += metadata.move(i, target)
            if (defaultCapIndex != null && !retiredOf(target)) {
                state.work += 8L
                metadata[target, NativeLearnedMetadata.STAGES] =
                    metadata[target, NativeLearnedMetadata.STAGES] and (CAP_KEEP or CAP_CLASSIFIED).inv()
            }
            val peer = metadata[target, NativeLearnedMetadata.ORIGIN] >= 0
            if (peer) peerImports++
            if (metadata[target, NativeLearnedMetadata.STAGES] and 1 != 0) {
                consumed++
                if (peer) consumedPeerImports++
            }
            if (metadata[target, NativeLearnedMetadata.PERMANENT] == 1) permanentCount++
            if (retiredOf(target)) {
                retiredCount++
                state.work += 8L
                enqueueRetiredMetadata(target)
            } else if (metadata[target, NativeLearnedMetadata.PERMANENT] == 0) {
                state.work++
                capCandidates.add(target)
            }
        }
        state.work += metadata.truncateTo(survivors)
        state.work += learnedPayloads.trimTo(survivors)
        learnedCount = survivors
        learnedUnits.clear()
        for (i in 0 until learnedCount) {
            state.work++
            if (!retiredOf(i) && clauseLen(baseCount + i) == 1) learnedUnits.add(baseCount + i)
        }
        for (column in listOf(learnedUnits, rechecks, capCandidates)) {
            state.work += column.learnedTrimmingWork
            column.trim()
        }
    }

    private fun pinUnit(h: Int, unitIdx: Int): Boolean {
        state.currentFactor = h
        state.currentLevel = state.levelToDecisionVar.size
        if (!state.pinLit(litOf(h, unitIdx), antecedentsExcept(h, unitIdx))) {
            recordConflict(h)
            return false
        }
        return true
    }

    fun onRetraction() {
        unitsPending = learnedUnits.size > 0
        pendingConflict = false
    }

    fun recheckAfterUndo(fid: Int) {
        val index = fid - baseCount
        if (index !in 0 until learnedCount || retiredOf(index) ||
            metadata[index, NativeLearnedMetadata.STAGES] and 2 != 0
        ) return
        metadata[index, NativeLearnedMetadata.STAGES] = metadata[index, NativeLearnedMetadata.STAGES] or 2
        rechecks.add(fid)
    }

    private fun recheck(cancellation: Cancellation): Boolean {
        while (recheckHead < rechecks.size) {
            if (cancellation !== Cancellation.Never && cancellation()) {
                state.runCancelled = true
                return false
            }
            val h = rechecks[recheckHead]
            var unit = -1
            var open = 0
            for (k in 0 until clauseLen(h)) {
                state.work++
                if (litTrue(litOf(h, k))) {
                    open = 2
                    break
                }
                if (!litFalse(litOf(h, k))) {
                    unit = k
                    if (++open == 2) break
                }
            }
            if (open == 0) {
                recordConflict(h)
                return false
            }
            metadata[h - baseCount, NativeLearnedMetadata.STAGES] =
                metadata[h - baseCount, NativeLearnedMetadata.STAGES] and 2.inv()
            recheckHead++
            if (open == 1 && !pinUnit(h, unit)) return false
        }
        rechecks.clear()
        recheckHead = 0
        return true
    }

    /**
     * Propagate to fixpoint. On the first [allFactors] call (the session bake) the base unit clauses
     * are pinned at root and the sweep starts from the trail head; afterwards each call resumes from
     * [bcpHead] over the pins added since. Returns the conflicting clause's decision levels, or null
     * at a clean fixpoint.
     */
    fun propagate(allFactors: Boolean, cancellation: Cancellation): IntArray? {
        if (pendingConflict) {
            pendingConflict = false
            return state.conflictLevels
        }
        state.conflictSeedFactors.clear()
        if (!recheck(cancellation)) return if (state.runCancelled) null else state.conflictLevels
        if (unitsPending) {
            unitsPending = false
            for (i in 0 until learnedUnits.size) {
                val h = learnedUnits[i]
                if (litFalse(litOf(h, 0))) {
                    unitsPending = true
                    recordConflict(h)
                    return state.conflictLevels
                }
                if (!litTrue(litOf(h, 0)) && !pinUnit(h, 0)) {
                    unitsPending = true
                    return state.conflictLevels
                }
            }
        }
        if (allFactors && !initialized) {
            initialized = true
            state.currentLevel = 0
            for (i in 0 until baseUnits.size) {
                val h = baseUnits[i]
                val lit = litOf(h, 0)
                if (litFalse(lit)) {
                    recordConflict(h)
                    return state.conflictLevels
                }
                if (!litTrue(lit)) {
                    state.currentFactor = h
                    if (!state.pinLit(lit, null)) {
                        recordConflict(h)
                        return state.conflictLevels
                    }
                }
            }
            bcpHead = 0
        }
        val pollable = cancellation !== Cancellation.Never
        var fireCount = 0
        val trail = state.boolPinOrder
        while (bcpHead < trail.size) {
            if (pollable) {
                if ((fireCount and CANCEL_POLL_MASK) == 0 && cancellation()) {
                    state.runCancelled = true
                    return null
                }
                fireCount++
            }
            val v = trail[bcpHead]
            bcpHead++
            val falseLit = Lit.make(v, !state.boolValueAt(v))
            if (!propagateFalse(falseLit)) return state.conflictLevels
        }
        return null
    }

    /** Process every clause watching [falseLit] (which just became false). Returns false on conflict. */
    private fun propagateFalse(falseLit: Int): Boolean {
        val entries = watchEntries[falseLit] ?: return true
        var wi = 0
        var liveScan = 0
        while (wi < watchSizes[falseLit]) {
            state.work++
            val entry = entries[wi]
            val h = entry.toInt()
            if (isRetired(h)) {
                // Unprocessed slots before the scan cursor contain only retired postings.
                val next = nextLiveWatch(falseLit, maxOf(wi + 1, liveScan))
                liveScan = next + 1
                if (next < watchSizes[falseLit]) {
                    val rewind = falseLit == watchCleanupLiteral && wi < watchCleanupIndex
                    val cleanupIndex = if (rewind) wi else watchCleanupIndex
                    val resetScan = falseLit == watchCleanupLiteral && watchCleanupReadIndex > cleanupIndex
                    state.work += 3L + (if (rewind) 1L else 0L) + (if (resetScan) 1L else 0L)
                    entries[wi] = entries[next]
                    entries[next] = entry
                    if (rewind) watchCleanupIndex = wi
                    if (resetScan) watchCleanupScan = cleanupIndex
                } else {
                    while (watchSizes[falseLit] > wi) {
                        state.work += watchRemovalWork(falseLit, watchSizes[falseLit] - 1)
                        removeWatchAt(falseLit, watchSizes[falseLit] - 1)
                    }
                }
                continue
            }
            val blocker = (entry ushr 32).toInt()
            if (litTrue(blocker)) {
                wi++
                continue
            }
            val baseClause = h < baseCount
            val lits = if (baseClause) arena.lits else learnedPayloads[h - baseCount]
            val start = if (baseClause) arena.start(h) else 0
            val len = if (baseClause) arena.length(h) else lits.size
            val i0 = watchPosition(h, 0)
            val i1 = watchPosition(h, 1)
            val slot0IsFalse = lits[start + i0] == falseLit
            val lIdx = if (slot0IsFalse) i0 else i1
            val otherIdx = if (slot0IsFalse) i1 else i0
            val otherLit = lits[start + otherIdx]
            if (litTrue(otherLit)) {
                entries[wi] = packWatch(h, otherLit) // refresh the blocker to the satisfying literal
                wi++
                continue
            }
            val scanStart = if (baseClause) 0 else metadata[h - baseCount, NativeLearnedMetadata.SCAN]
            val replacement = findNonFalse(lits, start, len, lIdx, otherIdx, scanStart)
            if (replacement >= 0) {
                trimRetiredWatchTail(falseLit, wi)
                if (!baseClause) {
                    metadata[h - baseCount, NativeLearnedMetadata.SCAN] =
                        if (replacement + 1 == len) 0 else replacement + 1
                }
                val newLit = lits[start + replacement]
                setWatchPosition(h, if (slot0IsFalse) 0 else 1, replacement)
                state.work += watchRemovalWork(falseLit, wi)
                removeWatchAt(falseLit, wi) // swap-pop; re-process wi without advancing
                addWatch(newLit, h, otherLit)
                continue
            }
            if (litFalse(otherLit)) {
                recordConflict(h)
                return false
            }
            if (!pinUnit(h, otherIdx)) return false
            wi++
        }
        return true
    }

    /** A non-false literal index other than the two watched ([lIdx], [otherIdx]); -1 if none. */
    private fun findNonFalse(lits: IntArray, start: Int, len: Int, lIdx: Int, otherIdx: Int, scanStart: Int): Int {
        if (len == 2) return -1
        if (len == 3) {
            val remaining = 3 - lIdx - otherIdx
            return if (!litFalse(lits[start + remaining])) remaining else -1
        }
        for (k in scanStart until len) {
            if (k == lIdx || k == otherIdx) continue
            if (!litFalse(lits[start + k])) return k
        }
        // Backtracking can reopen a prefix that an earlier scan passed while every literal was false.
        for (k in 0 until scanStart) {
            if (k == lIdx || k == otherIdx) continue
            if (!litFalse(lits[start + k])) return k
        }
        return -1
    }

    private companion object {
        const val RETIRED = 4
        const val RECENT_USED = 8
        const val CAP_KEEP = 16
        const val CAP_CLASSIFIED = 32
        // Poll the deadline every 4096 propagated literals.
        const val CANCEL_POLL_MASK = 0xFFF
    }
}
