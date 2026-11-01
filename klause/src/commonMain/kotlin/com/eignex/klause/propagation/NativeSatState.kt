package com.eignex.klause.propagation

import com.eignex.klause.ir.Lit
import com.eignex.klause.solver.search.LearnedKind
import com.eignex.klause.solver.search.LearnedStage
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.EmptyLongArray
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.LongArrayList
import com.eignex.klause.util.OriginCounts
import com.eignex.klause.util.SharingMemoryObservation

/**
 * Two-watched-literal BCP (Moskewicz et al., "Chaff: engineering an efficient SAT solver", DAC 2001,
 * https://doi.org/10.1145/378239.379017) for arena-packed pure-Boolean problems. A trail-pointer literal sweep
 * avoids factor dispatch and order-literal atoms. The general lane's trail, pin machinery,
 * antecedents and [ConflictAnalyzer] remain shared.
 *
 * Watch positions `watchPos(2h)` and `watchPos(2h+1)` index the two watched literals of clause `h`;
 * `watchEntries(L)` lists clauses watching `L`. Watches are rebuilt per session and may drift
 * across backtracking. Base literals stay in the immutable [ClauseArena]; learned clauses use
 * a growable side buffer. Relocation moves indices and leaves the arena shareable read-only.
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

    // Learned clauses appended during search: literals concatenated in [learnedLits], clause `i`
    // starting at learnedStarts[i]. Clause handle `h >= baseCount` decodes to learned index
    // `h - baseCount`. Base handles `[0, baseCount)` index straight into [arena].
    private val learnedLits = IntArrayList()
    private val learnedStarts = IntArrayList()
    private var learnedCount = 0
    private var permanentCount = 0
    private var retiredCount = 0
    private val capCandidates = IntArrayList(1)
    private val capRanking = LearnedCapRanking(capCandidates)
    private var capCalls = 0L
    private var capWork = 0L
    private var capVisits = 0L
    private var capBoundaryDefers = 0L
    private var capPreparationDefers = 0L
    private var capPendingReplayCalls = 0L

    // Per-learned-clause policy columns, parallel to the learned index — the native mirror of
    // LearnedClauseDb's forgetting/three-tier metadata. `tier` holds a [ClauseTier] ordinal;
    // `used` is a 0/1 reuse flag since the last reduction; `permanent` clauses survive every forget.
    private val learnedLbd = IntArrayList()
    private val learnedPermanent = IntArrayList()
    private val learnedTier = IntArrayList()
    private val learnedUsed = IntArrayList()
    private val learnedBoolReasons = IntArrayList(1)
    private val learnedAdmissions = LongArrayList(1)
    private val lifecycleStages = IntArrayList()
    private val learnedUnits = IntArrayList()
    private var unitsPending = false
    private val rechecks = IntArrayList()
    private var recheckHead = 0

    // The origin an imported learned clause came from, parallel to the learned columns; see [OriginCounts].
    private val learnedOrigin = IntArrayList()
    private val learnedScanPos = IntArrayList()

    /** Uses of imported learned clauses, by origin. */
    val importUses = OriginCounts()

    // Keep learned positions separate so the first learned clause cannot double the base allocation.
    // Single-literal clauses are examined directly after retraction instead of watching a literal.
    private val watchPos = IntArray(2 * baseCount)
    private val learnedWatchPos = IntArrayList()

    // Pack each handle and blocker into one word. Exact initial capacities avoid millions of empty
    // list objects and growth copies; literals with no watcher share the empty array.
    private val watchSizes = IntArray(2 * numBoolVars)
    private val watchEntries: Array<LongArray> = run {
        for (h in 0 until baseCount) {
            if (arena.length(h) < 2) continue
            watchSizes[arena.lits[arena.start(h)]]++
            watchSizes[arena.lits[arena.start(h) + 1]]++
        }
        Array(watchSizes.size) { lit ->
            if (watchSizes[lit] == 0) EmptyLongArray else LongArray(watchSizes[lit])
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

    private fun learnedEnd(i: Int): Int = if (i + 1 < learnedCount) learnedStarts[i + 1] else learnedLits.size

    private fun clauseLen(h: Int): Int =
        if (h < baseCount) arena.length(h) else learnedEnd(h - baseCount) - learnedStarts[h - baseCount]

    private fun litOf(h: Int, k: Int): Int =
        if (h < baseCount) arena.lits[arena.start(h) + k] else learnedLits[learnedStarts[h - baseCount] + k]

    private fun packWatch(handle: Int, blocker: Int): Long =
        (blocker.toLong() shl 32) or (handle.toLong() and 0xFFFFFFFFL)

    private fun watchPosition(h: Int, slot: Int): Int =
        if (h < baseCount) watchPos[2 * h + slot] else learnedWatchPos[2 * (h - baseCount) + slot]

    private fun setWatchPosition(h: Int, slot: Int, position: Int) {
        if (h < baseCount) watchPos[2 * h + slot] = position else learnedWatchPos[2 * (h - baseCount) + slot] = position
    }

    private fun addWatch(lit: Int, handle: Int, blocker: Int) {
        var entries = watchEntries[lit]
        val size = watchSizes[lit]
        if (size == entries.size) {
            entries = entries.copyOf((entries.size * 2).coerceAtLeast(2))
            watchEntries[lit] = entries
        }
        entries[size] = packWatch(handle, blocker)
        watchSizes[lit] = size + 1
    }

    /** Swap-pop the watcher at [idx] in literal [lit]'s lists. The caller re-processes [idx]. */
    private fun removeWatchAt(lit: Int, idx: Int) {
        val entries = watchEntries[lit]
        val last = watchSizes[lit] - 1
        entries[idx] = entries[last]
        watchSizes[lit] = last
    }

    /** The clause's literals as a fresh array (the seed reason for conflict analysis). */
    private fun clauseLits(h: Int): IntArray {
        val lits = if (h < baseCount) arena.lits else learnedLits.backingData
        val start = if (h < baseCount) arena.start(h) else learnedStarts[h - baseCount]
        return lits.copyOfRange(start, start + clauseLen(h)).also {
            state.factorAttribution?.remember(it, 1 shl FactorFamily.Clause.ordinal)
        }
    }

    /** The clause's literals except index [skip] — the antecedent set recorded for a forced unit,
     *  matching the general clause propagator's reason exactly. Null for a single-literal clause. */
    private fun antecedentsExcept(h: Int, skip: Int): IntArray? {
        val len = clauseLen(h)
        if (len <= 1) return null
        val lits = if (h < baseCount) arena.lits else learnedLits.backingData
        val start = if (h < baseCount) arena.start(h) else learnedStarts[h - baseCount]
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
    fun addLearned(lits: IntArray, lbd: Int, permanent: Boolean, origin: Int = OWN_ORIGIN): Int {
        state.learned.lifecycle.note(LearnedKind.Clause, LearnedStage.Constructed)
        val h = appendLearned(lits, lbd, permanent, origin)
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

    private fun appendLearned(lits: IntArray, lbd: Int, permanent: Boolean, origin: Int): Int {
        val i = learnedCount
        lifecycleStages.add(0)
        learnedStarts.add(learnedLits.size)
        learnedLits.addAll(lits)
        learnedLbd.add(lbd)
        learnedPermanent.add(if (permanent) 1 else 0)
        state.work++
        if (i == 0) state.work++
        if (permanent) {
            permanentCount++
        } else {
            if (capCandidates.size == capCandidates.backingData.size) state.work += capCandidates.size * 3L
            capCandidates.add(i)
        }
        learnedTier.add(ClauseTier.UNSET.ordinal)
        learnedUsed.add(0)
        state.work += 1L + when {
            learnedBoolReasons.size == learnedBoolReasons.backingData.size -> learnedBoolReasons.size * 3L + 1L
            learnedBoolReasons.size == 0 -> 1L
            else -> 0L
        }
        learnedBoolReasons.add(0)
        state.work += 3L + when {
            learnedAdmissions.size.toLong() * Long.SIZE_BYTES == learnedAdmissions.allocatedBytes ->
                learnedAdmissions.size * 3L + 1L
            learnedAdmissions.size == 0 -> 1L
            else -> 0L
        }
        check(state.nextLearnedAdmissionKey < Long.MAX_VALUE)
        learnedAdmissions.add(state.nextLearnedAdmissionKey++)
        learnedOrigin.add(origin)
        learnedScanPos.add(0)
        learnedCount++
        learnedWatchPos.add(0)
        learnedWatchPos.add(0)
        return baseCount + i
    }

    // Learned-clause policy accessors: the native mirror of the LearnedClauseDb columns.

    internal fun sharingMemory(sample: SharingMemoryObservation, protected: SharingMemoryObservation.Seen<Int>) {
        var peerImports = 0L
        var consumedPeerImports = 0L
        var consumed = 0L
        for (i in 0 until learnedCount) {
            sample.step()
            val peer = learnedOrigin[i] >= 0
            val used = lifecycleStages[i] and 1 != 0
            if (learnedPermanent[i] == 1) sample.retain(protected, i)
            if (peer) peerImports++
            if (peer && used) consumedPeerImports++
            if (used) consumed++
        }
        for (i in recheckHead until rechecks.size) {
            sample.step()
            val index = rechecks[i] - baseCount
            if (lifecycleStages[index] and 1 == 0) sample.retain(protected, index)
        }
        val protectedRows = protected.size > 0
        capRanking.sharingMemory(sample, protectedRows)
        state.learned.capRanking.sharingMemory(sample, protectedRows)
        val columns = listOf(learnedStarts, learnedLbd, learnedPermanent, learnedTier, learnedBoolReasons,
            learnedUsed, lifecycleStages, learnedUnits, rechecks, learnedOrigin, learnedScanPos, learnedWatchPos,
            capCandidates, state.learned.capCandidates, state.learned.boolReasons)
        var metadata = capRanking.primitiveBytes + state.learned.capRanking.primitiveBytes
        for (keys in listOf(learnedAdmissions, state.learned.admissions)) {
            sample.step()
            metadata += keys.allocatedBytes + Int.SIZE_BYTES
            sample.owned(keys, keys.allocatedBytes + Int.SIZE_BYTES, protectedRows)
            sample.ownedReferences(keys, 1L, protectedRows)
        }
        for (column in columns) {
            sample.step(); metadata += column.backingData.size.toLong() * Int.SIZE_BYTES
            sample.owned(column.backingData, column.backingData.size.toLong() * Int.SIZE_BYTES, protectedRows)
        }
        sample.owned(learnedLits.backingData, learnedLits.backingData.size.toLong() * Int.SIZE_BYTES, protectedRows)
        sample.owned(arena.lits, arena.lits.size.toLong() * Int.SIZE_BYTES, true, shared = true)
        sample.owned(arena.starts, arena.starts.size.toLong() * Int.SIZE_BYTES, true, shared = true)
        sample.owned(watchSizes, watchSizes.size.toLong() * Int.SIZE_BYTES, true)
        sample.owned(watchPos, watchPos.size.toLong() * Int.SIZE_BYTES, true)
        sample.owned(baseUnits.backingData, baseUnits.backingData.size.toLong() * Int.SIZE_BYTES, true)
        sample.ownedReferences(watchEntries, watchEntries.size.toLong(), true)
        var watchBytes = 0L
        for (literal in watchEntries.indices) {
            val entries = watchEntries[literal]
            sample.step(); watchBytes += entries.size.toLong() * Long.SIZE_BYTES
            var retained = false
            for (i in 0 until watchSizes[literal]) {
                sample.step()
                val handle = entries[i].toInt()
                if (!retained) retained = handle < baseCount || sample.contains(protected, handle - baseCount)
            }
            sample.owned(entries, entries.size.toLong() * Long.SIZE_BYTES, retained)
        }
        sample.record(mapOf(
            "learnedObjects" to learnedCount.toLong(),
            "learnedRetiredSlots" to retiredCount.toLong(),
            "learnedPayloadBytes" to learnedLits.size.toLong() * Int.SIZE_BYTES,
            "learnedArenaCapacityBytes" to learnedLits.backingData.size.toLong() * Int.SIZE_BYTES,
            "learnedMetadataCapacityBytes" to metadata,
            "learnedCapIndexStateBytes" to capRanking.modeledBytes + state.learned.capRanking.modeledBytes,
            "sessionWatchCapacityBytes" to watchBytes +
                (watchSizes.size.toLong() + watchPos.size) * Int.SIZE_BYTES +
                baseUnits.backingData.size.toLong() * Int.SIZE_BYTES,
            "rootRetiredSourceCapacityBytes" to (retiredRootSources?.allocatedBytes ?: 0L),
            "pendingAttachments" to (rechecks.size - recheckHead).toLong(),
            "importUseCapacityBytes" to importUses.allocatedBytes,
            "retainedPeerImports" to peerImports,
            "retainedConsumedPeerImports" to consumedPeerImports,
            "retainedConsumed" to consumed,
        ))
        retiredRootSources?.let { sample.owned(it, it.allocatedBytes, true) }
        sample.owned(importUses, importUses.allocatedBytes)
    }

    val count: Int get() = learnedCount
    val activeCount: Int get() = learnedCount - retiredCount
    fun retiredOf(i: Int): Boolean = lifecycleStages[i] and RETIRED != 0
    fun lbdOf(i: Int): Int = learnedLbd[i]
    fun permanentOf(i: Int): Boolean = learnedPermanent[i] == 1
    fun originOf(i: Int): Int = learnedOrigin[i]
    fun admissionKeyOf(i: Int): Long = learnedAdmissions[i]
    fun tierOf(i: Int): ClauseTier = ClauseTier.entries[learnedTier[i]]
    fun setTierOf(i: Int, tier: ClauseTier) {
        learnedTier[i] = tier.ordinal
    }
    fun usedOf(i: Int): Boolean = learnedUsed[i] == 1
    fun clearUsed(i: Int) {
        learnedUsed[i] = 0
    }

    internal fun maintainCapWindow(
        cap: Int,
        maxWork: Long,
        maxScratchBytes: Long,
        cancelled: () -> Boolean,
        canDrop: (Int) -> Boolean,
        keep: (Int) -> Boolean,
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
            capRanking.advance(cap, permanentCount, budget, capPolicy(budget, canDrop, keep))
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
    ): LearnedCapPolicy = object : LearnedCapPolicy {
        override fun alive(index: Int): Boolean = !retiredOf(index)
        private var prepared = -1
        private var protected = false
        override fun lbd(index: Int): Int = learnedLbd[index]
        override fun keep(index: Int): Boolean {
            val retained = retain(index)
            return retained || protected
        }
        override fun visited() { capVisits++ }
        override fun prepare(index: Int): Boolean {
            val price = CAP_ROW_POLICY_WORK + 8L
            if (budget.remainingWork < CAP_ROW_POLICY_WORK) budget.visit(CAP_ROW_POLICY_WORK)
            if (price > budget.remainingWork) {
                budget.visit(16L - CAP_ROW_INSPECTION_WORK)
                return false
            }
            protected = capProtected(index, budget, CAP_ROW_POLICY_WORK)
            prepared = index
            return true
        }
        override fun retire(index: Int): Boolean {
            if (!mayDrop(index)) return false
            val pinned = if (index == prepared) protected else capProtected(index, budget, 0L)
            if (pinned) return false
            budget.visit(0L)
            lifecycleStages[index] = lifecycleStages[index] or RETIRED
            state.learned.lifecycle.note(LearnedKind.Clause, LearnedStage.Retired)
            retiredCount++
            return true
        }
    }

    private fun capProtected(index: Int, budget: ReclamationPreparation, work: Long): Boolean {
        val length = literalCountOf(index)
        budget.visit(work + 8L)
        budget.visit(0L)
        return length <= 1 || lifecycleStages[index] and 2 != 0 || learnedBoolReasons[index] > 0
    }

    internal fun noteBoolReason(factor: Int, delta: Int) {
        val index = factor - baseCount
        if (index !in 0 until learnedCount) return
        state.work += 2L
        val count = learnedBoolReasons[index] + delta
        check(count >= 0)
        learnedBoolReasons[index] = count
    }

    internal fun retireSelectedLearnedClause(index: Int, budget: ReclamationPreparation): LearnedRetirementResult {
        if (!initialized || pendingConflict || bcpHead != state.boolPinOrder.size || state.runCancelled) {
            return LearnedRetirementResult(LearnedRetirementStatus.Deferred)
        }
        if (index !in 0 until learnedCount) return LearnedRetirementResult(LearnedRetirementStatus.AlreadyGone,
            payloadReleased = true, physicalReleased = true)
        if (retiredOf(index)) return LearnedRetirementResult(LearnedRetirementStatus.AlreadyGone)
        budget.visit(16L)
        if (learnedPermanent[index] == 1 || literalCountOf(index) <= 1 || learnedBoolReasons[index] > 0 ||
            lifecycleStages[index] and 2 != 0
        ) return LearnedRetirementResult(LearnedRetirementStatus.ProtectedFloor)
        budget.visit(16L)
        budget.visit(0L)
        lifecycleStages[index] = lifecycleStages[index] or RETIRED
        state.learned.lifecycle.note(LearnedKind.Clause, LearnedStage.Retired)
        retiredCount++
        capRanking.invalidateRetiredHandles()
        return LearnedRetirementResult(LearnedRetirementStatus.Retired)
    }

    internal fun capWindowStatistics(): Map<String, Long> = if (capCalls == 0L) emptyMap() else mapOf(
        "learnedCapNativeBackend" to 1L,
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
        val entries = watchEntries[lit]
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
        if (lifecycleStages[i] and 1 == 0) {
            lifecycleStages[i] = lifecycleStages[i] or 1
            state.learned.lifecycle.note(LearnedKind.Clause, LearnedStage.Consumed)
            state.factorAttribution?.note(1 shl FactorFamily.Clause.ordinal, FactorAttribution.Metric.Consumed)
        }
        if (learnedUsed[i] == 0) importUses.note(learnedOrigin[i])
        learnedUsed[i] = 1
    }

    internal fun prepareReclamation(
        budget: ReclamationPreparation,
        retained: BooleanArray,
        keep: (Int, Int) -> Boolean,
    ) {
        for (i in 0 until learnedCount) {
            budget.visit()
            retained[i] = retained[i] || (!retiredOf(i) && (learnedPermanent[i] == 1 || keep(i, learnedLbd[i])))
        }
        for (i in recheckHead until rechecks.size) {
            budget.visit()
            val index = rechecks[i] - baseCount
            if (lifecycleStages[index] and 1 == 0) retained[index] = true
        }
        budget.atomic(learnedLits.backingData.size.toLong(), learnedLits.backingData.size.toLong() * Int.SIZE_BYTES)
        budget.atomic(learnedAdmissions.allocatedBytes / Long.SIZE_BYTES, learnedAdmissions.allocatedBytes)
        for (column in listOf(learnedStarts, learnedLbd, learnedPermanent, learnedTier, learnedUsed, learnedBoolReasons,
            lifecycleStages, learnedUnits, rechecks, learnedOrigin, learnedScanPos, learnedWatchPos, capCandidates)) {
            budget.atomic(column.backingData.size.toLong(), column.backingData.size.toLong() * Int.SIZE_BYTES)
        }
        for (entries in watchEntries) {
            budget.visit()
            budget.atomic(entries.size.toLong() + 1L, entries.size.toLong() * Long.SIZE_BYTES)
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
            state.work++
            if (!retiredOf(i)) state.learned.lifecycle.note(LearnedKind.Clause, LearnedStage.Retired)
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
            val entries = watchEntries[lit]
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
            if (entries.size > w) watchEntries[lit] = entries.copyOf(w)
        }

        // A survivor's destination precedes its source, so packing forward cannot overwrite an
        // unread clause. Keep the original starts and buffer size live until every source is read.
        var literalWrite = 0
        permanentCount = 0
        retiredCount = 0
        capCandidates.clear()
        capRanking.reset()
        for (i in 0 until learnedCount) {
            state.work++
            val target = remap[i]
            if (target < 0) continue
            val start = learnedStarts[i]
            val end = learnedEnd(i)
            learnedStarts[target] = literalWrite
            for (k in start until end) {
                state.work++
                learnedLits[literalWrite++] = learnedLits[k]
            }
            lifecycleStages[target] = lifecycleStages[i]
            learnedLbd[target] = learnedLbd[i]
            learnedPermanent[target] = learnedPermanent[i]
            learnedTier[target] = learnedTier[i]
            learnedUsed[target] = learnedUsed[i]
            state.work++
            learnedBoolReasons[target] = learnedBoolReasons[i]
            state.work++
            learnedAdmissions[target] = learnedAdmissions[i]
            learnedOrigin[target] = learnedOrigin[i]
            learnedScanPos[target] = learnedScanPos[i]
            learnedWatchPos[2 * target] = learnedWatchPos[2 * i]
            learnedWatchPos[2 * target + 1] = learnedWatchPos[2 * i + 1]
            if (learnedPermanent[target] == 1) permanentCount++
            if (retiredOf(target)) retiredCount++
            else if (learnedPermanent[target] == 0) {
                state.work++
                capCandidates.add(target)
            }
        }
        lifecycleStages.truncateTo(survivors)
        learnedLits.truncateTo(literalWrite)
        learnedStarts.truncateTo(survivors)
        learnedLbd.truncateTo(survivors)
        learnedPermanent.truncateTo(survivors)
        learnedTier.truncateTo(survivors)
        learnedUsed.truncateTo(survivors)
        state.work += learnedBoolReasons.size.toLong() - survivors
        learnedBoolReasons.truncateTo(survivors)
        state.work += 2L + survivors.coerceAtLeast(1) * 2L
        learnedAdmissions.truncateTo(survivors)
        learnedAdmissions.trim()
        learnedOrigin.truncateTo(survivors)
        learnedScanPos.truncateTo(survivors)
        learnedWatchPos.truncateTo(2 * survivors)
        learnedCount = survivors
        learnedUnits.clear()
        for (i in 0 until learnedCount) {
            state.work++
            if (!retiredOf(i) && clauseLen(baseCount + i) == 1) learnedUnits.add(baseCount + i)
        }
        state.work += learnedBoolReasons.size.toLong() * 2L
        for (column in listOf(learnedLits, learnedStarts, learnedLbd, learnedPermanent, learnedTier, learnedBoolReasons,
            learnedUsed, lifecycleStages, learnedUnits, rechecks, learnedOrigin, learnedScanPos, learnedWatchPos,
            capCandidates)) {
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
        if (index !in 0 until learnedCount || retiredOf(index) || lifecycleStages[index] and 2 != 0) return
        lifecycleStages[index] = lifecycleStages[index] or 2
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
            lifecycleStages[h - baseCount] = lifecycleStages[h - baseCount] and 2.inv()
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
        val entries = watchEntries[falseLit]
        var wi = 0
        while (wi < watchSizes[falseLit]) {
            state.work++
            val entry = entries[wi]
            val h = entry.toInt()
            if (isRetired(h)) {
                removeWatchAt(falseLit, wi)
                continue
            }
            val blocker = (entry ushr 32).toInt()
            if (litTrue(blocker)) {
                wi++
                continue
            }
            val baseClause = h < baseCount
            val lits = if (baseClause) arena.lits else learnedLits.backingData
            val start = if (baseClause) arena.start(h) else learnedStarts[h - baseCount]
            val len = if (baseClause) arena.length(h) else learnedEnd(h - baseCount) - start
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
            val scanStart = if (baseClause) 0 else learnedScanPos[h - baseCount]
            val replacement = findNonFalse(lits, start, len, lIdx, otherIdx, scanStart)
            if (replacement >= 0) {
                if (!baseClause) learnedScanPos[h - baseCount] = if (replacement + 1 == len) 0 else replacement + 1
                val newLit = lits[start + replacement]
                setWatchPosition(h, if (slot0IsFalse) 0 else 1, replacement)
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
        // Poll the deadline every 4096 propagated literals.
        const val CANCEL_POLL_MASK = 0xFFF
    }
}
