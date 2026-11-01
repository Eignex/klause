package com.eignex.klause.propagation

import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.LongArrayList
import com.eignex.klause.util.MutableIntObjectMap

internal fun PropagationState.logBoolPin(v: Int) {
    boolPinPos[v] = undo.size
    undo.tag.add(0)
    undo.varId.add(v)
    if (problem.numIntVars == 0) return
    undo.level.add(0)
    undo.minLvl.add(0)
    undo.maxLvl.add(0)
    undo.minReason.add(0)
    undo.maxReason.add(0)
    undo.carved.add(0L)
    undo.domain.add(null)
    undo.minAnt.add(null)
    undo.maxAnt.add(null)
    undo.holeHistLen.add(0)
}

/** Capture int var `v`'s full prior state. Must be called *before* the mutation. */
internal fun PropagationState.logIntChange(v: Int) {
    (boundMoves[v] ?: IntArrayList(initialCapacity = 4).also { boundMoves[v] = it }).add(undo.size)
    val prior = intDomains[v]
    (boundMovePriorMin[v] ?: LongArrayList(initialCapacity = 4).also { boundMovePriorMin[v] = it }).add(prior.min)
    (boundMovePriorMax[v] ?: LongArrayList(initialCapacity = 4).also { boundMovePriorMax[v] = it }).add(prior.max)
    undo.tag.add(1)
    undo.varId.add(v)
    undo.level.add(intLevel[v])
    undo.minLvl.add(intMinLevel[v])
    undo.maxLvl.add(intMaxLevel[v])
    undo.minReason.add(intMinReason[v])
    undo.maxReason.add(intMaxReason[v])
    undo.carved.add(0L)
    undo.domain.add(intDomains[v])
    undo.minAnt.add(intMinAntecedents[v])
    undo.maxAnt.add(intMaxAntecedents[v])
    undo.holeHistLen.add(holeHistVal[v]?.size ?: 0)
}

/** Journal an interior carve as just the carved value (tag 2): replay re-inserts it instead
 *  of restoring a retained domain snapshot, which would cost O(holes) per carve and make deep
 *  searches on wide hole-list domains quadratic in retained heap. Columns:
 *  [UndoLog.carved] = carved value, [UndoLog.level] = prior intLevel,
 *  [UndoLog.maxReason] = prior holeHist length. */
internal fun PropagationState.logIntCarve(v: Int, value: Long) {
    undo.tag.add(2)
    undo.varId.add(v)
    undo.level.add(intLevel[v])
    undo.minLvl.add(intMinLevel[v])
    undo.maxLvl.add(intMaxLevel[v])
    undo.minReason.add(0)
    undo.maxReason.add(holeHistVal[v]?.size ?: 0)
    undo.carved.add(value)
    undo.domain.add(null)
    undo.minAnt.add(null)
    undo.maxAnt.add(null)
    undo.holeHistLen.add(0)
}

/** Variable id recorded by undo record `i`. */
internal fun PropagationState.undoVarAt(i: Int): Int = undo.varId[i]

/** True iff undo record `i` is a bool pin (vs. an int-domain change). */
internal fun PropagationState.undoIsBoolAt(i: Int): Boolean = undo.tag[i] == 0

/** Capture a [PropagationState.LevelMark] at the current state. Cheap: five ints plus a snapshotCopy of
 *  each [PropagationState.SnapshottablePayload]. The map is allocated only when at least one payload is
 *  present (Table / Mdd factors); the common no-payload case shares [PropagationState.emptyPayloads] and
 *  never allocates per push. */
internal fun PropagationState.mark(): PropagationState.LevelMark {
    @Suppress("DoubleMutabilityForCollection") // lazily allocated when a snapshot is taken
    var payloads: MutableIntObjectMap<PropagationState.SnapshottablePayload>? = null
    // Only the tracked snapshottable slots need copying — no per-pin scan of every factor.
    snapshottableIndices.forEach { i ->
        val p = refPayloadStore[i]
        if (p is PropagationState.SnapshottablePayload) {
            val m = payloads ?: MutableIntObjectMap<PropagationState.SnapshottablePayload>().also {
                payloads = it
            }
            m.put(i, p.snapshotCopy())
        }
    }
    return PropagationState.LevelMark(
        undoSize = undo.size,
        ltdvSize = levelToDecisionVar.size,
        pinOrderSize = boolPinOrder.size,
        snapshottablePayloads = payloads ?: emptyPayloads,
        revSize = undo.revTrail.size,
        atomUndoSize = atoms.undoAtomId.size,
    )
}

internal fun PropagationState.prepareMark(): Iterator<PropagationState.LevelMark?> = sequence {
    var visits = 0
    @Suppress("DoubleMutabilityForCollection") // lazily allocated when a snapshot is taken
    var payloads: MutableIntObjectMap<PropagationState.SnapshottablePayload>? = null
    // Only the tracked snapshottable slots need copying — no per-pin scan of every factor.
    snapshottableIndices.forEach { i ->
        if (++visits % PROPAGATION_PREPARATION_BATCH_SIZE == 0) yield(null)
        val p = refPayloadStore[i]
        if (p is PropagationState.SnapshottablePayload) {
            val m = payloads ?: MutableIntObjectMap<PropagationState.SnapshottablePayload>().also {
                payloads = it
            }
            m.put(i, p.snapshotCopy())
        }
    }
    yield(PropagationState.LevelMark(
        undoSize = undo.size,
        ltdvSize = levelToDecisionVar.size,
        pinOrderSize = boolPinOrder.size,
        snapshottablePayloads = payloads ?: emptyPayloads,
        revSize = undo.revTrail.size,
        atomUndoSize = atoms.undoAtomId.size,
    ))
}.iterator()

/**
 * Rewind the state to [mark] by replaying the undo log from the top down to
 * [PropagationState.LevelMark.undoSize], then truncating the append-only stacks (decision vars, pin
 * order) and restoring reversible cells, snapshottable payloads and atom truths. Replays in reverse so a var
 * narrowed several times since the mark lands on its mark-time value. Transient
 * bookkeeping (dirty queues, conflict seeds, current level/factor) is cleared — the
 * caller only ever marks / undoes between propagation cycles, when those are idle.
 */
internal fun PropagationState.undoTo(mark: PropagationState.LevelMark) {
    pendingFiniteMutation = null
    replayVarUndo(mark.undoSize)
    undo.truncateTo(mark.undoSize)
    restoreReversibleCells(mark.revSize)
    boolPinOrder.truncateTo(mark.pinOrderSize)
    levelToDecisionVar.truncateTo(mark.ltdvSize)
    // The native-SAT BCP cursor must not point past the truncated trail; the surviving prefix is
    // already propagated, so resume from its end. Any stashed conflict reason belonged to the
    // rewound state.
    learned.unitsPending = learned.units.size > 0
    nativeEngine?.let {
        it.onRetraction()
        it.bcpHead = boolPinOrder.size
        nativeConflictReason = null
    }
    // Restore snapshottable per-factor payloads. Defensive snapshotCopy so a later
    // undo to the same mark returns to the same logical state.
    mark.snapshottablePayloads.forEach { fid, payload ->
        refPayloadStore[fid] = payload.snapshotCopy()
    }
    restoreAtomTruths(mark.atomUndoSize)
    atoms.dirtyFactors.clear()
    dirtyBools.clear()
    dirtyInts.clear()
    clearPropagatorFailures()
    conflictLevels = null
    conflictSeedFactors.clear()
    lastDecisionConflictVar = -1
    currentLevel = 0
    currentFactor = -1
}

// The replay passes of [undoTo] are separate functions: each is a loop over its own log, and one body holding
// them all is compiled again on-stack for each loop a long backjump enters.
private fun PropagationState.replayVarUndo(undoSize: Int) {
    // Optional sink for variables made (potentially) free again by this revert. Used by
    // VSIDS-style pickers that remove a variable from their order heap when it's assigned
    // and need it re-inserted on backtrack (combined-index encoding: bool id `v`, int id
    // `numBoolVars + v`). Captured once so the no-listener case stays a single null check.
    val unassigned = unassignListener
    val retracting = retractingListener
    val numBool = problem.numBoolVars
    var i = undo.size - 1
    while (i >= undoSize) {
        when (val tag = undo.tag[i]) {
            0 -> { // bool pin — prior state is always unassigned
                val v = undo.varId[i]
                retracting?.invoke(v)
                recheckRetractedLearning(boolReason[v])
                noteLearnedBoolReason(boolReason[v], -1)
                boolValues[v] = null
                boolLevel[v] = -1
                boolReason[v] = -1
                boolAntecedents[v] = null
                unassigned?.invoke(v)
            }

            1, 2 -> replayIntVarUndo(i, tag, numBool, retracting, unassigned)

            else -> error("unknown undo tag")
        }
        i--
    }
}

private fun PropagationState.replayIntVarUndo(
    i: Int,
    tag: Int,
    numBool: Int,
    retracting: ((Int) -> Unit)?,
    unassigned: ((Int) -> Unit)?,
) {
    when (tag) {
        1 -> { // int change — restore the full recorded prior int-var state
            val v = undo.varId[i]
            retracting?.invoke(numBool + v)
            recheckRetractedLearning(intMinReason[v])
            recheckRetractedLearning(intMaxReason[v])
            boundMoves[v]?.let { it.truncateTo(it.size - 1) }
            boundMovePriorMin[v]?.let { it.truncateTo(it.size - 1) }
            boundMovePriorMax[v]?.let { it.truncateTo(it.size - 1) }
            unassigned?.invoke(numBool + v)
            intDomains[v] = requireNotNull(undo.domain[i])
            intLevel[v] = undo.level[i]
            intMinLevel[v] = undo.minLvl[i]
            intMaxLevel[v] = undo.maxLvl[i]
            intMinReason[v] = undo.minReason[i]
            intMaxReason[v] = undo.maxReason[i]
            intMinAntecedents[v] = undo.minAnt[i]
            intMaxAntecedents[v] = undo.maxAnt[i]
            // Truncate the interior-hole carve history back to its pre-mutation length so
            // the (value, level, reason) records stay aligned with the restored domain.
            truncateHoleHistory(v, undo.holeHistLen[i])
        }

        2 -> { // interior carve — re-insert the carved value
            val v = undo.varId[i]
            retracting?.invoke(numBool + v)
            unassigned?.invoke(numBool + v)
            intDomains[v] = intDomains[v].includeInteriorValue(undo.carved[i])
            intLevel[v] = undo.level[i]
            intMinLevel[v] = undo.minLvl[i]
            intMaxLevel[v] = undo.maxLvl[i]
            truncateHoleHistory(v, undo.maxReason[i])
        }

        else -> error("unknown undo tag")
    }
}

private fun PropagationState.restoreReversibleCells(revSize: Int) {
    // Roll back reversible cells (incremental factor state) top-down to the mark, so a cell
    // mutated several times since the mark lands on its mark-time value (LIFO via each cell's
    // own prior-value stack). Independent of the bool/int cells above (disjoint state), so the
    // replay order between the two groups is immaterial.
    val revTrail = undo.revTrail
    var r = revTrail.size - 1
    while (r >= revSize) {
        revTrail[r].restore()
        r--
    }
    while (revTrail.size > revSize) revTrail.removeAt(revTrail.size - 1)
}

private fun PropagationState.restoreAtomTruths(atomUndoSize: Int) {
    // Restore atom truths recorded since the mark (the reversible atom trail): replay top-down so
    // an atom whose truth changed several times since the mark lands on its mark-time value. This
    // is the atoms' own assignment trail — an atom-lit forced directly by a channeling / learned
    // clause (pinAtomLit) is undone here even when its bound never moved, which a domain-driven
    // reconcile could not do. Independent of the int/bool cells above (disjoint
    // state), so the replay order between the two groups is immaterial.
    var a = atoms.undoAtomId.size - 1
    while (a >= atomUndoSize) {
        val id = atoms.undoAtomId[a]
        atoms.truth[id] = atoms.undoTruth[a]
        atoms.lvl[id] = atoms.undoLvl[a]
        atoms.ant[id] = atoms.undoAnt[a]
        a--
    }
    atoms.undoAtomId.truncateTo(atomUndoSize)
    atoms.undoTruth.truncateTo(atomUndoSize)
    atoms.undoLvl.truncateTo(atomUndoSize)
    while (atoms.undoAnt.size > atomUndoSize) atoms.undoAnt.removeAt(atoms.undoAnt.size - 1)
}
