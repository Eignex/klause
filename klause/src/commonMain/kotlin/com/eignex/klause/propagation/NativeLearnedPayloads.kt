package com.eignex.klause.propagation

import com.eignex.klause.util.SharingMemoryObservation

internal class NativeLearnedPayloads {
    private class Page {
        val rows = arrayOfNulls<IntArray>(PAGE_SIZE)
        var words = 0L
        var live = 0
    }

    private var directory = arrayOfNulls<Page>(1)
    var size = 0
        private set
    private var words = 0L
    private var pageCount = 0
    val capacityBytes: Long get() = words * Int.SIZE_BYTES
    val referenceCapacity: Long get() = pageCount.toLong() * PAGE_SIZE
    val directoryCapacity: Long get() = directory.size.toLong()
    val primitiveBytes: Long get() = 16L + pageCount * 12L

    fun admissionWork(literals: Int): Long {
        val page = size ushr PAGE_SHIFT
        val growth = if (page >= directory.size) directory.size * 3L + 1L else 0L
        return literals * 2L + 12L + growth +
            if (directory.getOrNull(page) == null) PAGE_SIZE * 2L + 8L else 0L
    }

    fun add(literals: IntArray) {
        val index = size ushr PAGE_SHIFT
        if (index >= directory.size) directory = directory.copyOf(maxOf(directory.size * 2, index + 1))
        val page = directory[index] ?: Page().also { directory[index] = it; pageCount++ }
        page.rows[size and PAGE_MASK] = literals.copyOf()
        page.words += literals.size
        page.live++
        words += literals.size
        size++
    }

    operator fun get(index: Int): IntArray = checkNotNull(checkNotNull(directory[index ushr PAGE_SHIFT])
        .rows[index and PAGE_MASK])

    fun length(index: Int): Int = directory.getOrNull(index ushr PAGE_SHIFT)?.rows?.get(index and PAGE_MASK)?.size ?: 0

    fun release(index: Int) {
        val pageIndex = index ushr PAGE_SHIFT
        val page = directory[pageIndex] ?: return
        val slot = index and PAGE_MASK
        val literals = page.rows[slot] ?: return
        words -= literals.size
        page.words -= literals.size
        page.rows[slot] = null
        page.live--
        if (page.live == 0) {
            directory[pageIndex] = null
            pageCount--
        }
    }

    fun move(source: Int, target: Int): Long {
        if (source == target) return 1L
        val sourceIndex = source ushr PAGE_SHIFT
        val sourcePage = directory[sourceIndex] ?: return 2L
        val sourceSlot = source and PAGE_MASK
        val literals = sourcePage.rows[sourceSlot] ?: return 2L
        val targetIndex = target ushr PAGE_SHIFT
        var work = 24L
        val targetPage = directory[targetIndex] ?: Page().also {
            directory[targetIndex] = it
            pageCount++
            work += PAGE_SIZE * 2L + 8L
        }
        val targetSlot = target and PAGE_MASK
        check(targetPage.rows[targetSlot] == null)
        targetPage.rows[targetSlot] = literals
        sourcePage.rows[sourceSlot] = null
        if (sourcePage !== targetPage) {
            targetPage.words += literals.size
            targetPage.live++
            sourcePage.words -= literals.size
            sourcePage.live--
            if (sourcePage.live == 0) {
                directory[sourceIndex] = null
                pageCount--
            }
        }
        return work
    }

    fun trimTo(count: Int): Long {
        val retained = pageSlots(count)
        size = count
        if (directory.size == retained) return 1L
        directory = directory.copyOf(retained)
        return 2L + retained * 2L
    }

    fun prepareRemap(budget: ReclamationPreparation) {
        val pages = pageSlots(size).toLong()
        budget.atomic(directory.size * 2L + size + pages * PAGE_SIZE * 2L,
            (directory.size * 2L + pages * PAGE_SIZE) * Long.SIZE_BYTES + pages * 20L)
    }

    fun sharingMemory(sample: SharingMemoryObservation, protectedRows: Boolean, protected: (Int) -> Boolean) {
        for (index in directory.indices) {
            sample.step()
            val page = directory[index] ?: continue
            var retained = false
            var protectedWords = 0L
            if (protectedRows) {
                for (slot in page.rows.indices) {
                    sample.step()
                    val literals = page.rows[slot]
                    if (literals != null && protected((index shl PAGE_SHIFT) + slot)) {
                        retained = true
                        protectedWords += literals.size
                    }
                }
            }
            // Admission copies every array; the page owns one disjoint payload and reference pool.
            sample.ownedPool(page, 12L + page.words * Int.SIZE_BYTES,
                if (retained) 12L + protectedWords * Int.SIZE_BYTES else 0L)
            sample.ownedReferences(page, PAGE_SIZE + 1L, retained)
        }
        sample.owned(this, 16L, protectedRows)
        sample.ownedReferences(this, 1L, protectedRows)
        sample.ownedReferences(directory, directoryCapacity, protectedRows)
    }

    private fun pageSlots(count: Int): Int = ((count.toLong() + PAGE_MASK) ushr PAGE_SHIFT).toInt().coerceAtLeast(1)

    private companion object {
        const val PAGE_SHIFT = 6
        const val PAGE_SIZE = 1 shl PAGE_SHIFT
        const val PAGE_MASK = PAGE_SIZE - 1
    }
}
