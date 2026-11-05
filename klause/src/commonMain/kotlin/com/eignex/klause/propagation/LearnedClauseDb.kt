package com.eignex.klause.propagation

import com.eignex.klause.solver.search.LearnedKind
import com.eignex.klause.solver.search.LearnedLifecycle
import com.eignex.klause.solver.search.LearnedStage
import com.eignex.klause.util.OriginCounts
import com.eignex.util.collections.IntArrayList

/**
 * Learned-constraint database for [PropagationState]: the constraints learned during search plus the
 * parallel policy columns the three-tier reduction and forgetting passes read. Registration,
 * lookup and pruning live in `ClauseDb.kt`. Every learned constraint is stored as a [Propagator] so the
 * database is agnostic to the learned-constraint kind: a [com.eignex.klause.factor.bool.ClausePropagator]
 * and a pseudo-Boolean cutting-planes propagator share the same policy columns and forgetting machinery.
 */
internal class LearnedClauseDb(
    /** Count of binary (2-literal) clauses known — original problem clauses plus learned ones.
     *  Gates binary-resolution minimization, which is a no-op without binary clauses.
     *  Over-approximates after forgetting (never decremented), which only costs a harmless no-op
     *  pass — never correctness. */
    var binaryClauseCount: Int,
    initializeRows: Boolean = true,
    rowOwner: PropagationState? = null,
) {
    val lifecycle = LearnedLifecycle()
    val pendingAttach = IntArrayList()
    internal val capCandidates = IntArrayList(1)
    internal val capRanking = LearnedCapRanking(capCandidates)
    internal var permanentClauseCount = 0
    internal var permanentArithmeticCount = 0
    internal var nonClauseCount = 0
    internal var retiredCount = 0
    internal var watchCleanupLiteral = 0
    internal var watchCleanupIndex = 0
    internal var sweptRetirements = 0
    internal var sweepingRetirements = -1
    internal var deferredWatchTrimWork = 0L
    internal var deferredWatchTrimScratch = 0L
    internal var capCalls = 0L
    internal var capWork = 0L
    internal var capVisits = 0L
    internal var capBoundaryDefers = 0L
    internal var capPreparationDefers = 0L
    private var capAllocationCharged = false
    val units = IntArrayList()
    var unitsPending = false

    private val rowStore = if (initializeRows) lazyOf(GeneralLearnedRows(rowOwner))
        else lazy(LazyThreadSafetyMode.NONE) { GeneralLearnedRows(rowOwner) }
    val store: GeneralLearnedRows get() = rowStore.value
    val initializedStore: GeneralLearnedRows? get() = if (rowStore.isInitialized()) store else null
    internal var integerRows: LearnedIntegerRows? = null
    internal val retiredMetadataHead: Int get() = initializedStore?.retiredHeadIndex ?: -1
    internal var releasedPeerImports = 0
    internal var releasedConsumedPeerImports = 0
    internal var releasedConsumed = 0

    /** Uses of imported clauses, by origin. */
    val importUses = OriginCounts()

    /** Number of learned clauses. */
    val size: Int get() = initializedStore?.size ?: 0

    internal val activeCount: Int get() = size - retiredCount

    internal fun retiredOf(index: Int): Boolean = store[index, GeneralLearnedRows.STAGES] and RETIRED != 0
    internal fun usedOf(index: Int): Boolean = store[index, GeneralLearnedRows.STAGES] and RECENT_USED != 0
    internal fun markUsed(index: Int) {
        store[index, GeneralLearnedRows.STAGES] = store[index, GeneralLearnedRows.STAGES] or RECENT_USED
    }
    internal fun clearUsed(index: Int) {
        if (retiredOf(index)) return
        store[index, GeneralLearnedRows.STAGES] = store[index, GeneralLearnedRows.STAGES] and RECENT_USED.inv()
    }

    internal fun trackCapObject(index: Int, isPermanent: Boolean, kind: LearnedKind = LearnedKind.Clause): Long {
        var work = 1L
        if (!capAllocationCharged) {
            work++
            capAllocationCharged = true
        }
        if (isPermanent) {
            if (kind == LearnedKind.Clause) permanentClauseCount++ else permanentArithmeticCount++
        } else {
            if (capCandidates.size == capCandidates.backingData.size) work += capCandidates.size * 3L
            capCandidates.add(index)
        }
        return work
    }

    internal fun resetCapIndex(): Long {
        permanentClauseCount = 0
        permanentArithmeticCount = 0
        nonClauseCount = 0
        retiredCount = 0
        releasedPeerImports = 0
        releasedConsumedPeerImports = 0
        releasedConsumed = 0
        watchCleanupLiteral = 0
        watchCleanupIndex = 0
        sweptRetirements = 0
        sweepingRetirements = -1
        deferredWatchTrimWork = 0L
        deferredWatchTrimScratch = 0L
        capCandidates.clear()
        capRanking.reset()
        return initializedStore?.resetRetirements() ?: 0L
    }

    internal fun retire(index: Int, kind: LearnedKind) {
        check(store[index, GeneralLearnedRows.PERMANENT] == 0 && !retiredOf(index))
        store[index, GeneralLearnedRows.STAGES] = store[index, GeneralLearnedRows.STAGES] or RETIRED
        retiredCount++
        enqueueRetiredMetadata(index)
        if (kind != LearnedKind.Clause) nonClauseCount--
        lifecycle.note(kind, LearnedStage.Retired)
    }

    internal fun enqueueRetiredMetadata(index: Int) = store.enqueueRetired(index)

    internal fun releaseRetiredMetadata() {
        val peer = store.retiredValue(GeneralLearnedRows.ORIGIN) >= 0
        if (peer) releasedPeerImports++
        if (store.retiredValue(GeneralLearnedRows.STAGES) and 2 != 0) {
            releasedConsumed++
            if (peer) releasedConsumedPeerImports++
        }
        store.releaseRetired()
    }

    internal fun capWindowStatistics(): Map<String, Long> = if (capCalls == 0L) emptyMap() else mapOf(
        "learnedCapGeneralBackend" to 1L,
        "learnedCapCalls" to capCalls,
        "learnedCapWork" to capWork,
        "learnedCapVisits" to capVisits,
        "learnedCapBoundaryDefers" to capBoundaryDefers,
        "learnedCapFloorDefers" to 0L,
        "learnedCapPreparationDefers" to capPreparationDefers,
        "learnedCapRankedPasses" to capRanking.passes,
        "learnedCapPermanentClauses" to permanentClauseCount.toLong(),
        "learnedCapPermanentArithmetic" to permanentArithmeticCount.toLong(),
        "learnedCapNonClauseFloor" to 0L,
        "learnedCapActiveArithmetic" to nonClauseCount.toLong(),
        "learnedCapRetainedSlots" to size.toLong(),
        "learnedCapRetiredSlots" to retiredCount.toLong(),
        "learnedCapActiveClauses" to activeCount.toLong(),
    )

    private companion object {
        const val RETIRED = 8
        const val RECENT_USED = 32
    }
}

/** The origin of a learned clause the session derived itself rather than imported. */
internal const val OWN_ORIGIN = -1

/** The origin of an order-atom channeling clause: structural, rebuilt by every session that allocates the atoms, so
 *  never shared with another arm. */
internal const val CHANNELING_ORIGIN = -2
