package com.eignex.klause.propagation

import com.eignex.klause.util.MutableIntObjectIndex
import com.eignex.klause.util.SharingMemoryObservation

internal class NativeLearnedPayloads(private val state: PropagationState? = null) {
    private class Page(capacity: Int) {
        var rows = arrayOfNulls<IntArray>(capacity)
        var words = 0L
        var live = 0
        var queued = 0
        lateinit var slot: MutableIntObjectIndex.Slot<Page>
        var next: Page? = null
        var allocationIdentity = Any()

        fun growthWork(required: Int): Long = if (required <= rows.size) 0L else
            capacityFor(required) * 2L + rows.size + 4L

        fun growTo(required: Int) {
            if (required > rows.size) rows = rows.copyOf(capacityFor(required))
        }

        fun sharingMemory(
            sample: SharingMemoryObservation,
            base: Int,
            protectedRows: Boolean,
            allPermanent: Boolean,
            protected: (Int) -> Boolean,
        ): Boolean {
            var required = false
            var protectedWords = 0L
            if (allPermanent) {
                required = live > 0
                protectedWords = words
            } else if (protectedRows) {
                for (slot in rows.indices) {
                    sample.step()
                    val literals = rows[slot]
                    if (literals != null && protected(base + slot)) {
                        required = true
                        protectedWords += literals.size
                    }
                }
            }
            sample.ownedPool(allocationIdentity, 16L + words * Int.SIZE_BYTES,
                if (required) 16L + protectedWords * Int.SIZE_BYTES else 0L)
            sample.ownedReferencesPool(allocationIdentity, rows.size + 4L,
                if (required) rows.size + 4L else 0L)
            return required
        }
    }

    private val directory = MutableIntObjectIndex<Page>()
    private var directoryIdentity = Any()
    private var cachedIndex = -1
    private var cachedPage: Page? = null
    private var emptyHead: Page? = null
    private var emptyTail: Page? = null
    private var budget: ReclamationPreparation? = null
    var size = 0
        private set
    private var words = 0L
    private var pageCount = 0
    private var referenceSlots = 0L
    val capacityBytes: Long get() = words * Int.SIZE_BYTES
    val referenceCapacity: Long get() = referenceSlots
    val directoryCapacity: Long get() = directory.referenceCapacity
    val primitiveBytes: Long get() = 28L + pageCount * 16L + directory.primitiveCapacityBytes

    fun <T> usingBudget(active: ReclamationPreparation, operation: () -> T): T {
        val previous = budget
        budget = active
        return try { operation() } finally { budget = previous }
    }

    private fun locate(index: Int, visit: (Long) -> Unit): Page? {
        if (cachedIndex == index) return cachedPage
        val page = directory.lookup(index, visit)
        cachedIndex = index
        cachedPage = page
        return page
    }

    private fun pageAt(index: Int): Page? {
        if (cachedIndex == index) return cachedPage
        return locate(index) { units ->
            val active = budget
            if (active != null) active.visit(units) else state?.let { it.work += units }
        }
    }

    fun admissionWork(literals: Int, ownedLiterals: Boolean = false): Long {
        val index = size ushr PAGE_SHIFT
        val required = (size and PAGE_MASK) + 1
        var lookup = 0L
        val page = locate(index) { lookup += it }
        val insertion = if (page != null) 0L else if (directory.size == 0) 8L else
            (Int.SIZE_BITS - index.countLeadingZeroBits()) * 2L + 18L
        val allocation = page?.growthWork(required) ?: (capacityFor(required) * 2L + 8L)
        return (if (ownedLiterals) 0L else literals * 2L) + 14L + lookup + insertion + allocation
    }

    fun add(literals: IntArray, ownedLiterals: Boolean = false) {
        val index = size ushr PAGE_SHIFT
        val slot = size and PAGE_MASK
        val page = pageAt(index) ?: Page(capacityFor(slot + 1)).also {
            it.slot = directory.getOrPutSlot(index) { it }
            pageCount++
            referenceSlots += it.rows.size
            directoryIdentity = Any()
            cachedIndex = index
            cachedPage = it
        }
        val capacity = page.rows.size
        page.growTo(slot + 1)
        referenceSlots += page.rows.size - capacity
        page.rows[slot] = if (ownedLiterals) literals else literals.copyOf()
        page.words += literals.size
        page.live++
        page.allocationIdentity = Any()
        words += literals.size
        size++
    }

    operator fun get(index: Int): IntArray = checkNotNull(checkNotNull(pageAt(index ushr PAGE_SHIFT))
        .rows[index and PAGE_MASK])

    fun length(index: Int): Int = pageAt(index ushr PAGE_SHIFT)?.rows?.getOrNull(index and PAGE_MASK)?.size ?: 0

    fun release(index: Int) {
        val page = pageAt(index ushr PAGE_SHIFT) ?: return
        val slot = index and PAGE_MASK
        val literals = page.rows[slot] ?: return
        words -= literals.size
        page.words -= literals.size
        page.rows[slot] = null
        page.live--
        if (page.live == 0) empty(page)
        page.allocationIdentity = Any()
    }

    private fun empty(page: Page) {
        referenceSlots -= page.rows.size
        page.rows = emptyArray()
        if (page.queued != 0) return
        page.queued = 1
        if (emptyTail == null) emptyHead = page else checkNotNull(emptyTail).next = page
        emptyTail = page
    }

    fun sweepDirectory(budget: ReclamationPreparation) {
        while (true) {
            val page = emptyHead ?: return
            budget.visit(if (page.live == 0) 24L else 8L)
            budget.visit(0L)
            unlinkEmpty(page)
            budget.advanced()
        }
    }

    private fun unlinkEmpty(page: Page) {
        emptyHead = page.next
        if (emptyHead == null) emptyTail = null
        page.next = null
        page.queued = 0
        if (page.live != 0) return
        check(directory.remove(page.slot, {}, {}))
        pageCount--
        directoryIdentity = Any()
        if (cachedPage === page) { cachedPage = null; cachedIndex = -1 }
    }

    fun move(source: Int, target: Int): Long {
        if (source == target) return 1L
        var work = 32L
        val sourcePage = locate(source ushr PAGE_SHIFT) { work += it } ?: return work
        val sourceSlot = source and PAGE_MASK
        val literals = sourcePage.rows[sourceSlot] ?: return work
        val targetIndex = target ushr PAGE_SHIFT
        val targetSlot = target and PAGE_MASK
        val targetPage = locate(targetIndex) { work += it } ?: Page(capacityFor(targetSlot + 1)).also {
            it.slot = directory.getOrPutSlot(targetIndex) { it }
            work += directory.lastWork
            pageCount++
            referenceSlots += it.rows.size
            directoryIdentity = Any()
            cachedIndex = targetIndex
            cachedPage = it
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
            if (sourcePage.live == 0) empty(sourcePage)
        }
        sourcePage.allocationIdentity = Any()
        targetPage.allocationIdentity = Any()
        return work
    }

    fun trimTo(count: Int): Long {
        var work = 1L
        size = count
        while (emptyHead != null) {
            val page = checkNotNull(emptyHead)
            work += if (page.live == 0) 24L else 8L
            unlinkEmpty(page)
        }
        if (count > 0) {
            val page = locate((count - 1) ushr PAGE_SHIFT) { work += it }
            val required = ((count - 1) and PAGE_MASK) + 1
            if (page != null && page.rows.size > required) {
                referenceSlots -= page.rows.size - required
                page.rows = page.rows.copyOf(required)
                page.allocationIdentity = Any()
                work += required * 2L + 4L
            }
        }
        return work
    }

    fun prepareRemap(budget: ReclamationPreparation) {
        val pages = ((size.toLong() + PAGE_MASK) ushr PAGE_SHIFT).coerceAtLeast(1L)
        budget.atomic(size * 8L + directory.size * 8L + pages * PAGE_SIZE * 2L,
            pages * (PAGE_SIZE * Long.SIZE_BYTES * 2L + 128L))
    }

    fun sharingMemory(
        sample: SharingMemoryObservation,
        protectedRows: Boolean,
        allPermanent: Boolean,
        protected: (Int) -> Boolean,
    ) {
        val retained = directory.protectedCapacity({ units -> repeat(units.toInt()) { sample.step() } }) {
            index, page -> page.sharingMemory(sample, index shl PAGE_SHIFT, protectedRows, allPermanent, protected)
        }
        sample.owned(this, 28L, protectedRows)
        sample.ownedReferences(this, 7L, protectedRows)
        sample.ownedPool(directoryIdentity, directory.primitiveCapacityBytes,
            retained.first + if (protectedRows) 12L else 0L)
        sample.ownedReferencesPool(directoryIdentity, directory.referenceCapacity,
            retained.second + if (protectedRows) 1L else 0L)
    }

    private companion object {
        const val PAGE_SHIFT = 6
        const val PAGE_SIZE = 1 shl PAGE_SHIFT
        const val PAGE_MASK = PAGE_SIZE - 1
        fun capacityFor(count: Int): Int = 1 shl (Int.SIZE_BITS - (count - 1).countLeadingZeroBits())
    }
}
