package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.util.IntHashSet

/**
 * `inverse(f, g)` with optional offsets: `f(i) = j  ⇔  g(j - gOffset + fOffset) = i`.
 *
 * The canonical 0-based form is `f(i) = j ⇔ g(j) = i`. MiniZinc emits with index offsets
 * matching its 1-based default; the offsets are encoded into the factor so the dispatch
 * doesn't have to allocate channel vars.
 *
 * Propagation: pin-forcing channels — whenever `f(i)` becomes singleton with value `j`,
 * force `g(j')` to `i'` where the indices apply the offset; vice versa.
 */
class Inverse(
    /** Forward mapping variable ids: `f(i)` is the image of `i`. */
    val f: IntArray,
    /** Inverse mapping variable ids: `g(j)` is the preimage of `j`. */
    val g: IntArray,
    /** Index offset for the [f] domain. */
    val fOffset: Int = 0,
    /** Index offset for the [g] domain. */
    val gOffset: Int = 0,
) : Factor {

    init {
        require(f.size == g.size) { "inverse: f and g must have equal length" }
        require(f.isNotEmpty()) { "inverse: empty arrays" }
    }

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor =
        Inverse(f.remapVars(intMap), g.remapVars(intMap), fOffset, gOffset)

    // Positional: f(i)/g(i) are channelled by index, so neither array is sorted. Encodes both
    // offsets and the ordered f / g var sequences — fine enough that two non-equivalent Inverses
    // never share a key (required for sound symmetry verification). The f/g sides are kept distinct
    // (not canonicalised against each other); at worst this misses an f↔g symmetry, never unsound.
    override fun structuralKey(): String =
        "inverse:$fOffset:$gOffset:" + f.joinToString(",") + ":" + g.joinToString(",")

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = f + g

    private fun fValueToGIndex(j: Int): Int = j - gOffset
    private fun gValueToFIndex(i: Int): Int = i - fOffset

    /** Number of currently-violated channel cells: pairs where the f→g and g→f mappings
     *  disagree. Maintained incrementally for LS scoring. */
    private class State(var violated: Int)

    override fun initialize(state: LocalSearchState, factorId: Int) {
        var bad = 0
        // For each f(i): read its value j, look up g(j - gOff) and require it equals i + fOff.
        for (i in f.indices) {
            val j = state.assignment.intValue(f[i])
            val gIdx = fValueToGIndex(j)
            if (gIdx !in g.indices) {
                bad++
                continue
            }
            val gVal = state.assignment.intValue(g[gIdx])
            if (gVal != i + fOffset) bad++
        }
        // Symmetric check via g side.
        for (i in g.indices) {
            val j = state.assignment.intValue(g[i])
            val fIdx = gValueToFIndex(j)
            if (fIdx !in f.indices) {
                bad++
                continue
            }
            val fVal = state.assignment.intValue(f[fIdx])
            if (fVal != i + gOffset) bad++
        }
        state.refPayload[factorId] = State(bad)
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        val s = state.refPayload[factorId] as State
        return s.violated > 0
    }

    /** Graded violation: the number of mismatched channel cells, compressed — a move that
     *  repairs some (but not all) cells scores a real improvement instead of 0. */
    override fun violationDegree(state: LocalSearchState, factorId: Int): Int =
        compressViolation((state.refPayload[factorId] as State).violated.toLong(), state.violationSoftCap)

    /** Default brute-force: simulate, recount, return delta. Cost O(n) per query — fine
     *  for the structurally simple inverse but sub-optimal vs. an incremental Δ. */
    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val s = state.refPayload[factorId] as State
        val current = state.assignment.intValue(intVar)
        if (current == newValue) return 0
        val after = simulateViolations(state, intVar, newValue)
        return compressViolation(after.toLong(), state.violationSoftCap) -
            compressViolation(s.violated.toLong(), state.violationSoftCap)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
        val s = state.refPayload[factorId] as State
        val before = s.violated
        s.violated = countViolations(state)
        return compressViolation(s.violated.toLong(), state.violationSoftCap) -
            compressViolation(before.toLong(), state.violationSoftCap)
    }

    private fun countViolations(state: LocalSearchState): Int {
        var bad = 0
        for (i in f.indices) {
            val j = state.assignment.intValue(f[i])
            val gIdx = fValueToGIndex(j)
            if (gIdx !in g.indices) {
                bad++
                continue
            }
            val gVal = state.assignment.intValue(g[gIdx])
            if (gVal != i + fOffset) bad++
        }
        for (i in g.indices) {
            val j = state.assignment.intValue(g[i])
            val fIdx = gValueToFIndex(j)
            if (fIdx !in f.indices) {
                bad++
                continue
            }
            val fVal = state.assignment.intValue(f[fIdx])
            if (fVal != i + gOffset) bad++
        }
        return bad
    }

    private fun simulateViolations(state: LocalSearchState, intVar: Int, newValue: Int): Int {
        var bad = 0
        for (i in f.indices) {
            val j = if (f[i] == intVar) newValue else state.assignment.intValue(f[i])
            val gIdx = fValueToGIndex(j)
            if (gIdx !in g.indices) {
                bad++
                continue
            }
            val gVal = if (g[gIdx] == intVar) newValue else state.assignment.intValue(g[gIdx])
            if (gVal != i + fOffset) bad++
        }
        for (i in g.indices) {
            val j = if (g[i] == intVar) newValue else state.assignment.intValue(g[i])
            val fIdx = gValueToFIndex(j)
            if (fIdx !in f.indices) {
                bad++
                continue
            }
            val fVal = if (f[fIdx] == intVar) newValue else state.assignment.intValue(f[fIdx])
            if (fVal != i + gOffset) bad++
        }
        return bad
    }

    /*
     * GAC for the inverse channel. Three layers:
     *   1. range-tighten each f[i] / g[j] to the legal index span and force singletons across
     *      the channel;
     *   2. bidirectional value removal — if `i + fOffset` is absent from `dom(g[j])`, also remove
     *      `j + gOffset` from `dom(f[i])`, and symmetrically. This is the arc-consistent closure of
     *      `f[i]=j ⇔ g[j]=i`;
     *   3. Hall/matching filtering on f and on g (#541). The biconditional forces f and g to be
     *      bijections (`f[i1]=f[i2]=j ⇒ g[j]=i1=i2`), so each side is all-different; the channel AC
     *      alone reaches a mutual non-GAC fixpoint (e.g. it keeps `f2=0` because `g0=2` is unpruned
     *      and vice versa). Régin matching on f and on g punches the Hall-set values the channel
     *      misses, reusing the shared [reginFilter].
     */

    /** Hole-aware conflict reason, sharpened to the responsible channel var / pair captured
     *  by [propagate]; falls back to all vars when no pair was recorded. The scratch lives
     *  on the session's [InverseCache], not the factor, so portfolio workers sharing one
     *  Problem never cross reasons (#182). */
    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        collectHoleAndBoundAntecedents(state, (state.refPayload[factorId] as? InverseCache)?.conflictVars ?: intVars)

    /** Cached domain refs (f then g) at the last successful propagate. The O(n²) value-removal
     *  sweep reprocesses only rows/columns whose domain reference changed since this baseline;
     *  unchanged pairs were already consistent and stay so. A var pruned during a call has its
     *  cache entry nulled so its row/column is rechecked next fire — cascades are caught across
     *  fires (the engine re-queues on any prune), reaching the same fixpoint as the full sweep.
     *  [fRegin] / [gRegin] warm-start the per-side Régin matching (#541). Backtrack-safe via
     *  [snapshotCopy]. */
    private class InverseCache(
        val refs: Array<IntDomain?>,
        val fRegin: ReginCache = ReginCache(),
        val gRegin: ReginCache = ReginCache(),
    ) : PropagationState.SnapshottablePayload {
        /** The channel var / pair (or Hall violator set) behind the most recent propagate failure
         *  on this session; propagate-to-analysis transient, so excluded from [snapshotCopy] (#182). */
        var conflictVars: IntArray? = null

        override fun snapshotCopy(): InverseCache = InverseCache(
            refs.copyOf(),
            fRegin.snapshotCopy(),
            gRegin.snapshotCopy(),
        )
    }

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val cache = (state.refPayload[factorId] as? InverseCache) ?: run {
            val fresh = InverseCache(arrayOfNulls(intVars.size))
            state.refPayload[factorId] = fresh
            fresh
        }
        cache.conflictVars = null // stale-guard; set at each failure point below.
        val entryRefs = Array(intVars.size) { state.intDomains[intVars[it]] }
        // Range tightens are structural (no input antecedents). A failure means that one var
        // alone cannot reach the legal index span, so it is the sole reason.
        val gLo = gOffset
        val gHi = gOffset + g.size - 1
        for (i in f.indices) {
            if (!state.tightenIntMin(f[i], gLo)) {
                cache.conflictVars = intArrayOf(f[i])
                return false
            }
            if (!state.tightenIntMax(f[i], gHi)) {
                cache.conflictVars = intArrayOf(f[i])
                return false
            }
        }
        val fLo = fOffset
        val fHi = fOffset + f.size - 1
        for (i in g.indices) {
            if (!state.tightenIntMin(g[i], fLo)) {
                cache.conflictVars = intArrayOf(g[i])
                return false
            }
            if (!state.tightenIntMax(g[i], fHi)) {
                cache.conflictVars = intArrayOf(g[i])
                return false
            }
        }
        // Singleton-forcing: the source var's int trail antecedents drive the pin. A failure
        // implicates the pinned source and the target it forces across the channel.
        for (i in f.indices) {
            val d = state.intDomains[f[i]]
            if (d.min != d.max) continue
            val gIdx = d.min - gOffset
            if (gIdx !in g.indices) {
                cache.conflictVars = intArrayOf(f[i])
                return false
            }
            val ant = state.composeIntVarAtomAntecedents(intArrayOf(f[i]))
            if (!state.tightenIntMin(g[gIdx], i + fOffset, ant)) {
                cache.conflictVars = intArrayOf(f[i], g[gIdx])
                return false
            }
            if (!state.tightenIntMax(g[gIdx], i + fOffset, ant)) {
                cache.conflictVars = intArrayOf(f[i], g[gIdx])
                return false
            }
        }
        for (i in g.indices) {
            val d = state.intDomains[g[i]]
            if (d.min != d.max) continue
            val fIdx = d.min - fOffset
            if (fIdx !in f.indices) {
                cache.conflictVars = intArrayOf(g[i])
                return false
            }
            val ant = state.composeIntVarAtomAntecedents(intArrayOf(g[i]))
            if (!state.tightenIntMin(f[fIdx], i + gOffset, ant)) {
                cache.conflictVars = intArrayOf(g[i], f[fIdx])
                return false
            }
            if (!state.tightenIntMax(f[fIdx], i + gOffset, ant)) {
                cache.conflictVars = intArrayOf(g[i], f[fIdx])
                return false
            }
        }
        // Bidirectional value removal: for each (i, gIdx) where gIdx is in range, the
        // channel forces "j+gOffset in dom(f[i])  iff  i+fOffset in dom(g[gIdx])".
        // Whichever side has the value missing, remove from the other. A wipe-out failure
        // implicates exactly the channel pair (f[i], g[gIdx]). Incremental: a pair only needs
        // reprocessing when one of its two endpoints' domains changed vs the cached baseline,
        // so we sweep only changed rows and changed columns (each pair once).
        val fn = f.size
        fun fChanged(i: Int) = cache.refs[i] !== state.intDomains[f[i]]
        fun gChanged(j: Int) = cache.refs[fn + j] !== state.intDomains[g[j]]
        fun pair(i: Int, gIdx: Int): Boolean {
            val jVal = gIdx + gOffset // value f[i] would take to point to g[gIdx]
            val iVal = i + fOffset // value g[gIdx] would take to point back to f[i]
            val fHas = jVal in state.intDomains[f[i]]
            val gHas = iVal in state.intDomains[g[gIdx]]
            if (fHas && !gHas) {
                val ant = state.composeIntVarAtomAntecedents(intArrayOf(g[gIdx]))
                if (!state.excludeIntValue(f[i], jVal, ant)) {
                    cache.conflictVars = intArrayOf(f[i], g[gIdx])
                    return false
                }
            } else if (!fHas && gHas) {
                val ant = state.composeIntVarAtomAntecedents(intArrayOf(f[i]))
                if (!state.excludeIntValue(g[gIdx], iVal, ant)) {
                    cache.conflictVars = intArrayOf(f[i], g[gIdx])
                    return false
                }
            }
            return true
        }
        for (i in f.indices) {
            if (!fChanged(i)) continue
            for (gIdx in g.indices) if (!pair(i, gIdx)) return false
        }
        for (gIdx in g.indices) {
            if (!gChanged(gIdx)) continue
            for (i in f.indices) {
                if (fChanged(i)) continue // already handled in the changed-row sweep
                if (!pair(i, gIdx)) return false
            }
        }
        // Hall/matching filtering: f and g are each bijections, so apply Régin all-different
        // domain consistency to each side. This prunes the Hall-set values the pairwise channel
        // AC leaves at its mutual fixpoint. On infeasibility the returned Hall violators become
        // this session's conflict reason. Warm-started per side via the cache (#541).
        val fHall = reginFilter(state, f, NO_EXCEPT, cache.fRegin)
        if (fHall != null) {
            cache.conflictVars = fHall
            return false
        }
        val gHall = reginFilter(state, g, NO_EXCEPT, cache.gRegin)
        if (gHall != null) {
            cache.conflictVars = gHall
            return false
        }

        // Record the post-prune baseline. A var pruned this call (ref differs from entry) is
        // nulled so its row/column is rechecked next fire, propagating cascades to fixpoint.
        for (k in intVars.indices) {
            val cur = state.intDomains[intVars[k]]
            cache.refs[k] = if (entryRefs[k] !== cur) null else cur
        }
        return true
    }

    private companion object {
        /** Empty excepted-value set for the shared [reginFilter] (inverse's f and g are plain
         *  bijections, no shared values). [reginFilter] only reads it, so one shared instance is
         *  safe. */
        val NO_EXCEPT = IntHashSet()
    }

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        // Find a mismatched cell and propose setting one side to match the other.
        for (i in f.indices) {
            val j = state.assignment.intValue(f[i])
            val gIdx = fValueToGIndex(j)
            if (gIdx !in g.indices) {
                // f[i]'s value out of range: nudge into range.
                val d = state.problem.intDomains[f[i]]
                val mid = ((d.min + d.max) / 2)
                if (mid in d && mid != j) sink.addChannelingIntSet(state, f[i], mid)
                return
            }
            val gVal = state.assignment.intValue(g[gIdx])
            if (gVal != i + fOffset) {
                // Two repair candidates: align g side or change f's value.
                val gd = state.problem.intDomains[g[gIdx]]
                val target = i + fOffset
                if (target in gd && target != gVal) sink.addChannelingIntSet(state, g[gIdx], target)
                // Or change f[i] to point where g[gIdx] currently points back.
                val gFwd = gVal - fOffset
                if (gFwd in 0 until g.size) {
                    val targetFwd = gFwd + gOffset
                    val fd = state.problem.intDomains[f[i]]
                    if (targetFwd in fd && targetFwd != j) sink.addChannelingIntSet(state, f[i], targetFwd)
                }
                // Symmetric: scan for some jPrime where g[jPrime] already equals (i+fOffset),
                // and propose f[i] = jPrime + gOffset. Repairs i's constraint without touching
                // any g[*]. Necessary when the desired value already lives elsewhere on g.
                val fd = state.problem.intDomains[f[i]]
                for (jPrime in g.indices) {
                    if (state.assignment.intValue(g[jPrime]) == i + fOffset) {
                        val tgt = jPrime + gOffset
                        if (tgt in fd && tgt != j) {
                            sink.addChannelingIntSet(state, f[i], tgt)
                            break
                        }
                    }
                }
                return
            }
        }
    }
}
