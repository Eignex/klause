package com.eignex.klause.propagation

internal fun PropagationState.logBoolPin(v: Int) {
    undo.tag.add(0)
    undo.varId.add(v)
    undo.level.add(0)
    undo.minLvl.add(0)
    undo.maxLvl.add(0)
    undo.minReason.add(0)
    undo.maxReason.add(0)
    undo.domain.add(null)
    undo.minAnt.add(null)
    undo.maxAnt.add(null)
    undo.holeHistLen.add(0)
}

/** Capture int var `v`'s full prior state. Must be called *before* the mutation. */
internal fun PropagationState.logIntChange(v: Int) {
    undo.tag.add(1)
    undo.varId.add(v)
    undo.level.add(intLevel[v])
    undo.minLvl.add(intMinLevel[v])
    undo.maxLvl.add(intMaxLevel[v])
    undo.minReason.add(intMinReason[v])
    undo.maxReason.add(intMaxReason[v])
    undo.domain.add(intDomains[v])
    undo.minAnt.add(intMinAntecedents[v])
    undo.maxAnt.add(intMaxAntecedents[v])
    undo.holeHistLen.add(holeHistVal[v]?.size ?: 0)
}

/** Journal an interior carve as just the carved value: replay re-inserts it instead
 *  of restoring a retained domain snapshot, whose O(holes) cost per carve made deep
 *  searches on wide hole-list domains quadratic in retained heap (tag 2). Columns:
 *  [UndoLog.minReason] = carved value, [UndoLog.level] = prior intLevel,
 *  [UndoLog.maxReason] = prior holeHist length. */
internal fun PropagationState.logIntCarve(v: Int, value: Int) {
    undo.tag.add(2)
    undo.varId.add(v)
    undo.level.add(intLevel[v])
    undo.minLvl.add(intMinLevel[v])
    undo.maxLvl.add(intMaxLevel[v])
    undo.minReason.add(value)
    undo.maxReason.add(holeHistVal[v]?.size ?: 0)
    undo.domain.add(null)
    undo.minAnt.add(null)
    undo.maxAnt.add(null)
    undo.holeHistLen.add(0)
}

/** Journal one interior carve made by a *batched* exclusion ([excludeIntValues]) as an
 *  atom-only reset (tag 3). The whole batch shares a single tag-1 record that restores the
 *  prior domain, hole history and bound order literals wholesale; this record exists only to
 *  flip the carved value's `[v = value]` eq atom back to undetermined on backtrack (tag 1's
 *  range-limited [resetAtomTrailFor] covers the widened bounds, not interior holes). No domain
 *  rebuild on undo, so backtracking a wide batch stays O(carves), not O(carves · holes).
 *  Columns: [UndoLog.minReason] = carved value. */
internal fun PropagationState.logExclusionCarveAtom(v: Int, value: Int) {
    undo.tag.add(3)
    undo.varId.add(v)
    undo.level.add(0)
    undo.minLvl.add(0)
    undo.maxLvl.add(0)
    undo.minReason.add(value)
    undo.maxReason.add(0)
    undo.domain.add(null)
    undo.minAnt.add(null)
    undo.maxAnt.add(null)
    undo.holeHistLen.add(0)
}

/** Variable id recorded by undo record `i`. */
internal fun PropagationState.undoVarAt(i: Int): Int = undo.varId[i]

/** True iff undo record `i` is a bool pin (vs. an int-domain change). */
internal fun PropagationState.undoIsBoolAt(i: Int): Boolean = undo.tag[i] == 0

/** Capture a [PropagationState.LevelMark] at the current state. Cheap: three ints plus a snapshotCopy of
 *  each [PropagationState.SnapshottablePayload]. The map is allocated only when at least one payload is
 *  present (Table / Mdd factors); the common no-payload case shares [PropagationState.emptyPayloads] and
 *  never allocates per push. */
internal fun PropagationState.mark(): PropagationState.LevelMark {
    @Suppress("DoubleMutabilityForCollection") // lazily allocated when a snapshot is taken
    var payloads: HashMap<Int, PropagationState.SnapshottablePayload>? = null
    // Only the tracked snapshottable slots need copying — no per-pin scan of every factor.
    snapshottableIndices.forEach { i ->
        val p = refPayloadStore[i]
        if (p is PropagationState.SnapshottablePayload) {
            val m = payloads ?: HashMap<Int, PropagationState.SnapshottablePayload>().also { payloads = it }
            m[i] = p.snapshotCopy()
        }
    }
    return PropagationState.LevelMark(
        undoSize = undo.size,
        ltdvSize = levelToDecisionVar.size,
        pinOrderSize = boolPinOrder.size,
        snapshottablePayloads = payloads ?: emptyPayloads,
        revSize = undo.revTrail.size,
    )
}

/**
 * Rewind the state to [mark] by replaying the undo log from the top down to
 * [PropagationState.LevelMark.undoSize], then truncating the append-only stacks (decision vars, pin
 * order) and restoring snapshottable payloads. Replays in reverse so a var
 * narrowed several times since the mark lands on its mark-time value. Transient
 * bookkeeping (dirty queues, conflict seeds, current level/factor) is cleared — the
 * caller only ever marks / undoes between propagation cycles, when those are idle.
 */
internal fun PropagationState.undoTo(mark: PropagationState.LevelMark) {
    // Optional sink for variables made (potentially) free again by this revert. Used by
    // VSIDS-style pickers that remove a variable from their order heap when it's assigned
    // and need it re-inserted on backtrack (combined-index encoding: bool id `v`, int id
    // `numBoolVars + v`). Captured once so the no-listener case stays a single null check.
    val unassigned = unassignListener
    val numBool = problem.numBoolVars
    var i = undo.size - 1
    while (i >= mark.undoSize) {
        when (undo.tag[i]) {
            0 -> { // bool pin — prior state is always unassigned
                val v = undo.varId[i]
                boolValues[v] = null
                boolLevel[v] = -1
                boolReason[v] = -1
                boolAntecedents[v] = null
                unassigned?.invoke(v)
            }

            1 -> { // int change — restore the full recorded prior int-var state
                val v = undo.varId[i]
                unassigned?.invoke(numBool + v)
                // Tight bounds before restore — the widened range whose order literals flip
                // back to undetermined (see [resetAtomTrailFor]).
                val tightMin = intDomains[v].min
                val tightMax = intDomains[v].max
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
                holeHistVal[v]?.truncateTo(undo.holeHistLen[i])
                holeHistLvl[v]?.truncateTo(undo.holeHistLen[i])
                holeHistAnt[v]?.let { a -> while (a.size > undo.holeHistLen[i]) a.removeAt(a.size - 1) }
                // Clear only the order literals whose truth flips back to undetermined — the
                // range the domain just widened over (tight → restored). Must run post-restore.
                resetAtomTrailFor(v, tightMin, tightMax)
            }

            2 -> { // interior carve — re-insert the carved value
                val v = undo.varId[i]
                unassigned?.invoke(numBool + v)
                intDomains[v] = intDomains[v].includeInteriorValue(undo.minReason[i])
                intLevel[v] = undo.level[i]
                intMinLevel[v] = undo.minLvl[i]
                intMaxLevel[v] = undo.maxLvl[i]
                holeHistVal[v]?.truncateTo(undo.maxReason[i])
                holeHistLvl[v]?.truncateTo(undo.maxReason[i])
                holeHistAnt[v]?.let { a -> while (a.size > undo.maxReason[i]) a.removeAt(a.size - 1) }
                // The re-inserted value's eq atom flips false → undetermined (bounds unchanged).
                resetAtomTrailForCarve(v, undo.minReason[i])
            }

            3 -> { // batched interior carve — domain restored by this batch's tag-1 record;
                // only the eq atom needs flipping false → undetermined.
                resetAtomTrailForCarve(undo.varId[i], undo.minReason[i])
            }

            else -> error("unknown undo tag")
        }
        i--
    }
    undo.truncateTo(mark.undoSize)
    // Roll back reversible cells (incremental factor state) top-down to the mark, so a cell
    // mutated several times since the mark lands on its mark-time value (LIFO via each cell's
    // own prior-value stack). Independent of the bool/int cells above (disjoint state), so the
    // replay order between the two groups is immaterial.
    val revTrail = undo.revTrail
    var r = revTrail.size - 1
    while (r >= mark.revSize) {
        revTrail[r].restore()
        r--
    }
    while (revTrail.size > mark.revSize) revTrail.removeAt(revTrail.size - 1)
    boolPinOrder.truncateTo(mark.pinOrderSize)
    levelToDecisionVar.truncateTo(mark.ltdvSize)
    // Restore snapshottable per-factor payloads. Defensive snapshotCopy so a later
    // undo to the same mark returns to the same logical state.
    for ((fid, payload) in mark.snapshottablePayloads) {
        refPayloadStore[fid] = payload.snapshotCopy()
    }
    // Atoms carry no stored state to reconcile: truth, level and antecedents are all
    // derived on demand from the domains and histories restored above.
    dirtyAtomFactors.clear()
    dirtyBools.clear()
    dirtyInts.clear()
    conflictLevels = null
    conflictSeedFactors.clear()
    lastDecisionConflictVar = -1
    currentLevel = 0
    currentFactor = -1
}
