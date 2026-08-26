package com.eignex.klause.propagation

import com.eignex.klause.ir.Lit
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet

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
 * `watchClauses[L]` lists the clauses watching literal `L`. Base-clause literals live in the shared,
 * immutable [ClauseArena]; learned clauses append into a growable side buffer. Watch relocation moves
 * indices, never the literals, so the arena stays shareable read-only.
 */
internal class NativeSatState(private val state: PropagationState) {

    private val arena = state.projection.clauseArena
    private val baseCount = arena.clauseCount
    private val numBoolVars = state.problem.numBoolVars

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

    // Within-clause indices of the two watched literals per clause handle: watchPos[2h], watchPos[2h+1].
    // A single-literal clause watches nothing (its unit is pinned at root and never unassigned).
    private val watchPos = IntArrayList()

    // Per-literal watch lists (clause handles) with a parallel blocker literal: if the blocker
    // is already true the clause is satisfied and the wake is skipped. Indexed by literal id.
    private val watchClauses: Array<IntArrayList> = Array(2 * numBoolVars) { IntArrayList(initialCapacity = 2) }
    private val watchBlockers: Array<IntArrayList> = Array(2 * numBoolVars) { IntArrayList(initialCapacity = 2) }

    // Base unit clauses, pinned once at root during the initial full propagation.
    private val baseUnits = IntArrayList()

    /** Trail cursor: every literal in `boolPinOrder[0, bcpHead)` has been propagated. Reset to the
     *  (truncated) trail size on backtrack, since the surviving prefix is already propagated. */
    var bcpHead = 0

    private var initialized = false
    private var pendingConflict = false

    init {
        watchPos.growTo(2 * baseCount)
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

    private fun addWatch(lit: Int, handle: Int, blocker: Int) {
        watchClauses[lit].add(handle)
        watchBlockers[lit].add(blocker)
    }

    /** Swap-pop the watcher at [idx] in literal [lit]'s lists. The caller re-processes [idx]. */
    private fun removeWatchAt(lit: Int, idx: Int) {
        val wc = watchClauses[lit]
        val wb = watchBlockers[lit]
        val last = wc.size - 1
        wc[idx] = wc[last]
        wb[idx] = wb[last]
        wc.truncateTo(last)
        wb.truncateTo(last)
    }

    /** The clause's literals as a fresh array (the seed reason for conflict analysis). */
    private fun clauseLits(h: Int): IntArray {
        val len = clauseLen(h)
        return IntArray(len) { litOf(h, it) }
    }

    /** The clause's literals except index [skip] — the antecedent set recorded for a forced unit,
     *  matching the general clause propagator's reason exactly. Null for a single-literal clause. */
    private fun antecedentsExcept(h: Int, skip: Int): IntArray? {
        val len = clauseLen(h)
        if (len <= 1) return null
        val out = IntArray(len - 1)
        var w = 0
        for (k in 0 until len) if (k != skip) out[w++] = litOf(h, k)
        return out
    }

    private fun recordConflict(h: Int) {
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
    fun addLearned(lits: IntArray, lbd: Int, permanent: Boolean): Int {
        val h = appendLearned(lits, lbd, permanent)
        val len = lits.size
        if (len == 1) {
            if (state.litFalse(lits[0])) {
                recordConflict(h)
                pendingConflict = true
            } else if (!state.litTrue(lits[0])) {
                if (!pinUnit(h, 0)) pendingConflict = true
            }
            return h
        }
        var w0 = -1
        var w1 = -1
        for (k in 0 until len) {
            if (!state.litFalse(lits[k])) {
                if (w0 < 0) {
                    w0 = k
                } else {
                    w1 = k
                    break
                }
            }
        }
        if (w0 < 0) {
            // Every literal false — an immediate conflict. Watch the first two so a later backtrack
            // leaves the clause with valid watches.
            watchPos[2 * h] = 0
            watchPos[2 * h + 1] = 1
            addWatch(litOf(h, 0), h, litOf(h, 1))
            addWatch(litOf(h, 1), h, litOf(h, 0))
            recordConflict(h)
            pendingConflict = true
            return h
        }
        val second = if (w1 >= 0) {
            w1
        } else if (w0 == 0) {
            1
        } else {
            0
        }
        watchPos[2 * h] = w0
        watchPos[2 * h + 1] = second
        addWatch(litOf(h, w0), h, litOf(h, second))
        addWatch(litOf(h, second), h, litOf(h, w0))
        if (w1 < 0 && !pinUnit(h, w0)) pendingConflict = true // exactly one open literal — assert it
        return h
    }

    private fun appendLearned(lits: IntArray, lbd: Int, permanent: Boolean): Int {
        val i = learnedCount
        learnedStarts.add(learnedLits.size)
        for (l in lits) learnedLits.add(l)
        learnedLbd.add(lbd)
        learnedPermanent.add(if (permanent) 1 else 0)
        learnedTier.add(ClauseTier.UNSET.ordinal)
        learnedUsed.add(0)
        learnedCount++
        watchPos.add(0)
        watchPos.add(0)
        return baseCount + i
    }

    // Learned-clause policy accessors: the native mirror of the LearnedClauseDb columns.

    val count: Int get() = learnedCount
    fun lbdOf(i: Int): Int = learnedLbd[i]
    fun permanentOf(i: Int): Boolean = learnedPermanent[i] == 1
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

    /** Mark learned clause forced by factor id [fid] (if it is one) as reused since the last
     *  reduction — drives three-tier promotion, mirroring `PropagationState.noteLearnedUse`. */
    fun markUsed(fid: Int) {
        val i = fid - baseCount
        if (i in 0 until learnedCount) learnedUsed[i] = 1
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
        for (lit in watchClauses.indices) {
            val wc = watchClauses[lit]
            val wb = watchBlockers[lit]
            var w = 0
            for (r in 0 until wc.size) {
                val h = wc[r]
                val newH = if (h < baseCount) h else remap[h - baseCount].let { if (it < 0) -1 else baseCount + it }
                if (newH < 0) continue // dropped clause — skip
                wc[w] = newH
                wb[w] = wb[r]
                w++
            }
            wc.truncateTo(w)
            wb.truncateTo(w)
        }

        // Rebuild the learned literal buffer, starts, policy columns, and watchPos for survivors.
        val newLits = IntArrayList()
        val newStarts = IntArrayList()
        val newLbd = IntArrayList()
        val newPermanent = IntArrayList()
        val newTier = IntArrayList()
        val newUsed = IntArrayList()
        val newWatchPos = IntArrayList()
        newWatchPos.growTo(2 * baseCount)
        for (h in 0 until baseCount) {
            newWatchPos[2 * h] = watchPos[2 * h]
            newWatchPos[2 * h + 1] = watchPos[2 * h + 1]
        }
        for (i in 0 until learnedCount) {
            if (remap[i] < 0) continue
            val h = baseCount + i
            newStarts.add(newLits.size)
            val len = clauseLen(h)
            for (k in 0 until len) newLits.add(litOf(h, k))
            newLbd.add(learnedLbd[i])
            newPermanent.add(learnedPermanent[i])
            newTier.add(learnedTier[i])
            newUsed.add(learnedUsed[i])
            newWatchPos.add(watchPos[2 * h])
            newWatchPos.add(watchPos[2 * h + 1])
        }
        learnedLits.replaceWith(newLits)
        learnedStarts.replaceWith(newStarts)
        learnedLbd.replaceWith(newLbd)
        learnedPermanent.replaceWith(newPermanent)
        learnedTier.replaceWith(newTier)
        learnedUsed.replaceWith(newUsed)
        watchPos.replaceWith(newWatchPos)
        learnedCount = survivors
    }

    private fun IntArrayList.replaceWith(other: IntArrayList) {
        clear()
        for (i in 0 until other.size) add(other[i])
    }

    /** Assert `litOf(h, unitIdx)` as forced by clause [h]. Returns false (and records the conflict)
     *  when the assignment contradicts — the caller decides whether to flag [pendingConflict]. */
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
        if (allFactors && !initialized) {
            initialized = true
            state.currentLevel = 0
            for (i in 0 until baseUnits.size) {
                val h = baseUnits[i]
                val lit = litOf(h, 0)
                if (state.litFalse(lit)) {
                    recordConflict(h)
                    return state.conflictLevels
                }
                if (!state.litTrue(lit)) {
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
        val wc = watchClauses[falseLit]
        val wb = watchBlockers[falseLit]
        var wi = 0
        while (wi < wc.size) {
            val h = wc[wi]
            val blocker = wb[wi]
            if (state.litTrue(blocker)) {
                wi++
                continue
            }
            val i0 = watchPos[2 * h]
            val i1 = watchPos[2 * h + 1]
            val slot0IsFalse = litOf(h, i0) == falseLit
            val lIdx = if (slot0IsFalse) i0 else i1
            val otherIdx = if (slot0IsFalse) i1 else i0
            val otherLit = litOf(h, otherIdx)
            if (state.litTrue(otherLit)) {
                wb[wi] = otherLit // refresh the blocker to the satisfying literal
                wi++
                continue
            }
            val replacement = findNonFalse(h, lIdx, otherIdx)
            if (replacement >= 0) {
                val newLit = litOf(h, replacement)
                if (slot0IsFalse) watchPos[2 * h] = replacement else watchPos[2 * h + 1] = replacement
                removeWatchAt(falseLit, wi) // swap-pop; re-process wi without advancing
                addWatch(newLit, h, otherLit)
                continue
            }
            if (state.litFalse(otherLit)) {
                recordConflict(h)
                return false
            }
            if (!pinUnit(h, otherIdx)) return false
            wi++
        }
        return true
    }

    /** A non-false literal index other than the two watched ([lIdx], [otherIdx]); -1 if none. */
    private fun findNonFalse(h: Int, lIdx: Int, otherIdx: Int): Int {
        val len = clauseLen(h)
        for (k in 0 until len) {
            if (k == lIdx || k == otherIdx) continue
            if (!state.litFalse(litOf(h, k))) return k
        }
        return -1
    }

    private companion object {
        // Poll the deadline every 4096 propagated literals — matches the general fixpoint's cadence.
        const val CANCEL_POLL_MASK = 0xFFF
    }
}
