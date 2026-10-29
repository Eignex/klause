package com.eignex.klause.propagation

import com.eignex.klause.factor.bool.ClausePropagator
import com.eignex.klause.factor.bool.PseudoBooleanPropagator
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.SharingMemoryObservation

internal fun PropagationState.sharingMemory(sample: SharingMemoryObservation) {
    nativeEngine?.sharingMemory(sample) ?: learnedSharingMemory(sample)
    val reasons = sample.seen<IntArray>()
    var reasonBytes = 0L
    var reasonSlots = 0L
    fun add(reason: IntArray?) {
        sample.step()
        reasonSlots++
        if (reason != null && sample.retain(reasons, reason)) reasonBytes += reason.size.toLong() * Int.SIZE_BYTES
    }
    boolAntecedents.forEach(::add)
    intMinAntecedents.forEach(::add)
    intMaxAntecedents.forEach(::add)
    atoms.ant.forEach(::add)
    atoms.undoAnt.forEach(::add)
    undo.minAnt.forEach(::add)
    undo.maxAnt.forEach(::add)
    holeHistAnt.forEach { sample.step(); it?.forEach(::add) }
    lazyReasonMemo.forEach { (marker, reason) -> sample.step(); add(marker); add(reason) }
    add(nativeConflictReason)
    add(atoms.pendingMoveAnt)
    sample.record(mapOf("reasonPayloadBytes" to reasonBytes, "reasonArrays" to reasons.size.toLong(),
        "reasonReferenceSlots" to reasonSlots))

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

    val atomColumns = listOf(atoms.intVar, atoms.truth, atoms.lvl, atoms.undoAtomId, atoms.undoTruth, atoms.undoLvl)
    val atomRuns = sample.seen<VarAtomIndex>()
    var atomBytes = atoms.threshold.allocatedBytes
    for (column in atomColumns) { sample.step(); atomBytes += column.backingData.size.toLong() * Int.SIZE_BYTES }
    for (indexes in listOf(atoms.byIntVar, atoms.watchedByVar)) for (index in indexes) {
        sample.step()
        if (index == null || !sample.retain(atomRuns, index)) continue
        for (run in listOf(index.ge, index.le, index.eq)) {
            sample.step()
            atomBytes += run.mainKeys.allocatedBytes + run.pendKeys.allocatedBytes +
                (run.mainIds.backingData.size.toLong() + run.pendIds.backingData.size) * Int.SIZE_BYTES
        }
    }
    var channelBytes = 0L
    for (pending in pendingChanneling) { sample.step(); channelBytes += pending.size.toLong() * Int.SIZE_BYTES }
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

private fun PropagationState.learnedSharingMemory(sample: SharingMemoryObservation) {
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
    for (i in 0 until learned.size) {
        sample.step()
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
    val columns = listOf(learned.stages, learned.lbds, learned.permanent, learned.tier,
        learned.usedFlags, learned.origins, learned.units, learned.pendingAttach)
    var metadata = 0L
    for (column in columns) { sample.step(); metadata += column.backingData.size.toLong() * Int.SIZE_BYTES }
    var watchers = watches.pos.primitiveCapacityBytes
    for (lists in listOf(watches.byLit.asList(), watches.blockersByLit.asList(), atoms.watchersByLit)) {
        for (list in lists) { sample.step(); watchers += (list?.backingData?.size?.toLong() ?: 0L) * Int.SIZE_BYTES }
    }
    val events = intEvents
    val overflow = events.overflow
    var eventBytes = (events.baseOffsets.size.toLong() + events.baseFlat.size + events.dirtyKinds.size) * Int.SIZE_BYTES
    if (overflow != null) for (value in overflow.valuesInternal) {
        sample.step()
        if (value != null) eventBytes += (value as IntArrayList).backingData.size.toLong() * Int.SIZE_BYTES
    }
    eventBytes += overflow?.primitiveCapacityBytes ?: 0L
    for (list in events.dirtyVars) {
        sample.step()
        eventBytes += (list?.backingData?.size?.toLong() ?: 0L) * Int.SIZE_BYTES
    }
    for (mark in events.dirtyMark) { sample.step(); eventBytes += mark?.allocatedBytes ?: 0L }
    sample.record(mapOf(
        "sessionEventCapacityBytes" to eventBytes,
        "sessionEventIndexReferenceCapacity" to (overflow?.referenceCapacity?.toLong() ?: 0L),
        "sessionEventLiveReferenceSlots" to events.dirtyVars.size.toLong() + events.dirtyMark.size,
        "learnedObjects" to learned.size.toLong(),
        "learnedPayloadBytes" to payload,
        "learnedMetadataCapacityBytes" to metadata,
        "learnedReferenceSlots" to learned.size.toLong() * 2L + (learned.integerRows?.size ?: 0),
        "sessionWatchCapacityBytes" to watchers,
        "sessionQueueCapacityBytes" to propQueue.allocatedBytes + atoms.dirtyFactors.allocatedBytes,
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
    for (column in journal) { sample.step(); journalBytes += column.backingData.size.toLong() * Int.SIZE_BYTES }
    var historyBytes = 0L
    for (columns in listOf(boundMoves, holeHistLvl, holeHistPos)) for (column in columns) {
        sample.step()
        historyBytes += (column?.backingData?.size?.toLong() ?: 0L) * Int.SIZE_BYTES
    }
    for (columns in listOf(boundMovePriorMin, boundMovePriorMax, holeHistVal)) for (column in columns) {
        sample.step()
        historyBytes += column?.allocatedBytes ?: 0L
    }
    for (index in holeHistIndex) { sample.step(); historyBytes += index?.primitiveCapacityBytes ?: 0L }
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
