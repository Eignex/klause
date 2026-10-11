package com.eignex.klause.propagation

import com.eignex.klause.ir.Lit
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.EmptyLongArray
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.OriginCounts

/**
 * Two-watched-literal BCP over the arena-packed clauses of a pure-Boolean problem.
 * Replaces the general [PropagationState.runToFixpoint] factor-queue loop for a
 * native-SAT-eligible propagation problem: no order-literal atoms are ever
 * materialised, so the driver degenerates to classical CDCL, and propagation runs as a trail-pointer
 * literal sweep with no per-fire virtual dispatch, factor-object dereference, or payload cast.
 *
 * Reuse boundary: this owns only the BCP inner loop and the clause watch state. The trail
 * ([PropagationState.boolPinOrder]), the pin machinery ([PropagationState.pinLit] / `pinBoolImpl`,
 * which records reasons, levels, and antecedents), 1UIP conflict analysis, VSIDS, restarts, and the
 * search driver are shared with the general lane — a forced unit records the same antecedent set (the
 * clause's other literals) the general path records, so [ConflictAnalyzer] reads it identically.
 *
 * Watches are advisory mutable state, rebuilt per session and allowed to drift across backtrack: the
 * two watched literals of clause `h` sit at within-clause indices `watchPos[2h]` / `watchPos[2h+1]`, and
 * `watchEntries[L]` lists the clauses watching literal `L`. Base-clause literals live in the shared,
 * immutable [ClauseArena]; learned clauses append into a growable side buffer. Watch relocation moves
 * indices, never the literals, so the arena stays shareable read-only.
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

    // Per-learned-clause policy columns, parallel to the learned index — the native mirror of
    // LearnedClauseDb's forgetting/three-tier metadata. `tier` holds a [ClauseTier] ordinal;
    // `used` is a 0/1 reuse flag since the last reduction; `permanent` clauses survive every forget.
    private val learnedLbd = IntArrayList()
    private val learnedPermanent = IntArrayList()
    private val learnedTier = IntArrayList()
    private val learnedUsed = IntArrayList()

    // The origin an imported learned clause came from, parallel to the learned columns; see [OriginCounts].
    private val learnedOrigin = IntArrayList()
    private val learnedScanPos = IntArrayList()

    /** Uses of imported learned clauses, by origin. */
    val importUses = OriginCounts()

    // Keep learned positions separate so the first learned clause cannot double the base allocation.
    // A single-literal clause watches nothing (its unit is pinned at root and never unassigned).
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
        return lits.copyOfRange(start, start + clauseLen(h))
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
        val h = appendLearned(lits, lbd, permanent, origin)
        val len = lits.size
        if (len == 1) {
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
        learnedStarts.add(learnedLits.size)
        learnedLits.addAll(lits)
        learnedLbd.add(lbd)
        learnedPermanent.add(if (permanent) 1 else 0)
        learnedTier.add(ClauseTier.UNSET.ordinal)
        learnedUsed.add(0)
        learnedOrigin.add(origin)
        learnedScanPos.add(0)
        learnedCount++
        learnedWatchPos.add(0)
        learnedWatchPos.add(0)
        return baseCount + i
    }

    // Learned-clause policy accessors: the native mirror of the LearnedClauseDb columns.

    val count: Int get() = learnedCount
    fun lbdOf(i: Int): Int = learnedLbd[i]
    fun permanentOf(i: Int): Boolean = learnedPermanent[i] == 1
    fun originOf(i: Int): Int = learnedOrigin[i]
    fun tierOf(i: Int): ClauseTier = ClauseTier.entries[learnedTier[i]]
    fun setTierOf(i: Int, tier: ClauseTier) {
        learnedTier[i] = tier.ordinal
    }
    fun usedOf(i: Int): Boolean = learnedUsed[i] == 1
    fun clearUsed(i: Int) {
        learnedUsed[i] = 0
    }

    /** Literals of learned clause [i] as a fresh array, for glue export and introspection. */
    fun literalsOf(i: Int): IntArray = clauseLits(baseCount + i)

    fun literalCountOf(i: Int): Int = clauseLen(baseCount + i)

    fun variableOf(i: Int, k: Int): Int = Lit.variable(litOf(baseCount + i, k))

    fun forEachBinaryPartner(lit: Int, action: (Int) -> Unit) {
        val entries = watchEntries[lit]
        for (i in 0 until watchSizes[lit]) {
            val h = entries[i].toInt()
            if (clauseLen(h) != 2) continue
            val first = litOf(h, 0)
            action(if (first == lit) litOf(h, 1) else first)
        }
    }

    /** Mark learned clause forced by factor id [fid] (if it is one) as reused since the last
     *  reduction — drives three-tier promotion, mirroring `PropagationState.noteLearnedUse`. */
    fun markUsed(fid: Int) {
        val i = fid - baseCount
        if (i !in 0 until learnedCount) return
        if (learnedUsed[i] == 0) importUses.note(learnedOrigin[i])
        learnedUsed[i] = 1
    }

    /**
     * Drop every learned clause the [keep] predicate rejects and compact the survivors, renumbering
     * learned handles contiguously. Base clauses and their watches are untouched; surviving learned
     * clauses keep their drifted watch positions, and every watch-list entry naming a learned handle is
     * remapped or removed. Called at a restart boundary to bound the learned database.
     */
    fun forget(keep: (learnedIndex: Int, lbd: Int) -> Boolean) {
        // old learned index -> new learned index, or -1 if dropped.
        val remap = IntArray(learnedCount)
        var survivors = 0
        for (i in 0 until learnedCount) {
            remap[i] = if (keep(i, learnedLbd[i])) survivors++ else -1
        }
        if (survivors == learnedCount) return // nothing dropped

        // Remap/prune watch-list entries that name a learned handle; base handles pass through.
        for (lit in watchEntries.indices) {
            val entries = watchEntries[lit]
            var w = 0
            for (r in 0 until watchSizes[lit]) {
                val entry = entries[r]
                val h = entry.toInt()
                val newH = if (h < baseCount) h else remap[h - baseCount].let { if (it < 0) -1 else baseCount + it }
                if (newH < 0) continue // dropped clause — skip
                entries[w++] = packWatch(newH, (entry ushr 32).toInt())
            }
            watchSizes[lit] = w
        }

        // A survivor's destination precedes its source, so packing forward cannot overwrite an
        // unread clause. Keep the original starts and buffer size live until every source is read.
        var literalWrite = 0
        for (i in 0 until learnedCount) {
            val target = remap[i]
            if (target < 0) continue
            val start = learnedStarts[i]
            val end = learnedEnd(i)
            learnedStarts[target] = literalWrite
            for (k in start until end) learnedLits[literalWrite++] = learnedLits[k]
            learnedLbd[target] = learnedLbd[i]
            learnedPermanent[target] = learnedPermanent[i]
            learnedTier[target] = learnedTier[i]
            learnedUsed[target] = learnedUsed[i]
            learnedOrigin[target] = learnedOrigin[i]
            learnedScanPos[target] = learnedScanPos[i]
            learnedWatchPos[2 * target] = learnedWatchPos[2 * i]
            learnedWatchPos[2 * target + 1] = learnedWatchPos[2 * i + 1]
        }
        learnedLits.truncateTo(literalWrite)
        learnedStarts.truncateTo(survivors)
        learnedLbd.truncateTo(survivors)
        learnedPermanent.truncateTo(survivors)
        learnedTier.truncateTo(survivors)
        learnedUsed.truncateTo(survivors)
        learnedOrigin.truncateTo(survivors)
        learnedScanPos.truncateTo(survivors)
        learnedWatchPos.truncateTo(2 * survivors)
        learnedCount = survivors
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
        // Poll the deadline every 4096 propagated literals — matches the general fixpoint's cadence.
        const val CANCEL_POLL_MASK = 0xFFF
    }
}
