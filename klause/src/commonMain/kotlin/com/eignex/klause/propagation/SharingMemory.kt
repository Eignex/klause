package com.eignex.klause.propagation

import com.eignex.klause.factor.bool.ClausePropagator
import com.eignex.klause.factor.bool.PseudoBooleanPropagator
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.SharingMemoryObservation

internal fun PropagationState.sharingMemory(sample: SharingMemoryObservation) {
    selectedRetirements.sharingMemory(sample)
    sample.owned(this, Long.SIZE_BYTES.toLong(), true)
    val protected = protectedLearnedMemory(sample)
    nativeEngine?.sharingMemory(sample, protected) ?: learnedSharingMemory(sample, protected)
    factorAttribution?.sharingMemory(sample)
    val reasons = sample.seen<IntArray>()
    var reasonBytes = 0L
    var reasonSlots = 0L
    fun add(reason: IntArray?) {
        sample.step()
        reasonSlots++
        if (reason != null) {
            sample.owned(reason, reason.size.toLong() * Int.SIZE_BYTES, true)
            if (sample.retain(reasons, reason)) reasonBytes += reason.size.toLong() * Int.SIZE_BYTES
        }
    }
    boolAntecedents.forEach(::add)
    intMinAntecedents.forEach(::add)
    intMaxAntecedents.forEach(::add)
    atoms.ant.forEach(::add)
    atoms.undoAnt.forEach(::add)
    undo.minAnt.forEach(::add)
    undo.maxAnt.forEach(::add)
    sample.ownedReferences(holeHistAnt, holeHistAnt.size.toLong(), true)
    holeHistAnt.forEach {
        sample.step()
        if (it != null) {
            sample.ownedReferences(it, it.size.toLong(), true)
            it.forEach(::add)
        }
    }
    sample.ownedReferences(lazyReasonMemo, lazyReasonMemo.size.toLong() * 2L, true)
    lazyReasonMemo.forEach { (marker, reason) -> sample.step(); add(marker); add(reason) }
    add(nativeConflictReason)
    add(atoms.pendingMoveAnt)
    sample.record(mapOf("reasonPayloadBytes" to reasonBytes, "reasonArrays" to reasons.size.toLong(),
        "reasonReferenceSlots" to reasonSlots))

    val atomColumns = listOf(atoms.intVar, atoms.truth, atoms.lvl, atoms.undoAtomId, atoms.undoTruth, atoms.undoLvl)
    val atomRuns = sample.seen<VarAtomIndex>()
    var atomBytes = atoms.threshold.allocatedBytes
    sample.owned(atoms.threshold, atoms.threshold.allocatedBytes, true)
    for (column in atomColumns) {
        sample.step()
        atomBytes += column.backingData.size.toLong() * Int.SIZE_BYTES
        sample.owned(column.backingData, column.backingData.size.toLong() * Int.SIZE_BYTES, true)
    }
    for (indexes in listOf(atoms.byIntVar, atoms.watchedByVar)) for (index in indexes) {
        sample.step()
        if (index == null || !sample.retain(atomRuns, index)) continue
        for (run in listOf(index.ge, index.le, index.eq)) {
            sample.step()
            atomBytes += run.mainKeys.allocatedBytes + run.pendKeys.allocatedBytes +
                (run.mainIds.backingData.size.toLong() + run.pendIds.backingData.size) * Int.SIZE_BYTES
            sample.owned(run.mainKeys, run.mainKeys.allocatedBytes, true)
            sample.owned(run.pendKeys, run.pendKeys.allocatedBytes, true)
            sample.owned(run.mainIds.backingData, run.mainIds.backingData.size.toLong() * Int.SIZE_BYTES, true)
            sample.owned(run.pendIds.backingData, run.pendIds.backingData.size.toLong() * Int.SIZE_BYTES, true)
        }
    }
    sample.ownedReferences(atoms.byIntVar, atoms.byIntVar.size.toLong(), true)
    sample.ownedReferences(atoms.watchedByVar, atoms.watchedByVar.size.toLong(), true)
    sample.ownedReferences(atoms.kind, atoms.kind.size.toLong(), true)
    sample.ownedReferences(atoms.watchersByLit, atoms.watchersByLit.size.toLong(), true)
    var channelBytes = 0L
    for (pending in pendingChanneling) {
        sample.step(); channelBytes += pending.size.toLong() * Int.SIZE_BYTES
        sample.owned(pending, pending.size.toLong() * Int.SIZE_BYTES, true)
    }
    sample.ownedReferences(pendingChanneling, pendingChanneling.size.toLong(), true)
    sample.record(mapOf(
        "atomCapacityBytes" to atomBytes,
        "atomReferenceSlots" to atoms.kind.size.toLong() + atoms.ant.size + atoms.undoAnt.size +
            atoms.watchersByLit.size + atoms.byIntVar.size + atoms.watchedByVar.size,
        "pendingChannelingPayloadBytes" to channelBytes,
        "pendingChannelingReferenceSlots" to pendingChanneling.size.toLong(),
        "atomCount" to atoms.intVar.size.toLong(),
    ))
    reasonIndexMemory(sample)
}

private fun PropagationState.protectedLearnedMemory(
    sample: SharingMemoryObservation,
): SharingMemoryObservation.Seen<Int> {
    val protected = sample.seen<Int>()
    val count = nativeEngine?.count ?: learned.size
    fun protect(fid: Int) {
        sample.step()
        val index = fid - baseFactorCount
        if (index in 0 until count) sample.retain(protected, index)
    }
    boolReason.forEach(::protect)
    intMinReason.forEach(::protect)
    intMaxReason.forEach(::protect)
    for (i in 0 until undo.size) {
        sample.step()
        if (undo.tag[i] == 1) { protect(undo.minReason[i]); protect(undo.maxReason[i]) }
    }
    for (i in 0 until learned.pendingAttach.size) {
        sample.step()
        val fid = learned.pendingAttach[i]
        if (learned.stages[fid - baseFactorCount] and 2 == 0) protect(fid)
    }
    sample.record(mapOf("reasonProtectedObjects" to protected.size.toLong()))
    return protected
}

private fun PropagationState.protectedPosting(
    sample: SharingMemoryObservation,
    posting: IntArrayList,
    protected: SharingMemoryObservation.Seen<Int>,
): Boolean {
    var retained = false
    for (i in 0 until posting.size) {
        sample.step()
        val factor = posting[i]
        if (!retained) retained = factor < baseFactorCount || sample.contains(protected, factor - baseFactorCount)
    }
    return retained
}

private fun PropagationState.learnedSharingMemory(
    sample: SharingMemoryObservation,
    protected: SharingMemoryObservation.Seen<Int>,
) {
    val ints = sample.seen<IntArray>()
    val longs = sample.seen<LongArray>()
    var payload = 0L
    fun add(value: IntArray?) {
        sample.step()
        if (value != null && sample.retain(ints, value)) payload += value.size.toLong() * Int.SIZE_BYTES
    }
    fun addLong(value: LongArray) {
        if (sample.retain(longs, value)) payload += value.size.toLong() * Long.SIZE_BYTES
    }
    var peerImports = 0L
    var consumedPeerImports = 0L
    var consumed = 0L
    var protectedInteger = false
    for (i in 0 until learned.size) {
        sample.step()
        if (learned.permanent[i] == 1) sample.retain(protected, i)
        val retained = sample.contains(protected, i)
        ownLearned(sample, i, retained)
        val propagator = learned.store[i]
        add(propagator.boolVars)
        add(propagator.intVars)
        add(propagator.initialBoolWatchers)
        add(propagator.initialBoolWatcherBlockers)
        add(propagator.initialIntEventWatches)
        when (propagator) {
            is ClausePropagator -> add(propagator.literals)
            is PseudoBooleanPropagator -> {
                add(propagator.literals)
                add(propagator.byWeightDesc)
                addLong(propagator.weights)
            }
            is LearnedIntegerPropagator -> {
                protectedInteger = protectedInteger || retained
                add(propagator.row.variables)
                addLong(propagator.row.coefficients)
            }
        }
        add(refPayloadStore[baseFactorCount + i] as? IntArray)
        val peer = learned.origins[i] >= 0
        val used = learned.stages[i] and 2 != 0
        if (peer) peerImports++
        if (peer && used) consumedPeerImports++
        if (used) consumed++
    }
    val columns = listOf(learned.stages, learned.lbds, learned.permanent, learned.tier, learned.boolReasons,
        learned.usedFlags, learned.origins, learned.units, learned.pendingAttach, learned.capCandidates)
    val protectedRows = protected.size > 0
    learned.capRanking.sharingMemory(sample, protectedRows)
    var metadata = learned.capRanking.primitiveBytes + learned.admissions.allocatedBytes + Int.SIZE_BYTES
    sample.owned(learned.admissions, learned.admissions.allocatedBytes + Int.SIZE_BYTES, protectedRows)
    sample.ownedReferences(learned.admissions, 1L, protectedRows)
    for (column in columns) {
        sample.step(); metadata += column.backingData.size.toLong() * Int.SIZE_BYTES
        sample.owned(column.backingData, column.backingData.size.toLong() * Int.SIZE_BYTES, protectedRows)
    }
    sample.ownedReferences(learned.store, learned.size.toLong(), protectedRows)
    sample.ownedReferences(refPayloadStore, refPayloadStore.size.toLong(), baseFactorCount > 0 || protectedRows)
    learned.integerRows?.sharingMemory(sample, protectedInteger)
    var watchers = watches.pos.primitiveCapacityBytes
    var protectedWatchIndex = false
    sample.ownedReferences(watches.byLit, watches.byLit.size.toLong(), true)
    sample.ownedReferences(watches.blockersByLit, watches.blockersByLit.size.toLong(), true)
    for (literal in watches.byLit.indices) {
        sample.step()
        val posting = watches.byLit[literal]
        val blockers = watches.blockersByLit[literal]
        val retained = protectedPosting(sample, posting, protected)
        protectedWatchIndex = protectedWatchIndex || retained
        watchers += (posting.backingData.size.toLong() + blockers.backingData.size) * Int.SIZE_BYTES
        sample.owned(posting.backingData, posting.backingData.size.toLong() * Int.SIZE_BYTES, retained)
        sample.owned(blockers.backingData, blockers.backingData.size.toLong() * Int.SIZE_BYTES, retained)
    }
    sample.owned(watches.pos, watches.pos.primitiveCapacityBytes, protectedWatchIndex)
    for (posting in atoms.watchersByLit) {
        sample.step()
        if (posting == null) continue
        watchers += posting.backingData.size.toLong() * Int.SIZE_BYTES
        sample.owned(posting.backingData, posting.backingData.size.toLong() * Int.SIZE_BYTES,
            protectedPosting(sample, posting, protected))
    }
    val events = intEvents
    val overflow = events.overflow
    var eventBytes = (events.baseOffsets.size.toLong() + events.baseFlat.size + events.dirtyKinds.size) * Int.SIZE_BYTES
    for (array in listOf(events.baseOffsets, events.baseFlat, events.dirtyKinds)) {
        sample.owned(array, array.size.toLong() * Int.SIZE_BYTES, true)
    }
    var protectedEvents = false
    if (overflow != null) for (value in overflow.valuesInternal) {
        sample.step()
        if (value != null) {
            val posting = value as IntArrayList
            val array = posting.backingData
            val retained = protectedPosting(sample, posting, protected)
            protectedEvents = protectedEvents || retained
            eventBytes += array.size.toLong() * Int.SIZE_BYTES
            sample.owned(array, array.size.toLong() * Int.SIZE_BYTES, retained)
        }
    }
    eventBytes += overflow?.primitiveCapacityBytes ?: 0L
    if (overflow != null) {
        sample.owned(overflow, overflow.primitiveCapacityBytes, protectedEvents)
        sample.ownedReferences(overflow, overflow.referenceCapacity.toLong(), protectedEvents)
    }
    for (list in events.dirtyVars) {
        sample.step()
        eventBytes += (list?.backingData?.size?.toLong() ?: 0L) * Int.SIZE_BYTES
        if (list != null) sample.owned(list.backingData, list.backingData.size.toLong() * Int.SIZE_BYTES, true)
    }
    for (mark in events.dirtyMark) {
        sample.step(); eventBytes += mark?.allocatedBytes ?: 0L
        if (mark != null) sample.owned(mark, mark.allocatedBytes, true)
    }
    sample.ownedReferences(events.dirtyVars, events.dirtyVars.size.toLong(), true)
    sample.ownedReferences(events.dirtyMark, events.dirtyMark.size.toLong(), true)
    sample.owned(propQueue, propQueue.allocatedBytes, true)
    propagationSchedule?.let { sample.owned(it, it.capacityBytes, true) }
    sample.owned(atoms.dirtyFactors, atoms.dirtyFactors.allocatedBytes, true)
    sample.owned(learned.importUses, learned.importUses.allocatedBytes)
    sample.record(mapOf(
        "sessionEventCapacityBytes" to eventBytes,
        "sessionEventIndexReferenceCapacity" to (overflow?.referenceCapacity?.toLong() ?: 0L),
        "sessionEventLiveReferenceSlots" to events.dirtyVars.size.toLong() + events.dirtyMark.size,
        "learnedObjects" to learned.size.toLong(),
        "learnedRetiredSlots" to learned.retiredCount.toLong(),
        "learnedPayloadBytes" to payload,
        "learnedMetadataCapacityBytes" to metadata,
        "learnedCapIndexStateBytes" to learned.capRanking.modeledBytes,
        "learnedReferenceSlots" to learned.size.toLong() * 2L + (learned.integerRows?.capacity ?: 0),
        "learnedIntegerRowCacheCapacityBytes" to (learned.integerRows?.capacity?.toLong() ?: 0L) * Long.SIZE_BYTES,
        "learnedIntegerRowIndexBytes" to (learned.integerRows?.size?.toLong() ?: 0L) * Int.SIZE_BYTES,
        "sessionWatchCapacityBytes" to watchers,
        "sessionQueueCapacityBytes" to propQueue.allocatedBytes + atoms.dirtyFactors.allocatedBytes,
        "propagationScheduleCapacityBytes" to (propagationSchedule?.capacityBytes ?: 0L),
        "pendingAttachments" to learned.pendingAttach.size.toLong(),
        "importUseCapacityBytes" to learned.importUses.allocatedBytes,
        "retainedPeerImports" to peerImports,
        "retainedConsumedPeerImports" to consumedPeerImports,
        "retainedConsumed" to consumed,
    ))
}

private fun PropagationState.reasonIndexMemory(sample: SharingMemoryObservation) {
    val journal = listOf(undo.tag, undo.varId, undo.level, undo.minLvl, undo.maxLvl,
        undo.minReason, undo.maxReason, undo.holeHistLen)
    var journalBytes = undo.carved.allocatedBytes
    sample.owned(undo.carved, undo.carved.allocatedBytes, true)
    for (column in journal) {
        sample.step(); journalBytes += column.backingData.size.toLong() * Int.SIZE_BYTES
        sample.owned(column.backingData, column.backingData.size.toLong() * Int.SIZE_BYTES, true)
    }
    var historyBytes = 0L
    for (columns in listOf(boundMoves, holeHistLvl, holeHistPos)) for (column in columns) {
        sample.step()
        historyBytes += (column?.backingData?.size?.toLong() ?: 0L) * Int.SIZE_BYTES
        if (column != null) sample.owned(column.backingData, column.backingData.size.toLong() * Int.SIZE_BYTES, true)
    }
    for (columns in listOf(boundMovePriorMin, boundMovePriorMax, holeHistVal)) for (column in columns) {
        sample.step()
        historyBytes += column?.allocatedBytes ?: 0L
        if (column != null) sample.owned(column, column.allocatedBytes, true)
    }
    for (index in holeHistIndex) {
        sample.step(); historyBytes += index?.primitiveCapacityBytes ?: 0L
        if (index != null) sample.owned(index, index.primitiveCapacityBytes, true)
    }
    for (array in listOf(boolReason, intMinReason, intMaxReason, boolPinPos)) {
        sample.owned(array, array.size.toLong() * Int.SIZE_BYTES, true)
    }
    for (refs in listOf(boolAntecedents, intMinAntecedents, intMaxAntecedents)) {
        sample.ownedReferences(refs, refs.size.toLong(), true)
    }
    for (refs in listOf(atoms.ant, atoms.undoAnt, undo.minAnt, undo.maxAnt)) {
        sample.ownedReferences(refs, refs.size.toLong(), true)
    }
    for (refs in listOf(boundMoves, holeHistLvl, holeHistPos)) {
        sample.ownedReferences(refs, refs.size.toLong(), true)
    }
    for (refs in listOf(boundMovePriorMin, boundMovePriorMax, holeHistVal)) {
        sample.ownedReferences(refs, refs.size.toLong(), true)
    }
    sample.ownedReferences(holeHistIndex, holeHistIndex.size.toLong(), true)
    sample.ownedReferences(undo.domain, undo.domain.size.toLong(), true)
    sample.ownedReferences(undo.revTrail, undo.revTrail.size.toLong(), true)
    sample.record(mapOf(
        "reasonIndexCapacityBytes" to
            (boolReason.size.toLong() + intMinReason.size + intMaxReason.size + boolPinPos.size) * Int.SIZE_BYTES,
        "reasonJournalCapacityBytes" to journalBytes,
        "reasonHistoryCapacityBytes" to historyBytes,
        "reasonHistoryReferenceSlots" to boundMoves.size.toLong() + holeHistLvl.size + holeHistPos.size +
            boundMovePriorMin.size + boundMovePriorMax.size + holeHistVal.size + holeHistIndex.size +
            undo.domain.size + undo.revTrail.size,
    ))
}
