package com.eignex.klause.propagation

import com.eignex.klause.util.SharingMemoryObservation

internal const val NATIVE_CAP_PAGE_WORK = 28L
internal const val NATIVE_CAP_PAGE_BYTES = 52L
private const val CAP_PAGE_SIZE = 8

internal class NativeCapPage {
    val values = IntArray(CAP_PAGE_SIZE)
    var next: NativeCapPage? = null
    var end = 0
}

internal class NativeCapPages {
    private var free: NativeCapPage? = null
    var pages = 0L
        private set
    var available = 0L
        private set
    val slots: Long get() = pages * CAP_PAGE_SIZE

    fun reset() {
        free = null
        pages = 0L
        available = 0L
    }

    fun reserve() {
        free = NativeCapPage().also { it.next = free }
        pages++
        available++
    }

    fun take(): NativeCapPage {
        val page = checkNotNull(free)
        free = page.next
        page.next = null
        available--
        return page
    }

    fun recycle(page: NativeCapPage) {
        page.end = 0
        page.next = free
        free = page
        available++
    }

    fun release(): Boolean {
        val page = free ?: return false
        free = page.next
        page.next = null
        pages--
        available--
        return true
    }

    fun sharingMemory(sample: SharingMemoryObservation, protected: Boolean) {
        sample.owned(this, 16L, protected)
        sample.ownedReferences(this, 1L, protected)
        observeCapPages(free, sample, protected)
    }
}

internal class NativeCapQueue {
    private var head: NativeCapPage? = null
    private var tail: NativeCapPage? = null
    private var offset = 0
    var size = 0
        private set

    fun first(): Int = checkNotNull(head).values[offset]

    fun reset() {
        head = null
        tail = null
        offset = 0
        size = 0
    }

    fun canPush(pool: NativeCapPages): Boolean = tail?.end?.let { it < CAP_PAGE_SIZE } == true || pool.available > 0L

    fun push(value: Int, pool: NativeCapPages) {
        var page = tail
        if (page == null || page.end == CAP_PAGE_SIZE) {
            val next = pool.take()
            if (page == null) head = next else page.next = next
            tail = next
            page = next
        }
        page.values[page.end++] = value
        size++
    }

    fun pop(pool: NativeCapPages): Int {
        val page = checkNotNull(head)
        val value = page.values[offset++]
        size--
        if (offset == page.end) {
            head = page.next
            if (head == null) tail = null
            offset = 0
            pool.recycle(page)
        }
        return value
    }

    fun sharingMemory(sample: SharingMemoryObservation, protected: Boolean) {
        sample.owned(this, 8L, protected)
        sample.ownedReferences(this, 2L, protected)
        observeCapPages(head, sample, protected)
    }
}

private fun observeCapPages(first: NativeCapPage?, sample: SharingMemoryObservation, protected: Boolean) {
    var page = first
    while (page != null) {
        sample.step()
        sample.owned(page, 4L, protected)
        sample.ownedReferences(page, 2L, protected)
        sample.owned(page.values, CAP_PAGE_SIZE.toLong() * Int.SIZE_BYTES, protected)
        page = page.next
    }
}
