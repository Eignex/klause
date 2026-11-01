package com.eignex.klause.propagation

import com.eignex.klause.factor.bool.ClausePropagator
import com.eignex.klause.factor.bool.PseudoBooleanPropagator
import com.eignex.klause.util.IntArrayList

internal enum class ReclamationStatus { WITHIN_LIMIT, RECLAIMED, PROTECTED_FLOOR, DEFERRED, CANCELLED }

internal data class LearnedReclamation(
    val status: ReclamationStatus,
    val beforeBytes: Long? = null,
    val afterBytes: Long? = null,
    val protectedBytes: Long? = null,
    val work: Long = 0L,
    val protectedLimitExceeded: Boolean = false,
)

internal class ReclamationPreparation(
    private val maxWork: Long,
    private val maxScratchBytes: Long,
    private val cancelled: () -> Boolean,
    private val charge: (Long) -> Unit,
) {
    var work = 0L
        private set
    var atomicUnits = 0L
        private set
    private var scratch = 0L
    val remainingWork: Long get() = maxWork - work

    fun visit(units: Long = 1L, scratchBytes: Long = 0L) {
        if (cancelled() || units > maxWork - work || scratchBytes > maxScratchBytes - scratch) throw Deferred()
        charge(units)
        work += units
        scratch += scratchBytes
    }

    fun atomic(units: Long, scratchBytes: Long = 0L) {
        visit()
        if (units > (maxWork - work - atomicUnits) / ATOMIC_PRICE ||
            scratchBytes > maxScratchBytes - scratch) throw Deferred()
        atomicUnits += units * ATOMIC_PRICE
        scratch += scratchBytes
    }

    fun ready() {
        if (cancelled() || atomicUnits > maxWork - work) throw Deferred()
    }

    class Deferred : RuntimeException()

    private companion object { const val ATOMIC_PRICE = 32L }
}

internal fun PropagationState.prepareReclamation(
    budget: ReclamationPreparation,
    keep: (Int, Int) -> Boolean,
): BooleanArray {
    val count = nativeEngine?.count ?: learned.size
    budget.visit(scratchBytes = count.toLong() * 29L + baseFactorCount.toLong() * Long.SIZE_BYTES)
    budget.atomic(baseFactorCount.toLong())
    budget.atomic(count.toLong() * 16L)
    if (selectedRetirements.hasRequests) budget.atomic(selectedRetirements.remapWork)
    budget.atomic(learned.admissions.allocatedBytes / Long.SIZE_BYTES, learned.admissions.allocatedBytes)
    val retained = BooleanArray(count)
    collectLearnedReferences(retained) { budget.visit() }
    budget.atomic(boolReason.size.toLong() + intMinReason.size + intMaxReason.size + undo.size * 3L)
    nativeEngine?.prepareReclamation(budget, retained, keep)?.let { return retained }
    for (i in 0 until count) {
        budget.visit()
        retained[i] = retained[i] || (!learned.retiredOf(i) && (learned.permanent[i] == 1 || keep(i, learned.lbds[i])))
    }
    if (factorAttribution != null) budget.atomic(count.toLong() * count)
    budget.atomic(propQueue.size.toLong() * 3L + atoms.dirtyFactors.size * 3L + learned.pendingAttach.size,
        (propQueue.size.toLong() + atoms.dirtyFactors.size) * Int.SIZE_BYTES)
    budget.atomic(watches.pos.primitiveCapacityBytes, watches.pos.primitiveCapacityBytes * 2L)
    budget.atomic(watches.pos.size.toLong() * watches.pos.size)
    for (lists in listOf(watches.byLit.asList(), watches.blockersByLit.asList(), atoms.watchersByLit)) {
        for (list in lists) {
            budget.visit()
            budget.atomic(1L + (list?.backingData?.size ?: 0), (list?.backingData?.size?.toLong() ?: 0L) * 8L)
        }
    }
    if (intEvents.overflow != null) {
        intEvents.overflow?.let { overflow ->
            budget.atomic(overflow.primitiveCapacityBytes + overflow.size.toLong() * overflow.size,
                overflow.primitiveCapacityBytes * 2L + overflow.referenceCapacity.toLong() * Long.SIZE_BYTES * 2L)
            for (value in overflow.valuesInternal) {
                budget.visit()
                if (value != null) {
                    val capacity = (value as IntArrayList).backingData.size.toLong()
                    budget.atomic(capacity, capacity * Int.SIZE_BYTES)
                }
            }
        }
    }
    for (column in listOf(learned.stages, learned.lbds, learned.permanent, learned.tier, learned.boolReasons,
        learned.usedFlags, learned.origins, learned.units, learned.pendingAttach, learned.capCandidates)) {
        budget.atomic(column.backingData.size.toLong(), column.backingData.size.toLong() * Int.SIZE_BYTES)
    }
    arithmeticLearningTrace?.let { trace ->
        val entries = trace.reclamationEntries.toLong()
        budget.atomic(entries * (entries + 16L), entries * 128L)
    }
    budget.ready()
    return retained
}

internal fun PropagationState.ownLearned(sample: com.eignex.klause.util.SharingMemoryObservation,
    index: Int, protected: Boolean) {
    val prop = learned.store[index]
    fun ints(value: IntArray?) {
        if (value != null) sample.owned(value, value.size.toLong() * Int.SIZE_BYTES, protected)
    }
    fun longs(value: LongArray) { sample.owned(value, value.size.toLong() * Long.SIZE_BYTES, protected) }
    ints(prop.boolVars)
    ints(prop.intVars)
    ints(prop.initialBoolWatchers)
    ints(prop.initialBoolWatcherBlockers)
    ints(prop.initialIntEventWatches)
    when (prop) {
        is ClausePropagator -> ints(prop.literals)
        is PseudoBooleanPropagator -> { ints(prop.literals); ints(prop.byWeightDesc); longs(prop.weights) }
        is LearnedIntegerPropagator -> {
            sample.owned(prop, Int.SIZE_BYTES.toLong(), protected)
            ints(prop.row.variables)
            longs(prop.row.coefficients)
        }
    }
    ints(refPayloadStore[baseFactorCount + index] as? IntArray)
}
