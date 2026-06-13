package com.eignex.klause.solver.propagation

import com.eignex.klause.solver.Lit
import com.eignex.klause.util.IntArrayList

/** Register [atomId] in the watched index (idempotent per threshold/kind). */
internal fun PropagationState.markAtomWatched(atomId: Int) {
    val v = atomIntVar[atomId]
    val kind = atomKind[atomId]
    val k = atomThreshold[atomId]
    val idx = watchedAtomsByVar.getOrPut(v) { VarAtomIndex() }
    val keys = idx.keysOf(kind)
    val at = keys.lowerBound(k)
    if (at < keys.size && keys[at] == k) return // already tracked
    idx.insert(kind, k, atomId)
}

internal fun PropagationState.atomKey(intVar: Int, kind: Int, threshold: Int): Long {
    // Threshold can be negative; bias by Int.MIN_VALUE to keep it non-negative within
    // the lower 32 bits. Kind (0..2) takes bits 32..33; intVar takes bits 34..63.
    val biased = threshold.toLong() - Int.MIN_VALUE.toLong()
    return (intVar.toLong() shl 34) or (kind.toLong() shl 32) or biased
}

/** Encode a *positive* atom-lit (the atom holds) directly as a [Lit]-style id. */
internal fun PropagationState.atomLitGe(intVar: Int, threshold: Int): Int = Lit.make(atomVarGe(intVar, threshold), true)

/** Literal for the bound atom `intVar ≤ threshold`. */
internal fun PropagationState.atomLitLe(intVar: Int, threshold: Int): Int = Lit.make(atomVarLe(intVar, threshold), true)

/** Literal for the value atom `intVar = value`. */
internal fun PropagationState.atomLitEq(intVar: Int, value: Int): Int = Lit.make(atomVarEq(intVar, value), true)

/** Literal for the value atom `intVar ≠ value`. */
internal fun PropagationState.atomLitNe(intVar: Int, value: Int): Int = Lit.make(atomVarEq(intVar, value), false)

/** True iff `v` is an atom-id (past the bool var space). Used by the conflict
 *  analyzer to dispatch between bool-trail and atom-table lookups. */
internal fun PropagationState.isAtomVar(v: Int): Boolean = v >= problem.numBoolVars

/** Translate a virtual atom-var id back to its 0-based atom index. */
internal fun PropagationState.atomIdOf(v: Int): Int = v - problem.numBoolVars

/** Current truth of an atom — derived fresh from `intDomains`, not the
 *  snapshot-at-allocation `atomValue`. Returns `null` when undetermined (the bound
 *  isn't either side-decided yet). Used by [PropagationState.litTrue] / [PropagationState.litFalse] /
 *  [PropagationState.pinLit]. */
internal fun PropagationState.atomCurrentTruth(atomId: Int): Boolean? =
    atomTruthOf(atomIntVar[atomId], atomKind[atomId], atomThreshold[atomId])

/**
 * Decision level at which atom [atomId]'s **current** truth was established on the
 * **current** search path — recomputed from the bound-change and hole-carve histories,
 * which are truncated on every undo and re-pushed on every move, so they always
 * reflect the current path. An undetermined atom reports the live decision count
 * (conservative: it can only constrain at the current level).
 */
internal fun PropagationState.atomLevelForConflict(atomId: Int): Int {
    val v = atomIntVar[atomId]
    val k = atomThreshold[atomId]
    val truth = atomCurrentTruth(atomId) ?: return levelToDecisionVar.size
    return when (atomKind[atomId]) {
        0 -> if (truth) minLevelForGe(v, k) else maxLevelForLe(v, k - 1)

        // v ≥ k

        1 -> if (truth) maxLevelForLe(v, k) else minLevelForGe(v, k + 1)

        // v ≤ k

        2 -> if (truth) { // v = k : true when the later of the two bounds reached k
            maxOf(minLevelForGe(v, k), maxLevelForLe(v, k))
        } else { // v ≠ k : established at the level k left the domain
            val d = intDomains[v]
            when {
                k < d.min -> minLevelForGe(v, k + 1)
                k > d.max -> maxLevelForLe(v, k - 1)
                else -> holeLevelFor(v, k) // interior hole — carve history
            }
        }

        else -> levelToDecisionVar.size
    }
}

internal fun PropagationState.allocAtom(intVar: Int, kind: Int, threshold: Int): Int {
    val slot = intVar * 3 + kind
    if (atomMemoId[slot] >= 0 && atomMemoThr[slot] == threshold) {
        return problem.numBoolVars + atomMemoId[slot]
    }
    val key = atomKey(intVar, kind, threshold)
    val existing = atomByKey.getOrDefault(key, -1)
    if (existing >= 0) {
        atomMemoThr[slot] = threshold
        atomMemoId[slot] = existing
        return problem.numBoolVars + existing
    }
    val id = atomIntVar.size
    atomIntVar.add(intVar)
    atomKind.add(kind)
    atomThreshold.add(threshold)
    atomByKey.put(key, id)
    atomsByIntVar.getOrPut(intVar) { VarAtomIndex() }.insert(kind, threshold, id)
    atomMemoThr[slot] = threshold
    atomMemoId[slot] = id
    return problem.numBoolVars + id
}

/**
 * Antecedents of atom [atomId], derived on demand: a true bound atom resolves to the
 * recorded reason of the move that first established it (the bound histories); a
 * false bound atom and a bound-excluded eq atom cite the opposing bound atom, which
 * the analyzer unfolds through its own history; a true eq atom cites both endpoint
 * atoms; an interior-hole eq atom resolves to the carve's recorded reason. `null`
 * marks a root/bake fact or an undetermined atom — the analyzer keeps such literals
 * instead of resolving through them.
 */
internal fun PropagationState.atomAntecedentsDerived(atomId: Int): IntArray? {
    val v = atomIntVar[atomId]
    val k = atomThreshold[atomId]
    val truth = atomCurrentTruth(atomId) ?: return null
    return when (atomKind[atomId]) {
        0 -> if (truth) minReasonFor(v, k) else intArrayOf(Lit.make(atomVarLe(v, k - 1), false))

        1 -> if (truth) maxReasonFor(v, k) else intArrayOf(Lit.make(atomVarGe(v, k + 1), false))

        2 -> if (truth) {
            composeIntVarAtomAntecedents(intArrayOf(v))
        } else {
            val d = intDomains[v]
            when {
                k < d.min -> intArrayOf(Lit.make(atomVarGe(v, k + 1), false))
                k > d.max -> intArrayOf(Lit.make(atomVarLe(v, k - 1), false))
                else -> holeReasonFor(v, k)
            }
        }

        else -> null
    }
}

internal fun PropagationState.atomTruthOf(v: Int, kind: Int, k: Int): Boolean? {
    val d = intDomains[v]
    return when (kind) {
        0 -> when {
            d.min >= k -> true
            d.max < k -> false
            else -> null
        }

        1 -> when {
            d.max <= k -> true
            d.min > k -> false
            else -> null
        }

        2 -> when {
            d.min == d.max && d.min == k -> true

            // singleton {k} → eq true
            k !in d -> false

            // k absent → eq false
            else -> null
        }

        else -> error("unknown atom kind")
    }
}

/**
 * After a successful [tightenIntMinImpl] / [tightenIntMaxImpl] / [excludeIntValueImpl]
 * on int var `v`, recompute the truth of every atom that depends on `v`. Atoms whose
 * truth flipped get their level / antecedents updated, and watchers on the now-false
 * atom-lit are scheduled to fire.
 *
 * A move carries two reason sets: [antNear] justifies the requested bound, [antFar]
 * additionally carries the hole-crossing chain when the landed bound snapped further
 * (they alias without a snap). Each flipped atom takes the weakest set that still
 * implies its truth, split by threshold against the requested [reqMin] / [reqMax].
 * An eq atom flipping TRUE needs BOTH endpoint premises while the move supplied one,
 * so it cites the two bound atoms instead.
 */
internal fun PropagationState.propagateAtomsForVar(
    v: Int,
    @Suppress("UNUSED_PARAMETER") antNear: IntArray?,
    @Suppress("UNUSED_PARAMETER") antFar: IntArray? = antNear,
    @Suppress("UNUSED_PARAMETER") reqMin: Int = intDomains[v].min,
    @Suppress("UNUSED_PARAMETER") reqMax: Int = intDomains[v].max,
    oldMin: Int,
    oldMax: Int,
    carved: Int = NO_CARVE,
) {
    val idx = watchedAtomsByVar[v] ?: return
    val d = intDomains[v]
    val newMin = d.min
    val newMax = d.max
    if (newMin > oldMin) {
        visitAtomRange(idx.geKeys, idx.geIds, oldMin + 1, newMin) { id -> wakeAtom(id, true) }
        visitAtomRange(idx.leKeys, idx.leIds, oldMin, newMin - 1) { id -> wakeAtom(id, false) }
        visitAtomRange(idx.eqKeys, idx.eqIds, oldMin, newMin - 1) { id -> wakeAtom(id, false) }
    }
    if (newMax < oldMax) {
        visitAtomRange(idx.leKeys, idx.leIds, newMax, oldMax - 1) { id -> wakeAtom(id, true) }
        visitAtomRange(idx.geKeys, idx.geIds, newMax + 1, oldMax) { id -> wakeAtom(id, false) }
        visitAtomRange(idx.eqKeys, idx.eqIds, newMax + 1, oldMax) { id -> wakeAtom(id, false) }
    }
    if (carved != NO_CARVE && carved in (newMin + 1) until newMax) {
        visitAtomRange(idx.eqKeys, idx.eqIds, carved, carved) { id -> wakeAtom(id, false) }
    }
    if (newMin == newMax && (newMin > oldMin || newMax < oldMax)) {
        visitAtomRange(idx.eqKeys, idx.eqIds, newMin, newMin) { id -> wakeAtom(id, true) }
    }
}

internal inline fun PropagationState.visitAtomRange(
    keys: IntArrayList,
    ids: IntArrayList,
    from: Int,
    to: Int,
    action: (atomId: Int) -> Unit,
) {
    if (to < from || keys.size == 0) return
    var i = keys.lowerBound(from)
    while (i < keys.size && keys[i] <= to) {
        action(ids[i])
        i++
    }
}

/** Wake the watchers of [atomId]'s now-false literal after its truth flipped to
 *  [newT]. Truth itself is never stored — it is derived from the domains on read. */
internal fun PropagationState.wakeAtom(atomId: Int, newT: Boolean) {
    val falseLit = Lit.make(problem.numBoolVars + atomId, !newT)
    val w = atomWatchersByLit[falseLit] ?: return
    for (j in 0 until w.size) dirtyAtomFactors.addLast(w[j])
}

/** Install [fid] as a watcher of [lit]. Dispatches between [PropagationState.boolWatchersByLit]
 *  (bool var space) and [PropagationState.atomWatchersByLit] (atom var space). */
internal fun PropagationState.installLitWatch(lit: Int, fid: Int, blocker: Int = NO_BLOCKER) {
    val v = Lit.variable(lit)
    if (v < problem.numBoolVars) {
        val list = boolWatchersByLit[lit]
        boolWatchPos.put(packWatch(fid, lit), list.size) // position of the about-to-append entry
        list.add(fid)
        boolBlockersByLit[lit].add(blocker) // index-aligned with the watcher just appended
    } else {
        val list = atomWatchersByLit.getOrPut(lit) { IntArrayList(initialCapacity = 2) }
        list.add(fid)
        markAtomWatched(atomIdOf(v))
    }
}
