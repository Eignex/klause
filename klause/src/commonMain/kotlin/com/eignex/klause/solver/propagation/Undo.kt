package com.eignex.klause.solver.propagation

internal fun PropagationState.logBoolPin(v: Int) {
    undoTag.add(0)
    undoVar.add(v)
    undoLevel.add(0)
    undoMinLvl.add(0)
    undoMaxLvl.add(0)
    undoMinReason.add(0)
    undoMaxReason.add(0)
    undoDomain.add(null)
    undoMinAnt.add(null)
    undoMaxAnt.add(null)
    undoHoleHistLen.add(0)
}

/** Capture int var `v`'s full prior state. Must be called *before* the mutation. */
internal fun PropagationState.logIntChange(v: Int) {
    undoTag.add(1)
    undoVar.add(v)
    undoLevel.add(intLevel[v])
    undoMinLvl.add(intMinLevel[v])
    undoMaxLvl.add(intMaxLevel[v])
    undoMinReason.add(intMinReason[v])
    undoMaxReason.add(intMaxReason[v])
    undoDomain.add(intDomains[v])
    undoMinAnt.add(intMinAntecedents[v])
    undoMaxAnt.add(intMaxAntecedents[v])
    undoHoleHistLen.add(holeHistVal[v]?.size ?: 0)
}

/** Journal an interior carve as just the carved value: replay re-inserts it instead
 *  of restoring a retained domain snapshot, whose O(holes) cost per carve made deep
 *  searches on wide hole-list domains quadratic in retained heap (tag 2). Columns:
 *  [PropagationState.undoMinReason] = carved value, [PropagationState.undoLevel] = prior intLevel,
 *  [PropagationState.undoMaxReason] = prior holeHist length. */
internal fun PropagationState.logIntCarve(v: Int, value: Int) {
    undoTag.add(2)
    undoVar.add(v)
    undoLevel.add(intLevel[v])
    undoMinLvl.add(intMinLevel[v])
    undoMaxLvl.add(intMaxLevel[v])
    undoMinReason.add(value)
    undoMaxReason.add(holeHistVal[v]?.size ?: 0)
    undoDomain.add(null)
    undoMinAnt.add(null)
    undoMaxAnt.add(null)
    undoHoleHistLen.add(0)
}

internal fun PropagationState.truncateUndo(n: Int) {
    undoTag.truncateTo(n)
    undoVar.truncateTo(n)
    undoLevel.truncateTo(n)
    undoMinLvl.truncateTo(n)
    undoMaxLvl.truncateTo(n)
    undoMinReason.truncateTo(n)
    undoMaxReason.truncateTo(n)
    while (undoDomain.size > n) undoDomain.removeAt(undoDomain.size - 1)
    while (undoMinAnt.size > n) undoMinAnt.removeAt(undoMinAnt.size - 1)
    while (undoMaxAnt.size > n) undoMaxAnt.removeAt(undoMaxAnt.size - 1)
    undoHoleHistLen.truncateTo(n)
}

/** Variable id recorded by undo record `i`. */
internal fun PropagationState.undoVarAt(i: Int): Int = undoVar[i]

/** True iff undo record `i` is a bool pin (vs. an int-domain change). */
internal fun PropagationState.undoIsBoolAt(i: Int): Boolean = undoTag[i] == 0

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
        undoSize = undoTag.size,
        ltdvSize = levelToDecisionVar.size,
        pinOrderSize = boolPinOrder.size,
        snapshottablePayloads = payloads ?: emptyPayloads,
        revSize = revTrail.size,
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
    var i = undoTag.size - 1
    while (i >= mark.undoSize) {
        when (undoTag[i]) {
            0 -> { // bool pin — prior state is always unassigned
                val v = undoVar[i]
                boolValues[v] = null
                boolLevel[v] = -1
                boolReason[v] = -1
                boolAntecedents[v] = null
                unassigned?.invoke(v)
            }

            1 -> { // int change — restore the full recorded prior int-var state
                val v = undoVar[i]
                unassigned?.invoke(numBool + v)
                // Tight bounds before restore — the widened range whose order literals flip
                // back to undetermined (see [resetAtomTrailFor]).
                val tightMin = intDomains[v].min
                val tightMax = intDomains[v].max
                intDomains[v] = requireNotNull(undoDomain[i])
                intLevel[v] = undoLevel[i]
                intMinLevel[v] = undoMinLvl[i]
                intMaxLevel[v] = undoMaxLvl[i]
                intMinReason[v] = undoMinReason[i]
                intMaxReason[v] = undoMaxReason[i]
                intMinAntecedents[v] = undoMinAnt[i]
                intMaxAntecedents[v] = undoMaxAnt[i]
                // Truncate the interior-hole carve history back to its pre-mutation length so
                // the (value, level, reason) records stay aligned with the restored domain.
                holeHistVal[v]?.truncateTo(undoHoleHistLen[i])
                holeHistLvl[v]?.truncateTo(undoHoleHistLen[i])
                holeHistAnt[v]?.let { a -> while (a.size > undoHoleHistLen[i]) a.removeAt(a.size - 1) }
                // Clear only the order literals whose truth flips back to undetermined — the
                // range the domain just widened over (tight → restored). Must run post-restore.
                resetAtomTrailFor(v, tightMin, tightMax)
            }

            2 -> { // interior carve — re-insert the carved value
                val v = undoVar[i]
                unassigned?.invoke(numBool + v)
                intDomains[v] = intDomains[v].includeInteriorValue(undoMinReason[i])
                intLevel[v] = undoLevel[i]
                intMinLevel[v] = undoMinLvl[i]
                intMaxLevel[v] = undoMaxLvl[i]
                holeHistVal[v]?.truncateTo(undoMaxReason[i])
                holeHistLvl[v]?.truncateTo(undoMaxReason[i])
                holeHistAnt[v]?.let { a -> while (a.size > undoMaxReason[i]) a.removeAt(a.size - 1) }
                // The re-inserted value's eq atom flips false → undetermined (bounds unchanged).
                resetAtomTrailForCarve(v, undoMinReason[i])
            }

            else -> error("unknown undo tag")
        }
        i--
    }
    truncateUndo(mark.undoSize)
    // Roll back reversible cells (incremental factor state) top-down to the mark, so a cell
    // mutated several times since the mark lands on its mark-time value (LIFO via each cell's
    // own prior-value stack). Independent of the bool/int cells above (disjoint state), so the
    // replay order between the two groups is immaterial.
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
