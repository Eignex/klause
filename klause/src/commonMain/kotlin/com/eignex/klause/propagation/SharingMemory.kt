package com.eignex.klause.propagation

import com.eignex.klause.factor.bool.ClausePropagator
import com.eignex.klause.factor.bool.PseudoBooleanPropagator

internal fun PropagationState.sharingMemory(): Map<String, Long> {
    val retained = nativeEngine?.sharingMemory() ?: learnedSharingMemory()
    val reasons = HashSet<IntArray>()
    fun add(reason: IntArray?) { if (reason != null) reasons.add(reason) }
    boolAntecedents.forEach(::add)
    intMinAntecedents.forEach(::add)
    intMaxAntecedents.forEach(::add)
    atoms.ant.forEach(::add)
    atoms.undoAnt.forEach(::add)
    undo.minAnt.forEach(::add)
    undo.maxAnt.forEach(::add)
    holeHistAnt.forEach { it?.forEach(::add) }
    lazyReasonMemo.forEach { (marker, reason) -> add(marker); add(reason) }
    add(nativeConflictReason)
    add(atoms.pendingMoveAnt)
    val atomColumns = listOf(atoms.intVar, atoms.truth, atoms.lvl, atoms.undoAtomId, atoms.undoTruth, atoms.undoLvl)
    val atomRuns = (atoms.byIntVar.asList() + atoms.watchedByVar.asList()).filterNotNull()
    var atomBytes = atoms.threshold.allocatedBytes + atomColumns.sumOf { it.backingData.size.toLong() * Int.SIZE_BYTES }
    for (index in atomRuns) for (run in listOf(index.ge, index.le, index.eq)) {
        atomBytes += run.mainKeys.allocatedBytes + run.pendKeys.allocatedBytes +
            (run.mainIds.backingData.size.toLong() + run.pendIds.backingData.size) * Int.SIZE_BYTES
    }
    return retained + mapOf(
        "atomCapacityBytes" to atomBytes,
        "atomReferenceSlots" to atoms.kind.size.toLong() + atoms.ant.size + atoms.undoAnt.size +
            atoms.watchersByLit.size + atoms.byIntVar.size + atoms.watchedByVar.size,
        "reasonPayloadBytes" to reasons.sumOf { it.size.toLong() * Int.SIZE_BYTES },
        "reasonArrays" to reasons.size.toLong(),
        "reasonReferenceSlots" to boolAntecedents.size.toLong() + intMinAntecedents.size +
            intMaxAntecedents.size + atoms.ant.size + atoms.undoAnt.size + undo.minAnt.size + undo.maxAnt.size +
            holeHistAnt.sumOf { it?.size?.toLong() ?: 0L } + lazyReasonMemo.size.toLong() * 2L,
        "reasonProtectedObjects" to referencedLearned(nativeEngine?.count ?: learned.size).count { it }.toLong(),
        "memorySampleWork" to retained.getValue("memorySampleWork") + reasons.size + atomRuns.size * 3L +
            boolAntecedents.size + intMinAntecedents.size + intMaxAntecedents.size + atoms.ant.size +
            atoms.undoAnt.size + undo.minAnt.size + undo.maxAnt.size + boolReason.size +
            intMinReason.size + intMaxReason.size + undo.size * 3L + learned.pendingAttach.size +
            retained.getValue("learnedObjects") + holeHistAnt.sumOf { it?.size?.toLong() ?: 0L } + lazyReasonMemo.size,
        "atomCount" to atoms.intVar.size.toLong(),
    )
}

private fun PropagationState.learnedSharingMemory(): Map<String, Long> {
    val ints = HashSet<IntArray>()
    val longs = HashSet<LongArray>()
    fun add(value: IntArray?) { if (value != null) ints.add(value) }
    for (propagator in learned.store) {
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
                longs.add(propagator.weights)
            }
            is LearnedIntegerPropagator -> {
                add(propagator.row.variables)
                longs.add(propagator.row.coefficients)
            }
        }
    }
    for (fid in baseFactorCount until baseFactorCount + learned.size) add(refPayloadStore[fid] as? IntArray)
    val columns = listOf(learned.stages, learned.lbds, learned.permanent, learned.tier,
        learned.usedFlags, learned.origins, learned.units, learned.pendingAttach)
    val watchers = watches.byLit.sumOf { it.backingData.size.toLong() * Int.SIZE_BYTES } +
        watches.blockersByLit.sumOf { it.backingData.size.toLong() * Int.SIZE_BYTES } +
        atoms.watchersByLit.sumOf { (it?.backingData?.size?.toLong() ?: 0L) * Int.SIZE_BYTES }
    return mapOf(
        "memorySampleWork" to learned.size.toLong() * 5L + ints.size + longs.size + columns.size +
            watches.byLit.size + watches.blockersByLit.size + atoms.watchersByLit.size,
        "learnedObjects" to learned.size.toLong(),
        "learnedPayloadBytes" to ints.sumOf { it.size.toLong() * Int.SIZE_BYTES } +
            longs.sumOf { it.size.toLong() * Long.SIZE_BYTES },
        "learnedCapacityBytes" to columns.sumOf { it.backingData.size.toLong() * Int.SIZE_BYTES },
        "learnedReferenceSlots" to learned.size.toLong() * 2L + (learned.integerRows?.size ?: 0),
        "sessionWatchCapacityBytes" to watchers + watches.pos.primitiveCapacityBytes,
        "sessionQueueCapacityBytes" to propQueue.allocatedBytes + atoms.dirtyFactors.allocatedBytes,
        "pendingAttachments" to learned.pendingAttach.size.toLong(),
        "importUseCapacityBytes" to learned.importUses.allocatedBytes,
        "retainedPeerImports" to (0 until learned.size).count { learned.origins[it] >= 0 }.toLong(),
        "retainedConsumedPeerImports" to (0 until learned.size).count {
            learned.origins[it] >= 0 && learned.stages[it] and 2 != 0
        }.toLong(),
        "retainedConsumed" to (0 until learned.size).count { learned.stages[it] and 2 != 0 }.toLong(),
    )
}
