package com.eignex.klause.propagation

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.solver.Lit
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.LongArrayList
import com.eignex.klause.util.binarySearchLong

/** Register [atomId] in the watched index (idempotent per threshold/kind). */
internal fun PropagationState.markAtomWatched(atomId: Int) {
    val v = atoms.intVar[atomId]
    val kind = atoms.kind[atomId]
    val k = atoms.threshold[atomId]
    val idx = atoms.watchedByVar[v] ?: VarAtomIndex().also { atoms.watchedByVar[v] = it }
    // A membership test, so it goes through the merge-free lookup: reading the ordered array here would
    // fold the staging buffer on every watch installation, which is the whole cost this index avoids.
    if (idx.find(kind, k) >= 0) return // already tracked
    idx.insert(kind, k, atomId)
}

/** Translate a virtual atom-var id back to its 0-based atom index. */
internal fun PropagationState.atomIdOf(v: Int): Int = v - problem.numBoolVars

/** Slot index of atom literal [lit] in [AtomStore.watchersByLit]: two slots per
 *  atom — `atomId*2` for the positive literal, `atomId*2+1` for the negative. */
internal fun PropagationState.atomLitWatchIndex(lit: Int): Int =
    (atomIdOf(Lit.variable(lit)) shl 1) or (if (Lit.isPositive(lit)) 0 else 1)

/** Current truth of an atom. The stored [AtomStore.truth] slot is the fast path — a forward cache
 *  filled the instant a bound move crosses the threshold ([wakeAtom]) or a clause forces the literal
 *  ([PropagationState.pinAtomLit]), and undone on backtrack by the reversible atom trail. A `0` slot
 *  is *not* necessarily undetermined: an atom materialized after its bound already crossed carries no
 *  cached truth, so fall back to deriving it from the live domain ([atomTruthOf]) — sound and always
 *  current, just uncached. Used by [PropagationState.litTrue] / [PropagationState.litFalse] /
 *  [PropagationState.pinLit]. */
internal fun PropagationState.atomCurrentTruth(atomId: Int): Boolean? = when (atoms.truth[atomId]) {
    1 -> true
    2 -> false
    else -> atomTruthOf(atoms.intVar[atomId], atoms.kind[atomId], atoms.threshold[atomId])
}

/**
 * Decision level at which atom [atomId]'s **current** truth was established on the
 * **current** search path — read from its trail slot ([AtomStore.lvl]); for an atom materialized after
 * its bound already crossed (no slot stamped), reconstructed from the per-side bound levels or the
 * hole-carve record, both kept current-path-accurate by backtrack. An undetermined atom reports the
 * live decision count (conservative: it can only constrain at the current level).
 */
internal fun PropagationState.atomLevelForConflict(atomId: Int): Int {
    // Trail-resident: an atom assigned on the current path carries the level it was established at
    // on its [AtomStore.lvl] slot (a crossing/clause at level L is the level its truth was decided).
    val stored = atoms.lvl[atomId]
    if (stored >= 0) return stored
    // Lazy-materialized determined atom (materialized after its bound crossed): its level is the
    // establishment level of the live endpoint that fixes its truth. Exact for a threshold the endpoint
    // sits on, and that is the only case [atomAntecedentsDerived] explains; for one the endpoint has
    // overshot this over-states the level (the truth was really fixed by an earlier move), which keeps the
    // backjump shallow and the literal in the clause — sound, just weaker.
    val v = atoms.intVar[atomId]
    val k = atoms.threshold[atomId]
    val truth = atomCurrentTruth(atomId) ?: return levelToDecisionVar.size
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

internal fun PropagationState.allocAtom(intVar: Int, kind: AtomKind, threshold: Long): Int {
    val byVar = atoms.byIntVar[intVar]
    if (byVar != null) {
        val existing = byVar.find(kind, threshold)
        if (existing >= 0) return problem.numBoolVars + existing
    }
    val id = atoms.intVar.size
    atoms.intVar.add(intVar)
    atoms.kind.add(kind)
    atoms.threshold.add(threshold)
    // Register the atom undetermined regardless of the live domain. Its stored truth is a forward
    // cache owned solely by the reversible atom trail: filled only when a bound move or a clause
    // establishes it *on the current path* (a change the trail can undo), never snapshotted from a
    // bound that crossed before this atom existed (which the trail could not undo — the crossing has
    // no record on it). A determined-at-materialization atom reads its truth/level/reason from the
    // live domain instead ([atomCurrentTruth] / [atomLevelForConflict] / [atomAntecedentsDerived]
    // derive fallbacks), sound and current though uncached.
    atoms.truth.add(0)
    atoms.lvl.add(-1)
    atoms.ant.add(null)
    atoms.watchersByLit.add(null) // positive-literal watcher slot for this atom
    atoms.watchersByLit.add(null) // negative-literal watcher slot
    (byVar ?: VarAtomIndex().also { atoms.byIntVar[intVar] = it }).insert(kind, threshold, id)
    registerChannelingFor(id)
    return problem.numBoolVars + id
}

/**
 * Queue the LCG channeling clauses linking the just-materialized atom [newAtomId] to its already-
 * materialized same-var neighbours: order monotonicity between adjacent GE (resp. LE) thresholds
 * (`[x≥kt]→[x≥k]`, `[x≥k]→[x≥kl]`), and the eq↔bound links (`[x=k]→[x≥k]`, `[x=k]→[x≤k]`). Only
 * immediate neighbours are linked; transitivity across the chain covers the rest. Each atom is
 * materialized once, so each pair is queued once (no dedup needed). The clauses are appended to
 * [PropagationState.pendingChanneling] and registered as permanent clauses by
 * [flushPendingChanneling] at the next safe propagation boundary — never here, since [allocAtom]
 * also runs inside conflict analysis where the clause store must not be mutated.
 *
 * Skipped in incremental-presolve mode: channeling clauses are a search-time LCG reasoning aid, and
 * presolve only narrows domains (no conflict analysis needs them). Registering them there would push
 * factors into the learned-clause store while incremental [factorAt] routes every tail id to the
 * mid-life store, mixing the two stores under one id space.
 */
internal fun PropagationState.registerChannelingFor(newAtomId: Int) {
    if (incremental) return
    val v = atoms.intVar[newAtomId]
    val k = atoms.threshold[newAtomId]
    val idx = atoms.byIntVar[v] ?: return
    val nb = problem.numBoolVars
    val newVar = nb + newAtomId
    when (atoms.kind[newAtomId]) {
        AtomKind.GE -> {
            val run = idx.runOf(AtomKind.GE)
            val up = run.above(k)
            if (up >= 0) enqueueChannel(nb + up, newVar) // [x≥kt] → [x≥k]
            val down = run.below(k)
            if (down >= 0) enqueueChannel(newVar, nb + down) // [x≥k] → [x≥kl]
            val eq = idx.find(AtomKind.EQ, k)
            if (eq >= 0) enqueueChannel(nb + eq, newVar) // [x=k] → [x≥k]
            val dual = idx.find(AtomKind.LE, k - 1)
            if (dual >= 0) enqueueDuality(newVar, nb + dual) // [x≥k] ⟺ ¬[x≤k-1]
            val le = idx.find(AtomKind.LE, k)
            if (eq >= 0 && le >= 0) enqueueBoundsEq(newVar, nb + le, nb + eq) // [x≥k]∧[x≤k] → [x=k]
        }

        AtomKind.LE -> {
            val run = idx.runOf(AtomKind.LE)
            val down = run.below(k)
            if (down >= 0) enqueueChannel(nb + down, newVar) // [x≤kl] → [x≤k]
            val up = run.above(k)
            if (up >= 0) enqueueChannel(newVar, nb + up) // [x≤k] → [x≤kt]
            val eq = idx.find(AtomKind.EQ, k)
            if (eq >= 0) enqueueChannel(nb + eq, newVar) // [x=k] → [x≤k]
            val dual = idx.find(AtomKind.GE, k + 1)
            if (dual >= 0) enqueueDuality(nb + dual, newVar) // [x≥k+1] ⟺ ¬[x≤k]
            val ge = idx.find(AtomKind.GE, k)
            if (eq >= 0 && ge >= 0) enqueueBoundsEq(nb + ge, newVar, nb + eq) // [x≥k]∧[x≤k] → [x=k]
        }

        AtomKind.EQ -> {
            val ge = idx.find(AtomKind.GE, k)
            if (ge >= 0) enqueueChannel(newVar, nb + ge) // [x=k] → [x≥k]
            val le = idx.find(AtomKind.LE, k)
            if (le >= 0) enqueueChannel(newVar, nb + le) // [x=k] → [x≤k]
            if (ge >= 0 && le >= 0) enqueueBoundsEq(nb + ge, nb + le, newVar) // [x≥k]∧[x≤k] → [x=k]
        }
    }
}

/** Queue the channeling clause `¬[negVar] ∨ [posVar]` (i.e. `negVar → posVar`). */
private fun PropagationState.enqueueChannel(negVar: Int, posVar: Int) {
    pendingChanneling.add(intArrayOf(Lit.make(negVar, false), Lit.make(posVar, true)))
}

/** Queue the two GE/LE duality clauses for a complementary pair [geVar] = `[x≥m]`, [leVar] = `[x≤m-1]`
 *  (`[x≥m] ⟺ ¬[x≤m-1]`): `¬ge ∨ ¬le` (never both) and `ge ∨ le` (always one). Together they cascade
 *  a bound move across the GE/LE split — a raised min turning a `[x≤t]` false, a lowered max turning
 *  a `[x≥t]` false. */
private fun PropagationState.enqueueDuality(geVar: Int, leVar: Int) {
    pendingChanneling.add(intArrayOf(Lit.make(geVar, false), Lit.make(leVar, false)))
    pendingChanneling.add(intArrayOf(Lit.make(geVar, true), Lit.make(leVar, true)))
}

/** Queue the bounds→eq channeling clause `¬[x≥k] ∨ ¬[x≤k] ∨ [x=k]` — the two bounds pinning the
 *  var to the singleton `{k}` force the eq atom true. (The reverse, `[x=k] → [x≥k]`/`[x≤k]`, is the
 *  pair of eq→bound clauses.) Together they realize `[x=k] ⟺ [x≥k] ∧ [x≤k]`. */
private fun PropagationState.enqueueBoundsEq(geVar: Int, leVar: Int, eqVar: Int) {
    pendingChanneling.add(intArrayOf(Lit.make(geVar, false), Lit.make(leVar, false), Lit.make(eqVar, true)))
}

/** Register every queued channeling clause as a permanent learned clause. Called at a safe
 *  propagation boundary (top of [PropagationState.runToFixpoint]) so the store is never mutated mid-analysis. The
 *  clauses are structural tautologies of the integer semantics — always valid, kept across every
 *  backtrack and forgetting pass — so they serve as sound reasons and a domain/atom consistency net. */
internal fun PropagationState.flushPendingChanneling() {
    if (pendingChanneling.isEmpty()) return
    val batch = pendingChanneling.toTypedArray()
    pendingChanneling.clear()
    for (lits in batch) addLearnedClause(Clause(lits), lbd = lits.size, permanent = true)
}

/**
 * Antecedents of atom [atomId]: the reason on its trail slot ([AtomStore.ant]) for an atom established on
 * the current path, else derived on demand. The derived cases: a bound atom sitting exactly *at* the live
 * endpoint cites that endpoint's own premises ([endpointReason], over other variables); a true eq atom
 * cites both endpoint bounds; an interior-hole eq atom resolves to the carve's recorded reason
 * ([holeReasonFor]). `null` marks a root/bake fact, an undetermined atom, or an atom whose threshold the
 * live endpoint has since overshot — the analyzer keeps such literals instead of resolving through them.
 *
 * A threshold the endpoint has overshot deliberately gets no reason. Its truth was established by the
 * *earlier* move that first crossed the threshold, and only the live endpoint is on record, so any reason
 * built here would explain the atom by premises established after it — a back edge in the reason graph,
 * which makes the reverse-establishment resolution order 1UIP relies on unsatisfiable and lets an
 * already-resolved premise recur. Keeping the literal loses learning strength on that atom and nothing
 * else.
 */
internal fun PropagationState.atomAntecedentsDerived(atomId: Int): IntArray? {
    // Trail-resident: an atom assigned on the current path (a bound move crossed it — [wakeAtom] —
    // or a channeling / learned clause forced it — [pinAtomLit]) carries its forcing clause on the
    // [AtomStore.ant] slot; return it directly. A `null` slot at a stamped level is a decision/leaf.
    if (atoms.lvl[atomId] >= 0) return atoms.ant[atomId]
    val v = atoms.intVar[atomId]
    val k = atoms.threshold[atomId]
    val d = intDomains[v]
    val truth = atomCurrentTruth(atomId) ?: return null
    // Only a threshold the live endpoint sits exactly on can be explained: that endpoint move is the
    // one that established this atom's truth, so its premises ([endpointReason], over other
    // variables) are all established before it, and before anything that cites the atom. A true eq
    // atom is the singleton case and cites its two live endpoint bounds; a carved eq-false atom
    // cites the cross-variable carve reason. Every reason is a valid standalone clause, so recursive
    // clause minimization resolving through it stays sound.
    return when (atoms.kind[atomId]) {
        AtomKind.GE ->
            if (truth) {
                if (k >= d.min) endpointReason(v, viaMax = false) else null
            } else {
                if (k - 1 <= d.max) endpointReason(v, viaMax = true) else null
            }

        AtomKind.LE ->
            if (truth) {
                if (k <= d.max) endpointReason(v, viaMax = true) else null
            } else {
                if (k + 1 >= d.min) endpointReason(v, viaMax = false) else null
            }

        AtomKind.EQ ->
            if (truth) {
                composeIntVarAtomAntecedents(intArrayOf(v))
            } else {
                if (holeHistHas(v, k) || k in d.min..d.max) holeReasonFor(v, k) else null
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
 * loop it never lingers as a mis-levelled leaf. Citing the complementary atom instead would keep
 * the level tight but reintroduce a fatal reason cycle; avoiding the cycle wins.
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

internal fun PropagationState.atomTruthOf(v: Int, kind: AtomKind, k: Long): Boolean? {
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
 * Wake the order literals crossed by a lower-bound move `oldMin → newMin` under reason [ant]:
 * `[v ≥ t]` atoms in `(oldMin, newMin]` flip true, `[v ≤ t]` atoms in `[oldMin, newMin)` flip
 * false, and [eqAction] runs for each `[v = t]` atom in `[oldMin, newMin)` — the single variation
 * point across the three crossing paths (the single-move path also records the value's death
 * record; the batch and set-restriction paths just wake false). Inline so the eq callback and the
 * range visits stay allocation-free. Soundness rests on visiting *every* crossed threshold — a
 * missed wake strands a stale atom truth (see the [IntEvent] contract).
 */
private inline fun PropagationState.wakeMinCrossing(
    idx: VarAtomIndex,
    oldMin: Long,
    newMin: Long,
    ant: IntArray?,
    eqAction: (atomId: Int) -> Unit,
) {
    atoms.pendingMoveAnt = ant
    // The frontier `[v ≥ newMin]` goes on the trail before the looser thresholds that cite it as their
    // monotonicity anchor ([channelingReasonAtWake]): 1UIP resolves the trail in reverse establishment
    // order, so a premise stamped *after* the literal it explains would be resolved out first and then
    // recur as a genuine premise. The ascending visit reaches the frontier last, hence the explicit wake.
    val frontier = idx.find(AtomKind.GE, newMin)
    if (frontier >= 0) wakeAtom(frontier, true)
    idx.ge.visitRange(oldMin + 1, newMin - 1) { id -> wakeAtom(id, true) }
    idx.le.visitRange(oldMin, newMin - 1) { id -> wakeAtom(id, false) }
    idx.eq.visitRange(oldMin, newMin - 1, eqAction)
}

/** Mirror of [wakeMinCrossing] for an upper-bound move `oldMax → newMax`: `[v ≤ t]` in
 *  `[newMax, oldMax)` flip true, `[v ≥ t]` in `(newMax, oldMax]` flip false, [eqAction] per
 *  `[v = t]` in `(newMax, oldMax]`. */
private inline fun PropagationState.wakeMaxCrossing(
    idx: VarAtomIndex,
    newMax: Long,
    oldMax: Long,
    ant: IntArray?,
    eqAction: (atomId: Int) -> Unit,
) {
    atoms.pendingMoveAnt = ant
    idx.le.visitRange(newMax, oldMax - 1) { id -> wakeAtom(id, true) }
    idx.ge.visitRange(newMax + 1, oldMax) { id -> wakeAtom(id, false) }
    idx.eq.visitRange(newMax + 1, oldMax, eqAction)
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
    reqMin: Long = intDomains[v].min,
    reqMax: Long = intDomains[v].max,
    oldMin: Long,
    oldMax: Long,
    carved: Long = NO_CARVE,
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
    // [antFar] would be the same-var cycle 1UIP cannot collapse.
    val idx = atoms.byIntVar[v] ?: return
    val d = intDomains[v]
    val newMin = d.min
    val newMax = d.max
    // Materialize the live frontier bound atoms *before* the crossing loop, so a crossed looser
    // atom can cite the frontier as its monotonicity anchor ([channelingReasonAtWake]) without a
    // mid-iteration insert into this var's atom index. Idempotent: usually already present.
    if (newMin > oldMin) atomVarGe(v, newMin)
    if (newMax < oldMax) atomVarLe(v, newMax)
    if (newMin > oldMin) {
        wakeMinCrossing(idx, oldMin, newMin, antFar) { id ->
            recordEqDeath(v, atoms.threshold[id], near = atoms.threshold[id] < reqMin, antNear, antFar)
            wakeAtom(id, false)
        }
    }
    if (newMax < oldMax) {
        wakeMaxCrossing(idx, newMax, oldMax, antFar) { id ->
            recordEqDeath(v, atoms.threshold[id], near = atoms.threshold[id] > reqMax, antNear, antFar)
            wakeAtom(id, false)
        }
    }
    if (carved != NO_CARVE && carved in (newMin + 1) until newMax) {
        atoms.pendingMoveAnt = antFar
        idx.eq.visitRange(carved, carved) { id -> wakeAtom(id, false) }
    }
    if (newMin == newMax && (newMin > oldMin || newMax < oldMax)) {
        atoms.pendingMoveAnt = antFar
        idx.eq.visitRange(newMin, newMin) { id -> wakeAtom(id, true) }
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
    oldMin: Long,
    oldMax: Long,
    antMin: IntArray?,
    antMax: IntArray?,
    interiorVals: LongArrayList?,
    interiorAnt: IntArray?,
) {
    val idx = atoms.byIntVar[v] ?: return
    val d = intDomains[v]
    val newMin = d.min
    val newMax = d.max
    if (newMin > oldMin) {
        wakeMinCrossing(idx, oldMin, newMin, antMin) { id -> wakeAtom(id, false) }
    }
    if (newMax < oldMax) {
        wakeMaxCrossing(idx, newMax, oldMax, antMax) { id -> wakeAtom(id, false) }
    }
    if (interiorVals != null) {
        atoms.pendingMoveAnt = interiorAnt
        for (i in 0 until interiorVals.size) {
            val x = interiorVals[i]
            idx.eq.visitRange(x, x) { id -> wakeAtom(id, false) }
        }
    }
    if (newMin == newMax && (newMin > oldMin || newMax < oldMax)) {
        idx.eq.visitRange(newMin, newMin) { id -> wakeAtom(id, true) }
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
    oldMin: Long,
    oldMax: Long,
    survivors: LongArray,
    ant: IntArray?,
) {
    val idx = atoms.byIntVar[v] ?: return
    val d = intDomains[v]
    val newMin = d.min
    val newMax = d.max
    if (newMin > oldMin) {
        wakeMinCrossing(idx, oldMin, newMin, ant) { id -> wakeAtom(id, false) }
    }
    if (newMax < oldMax) {
        wakeMaxCrossing(idx, newMax, oldMax, ant) { id -> wakeAtom(id, false) }
    }
    atoms.pendingMoveAnt = ant
    idx.eq.visitRange(newMin, newMax) { id ->
        if (survivors.binarySearchLong(atoms.threshold[id]) < 0) wakeAtom(id, false)
    }
    if (newMin == newMax && (newMin > oldMin || newMax < oldMax)) {
        idx.eq.visitRange(newMin, newMin) { id -> wakeAtom(id, true) }
    }
}

/** Record an eq atom's carve reason at its first death during a bound move. A value killed in
 *  the move's NEAR region — below the raised min / above the lowered max *requested* bound — is ruled
 *  out by that requested bound alone, whose reason [antNear] cites other variables: a sound, acyclic
 *  per-value reason (`antNear ⟹ v ≥ reqMin ⟹ v ≠ k` for `k < reqMin`, symmetric for the max side).
 *  Recorded once (guarded) so a later bound move that snaps across the now-dead value finds it via
 *  [holeReasonFor] instead of deriving it from the live opposing bound — i.e. from that later move,
 *  whose reason lists this very eq-atom, the same-var cycle 1UIP cannot collapse. A FAR-region
 *  crossing reuses the record from the value's first (near) death. [AtomStore.pendingMoveAnt] is the fallback
 *  reason [wakeAtom] uses when no record is on file. */
private fun PropagationState.recordEqDeath(v: Int, k: Long, near: Boolean, antNear: IntArray?, antFar: IntArray?) {
    if (near && !holeHistHas(v, k)) pushHoleHist(v, k, currentLevel, antNear)
    atoms.pendingMoveAnt = if (near) antNear else antFar
}

/** Push [atomId]'s current truth/level/reason onto the reversible atom trail before a forward step
 *  ([wakeAtom] / [PropagationState.pinAtomLit]) overwrites it, so [PropagationState.undoTo] restores
 *  it exactly on backtrack. No-op when undo logging is off — the one-shot propagate / bake fixpoint
 *  never backtracks, so it pays nothing (mirrors the int/bool `log*` guards). */
internal fun PropagationState.recordAtomTruthChange(atomId: Int) {
    if (!undoLogging) return
    atoms.undoAtomId.add(atomId)
    atoms.undoTruth.add(atoms.truth[atomId])
    atoms.undoLvl.add(atoms.lvl[atomId])
    atoms.undoAnt.add(atoms.ant[atomId])
}

/** Record [atomId]'s establishment after a bound move flipped its truth to [newT], then wake the
 *  watchers of the now-false literal. The move crossed the atom's threshold, so its truth was
 *  established at the move's [PropagationState.currentLevel]: store the truth ([AtomStore.truth]),
 *  that level ([AtomStore.lvl]) and the move's literal-form reason ([AtomStore.ant]) on
 *  the atom's trail slot, so [atomLevelForConflict] / [atomAntecedentsDerived] read them directly
 *  (a crossing at level L *is* the level the bound first reached the threshold). The prior slot is
 *  pushed on the reversible atom trail first ([recordAtomTruthChange]) so backtrack restores it. */
internal fun PropagationState.wakeAtom(atomId: Int, newT: Boolean) {
    val targetState = if (newT) 1 else 2
    // Single establishment: stamp the trail slot only when the atom's truth actually flips into
    // [targetState]. A later bound move that merely re-crosses an atom already at this truth (a
    // bound still satisfied, a hole still excluded) leaves its FIRST establishment intact — the
    // level/position/reason where its truth was really decided on the current path. Re-stamping it
    // at the later move would mis-date the literal (a hole carved at level 3 then swept at level 5
    // is false since level 3) and append a second, out-of-order trail entry, both of which break
    // the reverse-assignment-order resolution the unified trail relies on. Watchers still fire so
    // dependent factors re-examine the re-crossed literal.
    if (atoms.truth[atomId] == targetState) {
        val ww = atoms.watchersByLit[(atomId shl 1) or (if (!newT) 0 else 1)] ?: return
        for (j in 0 until ww.size) atoms.dirtyFactors.addLast(ww[j])
        return
    }
    recordAtomTruthChange(atomId)
    boolPinOrder.add(problem.numBoolVars + atomId)
    atoms.truth[atomId] = targetState
    atoms.lvl[atomId] = currentLevel
    // Record the establishment reason on the trail slot: a true eq atom (the var just became
    // the singleton {k}) rests on BOTH endpoint bounds, so cite them; a crossed hole (false eq)
    // rests on why its value was excluded — its per-value carve reason ([holeReasonFor]), which
    // cites other variables. The bound move's reason ([AtomStore.pendingMoveAnt]) lists this very eq-atom
    // when it snapped across the hole ([antecedentsAcrossHoles]); citing it back would form the
    // same-var cycle 1UIP cannot collapse. Every other crossed literal rests on the move.
    atoms.ant[atomId] = when {
        newT && atoms.kind[atomId] == AtomKind.EQ ->
            composeIntVarAtomAntecedents(intArrayOf(atoms.intVar[atomId]))

        !newT && atoms.kind[atomId] == AtomKind.EQ && holeHistHas(atoms.intVar[atomId], atoms.threshold[atomId]) ->
            holeReasonFor(atoms.intVar[atomId], atoms.threshold[atomId])

        // A bound atom crossed by this move: the tight frontier atom (threshold at the live bound)
        // rests on the move's explanation; a looser one is entailed by that frontier atom through a
        // monotonicity channeling clause, so it cites the single frontier literal. Recording the
        // channeling clause body here — not the move's premises — keeps a looser literal's reason a
        // valid standalone clause the analyzer can resolve through.
        else -> channelingReasonAtWake(atomId, newT)
    }
    // Wake watchers of the now-false literal: index = atomId*2 + (positive?0:1); the false
    // literal's polarity is `!newT`, so its slot is `atomId*2 + (!newT ? 0 : 1)`.
    val w = atoms.watchersByLit[(atomId shl 1) or (if (!newT) 0 else 1)] ?: return
    for (j in 0 until w.size) atoms.dirtyFactors.addLast(w[j])
}

/**
 * The channeling reason a crossed bound atom is stamped with (see [wakeAtom]). The tight frontier
 * atom — threshold exactly at the live bound — rests on the move's explanation
 * ([AtomStore.pendingMoveAnt], the propagator's premises over other variables). A looser atom is
 * entailed by that frontier atom via a monotonicity channeling clause, so it cites the single
 * frontier literal `¬[x≥min]` / `¬[x≤max]`. The frontier atom is materialized *before* the crossing
 * loop by [propagateAtomsForVar], so the [PropagationState.atomVarGe] / [PropagationState.atomVarLe]
 * look-ups here are idempotent and never mutate the index mid-iteration.
 */
private fun PropagationState.channelingReasonAtWake(atomId: Int, newT: Boolean): IntArray? {
    val v = atoms.intVar[atomId]
    val k = atoms.threshold[atomId]
    val d = intDomains[v]
    val idx = atoms.byIntVar[v]

    // Non-materializing frontier lookup: the frontier bound atom is pre-materialized by
    // [propagateAtomsForVar] before the crossing loop, so it is present here for the main path.
    // If it is not (a batch/set-restriction path that did not pre-materialize), fall back to the
    // move's own explanation — still a sound reason, just not the tighter channeling anchor.
    fun frontier(kind: AtomKind, threshold: Long): IntArray? {
        val f = idx?.find(kind, threshold) ?: -1
        return if (f >= 0) intArrayOf(Lit.make(problem.numBoolVars + f, false)) else atoms.pendingMoveAnt
    }
    return when (atoms.kind[atomId]) {
        AtomKind.GE ->
            if (newT) {
                if (k >= d.min) atoms.pendingMoveAnt else frontier(AtomKind.GE, d.min)
            } else {
                if (k - 1 <= d.max) atoms.pendingMoveAnt else frontier(AtomKind.LE, d.max)
            }

        AtomKind.LE ->
            if (newT) {
                if (k <= d.max) atoms.pendingMoveAnt else frontier(AtomKind.LE, d.max)
            } else {
                if (k + 1 >= d.min) atoms.pendingMoveAnt else frontier(AtomKind.GE, d.min)
            }

        AtomKind.EQ -> atoms.pendingMoveAnt // eq truth/hole handled by the caller; unreachable
    }
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
