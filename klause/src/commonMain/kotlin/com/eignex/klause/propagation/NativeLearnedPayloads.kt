package com.eignex.klause.propagation

import com.eignex.klause.util.SharingMemoryObservation

internal class NativeLearnedPayloads {
    private var rows = arrayOfNulls<IntArray>(1)
    var size = 0
        private set
    private var words = 0L
    val capacityBytes: Long get() = words * Int.SIZE_BYTES
    val referenceCapacity: Long get() = rows.size.toLong()

    fun admissionWork(literals: Int): Long = literals * 2L + 8L +
        if (size == rows.size) rows.size * 3L + 1L else 0L

    fun add(literals: IntArray) {
        if (size == rows.size) rows = rows.copyOf(rows.size * 2)
        rows[size++] = literals.copyOf()
        words += literals.size
    }

    operator fun get(index: Int): IntArray = checkNotNull(rows[index])

    fun length(index: Int): Int = rows[index]?.size ?: 0

    fun release(index: Int) {
        val literals = rows[index] ?: return
        words -= literals.size
        rows[index] = null
    }

    fun move(source: Int, target: Int) { rows[target] = rows[source] }

    fun trimTo(count: Int) {
        rows = rows.copyOf(count.coerceAtLeast(1))
        size = count
    }

    fun prepareRemap(budget: ReclamationPreparation) {
        budget.atomic(rows.size * 2L + size, rows.size.toLong() * Long.SIZE_BYTES * 2L)
    }

    fun sharingMemory(sample: SharingMemoryObservation, protected: SharingMemoryObservation.Seen<Int>,
        protectedRows: Boolean) {
        for (index in 0 until size) {
            sample.step()
            val literals = rows[index] ?: continue
            sample.owned(literals, literals.size.toLong() * Int.SIZE_BYTES, sample.contains(protected, index))
        }
        sample.owned(this, 12L, protectedRows)
        sample.ownedReferences(this, 1L, protectedRows)
        sample.ownedReferences(rows, referenceCapacity, protectedRows)
    }
}
