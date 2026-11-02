package com.eignex.klause.propagation

import com.eignex.klause.util.SharingMemoryObservation

internal class LearnedIntegerRows : AbstractList<LearnedIntegerPropagator>() {
    private var entries: Array<LearnedIntegerPropagator?> = emptyArray()
    override var size = 0
        private set
    val capacity: Int get() = entries.size
    val admissionWork: Long get() = 4L + if (size == capacity) size * 3L + 1L else 0L
    val trimmingWork: Long get() = if (capacity > size) size * 2L + 1L else 0L

    override fun get(index: Int): LearnedIntegerPropagator {
        if (index !in 0 until size) throw IndexOutOfBoundsException("$index of $size")
        return checkNotNull(entries[index])
    }

    fun admit(row: LearnedIntegerPropagator) {
        if (size == capacity) entries = entries.copyOf((capacity * 2).coerceAtLeast(1))
        row.retentionSlot = size
        entries[size++] = row
    }

    fun unlink(row: LearnedIntegerPropagator) {
        val slot = row.retentionSlot
        check(slot in 0 until size && entries[slot] === row)
        size--
        val moved = entries[size]
        entries[size] = null
        if (slot < size) {
            entries[slot] = moved
            checkNotNull(moved).retentionSlot = slot
        }
        row.retentionSlot = -1
        if (size == 0) entries = emptyArray()
    }

    fun reset() {
        entries.fill(null, 0, size)
        size = 0
    }

    fun trim() {
        if (capacity > size) entries = entries.copyOf(size)
    }

    fun sharingMemory(sample: SharingMemoryObservation, protected: Boolean) {
        sample.ownedReferences(this, capacity.toLong(), protected)
    }
}
