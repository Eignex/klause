package com.eignex.klause.propagation

import com.eignex.klause.util.SharingMemoryObservation

internal class NativeWatchEntries(initialCapacity: Int = 0) {
    private var pages = arrayOfNulls<LongArray>(reservedPageSlots(initialCapacity).coerceAtLeast(1))
    private var capacityWords = 0L
    var size = 0
        private set
    val primitiveBytes: Long get() = 12L + capacityWords * Long.SIZE_BYTES
    val referenceSlots: Long get() = pages.size + 1L

    init {
        // A base posting can admit one replacement without copying its established page.
        val reserved = if (initialCapacity == 0) 0L else initialCapacity.toLong() + 1L
        for (index in 0 until reservedPageSlots(initialCapacity)) {
            val remaining = reserved - (index.toLong() shl PAGE_SHIFT)
            val capacity = minOf(PAGE_SIZE.toLong(), remaining).toInt()
            pages[index] = LongArray(capacity)
            capacityWords += capacity
        }
    }

    operator fun get(index: Int): Long = checkNotNull(pages[index ushr PAGE_SHIFT])[index and PAGE_MASK]

    operator fun set(index: Int, value: Long) {
        checkNotNull(pages[index ushr PAGE_SHIFT])[index and PAGE_MASK] = value
    }

    val appendWork: Long
        get() {
            val index = size ushr PAGE_SHIFT
            val required = (size and PAGE_MASK) + 1
            val page = pages.getOrNull(index)
            val directoryGrowth = if (index >= pages.size) pages.size * 3L + 1L else 0L
            val pageGrowth = when {
                page == null -> 6L
                required > page.size -> minOf(PAGE_SIZE, page.size * 2) * 2L + page.size + 4L
                else -> 0L
            }
            return 1L + directoryGrowth + pageGrowth
        }

    fun add(value: Long) {
        val index = size ushr PAGE_SHIFT
        val slot = size and PAGE_MASK
        if (index >= pages.size) pages = pages.copyOf(maxOf(pages.size * 2, index + 1))
        var page = pages[index]
        if (page == null) {
            page = LongArray(1)
            pages[index] = page
            capacityWords++
        } else if (slot >= page.size) {
            val capacity = minOf(PAGE_SIZE, page.size * 2)
            capacityWords += capacity - page.size
            page = page.copyOf(capacity)
            pages[index] = page
        }
        page[slot] = value
        size++
    }

    val removalWork: Long get() = if ((size - 1) and PAGE_MASK == 0) 8L else 1L

    fun removeLast() {
        check(size > 0)
        size--
        if (size and PAGE_MASK == 0) {
            val index = size ushr PAGE_SHIFT
            val page = checkNotNull(pages[index])
            capacityWords -= page.size
            pages[index] = null
        }
    }

    fun truncationWork(count: Int): Long = 1L + (pages.size - pageSlots(count)) * 8L

    fun truncateTo(count: Int) {
        require(count in 0..size)
        for (index in pageSlots(count) until pages.size) {
            val page = pages[index] ?: continue
            capacityWords -= page.size
            pages[index] = null
        }
        size = count
    }

    fun sharingMemory(sample: SharingMemoryObservation, protected: (Long) -> Boolean): Long {
        var protectedWords = 0L
        for (index in pages.indices) {
            sample.step()
            val page = pages[index] ?: continue
            val end = minOf(page.size, size - (index shl PAGE_SHIFT))
            for (slot in 0 until end) {
                sample.step()
                if (protected(page[slot])) {
                    protectedWords += page.size
                    break
                }
            }
        }
        val retained = protectedWords > 0L
        sample.ownedPool(this, primitiveBytes, if (retained) 12L + protectedWords * Long.SIZE_BYTES else 0L)
        sample.ownedReferences(this, 1L, retained)
        sample.ownedReferences(pages, pages.size.toLong(), retained)
        return primitiveBytes
    }

    private fun reservedPageSlots(count: Int): Int {
        require(count >= 0)
        return if (count == 0) 0 else ((count.toLong() + 1L + PAGE_MASK) ushr PAGE_SHIFT).toInt()
    }

    private fun pageSlots(count: Int): Int = ((count.toLong() + PAGE_MASK) ushr PAGE_SHIFT).toInt()

    private companion object {
        const val PAGE_SHIFT = 4
        const val PAGE_SIZE = 1 shl PAGE_SHIFT
        const val PAGE_MASK = PAGE_SIZE - 1
    }
}
