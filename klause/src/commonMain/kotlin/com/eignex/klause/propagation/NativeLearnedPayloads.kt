package com.eignex.klause.propagation

import com.eignex.klause.util.MutableIntObjectIndex
import com.eignex.klause.util.SharingMemoryObservation

internal class NativeLearnedPayloads(private val state: PropagationState? = null) {
    private class Page(capacity: Int) {
        var rows = arrayOfNulls<IntArray>(capacity)
        var words = 0L
        var live = 0
        var queued = 0
        var slot: MutableIntObjectIndex.Slot<Page>? = null
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
    private var primary: Page? = null
    private val cachedIndices = IntArray(CACHE_SIZE)
    private val cachedPages = arrayOfNulls<Page>(CACHE_SIZE)
    private var emptyHead: Page? = null
    private var emptyTail: Page? = null
    private var budget: ReclamationPreparation? = null
    var size = 0
        private set
    private var words = 0L
    private var pageCount = 0
    private var referenceSlots = 0L
    val preparationWork: Long get() = 46L + cachedIndices.size * 2L + cachedPages.size * 2L
    val capacityBytes: Long get() = words * Int.SIZE_BYTES
    val referenceCapacity: Long get() = referenceSlots
    // The index-array owner reference accompanies the cache's reference slots.
    val cacheReferenceCapacity: Long get() = cachedPages.size + 1L
    val directoryCapacity: Long get() = directory.referenceCapacity
    val primitiveBytes: Long get() = 24L + cachedIndices.size.toLong() * Int.SIZE_BYTES +
        pageCount * 16L + directory.primitiveCapacityBytes

    fun <T> usingBudget(active: ReclamationPreparation, operation: () -> T): T {
        val previous = budget
        budget = active
        return try { operation() } finally { budget = previous }
    }

    private fun locate(index: Int, visit: (Long) -> Unit): Page? {
        if (index == 0) return primary
        val cacheSlot = index and CACHE_MASK
        if (cachedIndices[cacheSlot] == index) return cachedPages[cacheSlot]
        val page = directory.lookup(index, visit)
        cachedIndices[cacheSlot] = index
        cachedPages[cacheSlot] = page
        return page
    }

    private fun pageAt(index: Int): Page? {
        if (index == 0) return primary
        val cacheSlot = index and CACHE_MASK
        if (cachedIndices[cacheSlot] == index) return cachedPages[cacheSlot]
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
        val insertion = if (page != null || index == 0) 0L else if (directory.size == 0) 8L else
            (Int.SIZE_BITS - index.countLeadingZeroBits()) * 2L + 18L
        val allocation = page?.growthWork(required) ?: (capacityFor(required) * 2L + 8L)
        return (if (ownedLiterals) 0L else literals * 2L) + (if (index == 0) 12L else 14L) +
            lookup + insertion + allocation
    }

    fun add(literals: IntArray, ownedLiterals: Boolean = false) {
        val index = size ushr PAGE_SHIFT
        val slot = size and PAGE_MASK
        val page = pageAt(index) ?: Page(capacityFor(slot + 1)).also { install(index, it) {} }
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

    private fun install(index: Int, page: Page, insertionWork: (Long) -> Unit) {
        if (index == 0) primary = page else {
            page.slot = directory.getOrPutSlot(index) { page }
            insertionWork(directory.lastWork)
            directoryIdentity = Any()
            val cacheSlot = index and CACHE_MASK
            cachedIndices[cacheSlot] = index
            cachedPages[cacheSlot] = page
        }
        pageCount++
        referenceSlots += page.rows.size
    }

    fun isLiveCached(index: Int): Boolean {
        val pageIndex = index ushr PAGE_SHIFT
        val page = if (pageIndex == 0) primary else {
            val cacheSlot = pageIndex and CACHE_MASK
            if (cachedIndices[cacheSlot] != pageIndex) return false
            cachedPages[cacheSlot]
        }
        return page?.rows?.getOrNull(index and PAGE_MASK) != null
    }

    operator fun get(index: Int): IntArray = checkNotNull(checkNotNull(pageAt(index ushr PAGE_SHIFT))
        .rows[index and PAGE_MASK])

    fun length(index: Int): Int = pageAt(index ushr PAGE_SHIFT)?.rows?.getOrNull(index and PAGE_MASK)?.size ?: 0

    fun release(index: Int) {
        val page = pageAt(index ushr PAGE_SHIFT) ?: return
        val slot = index and PAGE_MASK
        val literals = page.rows.getOrNull(slot) ?: return
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
            budget.visit(unlinkingWork(page))
            budget.visit(0L)
            unlinkEmpty(page)
            budget.advanced()
        }
    }

    private fun unlinkingWork(page: Page): Long = when {
        page.live != 0 -> 8L
        page.slot == null -> 12L
        else -> 24L
    }

    private fun unlinkEmpty(page: Page) {
        emptyHead = page.next
        if (emptyHead == null) emptyTail = null
        page.next = null
        page.queued = 0
        if (page.live != 0) return
        val slot = page.slot
        val cacheSlot = slot?.let { it.key and CACHE_MASK } ?: -1
        if (slot == null) { check(primary === page); primary = null } else {
            check(directory.remove(slot, {}, {}))
            directoryIdentity = Any()
        }
        pageCount--
        if (cacheSlot >= 0 && cachedPages[cacheSlot] === page) {
            cachedIndices[cacheSlot] = 0
            cachedPages[cacheSlot] = null
        }
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
            install(targetIndex, it) { units -> work += units }
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
            work += unlinkingWork(page)
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
        primary?.sharingMemory(sample, 0, protectedRows, allPermanent, protected)
        val retained = directory.protectedCapacity({ units -> repeat(units.toInt()) { sample.step() } }) {
            index, page -> page.sharingMemory(sample, index shl PAGE_SHIFT, protectedRows, allPermanent, protected)
        }
        sample.owned(this, 24L, protectedRows)
        sample.owned(cachedIndices, cachedIndices.size.toLong() * Int.SIZE_BYTES, protectedRows)
        sample.ownedReferences(this, 9L, protectedRows)
        sample.ownedReferences(cachedPages, cachedPages.size.toLong(), protectedRows)
        sample.ownedPool(directoryIdentity, directory.primitiveCapacityBytes,
            retained.first + if (protectedRows) 12L else 0L)
        sample.ownedReferencesPool(directoryIdentity, directory.referenceCapacity,
            retained.second + if (protectedRows) 1L else 0L)
    }

    private companion object {
        const val CACHE_SIZE = 128
        const val CACHE_MASK = CACHE_SIZE - 1
        const val PAGE_SHIFT = 6
        const val PAGE_SIZE = 1 shl PAGE_SHIFT
        const val PAGE_MASK = PAGE_SIZE - 1
        fun capacityFor(count: Int): Int = 1 shl (Int.SIZE_BITS - (count - 1).countLeadingZeroBits())
    }
}
