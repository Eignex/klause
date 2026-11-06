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
    budget.visit(scratchBytes = count.toLong() * 13L)
    budget.atomic(count.toLong() * 16L)
    val retained = BooleanArray(count)
    fun protect(fid: Int) {
        budget.visit()
        val index = fid - baseFactorCount
        if (index in retained.indices) retained[index] = true
    }
    boolReason.forEach(::protect)
    intMinReason.forEach(::protect)
    intMaxReason.forEach(::protect)
    budget.atomic(boolReason.size.toLong() + intMinReason.size + intMaxReason.size + undo.size * 3L)
    for (i in 0 until undo.size) {
        budget.visit()
        if (undo.tag[i] == 1) { protect(undo.minReason[i]); protect(undo.maxReason[i]) }
    }
    for (i in 0 until learned.pendingAttach.size) {
        budget.visit()
        val fid = learned.pendingAttach[i]
        if (learned.stages[fid - baseFactorCount] and 2 == 0) protect(fid)
    }
    nativeEngine?.prepareReclamation(budget, retained, keep)?.let { return retained }
    for (i in 0 until count) {
        budget.visit()
        retained[i] = retained[i] || learned.permanent[i] == 1 || keep(i, learned.lbds[i])
    }
    budget.atomic(propQueue.size.toLong() * 3L + atoms.dirtyFactors.size * 3L + learned.pendingAttach.size,
        (propQueue.size.toLong() + atoms.dirtyFactors.size) * Int.SIZE_BYTES)
    budget.atomic(watches.pos.primitiveCapacityBytes, watches.pos.primitiveCapacityBytes * 2L)
    for (lists in listOf(watches.byLit.asList(), watches.blockersByLit.asList(), atoms.watchersByLit)) {
        for (list in lists) {
            budget.visit()
            budget.atomic(1L + (list?.backingData?.size ?: 0), (list?.backingData?.size?.toLong() ?: 0L) * 8L)
        }
    }
    if (learned.integerRows != null) {
        intEvents.overflow?.let { overflow ->
            budget.atomic(overflow.primitiveCapacityBytes)
            for (value in overflow.valuesInternal) {
                budget.visit()
                if (value != null) {
                    val capacity = (value as IntArrayList).backingData.size.toLong()
                    budget.atomic(capacity, capacity * Int.SIZE_BYTES)
                }
            }
        }
    }
    for (column in listOf(learned.stages, learned.lbds, learned.permanent, learned.tier,
        learned.usedFlags, learned.origins, learned.units, learned.pendingAttach)) {
        budget.atomic(column.backingData.size.toLong(), column.backingData.size.toLong() * Int.SIZE_BYTES)
    }
    // Diagnostic trace publication has its own opaque retained map and is outside this atomic envelope.
    if (arithmeticLearningTrace != null) throw ReclamationPreparation.Deferred()
    budget.ready()
    return retained
}

internal fun PropagationState.ownLearned(sample: com.eignex.klause.util.SharingMemoryObservation,
    index: Int, protected: Boolean) {
    val prop = learned.store[index]
    fun ints(value: IntArray?) { if (value != null) sample.owned(value, value.size.toLong() * Int.SIZE_BYTES, protected) }
    fun longs(value: LongArray) { sample.owned(value, value.size.toLong() * Long.SIZE_BYTES, protected) }
    ints(prop.boolVars)
    ints(prop.intVars)
    ints(prop.initialBoolWatchers)
    ints(prop.initialBoolWatcherBlockers)
    ints(prop.initialIntEventWatches)
    when (prop) {
        is ClausePropagator -> ints(prop.literals)
        is PseudoBooleanPropagator -> { ints(prop.literals); ints(prop.byWeightDesc); longs(prop.weights) }
        is LearnedIntegerPropagator -> { ints(prop.row.variables); longs(prop.row.coefficients) }
    }
    ints(refPayloadStore[baseFactorCount + index] as? IntArray)
}
