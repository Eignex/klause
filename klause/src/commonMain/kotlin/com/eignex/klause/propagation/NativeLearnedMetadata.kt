package com.eignex.klause.propagation

import com.eignex.klause.util.SharingMemoryObservation

internal class NativeLearnedMetadata {
    private class Page {
        val values = IntArray(PAGE_SIZE * COLUMNS)
        val admissions = LongArray(PAGE_SIZE)
        var retained = 0
    }

    private var pages = arrayOfNulls<Page>(1)
    private var pageCount = 0
    var size = 0
        private set
    val primitiveBytes: Long get() = 8L + pageCount * PAGE_BYTES
    val admissionWork: Long
        get() {
            val page = size ushr PAGE_SHIFT
            return 32L + (if (page >= pages.size) pages.size * 3L + 1L else 0L) +
                (if (pages.getOrNull(page) == null) PAGE_BYTES / Int.SIZE_BYTES * 2L + 8L else 0L)
        }

    operator fun get(index: Int, column: Int): Int {
        val page = pages.getOrNull(index ushr PAGE_SHIFT)
        val slot = index and PAGE_MASK
        if (page == null || page.retained and (1 shl slot) == 0) return when (column) {
            STAGES -> RETIRED
            ORIGIN -> OWN_ORIGIN
            else -> 0
        }
        return page.values[slot * COLUMNS + column]
    }

    operator fun set(index: Int, column: Int, value: Int) {
        checkNotNull(pages[index ushr PAGE_SHIFT]).values[(index and PAGE_MASK) * COLUMNS + column] = value
    }

    fun admission(index: Int): Long = pages.getOrNull(index ushr PAGE_SHIFT)?.let { page ->
        val slot = index and PAGE_MASK
        if (page.retained and (1 shl slot) != 0) page.admissions[slot] else 0L
    } ?: 0L

    fun add(lbd: Int, permanent: Boolean, origin: Int, admission: Long) {
        val pageIndex = size ushr PAGE_SHIFT
        if (pageIndex >= pages.size) pages = pages.copyOf(maxOf(pageIndex + 1, pages.size * 2))
        val page = pages[pageIndex] ?: Page().also { pages[pageIndex] = it; pageCount++ }
        val slot = size and PAGE_MASK
        page.retained = page.retained or (1 shl slot)
        page.values.fill(0, slot * COLUMNS, (slot + 1) * COLUMNS)
        page.values[slot * COLUMNS + LBD] = lbd
        page.values[slot * COLUMNS + PERMANENT] = if (permanent) 1 else 0
        page.values[slot * COLUMNS + TIER] = ClauseTier.UNSET.ordinal
        page.values[slot * COLUMNS + ORIGIN] = origin
        page.admissions[slot] = admission
        size++
    }

    fun release(index: Int) {
        val pageIndex = index ushr PAGE_SHIFT
        val page = pages[pageIndex] ?: return
        page.retained = page.retained and (1 shl (index and PAGE_MASK)).inv()
        if (page.retained == 0) {
            pages[pageIndex] = null
            pageCount--
        }
    }

    fun move(source: Int, target: Int): Long {
        if (source == target) return 1L
        val sourcePage = checkNotNull(pages[source ushr PAGE_SHIFT])
        val sourceSlot = source and PAGE_MASK
        check(sourcePage.retained and (1 shl sourceSlot) != 0)
        val pageIndex = target ushr PAGE_SHIFT
        val allocated = pages[pageIndex] == null
        val targetPage = pages[pageIndex] ?: Page().also { pages[pageIndex] = it; pageCount++ }
        val targetSlot = target and PAGE_MASK
        sourcePage.values.copyInto(targetPage.values, targetSlot * COLUMNS,
            sourceSlot * COLUMNS, (sourceSlot + 1) * COLUMNS)
        targetPage.admissions[targetSlot] = sourcePage.admissions[sourceSlot]
        targetPage.retained = targetPage.retained or (1 shl targetSlot)
        return COLUMNS * 2L + 8L + if (allocated) PAGE_BYTES / Int.SIZE_BYTES * 2L + 8L else 0L
    }

    fun truncateTo(count: Int): Long {
        require(count in 0..size)
        var work = 4L
        if (count == 0 && pages[0] != null) {
            pages[0] = null
            pageCount--
        }
        if (count > 0 && count and PAGE_MASK != 0) {
            val last = pages[(count - 1) ushr PAGE_SHIFT]
            if (last != null) {
                last.retained = last.retained and ((1 shl (count and PAGE_MASK)) - 1)
                if (last.retained == 0) {
                    pages[(count - 1) ushr PAGE_SHIFT] = null
                    pageCount--
                }
            }
        }
        val retained = pageSlots(count)
        for (index in retained until pages.size) {
            work += 4L
            if (pages[index] != null) { pages[index] = null; pageCount-- }
        }
        if (pages.size != retained) {
            pages = pages.copyOf(retained)
            work += retained * 2L + 2L
        }
        size = count
        return work
    }

    fun prepareRemap(budget: ReclamationPreparation) {
        val count = pageSlots(size).toLong()
        budget.atomic(size * (COLUMNS * 2L + 12L) + count * PAGE_BYTES / Int.SIZE_BYTES * 2L + pages.size * 4L,
            count * PAGE_BYTES + pages.size * Long.SIZE_BYTES)
    }

    fun sharingMemory(sample: SharingMemoryObservation, protectedRows: Boolean, protected: (Int) -> Boolean) {
        var protectedBytes = if (protectedRows) 8L else 0L
        for (index in pages.indices) {
            sample.step()
            val page = pages[index] ?: continue
            for (slot in 0 until PAGE_SIZE) {
                sample.step()
                if (page.retained and (1 shl slot) != 0 && protected((index shl PAGE_SHIFT) + slot)) {
                    protectedBytes += PAGE_BYTES
                    break
                }
            }
        }
        // Arrays are exclusive to this table; one disjoint pool avoids per-row observation scratch.
        sample.ownedPool(this, primitiveBytes, protectedBytes)
        sample.ownedReferences(this, 1L + pages.size + pageCount * 2L, protectedRows)
    }

    private fun pageSlots(count: Int): Int = ((count.toLong() + PAGE_MASK) ushr PAGE_SHIFT).toInt().coerceAtLeast(1)

    companion object {
        const val LBD = 0
        const val PERMANENT = 1
        const val TIER = 2
        const val BOOL_REASONS = 3
        const val STAGES = 4
        const val ORIGIN = 5
        const val SCAN = 6
        const val WATCH_FIRST = 7
        const val WATCH_SECOND = 8
        private const val COLUMNS = 9
        private const val RETIRED = 4
        private const val PAGE_SHIFT = 4
        private const val PAGE_SIZE = 1 shl PAGE_SHIFT
        private const val PAGE_MASK = PAGE_SIZE - 1
        private val PAGE_BYTES = PAGE_SIZE.toLong() * (COLUMNS * Int.SIZE_BYTES + Long.SIZE_BYTES) + Int.SIZE_BYTES
    }
}
