package com.eignex.klause.propagation

import com.eignex.klause.util.SharingMemoryObservation

internal class NativeLearnedPayloads {
    private class Page(capacity: Int) {
        var rows = arrayOfNulls<IntArray>(capacity)
        var words = 0L
        var live = 0

        fun growthWork(required: Int): Long = if (required <= rows.size) 0L else
            capacityFor(required) * 2L + rows.size + 4L

        fun growTo(required: Int) {
            if (required > rows.size) rows = rows.copyOf(capacityFor(required))
        }
    }

    private var directory = arrayOfNulls<Page>(1)
    var size = 0
        private set
    private var words = 0L
    private var pageCount = 0
    private var referenceSlots = 0L
    val capacityBytes: Long get() = words * Int.SIZE_BYTES
    val referenceCapacity: Long get() = referenceSlots
    val directoryCapacity: Long get() = directory.size.toLong()
    val primitiveBytes: Long get() = 24L + pageCount * 12L

    fun admissionWork(literals: Int): Long {
        val index = size ushr PAGE_SHIFT
        val required = (size and PAGE_MASK) + 1
        val page = directory.getOrNull(index)
        val growth = if (index >= directory.size) directory.size * 3L + 1L else 0L
        val allocation = page?.growthWork(required) ?: (capacityFor(required) * 2L + 8L)
        return literals * 2L + 12L + growth + allocation
    }

    fun add(literals: IntArray) {
        val index = size ushr PAGE_SHIFT
        val slot = size and PAGE_MASK
        if (index >= directory.size) directory = directory.copyOf(maxOf(directory.size * 2, index + 1))
        val page = directory[index] ?: Page(capacityFor(slot + 1)).also {
            directory[index] = it
            pageCount++
            referenceSlots += it.rows.size
        }
        val capacity = page.rows.size
        page.growTo(slot + 1)
        referenceSlots += page.rows.size - capacity
        page.rows[slot] = literals.copyOf()
        page.words += literals.size
        page.live++
        words += literals.size
        size++
    }

    operator fun get(index: Int): IntArray = checkNotNull(checkNotNull(directory[index ushr PAGE_SHIFT])
        .rows[index and PAGE_MASK])

    fun length(index: Int): Int =
        directory.getOrNull(index ushr PAGE_SHIFT)?.rows?.getOrNull(index and PAGE_MASK)?.size ?: 0

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
            referenceSlots -= page.rows.size
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
        val targetSlot = target and PAGE_MASK
        var work = 32L
        val targetPage = directory[targetIndex] ?: Page(capacityFor(targetSlot + 1)).also {
            directory[targetIndex] = it
            pageCount++
            referenceSlots += it.rows.size
            work += it.rows.size * 2L + 8L
        }
        work += targetPage.growthWork(targetSlot + 1)
        val capacity = targetPage.rows.size
        targetPage.growTo(targetSlot + 1)
        referenceSlots += targetPage.rows.size - capacity
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
                referenceSlots -= sourcePage.rows.size
                pageCount--
            }
        }
        return work
    }

    fun trimTo(count: Int): Long {
        var work = 1L
        val retained = pageSlots(count)
        size = count
        if (count > 0) {
            val page = directory[retained - 1]
            val required = ((count - 1) and PAGE_MASK) + 1
            if (page != null && page.rows.size > required) {
                referenceSlots -= page.rows.size - required
                page.rows = page.rows.copyOf(required)
                work += required * 2L + 4L
            }
        }
        if (directory.size != retained) {
            directory = directory.copyOf(retained)
            work += 2L + retained * 2L
        }
        return work
    }

    fun prepareRemap(budget: ReclamationPreparation) {
        val pages = pageSlots(size).toLong()
        budget.atomic(directory.size * 2L + size + pages * PAGE_SIZE * 2L,
            (directory.size * 2L + pages * PAGE_SIZE * 2L) * Long.SIZE_BYTES + pages * 20L)
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
            sample.ownedReferences(page, page.rows.size + 1L, retained)
        }
        sample.owned(this, 24L, protectedRows)
        sample.ownedReferences(this, 1L, protectedRows)
        sample.ownedReferences(directory, directoryCapacity, protectedRows)
    }

    private fun pageSlots(count: Int): Int = ((count.toLong() + PAGE_MASK) ushr PAGE_SHIFT).toInt().coerceAtLeast(1)

    private companion object {
        const val PAGE_SHIFT = 6
        const val PAGE_SIZE = 1 shl PAGE_SHIFT
        const val PAGE_MASK = PAGE_SIZE - 1
        fun capacityFor(count: Int): Int = 1 shl (Int.SIZE_BITS - (count - 1).countLeadingZeroBits())
    }
}
