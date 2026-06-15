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

internal fun PropagationState.atomKey(intVar: Int, kind: AtomKind, threshold: Int): Long {
    // Threshold can be negative; bias by Int.MIN_VALUE to keep it non-negative within
    // the lower 32 bits. Kind (0..2) takes bits 32..33; intVar takes bits 34..63.
    val biased = threshold.toLong() - Int.MIN_VALUE.toLong()
    return (intVar.toLong() shl 34) or (kind.ordinal.toLong() shl 32) or biased
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

/** Slot index of atom literal [lit] in [PropagationState.atomWatchersByLit]: two slots per
 *  atom — `atomId*2` for the positive literal, `atomId*2+1` for the negative. */
internal fun PropagationState.atomLitWatchIndex(lit: Int): Int =
    (atomIdOf(Lit.variable(lit)) shl 1) or (if (Lit.isPositive(lit)) 0 else 1)

/** Current truth of an atom — derived fresh from `intDomains`, not the
 *  snapshot-at-allocation `atomValue`. Returns `null` when undetermined (the bound
 *  isn't either side-decided yet). Used by [PropagationState.litTrue] / [PropagationState.litFalse] /
 *  [PropagationState.pinLit]. */
internal fun PropagationState.atomCurrentTruth(atomId: Int): Boolean? = when (atomState[atomId]) {
    1 -> true
    2 -> false
    else -> null // canonical: atomState is maintained for every materialized atom (no derive)
}

/**
 * Decision level at which atom [atomId]'s **current** truth was established on the
 * **current** search path — recomputed from the bound-change and hole-carve histories,
 * which are truncated on every undo and re-pushed on every move, so they always
 * reflect the current path. An undetermined atom reports the live decision count
 * (conservative: it can only constrain at the current level).
 */
internal fun PropagationState.atomLevelForConflict(atomId: Int): Int {
    // Trail-resident: a determined order literal carries the level it was established at on
    // its [atomLvl] slot — set when a bound move crossed it ([wakeAtom]) and kept across
    // backtracks (only the flipped range is reset, see [resetAtomTrailFor]); reconstructed from
    // the per-side [intMinLevel]/[intMaxLevel] slots for an atom materialized at the current
    // bound. This is the canonical replacement for the old bound-history binary search.
    val stored = atomLvl[atomId]
    if (stored >= 0) return stored
    val v = atomIntVar[atomId]
    val k = atomThreshold[atomId]
    val truth = atomCurrentTruth(atomId) ?: return levelToDecisionVar.size
    // atomLvl < 0 with the atom determined: the only remaining case is an eq atom ruled out by
    // an *interior hole* materialized after the carve — its level comes from the hole-carve
    // record. Bound atoms always carry a stored level once determined.
    if (atomKind[atomId] == AtomKind.EQ && !truth) {
        val d = intDomains[v]
        if (k > d.min && k < d.max) return holeLevelFor(v, k)
    }
    val rl = reconstructCurrentBoundLevel(v, atomKind[atomId], k, stateOfTruth(truth))
    return if (rl >= 0) rl else levelToDecisionVar.size
}

/** Reconstruct the trail level of a freshly-materialized **determined** atom from the per-side
 *  [PropagationState.intMinLevel] / [PropagationState.intMaxLevel] slots, when its threshold is
 *  exactly the current opposing bound (the only case a single slot can answer; provably equal to
 *  [atomLevelForConflict]'s bound-history result). Returns -1 ("not reconstructed") for undetermined
 *  atoms, looser-than-current bounds, and interior-hole eq atoms — those keep -1 and the level read
 *  falls back to the history derivation. A -1 per-side slot means the bound is still at its root, i.e.
 *  level 0. [st]: 0 = undetermined, 1 = true, 2 = false. */
internal fun PropagationState.reconstructCurrentBoundLevel(v: Int, kind: AtomKind, k: Int, st: Int): Int {
    if (st == 0) return -1
    val d = intDomains[v]
    fun lvlOrRoot(lvl: Int): Int = if (lvl >= 0) lvl else 0
    return when (kind) {
        AtomKind.GE -> when {
            st == 1 && k == d.min -> lvlOrRoot(intMinLevel[v])

            // [v ≥ d.min] true
            st == 2 && k - 1 == d.max -> lvlOrRoot(intMaxLevel[v])

            // [v ≥ k] false, k-1 == current max
            else -> -1
        }

        AtomKind.LE -> when {
            st == 1 && k == d.max -> lvlOrRoot(intMaxLevel[v])

            // [v ≤ d.max] true
            st == 2 && k + 1 == d.min -> lvlOrRoot(intMinLevel[v])

            // [v ≤ k] false, k+1 == current min
            else -> -1
        }

        AtomKind.EQ -> when {
            st == 1 && d.min == d.max && k == d.min -> maxOf(lvlOrRoot(intMinLevel[v]), lvlOrRoot(intMaxLevel[v]))

            st == 2 && k + 1 == d.min -> lvlOrRoot(intMinLevel[v])

            // ruled out just below the min
            st == 2 && k - 1 == d.max -> lvlOrRoot(intMaxLevel[v])

            // ruled out just above the max
            else -> -1 // interior hole — needs the carve history
        }
    }
}

/** Reconstruct the trail antecedent of a freshly-materialized **determined** atom from the
 *  per-side stored [PropagationState.intMinAntecedents] / [PropagationState.intMaxAntecedents]
 *  (and, for a true eq, both endpoint bounds), for the same exact current-bound cases as
 *  [reconstructCurrentBoundLevel]. Returns `null` for the looser / interior-hole / undetermined
 *  cases — those keep `null` and (paired with a -1 reconstructed level) fall back to the history
 *  derivation in [atomAntecedentsDerived]. A `null` per-side slot is itself a valid root/leaf reason. */
internal fun PropagationState.reconstructCurrentBoundReason(v: Int, kind: AtomKind, k: Int, st: Int): IntArray? {
    if (st == 0) return null
    val d = intDomains[v]
    return when (kind) {
        AtomKind.GE -> when {
            st == 1 && k == d.min -> intMinAntecedents[v]
            st == 2 && k - 1 == d.max -> intMaxAntecedents[v]
            else -> null
        }

        AtomKind.LE -> when {
            st == 1 && k == d.max -> intMaxAntecedents[v]
            st == 2 && k + 1 == d.min -> intMinAntecedents[v]
            else -> null
        }

        AtomKind.EQ -> when {
            st == 1 && d.min == d.max && k == d.min -> composeIntVarAtomAntecedents(intArrayOf(v))
            st == 2 && k + 1 == d.min -> intMinAntecedents[v]
            st == 2 && k - 1 == d.max -> intMaxAntecedents[v]
            else -> null
        }
    }
}

internal fun PropagationState.allocAtom(intVar: Int, kind: AtomKind, threshold: Int): Int {
    val key = atomKey(intVar, kind, threshold)
    val existing = atomByKey.getOrDefault(key, -1)
    if (existing >= 0) return problem.numBoolVars + existing
    val id = atomIntVar.size
    atomIntVar.add(intVar)
    atomKind.add(kind)
    atomThreshold.add(threshold)
    // Trail slots default to "not established on the current path"; channeling (a bound
    // move crossing this threshold) and lazy reconstruction in [reconstructAtomTrail]
    // populate them.
    // Snapshot the atom's current truth so it is canonical from birth — a determined-at-
    // materialization atom (its bound already crossed) carries stored truth without any
    // derive fallback, letting [atomCurrentTruth] read the bit instead of recomputing from
    // the domain. Maintained on every crossing ([wakeAtom]) and recomputed on backtrack
    // ([resetAtomSlots] after the domain is restored).
    val st = stateOfTruth(atomTruthOf(intVar, kind, threshold))
    atomState.add(st)
    atomLvl.add(reconstructCurrentBoundLevel(intVar, kind, threshold, st))
    atomRsn.add(-1)
    atomAnt.add(reconstructCurrentBoundReason(intVar, kind, threshold, st))
    atomWatchersByLit.add(null) // positive-literal watcher slot for this atom
    atomWatchersByLit.add(null) // negative-literal watcher slot
    atomByKey.put(key, id)
    atomsByIntVar.getOrPut(intVar) { VarAtomIndex() }.insert(kind, threshold, id)
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
    // Trail-resident fast path: an atom established on the current path (channeled by a bound
    // move, or reconstructed at materialization) carries the move's reason on [atomAnt] at its
    // exact establishment level — return it directly instead of re-deriving from the histories.
    // The remaining history derivation below covers only the not-yet-stored cases (interior-hole
    // eq atoms) and undetermined atoms.
    if (atomLvl[atomId] >= 0) return atomAnt[atomId]
    val v = atomIntVar[atomId]
    val k = atomThreshold[atomId]
    val d = intDomains[v]
    val truth = atomCurrentTruth(atomId) ?: return null
    return when (atomKind[atomId]) {
        // True bound atom with no stored reason: reconstruct from the current-bound per-var
        // antecedent (null = looser dead case ⇒ a leaf, sound). A false bound atom cites the
        // opposing live bound's stored antecedent ([falseBoundReason]). No bound history.
        AtomKind.GE -> if (truth) {
            reconstructCurrentBoundReason(v, AtomKind.GE, k, 1)
        } else {
            falseBoundReason(v, viaMax = true, complementK = k - 1)
        }

        AtomKind.LE -> if (truth) {
            reconstructCurrentBoundReason(v, AtomKind.LE, k, 1)
        } else {
            falseBoundReason(v, viaMax = false, complementK = k + 1)
        }

        AtomKind.EQ -> if (truth) {
            composeIntVarAtomAntecedents(intArrayOf(v))
        } else {
            when {
                k < d.min -> falseBoundReason(v, viaMax = false, complementK = k + 1)
                k > d.max -> falseBoundReason(v, viaMax = true, complementK = k - 1)
                else -> holeReasonFor(v, k)
            }
        }
    }
}

/**
 * Antecedent of a bound atom that is **false** because `v`'s opposing bound rules it out.
 * The classical lazy scheme cited the *complementary* bound atom (`¬[v ≥ k+1]` for a false
 * `[v ≤ k]`); the analyzer then unfolded that atom through its own reason, which couples a
 * GE atom to the LE atom of the same var and back — the same-level cycle that makes 1UIP
 * keep a resolved literal and the learned clause non-asserting (the 0.37%-asserting-rate
 * pathology this rewrite targets). Instead, when the falsifying bound is exactly the current
 * opposing endpoint (`viaMax`: `d.max == complementK`, i.e. the atom is the complement of the
 * live upper bound; symmetric for the min side), cite the **per-var stored antecedent of that
 * endpoint** — the real reason the bound moved there — so resolution flows straight to the
 * other variables' bounds with no GE↔LE round trip. A `null` stored antecedent means the
 * endpoint is a root/bake fact (no search move set it), which is the correct leaf. For a
 * *looser* false atom (the opposing bound sits strictly past the complement) the endpoint's
 * reason would over-attribute the level, so fall back to the classical complementary citation.
 */
internal fun PropagationState.falseBoundReason(v: Int, viaMax: Boolean, complementK: Int): IntArray? {
    val d = intDomains[v]
    return if (viaMax) {
        if (d.max == complementK) intMaxAntecedents[v] else intArrayOf(Lit.make(atomVarLe(v, complementK), false))
    } else {
        if (d.min == complementK) intMinAntecedents[v] else intArrayOf(Lit.make(atomVarGe(v, complementK), false))
    }
}

internal fun PropagationState.atomTruthOf(v: Int, kind: AtomKind, k: Int): Boolean? {
    val d = intDomains[v]
    return when (kind) {
        AtomKind.GE -> when {
            d.min >= k -> true
            d.max < k -> false
            else -> null
        }

        AtomKind.LE -> when {
            d.max <= k -> true
            d.min > k -> false
            else -> null
        }

        AtomKind.EQ -> when {
            d.min == d.max && d.min == k -> true

            // singleton {k} → eq true
            k !in d -> false

            // k absent → eq false
            else -> null
        }
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
    // Iterate all materialized atoms so every crossed order literal becomes a chronological
    // trail fact (see [wakeAtom]), with the move's reason recorded on its slot.
    val idx = atomsByIntVar[v] ?: return
    pendingMoveAnt = antFar
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
 *  [newT]. Truth itself is never stored — it is derived from the domains on read — but
 *  the **level** the atom became determined at is recorded on its trail slot: this move
 *  crossed the atom's threshold, so its truth was established at the move's [currentLevel].
 *  That stored level is the trail-resident replacement for the bound-history binary search
 *  in [atomLevelForConflict] (provably equal: a crossing at level L *is* the level the
 *  bound first reached the threshold). Reset to -1 on backtrack of the underlying var. */
internal fun PropagationState.wakeAtom(atomId: Int, newT: Boolean) {
    if (atomLvl[atomId] != currentLevel || atomRsn[atomId] != currentFactor) {
        boolPinOrder.add(problem.numBoolVars + atomId)
    }
    atomState[atomId] = if (newT) 1 else 2
    atomLvl[atomId] = currentLevel
    atomRsn[atomId] = currentFactor
    // Record the establishment reason on the trail slot: a true eq atom (the var just became
    // the singleton {k}) rests on BOTH endpoint bounds, so cite them; every other crossed
    // literal rests on the move that crossed it (the channeled [pendingMoveAnt]).
    atomAnt[atomId] = if (newT && atomKind[atomId] == AtomKind.EQ) {
        composeIntVarAtomAntecedents(intArrayOf(atomIntVar[atomId]))
    } else {
        pendingMoveAnt
    }
    // Wake watchers of the now-false literal: index = atomId*2 + (positive?0:1); the false
    // literal's polarity is `!newT`, so its slot is `atomId*2 + (!newT ? 0 : 1)`.
    val w = atomWatchersByLit[(atomId shl 1) or (if (!newT) 0 else 1)] ?: return
    for (j in 0 until w.size) dirtyAtomFactors.addLast(w[j])
}

/** Encode a three-valued truth as the [PropagationState.atomState] code: true→1, false→2, null→0. */
internal fun stateOfTruth(t: Boolean?): Int = when (t) {
    true -> 1
    false -> 2
    null -> 0
}

/** Clear [atomId]'s trail slot to "undetermined / unestablished". */
private fun PropagationState.clearAtomSlot(atomId: Int) {
    atomState[atomId] = 0
    atomLvl[atomId] = -1
    atomRsn[atomId] = -1
    atomAnt[atomId] = null
}

/** Reset an EQ atom in a widened range: it flips false→undetermined ONLY if its value
 *  re-entered the (restored) domain. If the value is still an interior hole it stays false
 *  and keeps its carve-time slot (the carve is undone by its own separate record), so leave
 *  it untouched. (GE/LE atoms never sit on holes, so they clear unconditionally.) */
private fun PropagationState.clearEqIfFreed(atomId: Int) {
    if (atomTruthOf(atomIntVar[atomId], AtomKind.EQ, atomThreshold[atomId]) == null) clearAtomSlot(atomId)
}

/**
 * On backtrack of int var [v], clear ONLY the order literals whose truth flips back to
 * undetermined — exactly the threshold range the domain just widened over (the reverse of
 * [propagateAtomsForVar]'s crossing). Atoms outside that range keep their still-correct
 * (bounds move monotonically) stored truth / level / reason, so every *determined* atom
 * retains a trail-resident level+reason and conflict analysis never needs a bound history.
 *
 * [oldMin]/[oldMax] are the *tight* bounds from before the domain was restored; the current
 * [intDomains] holds the restored (wider) domain. Mirrors [propagateAtomsForVar]'s ranges
 * exactly (off-by-one parity matters: [atomCurrentTruth] has no derive fallback, so a missed
 * flip would leave a stale truth bit).
 */
internal fun PropagationState.resetAtomTrailFor(v: Int, oldMin: Int, oldMax: Int) {
    val idx = atomsByIntVar[v] ?: return
    val d = intDomains[v]
    val newMin = d.min
    val newMax = d.max
    if (newMin < oldMin) {
        visitAtomRange(idx.geKeys, idx.geIds, newMin + 1, oldMin) { id -> clearAtomSlot(id) }
        visitAtomRange(idx.leKeys, idx.leIds, newMin, oldMin - 1) { id -> clearAtomSlot(id) }
        visitAtomRange(idx.eqKeys, idx.eqIds, newMin, oldMin - 1) { id -> clearEqIfFreed(id) }
    }
    if (newMax > oldMax) {
        visitAtomRange(idx.leKeys, idx.leIds, oldMax, newMax - 1) { id -> clearAtomSlot(id) }
        visitAtomRange(idx.geKeys, idx.geIds, oldMax + 1, newMax) { id -> clearAtomSlot(id) }
        visitAtomRange(idx.eqKeys, idx.eqIds, oldMax + 1, newMax) { id -> clearEqIfFreed(id) }
    }
    // A tight singleton {oldMin}'s eq atom was true and sits between the two widened ranges
    // (reverse of [propagateAtomsForVar]'s singleton block) — clear it explicitly.
    if (oldMin == oldMax) {
        visitAtomRange(idx.eqKeys, idx.eqIds, oldMin, oldMin) { id -> clearEqIfFreed(id) }
    }
}

/** Backtrack of an interior carve: re-inserting [value] flips `[v = value]` from false to
 *  undetermined (the bounds are unchanged), so clear that eq atom's slot. */
internal fun PropagationState.resetAtomTrailForCarve(v: Int, value: Int) {
    val idx = atomsByIntVar[v] ?: return
    visitAtomRange(idx.eqKeys, idx.eqIds, value, value) { id -> clearAtomSlot(id) }
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
        val idx = atomLitWatchIndex(lit)
        val list = atomWatchersByLit[idx] ?: IntArrayList(initialCapacity = 2).also { atomWatchersByLit[idx] = it }
        list.add(fid)
        markAtomWatched(atomIdOf(v))
    }
}
