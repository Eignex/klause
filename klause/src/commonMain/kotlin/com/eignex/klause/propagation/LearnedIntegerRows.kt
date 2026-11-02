package com.eignex.klause.propagation

import com.eignex.klause.util.SharingMemoryObservation

internal class LearnedIntegerRows : AbstractList<LearnedIntegerPropagator>() {
    private var entries: Array<LearnedIntegerPropagator?> = emptyArray()
    private var pages: Array<Array<LearnedIntegerPropagator?>?> = emptyArray()
    private var tailCapacity = 0
    override var size = 0
        private set
    val capacity: Int get() = entries.size + tailCapacity
    val primitiveCapacityBytes: Long get() = 8L
    val referenceCapacity: Long get() = 2L + capacity + pages.size
    val admissionWork: Long
        get() {
            if (size < PAGE_SIZE) return 4L + if (size == entries.size) size * 3L + 1L else 0L
            val index = (size - PAGE_SIZE) ushr PAGE_SHIFT
            val slot = (size - PAGE_SIZE) and PAGE_MASK
            val page = pages.getOrNull(index)
            val directory = if (index >= pages.size) pages.size * 3L + 1L else 0L
            val allocation = if (page == null) 1L else if (slot == page.size) page.size * 3L + 1L else 0L
            return 8L + directory + allocation
        }
    val trimmingWork: Long
        get() {
            val primary = minOf(size, PAGE_SIZE)
            var work = if (entries.size > primary) primary * 2L + 1L else 0L
            if (size <= PAGE_SIZE) return work + if (pages.isNotEmpty()) 4L else 0L
            val count = tailPages
            val last = tailSize
            if (pages.size > count) work += count * 2L + 1L
            if (checkNotNull(pages[count - 1]).size > last) work += last * 2L + 1L
            if (tailCapacity > size - PAGE_SIZE) work += 4L
            return work
        }
    val trimmingScratchBytes: Long
        get() {
            val primary = minOf(size, PAGE_SIZE)
            var slots = if (entries.size > primary) primary else 0
            if (size > PAGE_SIZE) {
                val count = tailPages
                if (pages.size > count) slots += count
                if (checkNotNull(pages[count - 1]).size > tailSize) slots += tailSize
            }
            return slots.toLong() * Long.SIZE_BYTES
        }
    private val tailPages: Int get() = ((size.toLong() - PAGE_SIZE + PAGE_MASK) ushr PAGE_SHIFT).toInt()
    private val tailSize: Int get() = ((size - PAGE_SIZE - 1) and PAGE_MASK) + 1

    override fun get(index: Int): LearnedIntegerPropagator {
        if (index !in 0 until size) throw IndexOutOfBoundsException("$index of $size")
        return checkNotNull(if (index < PAGE_SIZE) entries[index]
            else checkNotNull(pages[(index - PAGE_SIZE) ushr PAGE_SHIFT])[(index - PAGE_SIZE) and PAGE_MASK])
    }

    private fun setSlot(index: Int, row: LearnedIntegerPropagator?) {
        if (index < PAGE_SIZE) entries[index] = row
        else checkNotNull(pages[(index - PAGE_SIZE) ushr PAGE_SHIFT])[(index - PAGE_SIZE) and PAGE_MASK] = row
    }

    fun admit(row: LearnedIntegerPropagator) {
        if (size < PAGE_SIZE) {
            if (size == entries.size) entries = entries.copyOf((entries.size * 2).coerceAtLeast(1))
        } else {
            val index = (size - PAGE_SIZE) ushr PAGE_SHIFT
            val slot = (size - PAGE_SIZE) and PAGE_MASK
            if (index >= pages.size) pages = pages.copyOf((pages.size * 2).coerceAtLeast(1))
            val page = pages[index]
            if (page == null) {
                pages[index] = arrayOfNulls(1)
                tailCapacity++
            } else if (slot == page.size) {
                val capacity = (page.size * 2).coerceAtMost(PAGE_SIZE)
                pages[index] = page.copyOf(capacity)
                tailCapacity += capacity - page.size
            }
        }
        row.retentionSlot = size
        setSlot(size++, row)
    }

    fun unlink(row: LearnedIntegerPropagator) {
        val slot = row.retentionSlot
        check(slot in 0 until size && get(slot) === row)
        val moved = get(size - 1)
        size--
        setSlot(size, null)
        if (slot < size) {
            setSlot(slot, moved)
            moved.retentionSlot = slot
        }
        row.retentionSlot = -1
        if (size == 0) {
            entries = emptyArray()
            pages = emptyArray()
            tailCapacity = 0
        }
    }

    fun reset() {
        for (index in 0 until size) setSlot(index, null)
        size = 0
    }

    fun trim() {
        val primary = minOf(size, PAGE_SIZE)
        if (entries.size > primary) entries = entries.copyOf(primary)
        if (size <= PAGE_SIZE) {
            pages = emptyArray()
            tailCapacity = 0
        } else {
            val count = tailPages
            if (pages.size > count) pages = pages.copyOf(count)
            val last = checkNotNull(pages[count - 1])
            if (last.size > tailSize) pages[count - 1] = last.copyOf(tailSize)
            tailCapacity = size - PAGE_SIZE
        }
    }

    fun sharingMemory(sample: SharingMemoryObservation, protected: Boolean) {
        sample.owned(this, primitiveCapacityBytes, protected)
        sample.ownedReferences(this, referenceCapacity, protected)
    }

    private companion object {
        const val PAGE_SHIFT = 6
        const val PAGE_SIZE = 1 shl PAGE_SHIFT
        const val PAGE_MASK = PAGE_SIZE - 1
    }
}
