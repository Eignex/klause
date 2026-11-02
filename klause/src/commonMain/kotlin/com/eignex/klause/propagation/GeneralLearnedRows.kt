package com.eignex.klause.propagation

import com.eignex.klause.util.SharingMemoryObservation

internal class GeneralLearnedRows : AbstractList<LearnedPropagator>() {
    private class Page(capacity: Int) {
        var values = IntArray(capacity * COLUMNS)
        var admissions = LongArray(capacity)
        var propagators = arrayOfNulls<LearnedPropagator>(capacity)
        var payloads = arrayOfNulls<Any>(capacity)
        var retained = 0
        val bytes: Long get() = values.size.toLong() * Int.SIZE_BYTES + admissions.size.toLong() * Long.SIZE_BYTES
        val capacity: Int get() = admissions.size
    }

    private var pages = arrayOfNulls<Page>(1).also { it[0] = Page(INITIAL_CAPACITY) }
    private var pageCount = 1
    private var allocatedBytes = INITIAL_CAPACITY * ROW_BYTES
    private var allocationIdentity = Any()
    override var size = 0
        private set
    val primitiveBytes: Long get() = 16L + allocatedBytes + pageCount * Int.SIZE_BYTES
    val referenceCapacity: Long get() = 2L + pages.size + pageCount * 4L + allocatedBytes / ROW_BYTES * 2L
    val preparationWork: Long get() = INITIAL_CAPACITY * ROW_WORK * 2L + 10L
    val hasEmptyReservation: Boolean get() = size == 0 && pageCount > 0
    val admissionWork: Long
        get() {
            val index = size ushr PAGE_SHIFT
            return 9L + (if (index >= pages.size) pages.size * 3L + 1L else 0L) +
                allocationWork(pages.getOrNull(index), (size and PAGE_MASK) + 1)
        }

    private fun allocationWork(page: Page?, required: Int): Long = when {
        page == null -> capacityFor(required) * ROW_WORK * 2L + 8L
        required > page.capacity -> capacityFor(required) * ROW_WORK * 2L +
            page.bytes / Int.SIZE_BYTES + page.capacity * 2L + 8L
        else -> 0L
    }

    private fun pageFor(index: Int, required: Int): Page {
        if (index >= pages.size) pages = pages.copyOf(maxOf(index + 1, pages.size * 2))
        val page = pages[index]
        if (page == null) {
            val created = Page(capacityFor(required))
            pages[index] = created
            pageCount++
            allocatedBytes += created.bytes
            allocationIdentity = Any()
            return created
        }
        if (required > page.capacity) {
            val capacity = capacityFor(required)
            allocatedBytes -= page.bytes
            page.values = page.values.copyOf(capacity * COLUMNS)
            page.admissions = page.admissions.copyOf(capacity)
            page.propagators = page.propagators.copyOf(capacity)
            page.payloads = page.payloads.copyOf(capacity)
            allocatedBytes += page.bytes
            allocationIdentity = Any()
        }
        return page
    }

    override operator fun get(index: Int): LearnedPropagator {
        if (index !in 0 until size) throw IndexOutOfBoundsException("learned row $index outside $size")
        return pages.getOrNull(index ushr PAGE_SHIFT)?.propagators?.getOrNull(index and PAGE_MASK)
            ?: RetiredLearnedPropagator
    }

    operator fun set(index: Int, propagator: LearnedPropagator) {
        checkNotNull(pages[index ushr PAGE_SHIFT]).propagators[index and PAGE_MASK] =
            if (propagator === RetiredLearnedPropagator) null else propagator
    }

    fun payload(index: Int): Any? = pages.getOrNull(index ushr PAGE_SHIFT)?.payloads?.getOrNull(index and PAGE_MASK)

    fun setPayload(index: Int, value: Any?): Any? {
        val page = checkNotNull(pages[index ushr PAGE_SHIFT])
        val slot = index and PAGE_MASK
        val old = page.payloads[slot]
        page.payloads[slot] = value
        return old
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

    fun add(propagator: LearnedPropagator, stage: Int, lbd: Int, permanent: Boolean, origin: Int, admission: Long) {
        val slot = size and PAGE_MASK
        val page = pageFor(size ushr PAGE_SHIFT, slot + 1)
        page.retained = page.retained or (1 shl slot)
        page.propagators[slot] = propagator
        page.values[slot * COLUMNS + STAGES] = stage
        page.values[slot * COLUMNS + LBD] = lbd
        page.values[slot * COLUMNS + PERMANENT] = if (permanent) 1 else 0
        page.values[slot * COLUMNS + TIER] = ClauseTier.UNSET.ordinal
        page.values[slot * COLUMNS + ORIGIN] = origin
        page.admissions[slot] = admission
        size++
    }

    fun releaseEmptyReservation() {
        check(size == 0)
        releasePage(0)
    }

    fun release(index: Int) {
        val pageIndex = index ushr PAGE_SHIFT
        val page = pages[pageIndex] ?: return
        val slot = index and PAGE_MASK
        page.propagators[slot] = null
        page.payloads[slot] = null
        page.retained = page.retained and (1 shl slot).inv()
        if (page.retained == 0) releasePage(pageIndex)
    }

    private fun releasePage(index: Int) {
        val page = pages[index] ?: return
        allocatedBytes -= page.bytes
        pages[index] = null
        pageCount--
        allocationIdentity = Any()
    }

    fun move(source: Int, target: Int): Long {
        if (source == target) return 1L
        val sourcePage = checkNotNull(pages[source ushr PAGE_SHIFT])
        val sourceSlot = source and PAGE_MASK
        check(sourcePage.retained and (1 shl sourceSlot) != 0)
        val pageIndex = target ushr PAGE_SHIFT
        val targetSlot = target and PAGE_MASK
        val allocation = allocationWork(pages[pageIndex], targetSlot + 1)
        val targetPage = pageFor(pageIndex, targetSlot + 1)
        sourcePage.values.copyInto(targetPage.values, targetSlot * COLUMNS,
            sourceSlot * COLUMNS, (sourceSlot + 1) * COLUMNS)
        targetPage.admissions[targetSlot] = sourcePage.admissions[sourceSlot]
        targetPage.propagators[targetSlot] = sourcePage.propagators[sourceSlot]
        targetPage.payloads[targetSlot] = sourcePage.payloads[sourceSlot]
        targetPage.retained = targetPage.retained or (1 shl targetSlot)
        return COLUMNS * 2L + 12L + allocation
    }

    fun truncateTo(count: Int): Long {
        require(count in 0..size)
        var work = 4L
        if (count == 0) releasePage(0)
        if (count > 0 && count and PAGE_MASK != 0) {
            val index = (count - 1) ushr PAGE_SHIFT
            val last = pages[index]
            if (last != null) {
                val required = count and PAGE_MASK
                last.retained = last.retained and ((1 shl required) - 1)
                if (last.retained == 0) releasePage(index) else {
                    allocatedBytes -= last.bytes
                    last.values = last.values.copyOf(required * COLUMNS)
                    last.admissions = last.admissions.copyOf(required)
                    last.propagators = last.propagators.copyOf(required)
                    last.payloads = last.payloads.copyOf(required)
                    allocatedBytes += last.bytes
                    allocationIdentity = Any()
                    work += required * ROW_WORK * 2L + 8L
                }
            }
        }
        val retained = pageSlots(count)
        for (index in retained until pages.size) {
            work += 8L
            releasePage(index)
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
        budget.atomic(size * (COLUMNS * 2L + 12L) + count * PAGE_BYTES / Int.SIZE_BYTES * 2L + pages.size * 8L,
            count * (PAGE_BYTES + PAGE_SIZE * Long.SIZE_BYTES * 2L) * 4L + pages.size * Long.SIZE_BYTES)
    }

    fun sharingMemory(sample: SharingMemoryObservation, protectedRows: Boolean, protected: (Int) -> Boolean) {
        var protectedBytes = 0L
        for (index in pages.indices) {
            sample.step()
            val page = pages[index] ?: continue
            for (slot in 0 until page.capacity) {
                sample.step()
                if (page.retained and (1 shl slot) != 0 && protected((index shl PAGE_SHIFT) + slot)) {
                    protectedBytes += page.bytes + Int.SIZE_BYTES
                    break
                }
            }
        }
        sample.owned(this, 16L, protectedRows)
        // Private arrays form a disjoint pool; capacity changes replace its immutable allocation identity.
        sample.ownedPool(allocationIdentity, primitiveBytes - 16L, protectedBytes)
        sample.ownedReferences(this, referenceCapacity, protectedRows)
    }

    private fun pageSlots(count: Int): Int = ((count.toLong() + PAGE_MASK) ushr PAGE_SHIFT).toInt().coerceAtLeast(1)

    companion object {
        const val LBD = 0
        const val PERMANENT = 1
        const val TIER = 2
        const val BOOL_REASONS = 3
        const val STAGES = 4
        const val ORIGIN = 5
        const val NEXT_RETIRED = 6
        private const val INITIAL_CAPACITY = 8
        private const val COLUMNS = 7
        private const val RETIRED = 8
        private const val PAGE_SHIFT = 4
        private const val PAGE_SIZE = 1 shl PAGE_SHIFT
        private const val PAGE_MASK = PAGE_SIZE - 1
        private val ROW_BYTES = COLUMNS.toLong() * Int.SIZE_BYTES + Long.SIZE_BYTES
        private val ROW_WORK = ROW_BYTES / Int.SIZE_BYTES + 2L
        private val PAGE_BYTES = PAGE_SIZE * ROW_BYTES + Int.SIZE_BYTES
        private fun capacityFor(required: Int): Int =
            1 shl (Int.SIZE_BITS - (required - 1).countLeadingZeroBits())
    }
}
