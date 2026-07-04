package com.eignex.klause.propagation

import com.eignex.klause.solver.Lit
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.binarySearchInt

/** Register [atomId] in the watched index (idempotent per threshold/kind). */
internal fun PropagationState.markAtomWatched(atomId: Int) {
    val v = atoms.intVar[atomId]
    val kind = atoms.kind[atomId]
    val k = atoms.threshold[atomId]
    val idx = atoms.watchedByVar[v] ?: VarAtomIndex().also { atoms.watchedByVar[v] = it }
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

/** Translate a virtual atom-var id back to its 0-based atom index. */
internal fun PropagationState.atomIdOf(v: Int): Int = v - problem.numBoolVars

/** Slot index of atom literal [lit] in [AtomStore.watchersByLit]: two slots per
 *  atom — `atomId*2` for the positive literal, `atomId*2+1` for the negative. */
internal fun PropagationState.atomLitWatchIndex(lit: Int): Int =
    (atomIdOf(Lit.variable(lit)) shl 1) or (if (Lit.isPositive(lit)) 0 else 1)

/** Current truth of an atom — read from the stored [AtomStore.truth] slot (kept in
 *  sync with `intDomains` by channeling), not re-derived from the domain on every touch. Returns
 *  `null` when undetermined (the bound isn't either side-decided yet). Used by
 *  [PropagationState.litTrue] / [PropagationState.litFalse] / [PropagationState.pinLit]. */
internal fun PropagationState.atomCurrentTruth(atomId: Int): Boolean? = when (atoms.truth[atomId]) {
    1 -> true
    2 -> false
    else -> null // canonical: atoms.truth is maintained for every materialized atom (no derive)
}

/**
 * Decision level at which atom [atomId]'s **current** truth was established on the
 * **current** search path — read from its trail slot ([AtomStore.lvl]); for an atom materialized after
 * its bound already crossed (no slot stamped), reconstructed from the per-side bound levels or the
 * hole-carve record, both kept current-path-accurate by backtrack. An undetermined atom reports the
 * live decision count (conservative: it can only constrain at the current level).
 */
internal fun PropagationState.atomLevelForConflict(atomId: Int): Int {
    // Trail-resident: a determined order literal carries the level it was established at on
    // its [AtomStore.lvl] slot — set when a bound move crossed it ([wakeAtom]) and kept across
    // backtracks (only the flipped range is reset, see [resetAtomTrailFor]); reconstructed from
    // the per-side [intMinLevel]/[intMaxLevel] slots for an atom materialized at the current bound.
    val stored = atoms.lvl[atomId]
    if (stored >= 0) return stored
    val v = atoms.intVar[atomId]
    val k = atoms.threshold[atomId]
    val truth = atomCurrentTruth(atomId) ?: return levelToDecisionVar.size
    // Unstamped but determined: the atom was materialized after its bound already crossed, at a
    // threshold *looser* than the tight endpoint (the tight case is stamped at allocation). Its
    // truth is fixed by a live endpoint of `v`, so report that endpoint's establishment level — the
    // side [atomAntecedentsDerived] cites the endpoint's reason from. Sound (the live bound entails
    // the looser one) and resolvable: the atom resolves against that reason in the 1UIP loop instead
    // of lingering as a current-level leaf, which is what left cumulative pointwise nogoods
    // non-asserting (#744).
    return when (atoms.kind[atomId]) {
        AtomKind.GE -> endpointLevel(v, viaMax = !truth)

        AtomKind.LE -> endpointLevel(v, viaMax = truth)

        AtomKind.EQ -> if (truth) {
            // A singleton: both endpoints pin it.
            maxOf(endpointLevel(v, viaMax = false), endpointLevel(v, viaMax = true))
        } else {
            val d = intDomains[v]
            // A value carved on this path (or strictly interior) was ruled out at its carve, so the
            // carve level is the real level — not a later bound move that snapped over an
            // already-dead value. A value swept past an endpoint is ruled out by that live endpoint.
            when {
                holeHistHas(v, k) -> holeLevelFor(v, k)
                k < d.min -> endpointLevel(v, viaMax = false)
                k > d.max -> endpointLevel(v, viaMax = true)
                else -> holeLevelFor(v, k)
            }
        }
    }
}

/** Reconstruct the trail level of a freshly-materialized **determined** atom from the per-side
 *  [PropagationState.intMinLevel] / [PropagationState.intMaxLevel] slots, when its threshold is
 *  exactly the current opposing bound (the only case a single slot can answer; the level at which
 *  that endpoint was set is exactly when the atom's truth was decided). Returns -1 ("not
 *  reconstructed") for undetermined atoms, looser-than-current bounds, and interior-hole eq atoms —
 *  those keep -1 and the level read falls back to the hole-carve record. A -1 per-side slot means
 *  the bound is still at its root, i.e. level 0. [st]: 0 = undetermined, 1 = true, 2 = false. */
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
    val existing = atoms.byKey.getOrDefault(key, -1)
    if (existing >= 0) return problem.numBoolVars + existing
    val id = atoms.intVar.size
    atoms.intVar.add(intVar)
    atoms.kind.add(kind)
    atoms.threshold.add(threshold)
    // Snapshot the atom's current truth/level/reason so it is canonical from birth — a
    // determined-at-materialization atom (its bound already crossed) carries stored state
    // without any derive fallback, letting atomCurrentTruth read the bit instead of
    // recomputing from the domain. Maintained on every crossing (wakeAtom) and recomputed on
    // backtrack (resetAtomTrailFor after the domain is restored).
    val st = stateOfTruth(atomTruthOf(intVar, kind, threshold))
    atoms.truth.add(st)
    atoms.lvl.add(reconstructCurrentBoundLevel(intVar, kind, threshold, st))
    atoms.ant.add(reconstructCurrentBoundReason(intVar, kind, threshold, st))
    atoms.watchersByLit.add(null) // positive-literal watcher slot for this atom
    atoms.watchersByLit.add(null) // negative-literal watcher slot
    atoms.byKey.put(key, id)
    (atoms.byIntVar[intVar] ?: VarAtomIndex().also { atoms.byIntVar[intVar] = it }).insert(kind, threshold, id)
    return problem.numBoolVars + id
}

/**
 * Antecedents of atom [atomId]: the reason on its trail slot ([AtomStore.ant]) for an atom established on
 * the current path, else derived on demand. The derived cases: a determined bound atom (of any
 * looseness) cites the live endpoint that fixes its truth ([endpointReason], over other variables);
 * a true eq atom cites both endpoint bounds; a bound-excluded eq atom cites the live endpoint; an
 * interior-hole eq atom resolves to the carve's recorded reason ([holeReasonFor]). `null` marks a
 * root/bake fact or an undetermined atom — the analyzer keeps such literals instead of resolving
 * through them.
 */
internal fun PropagationState.atomAntecedentsDerived(atomId: Int): IntArray? {
    // Trail-resident fast path: an atom established on the current path (stamped by a bound move,
    // or reconstructed at materialization) carries its reason on [AtomStore.ant] at its exact
    // establishment level — return it directly. The derivation below covers only the not-yet-stored
    // cases (looser-than-tight bound atoms, interior-hole eq atoms) and undetermined atoms.
    if (atoms.lvl[atomId] >= 0) return atoms.ant[atomId]
    val v = atoms.intVar[atomId]
    val k = atoms.threshold[atomId]
    val d = intDomains[v]
    val truth = atomCurrentTruth(atomId) ?: return null
    return when (atoms.kind[atomId]) {
        // A determined bound atom is fixed by one live endpoint of `v`: a true `[v ≥ k]` (resp. a
        // false one) by the min (resp. max) bound, a true `[v ≤ k]` (resp. false) by the max (resp.
        // min) bound. Cite that endpoint's stored antecedent ([endpointReason]) for *any* looseness —
        // the live bound entails the looser threshold, so resolution flows to the *other* variables
        // with no same-var GE<->LE round trip (the cycle that left clauses non-asserting, #671/#744).
        AtomKind.GE -> endpointReason(v, viaMax = !truth)

        AtomKind.LE -> endpointReason(v, viaMax = truth)

        AtomKind.EQ -> if (truth) {
            composeIntVarAtomAntecedents(intArrayOf(v))
        } else {
            // Prefer the recorded interior-carve reason whenever the value was carved on the
            // current path — it cites the other variables that forced the exclusion (the original,
            // tightest reason). Otherwise the value was swept past by a bound move, so the live
            // opposing endpoint rules it out; [endpointReason] cites that endpoint's stored
            // reason (other variables), keeping resolution off the same-var GE<->LE cycle that
            // leaves the clause non-asserting (#671).
            when {
                holeHistHas(v, k) -> holeReasonFor(v, k)
                k < d.min -> endpointReason(v, viaMax = false)
                k > d.max -> endpointReason(v, viaMax = true)
                else -> holeReasonFor(v, k)
            }
        }
    }
}

/**
 * Antecedent of a determined bound atom: the **per-var stored antecedent of the live endpoint** that
 * fixes its truth ([PropagationState.intMaxAntecedents] when [viaMax], else
 * [PropagationState.intMinAntecedents]) — the real reason the bound stands where it does, so
 * resolution flows straight to the *other* variables' bounds with no same-var GE<->LE round trip.
 *
 * The classical lazy scheme cited the *complementary* bound atom (`¬[v ≥ k+1]` for a false
 * `[v ≤ k]`); the analyzer then unfolded that atom through its own reason, which couples a
 * GE atom to the LE atom of the same var and back — the same-level cycle that makes 1UIP
 * keep a resolved literal and the learned clause non-asserting (the 0.37%-asserting-rate
 * pathology this rewrite targets). Citing the endpoint directly avoids it.
 *
 * This holds for **any** looseness and **either** polarity, not just an atom adjacent to the
 * endpoint: a false `[v ≥ k]` for every `k > d.max` is justified by `v ≤ d.max`, and a true
 * `[v ≤ k]` for every `k ≥ d.max` is *entailed* by the same `v ≤ d.max` (symmetric on the min side).
 * A `null` stored antecedent means the endpoint is a root/bake fact (no search move set it), the
 * correct leaf.
 *
 * For a threshold strictly past the endpoint this attributes the endpoint's (possibly later)
 * establishment level rather than the exact level the atom's truth was first fixed — sound (a true
 * antecedent cited at its real level), and since the atom resolves against this reason in the 1UIP
 * loop it never lingers as a mis-levelled leaf. The earlier complementary-atom citation kept the
 * level tight but reintroduced the fatal cycle; avoiding the cycle wins.
 */
internal fun PropagationState.endpointReason(v: Int, viaMax: Boolean): IntArray? =
    if (viaMax) intMaxAntecedents[v] else intMinAntecedents[v]

/** Establishment level of the live endpoint that fixes a determined bound atom's truth — the
 *  per-side [PropagationState.intMaxLevel] (when [viaMax]) or [PropagationState.intMinLevel],
 *  paired with [endpointReason]. A -1 slot means the bound is still at its root value, i.e. level 0.
 */
internal fun PropagationState.endpointLevel(v: Int, viaMax: Boolean): Int {
    val lvl = if (viaMax) intMaxLevel[v] else intMinLevel[v]
    return if (lvl >= 0) lvl else 0
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
    antNear: IntArray?,
    antFar: IntArray? = antNear,
    reqMin: Int = intDomains[v].min,
    reqMax: Int = intDomains[v].max,
    oldMin: Int,
    oldMax: Int,
    carved: Int = NO_CARVE,
) {
    // Iterate all materialized atoms so every crossed order literal becomes a chronological
    // trail fact (see [wakeAtom]), with the move's reason recorded on its slot. A move carries
    // two reason sets: [antNear] justifies the *requested* bound (a factor reason over other
    // variables), [antFar] additionally carries the hole-crossing chain when the landed bound
    // snapped past holes. Each crossed literal takes the weakest set that still implies its
    // truth, split by its threshold against the requested [reqMin] / [reqMax]: a literal implied
    // by the requested bound alone gets [antNear]; one that needs the further snap gets [antFar].
    // Critically the requested-bound atom itself ([v ≥ reqMin] / [v ≤ reqMax]) takes [antNear],
    // which does NOT list that atom — [antFar] does (via [antecedentsAcrossHoles]), so giving it
    // [antFar] would be the same-var cycle 1UIP cannot collapse (#671).
    val idx = atoms.byIntVar[v] ?: return
    val d = intDomains[v]
    val newMin = d.min
    val newMax = d.max
    if (newMin > oldMin) {
        atoms.pendingMoveAnt = antFar
        visitAtomRange(idx.geKeys, idx.geIds, oldMin + 1, newMin) { id -> wakeAtom(id, true) }
        visitAtomRange(idx.leKeys, idx.leIds, oldMin, newMin - 1) { id -> wakeAtom(id, false) }
        visitAtomRange(idx.eqKeys, idx.eqIds, oldMin, newMin - 1) { id ->
            recordEqDeath(v, atoms.threshold[id], near = atoms.threshold[id] < reqMin, antNear, antFar)
            wakeAtom(id, false)
        }
    }
    if (newMax < oldMax) {
        atoms.pendingMoveAnt = antFar
        visitAtomRange(idx.leKeys, idx.leIds, newMax, oldMax - 1) { id -> wakeAtom(id, true) }
        visitAtomRange(idx.geKeys, idx.geIds, newMax + 1, oldMax) { id -> wakeAtom(id, false) }
        visitAtomRange(idx.eqKeys, idx.eqIds, newMax + 1, oldMax) { id ->
            recordEqDeath(v, atoms.threshold[id], near = atoms.threshold[id] > reqMax, antNear, antFar)
            wakeAtom(id, false)
        }
    }
    if (carved != NO_CARVE && carved in (newMin + 1) until newMax) {
        atoms.pendingMoveAnt = antFar
        visitAtomRange(idx.eqKeys, idx.eqIds, carved, carved) { id -> wakeAtom(id, false) }
    }
    if (newMin == newMax && (newMin > oldMin || newMax < oldMax)) {
        atoms.pendingMoveAnt = antFar
        visitAtomRange(idx.eqKeys, idx.eqIds, newMin, newMin) { id -> wakeAtom(id, true) }
    }
}

/**
 * Atom maintenance for a *batched* exclusion ([excludeIntValues]): the same crossing logic as
 * [propagateAtomsForVar] for whichever endpoints moved, plus an explicit `[v = x]` → false wake
 * for every interior hole the batch carved. Unlike the single-value path, the min and max sides
 * may both move in one step and rest on *different* reasons ([antMin] / [antMax]); interior carves
 * all rest on [interiorAnt]. End state is identical to folding [propagateAtomsForVar] over the same
 * exclusions one at a time — every order literal whose truth changed is woken exactly once here.
 */
internal fun PropagationState.propagateAtomsForExclusionBatch(
    v: Int,
    oldMin: Int,
    oldMax: Int,
    antMin: IntArray?,
    antMax: IntArray?,
    interiorVals: IntArrayList?,
    interiorAnt: IntArray?,
) {
    val idx = atoms.byIntVar[v] ?: return
    val d = intDomains[v]
    val newMin = d.min
    val newMax = d.max
    if (newMin > oldMin) {
        atoms.pendingMoveAnt = antMin
        visitAtomRange(idx.geKeys, idx.geIds, oldMin + 1, newMin) { id -> wakeAtom(id, true) }
        visitAtomRange(idx.leKeys, idx.leIds, oldMin, newMin - 1) { id -> wakeAtom(id, false) }
        visitAtomRange(idx.eqKeys, idx.eqIds, oldMin, newMin - 1) { id -> wakeAtom(id, false) }
    }
    if (newMax < oldMax) {
        atoms.pendingMoveAnt = antMax
        visitAtomRange(idx.leKeys, idx.leIds, newMax, oldMax - 1) { id -> wakeAtom(id, true) }
        visitAtomRange(idx.geKeys, idx.geIds, newMax + 1, oldMax) { id -> wakeAtom(id, false) }
        visitAtomRange(idx.eqKeys, idx.eqIds, newMax + 1, oldMax) { id -> wakeAtom(id, false) }
    }
    if (interiorVals != null) {
        atoms.pendingMoveAnt = interiorAnt
        for (i in 0 until interiorVals.size) {
            val x = interiorVals[i]
            visitAtomRange(idx.eqKeys, idx.eqIds, x, x) { id -> wakeAtom(id, false) }
        }
    }
    if (newMin == newMax && (newMin > oldMin || newMax < oldMax)) {
        visitAtomRange(idx.eqKeys, idx.eqIds, newMin, newMin) { id -> wakeAtom(id, true) }
    }
}

/**
 * Wake the atoms invalidated by restricting `v`'s domain to [survivors] (a set-restriction seed, whose
 * excluded values are the domain's holes). The bound moves fire exactly as [propagateAtomsForExclusionBatch];
 * the interior is driven off the value-sorted eq-atom index rather than the (potentially span-sized) hole
 * list — only eq-atoms whose value is not a survivor are woken false. Cost is O(eq-atoms in range), never
 * O(span), which is the point of recording the reduction as a survivor set. [ant] is the (root-seed) reason.
 */
internal fun PropagationState.propagateAtomsForSetRestriction(
    v: Int,
    oldMin: Int,
    oldMax: Int,
    survivors: IntArray,
    ant: IntArray?,
) {
    val idx = atoms.byIntVar[v] ?: return
    val d = intDomains[v]
    val newMin = d.min
    val newMax = d.max
    if (newMin > oldMin) {
        atoms.pendingMoveAnt = ant
        visitAtomRange(idx.geKeys, idx.geIds, oldMin + 1, newMin) { id -> wakeAtom(id, true) }
        visitAtomRange(idx.leKeys, idx.leIds, oldMin, newMin - 1) { id -> wakeAtom(id, false) }
        visitAtomRange(idx.eqKeys, idx.eqIds, oldMin, newMin - 1) { id -> wakeAtom(id, false) }
    }
    if (newMax < oldMax) {
        atoms.pendingMoveAnt = ant
        visitAtomRange(idx.leKeys, idx.leIds, newMax, oldMax - 1) { id -> wakeAtom(id, true) }
        visitAtomRange(idx.geKeys, idx.geIds, newMax + 1, oldMax) { id -> wakeAtom(id, false) }
        visitAtomRange(idx.eqKeys, idx.eqIds, newMax + 1, oldMax) { id -> wakeAtom(id, false) }
    }
    atoms.pendingMoveAnt = ant
    visitAtomRange(idx.eqKeys, idx.eqIds, newMin, newMax) { id ->
        if (survivors.binarySearchInt(atoms.threshold[id]) < 0) wakeAtom(id, false)
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

/** Record an eq atom's carve reason at its first death during a bound move (#671). A value killed in
 *  the move's NEAR region — below the raised min / above the lowered max *requested* bound — is ruled
 *  out by that requested bound alone, whose reason [antNear] cites other variables: a sound, acyclic
 *  per-value reason (`antNear ⟹ v ≥ reqMin ⟹ v ≠ k` for `k < reqMin`, symmetric for the max side).
 *  Recorded once (guarded) so a later bound move that snaps across the now-dead value finds it via
 *  [holeReasonFor] instead of deriving it from the live opposing bound — i.e. from that later move,
 *  whose reason lists this very eq-atom, the same-var cycle 1UIP cannot collapse. A FAR-region
 *  crossing reuses the record from the value's first (near) death. [AtomStore.pendingMoveAnt] is the fallback
 *  reason [wakeAtom] uses when no record is on file. */
private fun PropagationState.recordEqDeath(v: Int, k: Int, near: Boolean, antNear: IntArray?, antFar: IntArray?) {
    if (near && !holeHistHas(v, k)) pushHoleHist(v, k, currentLevel, antNear)
    atoms.pendingMoveAnt = if (near) antNear else antFar
}

/** Record [atomId]'s establishment after a bound move flipped its truth to [newT], then wake the
 *  watchers of the now-false literal. The move crossed the atom's threshold, so its truth was
 *  established at the move's [PropagationState.currentLevel]: store the truth ([AtomStore.truth]),
 *  that level ([AtomStore.lvl]) and the move's literal-form reason ([AtomStore.ant]) on
 *  the atom's trail slot, so [atomLevelForConflict] / [atomAntecedentsDerived] read them directly
 *  (a crossing at level L *is* the level the bound first reached the threshold). The slot is reset
 *  on backtrack of the underlying var ([resetAtomTrailFor]). */
internal fun PropagationState.wakeAtom(atomId: Int, newT: Boolean) {
    val targetState = if (newT) 1 else 2
    // Single establishment: stamp the trail slot only when the atom's truth actually flips into
    // [targetState]. A later bound move that merely re-crosses an atom already at this truth (a
    // bound still satisfied, a hole still excluded) leaves its FIRST establishment intact — the
    // level/position/reason where its truth was really decided on the current path. Re-stamping it
    // at the later move would mis-date the literal (a hole carved at level 3 then swept at level 5
    // is false since level 3) and append a second, out-of-order trail entry, both of which break
    // the reverse-assignment-order resolution the unified trail relies on. Watchers still fire so
    // dependent factors re-examine the re-crossed literal. (#612 trail residency.)
    if (atoms.truth[atomId] == targetState) {
        val ww = atoms.watchersByLit[(atomId shl 1) or (if (!newT) 0 else 1)] ?: return
        for (j in 0 until ww.size) atoms.dirtyFactors.addLast(ww[j])
        return
    }
    boolPinOrder.add(problem.numBoolVars + atomId)
    atoms.truth[atomId] = targetState
    atoms.lvl[atomId] = currentLevel
    // Record the establishment reason on the trail slot: a true eq atom (the var just became
    // the singleton {k}) rests on BOTH endpoint bounds, so cite them; a crossed hole (false eq)
    // rests on why its value was excluded — its per-value carve reason ([holeReasonFor]), which
    // cites other variables. The bound move's reason ([AtomStore.pendingMoveAnt]) lists this very eq-atom
    // when it snapped across the hole ([antecedentsAcrossHoles]); citing it back would form the
    // same-var cycle 1UIP cannot collapse (#671). Every other crossed literal rests on the move.
    atoms.ant[atomId] = when {
        newT && atoms.kind[atomId] == AtomKind.EQ ->
            composeIntVarAtomAntecedents(intArrayOf(atoms.intVar[atomId]))

        !newT && atoms.kind[atomId] == AtomKind.EQ && holeHistHas(atoms.intVar[atomId], atoms.threshold[atomId]) ->
            holeReasonFor(atoms.intVar[atomId], atoms.threshold[atomId])

        else -> atoms.pendingMoveAnt
    }
    // Wake watchers of the now-false literal: index = atomId*2 + (positive?0:1); the false
    // literal's polarity is `!newT`, so its slot is `atomId*2 + (!newT ? 0 : 1)`.
    val w = atoms.watchersByLit[(atomId shl 1) or (if (!newT) 0 else 1)] ?: return
    for (j in 0 until w.size) atoms.dirtyFactors.addLast(w[j])
}

/** Encode a three-valued truth as the [AtomStore.truth] code: true→1, false→2, null→0. */
internal fun stateOfTruth(t: Boolean?): Int = when (t) {
    true -> 1
    false -> 2
    null -> 0
}

/** Clear [atomId]'s trail slot to "undetermined / unestablished". */
private fun PropagationState.clearAtomSlot(atomId: Int) {
    atoms.truth[atomId] = 0
    atoms.lvl[atomId] = -1
    atoms.ant[atomId] = null
}

/** Reset an EQ atom in a widened range. If its value re-enters the restored domain it flips
 *  false→undetermined and the slot is cleared ([clearAtomSlot]); if it is still an interior hole the
 *  slot is **kept** untouched.
 *
 *  Keeping it is sound under single establishment ([wakeAtom] never re-stamps a still-determined
 *  atom): the slot holds the atom's first establishment — its carve / first death, recorded at the
 *  level its truth was really decided — and that establishment is still in force (the value is still
 *  excluded, by a move below this one on the trail). The carve's own undo ([resetAtomTrailForCarve],
 *  or [clearAtomSlot] here when the *killing* move is the one being undone) clears it once we
 *  backtrack past it; its [PropagationState.boolPinOrder] entry sits below this mark and survives the
 *  truncation in step. (The former "reset to derive-from-history" was needed only because a sweeping bound move
 *  used to *overwrite* the carve slot; single establishment removes that overwrite.)
 *  (GE/LE atoms never sit on holes, so they clear unconditionally in the caller.) */
private fun PropagationState.clearEqIfFreed(atomId: Int) {
    if (atomTruthOf(atoms.intVar[atomId], AtomKind.EQ, atoms.threshold[atomId]) == null) {
        clearAtomSlot(atomId)
    } // still excluded ⇒ keep the first (carve) establishment
}

/**
 * On backtrack of int var [v], clear ONLY the order literals whose truth flips back to
 * undetermined — exactly the threshold range the domain just widened over (the reverse of
 * [propagateAtomsForVar]'s crossing). Atoms outside that range keep their still-correct
 * (bounds move monotonically) stored truth / level / reason, so every *determined* atom
 * retains a trail-resident level+reason and conflict analysis never needs a bound history.
 *
 * [oldMin]/[oldMax] are the *tight* bounds from before the domain was restored; the current
 * [PropagationState.intDomains] holds the restored (wider) domain. Mirrors [propagateAtomsForVar]'s ranges
 * exactly (off-by-one parity matters: [atomCurrentTruth] has no derive fallback, so a missed
 * flip would leave a stale truth bit).
 */
internal fun PropagationState.resetAtomTrailFor(v: Int, oldMin: Int, oldMax: Int) {
    val idx = atoms.byIntVar[v] ?: return
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
    val idx = atoms.byIntVar[v] ?: return
    visitAtomRange(idx.eqKeys, idx.eqIds, value, value) { id -> clearAtomSlot(id) }
}

/** Install [fid] as a watcher of [lit]. Dispatches between [BoolWatcherIndex.byLit]
 *  (bool var space) and [AtomStore.watchersByLit] (atom var space). */
internal fun PropagationState.installLitWatch(lit: Int, fid: Int, blocker: Int = NO_BLOCKER) {
    val v = Lit.variable(lit)
    if (v < problem.numBoolVars) {
        val list = watches.byLit[lit]
        watches.pos.put(packWatch(fid, lit), list.size) // position of the about-to-append entry
        list.add(fid)
        watches.blockersByLit[lit].add(blocker) // index-aligned with the watcher just appended
    } else {
        val idx = atomLitWatchIndex(lit)
        val list = atoms.watchersByLit[idx] ?: IntArrayList(initialCapacity = 2).also { atoms.watchersByLit[idx] = it }
        list.add(fid)
        markAtomWatched(atomIdOf(v))
    }
}
