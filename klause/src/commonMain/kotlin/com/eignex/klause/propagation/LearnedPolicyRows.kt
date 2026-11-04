package com.eignex.klause.propagation

import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.MutableIntObjectIndex
import com.eignex.klause.util.SharingMemoryObservation

internal class LearnedPolicyRows(
    private val columns: Int,
    private val retiredFlag: Int,
    private val objectColumns: Boolean,
    private val state: PropagationState?,
) {
    private class Page(capacity: Int, columns: Int, objectColumns: Boolean) {
        var values = IntArray(capacity * columns)
        var admissions = LongArray(capacity)
        var propagators: Array<LearnedPropagator?>? = if (objectColumns) arrayOfNulls(capacity) else null
        var payloads: Array<Any?>? = if (objectColumns) arrayOfNulls(capacity) else null
        var retained = 0
        var pending = 0
        var queued = 0
        var slot: MutableIntObjectIndex.Slot<Page>? = null
        var nextRetired: Page? = null
        var nextEmpty: Page? = null
        val bytes: Long get() = values.size.toLong() * Int.SIZE_BYTES + admissions.size.toLong() * Long.SIZE_BYTES
        val capacity: Int get() = admissions.size
        val references: Long get() = 7L + (propagators?.size ?: 0) + (payloads?.size ?: 0)
    }

    private val directory = MutableIntObjectIndex<Page>()
    private var primary: Page? = Page(INITIAL_CAPACITY, columns, objectColumns)
    private val cachedPages = arrayOfNulls<Page>(if (objectColumns) GENERAL_CACHE_SIZE else NATIVE_CACHE_SIZE)
    private var retiredHead: Page? = null
    private var retiredTail: Page? = null
    private var emptyHead: Page? = null
    private var emptyTail: Page? = null
    private var budget: ReclamationPreparation? = null
    private var observation: SharingMemoryObservation? = null
    private var allocationIdentity = Any()
    private var directoryIdentity = Any()
    private var pageCount = 1
    private var allocatedBytes = INITIAL_CAPACITY * rowBytes
    var size = 0
        private set
    private val rowBytes: Long get() = columns.toLong() * Int.SIZE_BYTES + Long.SIZE_BYTES
    private val rowWork: Long get() = rowBytes / Int.SIZE_BYTES + if (objectColumns) 2L else 0L
    val primitiveBytes: Long get() = 25L + allocatedBytes + pageCount * 12L + directory.primitiveCapacityBytes
    val referenceCapacity: Long get() = 12L + cachedPages.size + directory.referenceCapacity + pageCount * 7L +
        if (objectColumns) allocatedBytes / rowBytes * 2L else 0L
    val preparationWork: Long get() = INITIAL_CAPACITY * rowWork * 2L + cachedPages.size * 2L + 80L
    val hasEmptyReservation: Boolean get() = size == 0 && primary?.capacity?.let { it > 0 } == true
    val hasRetiredRows: Boolean get() = retiredHead != null
    val retiredHeadIndex: Int
        get() = retiredHead?.let { key(it) * PAGE_SIZE + it.pending.countTrailingZeroBits() } ?: -1

    fun <T> usingBudget(active: ReclamationPreparation, operation: () -> T): T {
        val previous = budget
        budget = active
        return try { operation() } finally { budget = previous }
    }

    fun <T> usingObservation(active: SharingMemoryObservation, operation: () -> T): T {
        val previous = observation
        observation = active
        return try { operation() } finally { observation = previous }
    }

    private inline fun locate(index: Int, visit: (Long) -> Unit): Page? {
        if (index == 0) return primary
        val cached = cachedPages[index and cachedPages.lastIndex]
        if (cached?.slot?.key == index) return cached
        val page = directory.lookup(index, visit)
        cachedPages[index and cachedPages.lastIndex] = page
        return page
    }

    private fun chargeLookup(units: Long) {
        val sample = observation
        val active = budget
        when {
            sample != null -> repeat(units.toInt()) { sample.step() }
            active != null -> active.visit(units)
            else -> state?.let { it.work += units }
        }
    }

    private fun pageAt(index: Int): Page? {
        if (index == 0) return primary
        val cached = cachedPages[index and cachedPages.lastIndex]
        if (cached?.slot?.key == index) return cached
        return locate(index, ::chargeLookup)
    }

    private fun allocationWork(page: Page?, required: Int): Long = when {
        page == null -> capacityFor(required) * rowWork * 2L + 8L
        required > page.capacity -> capacityFor(required) * rowWork * 2L + page.bytes / Int.SIZE_BYTES +
            (if (objectColumns) page.capacity * 2L else 0L) + 8L
        else -> 0L
    }

    val admissionWork: Long
        get() {
            val index = size ushr PAGE_SHIFT
            var lookup = 0L
            val page = locate(index) { lookup += it }
            val insertion = if (index == 0 || page != null) 0L else if (directory.size == 0) 8L else
                (Int.SIZE_BITS - index.countLeadingZeroBits()) * 2L + 18L
            return (if (objectColumns) 9L else 7L) + lookup + insertion + allocationWork(page, (size and PAGE_MASK) + 1)
        }

    private fun pageFor(index: Int, required: Int, visit: (Long) -> Unit, allocated: (Long) -> Unit): Page {
        val page = locate(index, visit)
        allocated(allocationWork(page, required))
        if (page == null) {
            val created = Page(capacityFor(required), columns, objectColumns)
            if (index == 0) primary = created else {
                created.slot = directory.getOrPutSlot(index) { created }
                allocated(directory.lastWork)
                directoryIdentity = Any()
                cachedPages[index and cachedPages.lastIndex] = created
            }
            pageCount++
            allocatedBytes += created.bytes
            allocationIdentity = Any()
            return created
        }
        if (required > page.capacity) resize(page, capacityFor(required))
        return page
    }

    private fun resize(page: Page, capacity: Int) {
        allocatedBytes -= page.bytes
        page.values = page.values.copyOf(capacity * columns)
        page.admissions = page.admissions.copyOf(capacity)
        if (objectColumns) {
            page.propagators = checkNotNull(page.propagators).copyOf(capacity)
            page.payloads = checkNotNull(page.payloads).copyOf(capacity)
        }
        allocatedBytes += page.bytes
        allocationIdentity = Any()
    }

    operator fun get(index: Int, column: Int): Int {
        val page = pageAt(index ushr PAGE_SHIFT)
        val slot = index and PAGE_MASK
        if (page == null || page.retained and (1 shl slot) == 0) return when (column) {
            STAGES -> retiredFlag
            ORIGIN -> OWN_ORIGIN
            else -> 0
        }
        return page.values[slot * columns + column]
    }

    operator fun set(index: Int, column: Int, value: Int) {
        checkNotNull(pageAt(index ushr PAGE_SHIFT)).values[(index and PAGE_MASK) * columns + column] = value
    }

    fun admission(index: Int): Long = pageAt(index ushr PAGE_SHIFT)?.let { page ->
        val slot = index and PAGE_MASK
        if (page.retained and (1 shl slot) != 0) page.admissions[slot] else 0L
    } ?: 0L

    fun propagator(index: Int): LearnedPropagator? = pageAt(index ushr PAGE_SHIFT)?.propagators
        ?.getOrNull(index and PAGE_MASK)

    fun setPropagator(index: Int, value: LearnedPropagator) {
        checkNotNull(checkNotNull(pageAt(index ushr PAGE_SHIFT)).propagators)[index and PAGE_MASK] =
            value.takeUnless { it === RetiredLearnedPropagator }
    }

    fun payload(index: Int): Any? = pageAt(index ushr PAGE_SHIFT)?.payloads?.getOrNull(index and PAGE_MASK)

    fun setPayload(index: Int, value: Any?): Any? {
        val values = checkNotNull(checkNotNull(pageAt(index ushr PAGE_SHIFT)).payloads)
        val slot = index and PAGE_MASK
        val old = values[slot]
        values[slot] = value
        return old
    }

    fun add(stage: Int, lbd: Int, permanent: Boolean, origin: Int, admission: Long, value: LearnedPropagator?) {
        val slot = size and PAGE_MASK
        val page = pageFor(size ushr PAGE_SHIFT, slot + 1, ::chargeLookup) {}
        page.retained = page.retained or (1 shl slot)
        if (stage != 0) page.values[slot * columns + STAGES] = stage
        page.values[slot * columns + LBD] = lbd
        page.values[slot * columns + PERMANENT] = if (permanent) 1 else 0
        page.values[slot * columns + TIER] = ClauseTier.UNSET.ordinal
        page.values[slot * columns + ORIGIN] = origin
        page.admissions[slot] = admission
        if (objectColumns) checkNotNull(page.propagators)[slot] = value
        size++
    }

    fun enqueueRetired(index: Int) {
        val page = checkNotNull(pageAt(index ushr PAGE_SHIFT))
        val bit = 1 shl (index and PAGE_MASK)
        check(page.pending and bit == 0)
        if (page.pending == 0) {
            if (retiredTail == null) retiredHead = page else checkNotNull(retiredTail).nextRetired = page
            retiredTail = page
        }
        page.pending = page.pending or bit
    }

    fun retiredValue(column: Int): Int {
        val page = checkNotNull(retiredHead)
        return page.values[page.pending.countTrailingZeroBits() * columns + column]
    }

    fun releaseRetired() {
        val page = checkNotNull(retiredHead)
        val slot = page.pending.countTrailingZeroBits()
        page.pending = page.pending and (1 shl slot).inv()
        releaseSlot(page, slot)
        if (page.pending == 0) {
            retiredHead = page.nextRetired
            page.nextRetired = null
            if (retiredHead == null) retiredTail = null
        }
    }

    fun resetRetirements(): Long {
        var work = 2L
        fun clear(page: Page) { page.pending = 0; page.nextRetired = null; work += 2L }
        primary?.let(::clear)
        directory.forEach({ work += it }) { _, page -> clear(page) }
        retiredHead = null
        retiredTail = null
        return work
    }

    fun releaseEmptyReservation() {
        check(size == 0)
        primary?.let(::discard)
    }

    fun release(index: Int) {
        val page = pageAt(index ushr PAGE_SHIFT) ?: return
        check(page.pending and (1 shl (index and PAGE_MASK)) == 0)
        releaseSlot(page, index and PAGE_MASK)
    }

    private fun releaseSlot(page: Page, slot: Int) {
        if (page.retained and (1 shl slot) == 0) return
        if (objectColumns) {
            checkNotNull(page.propagators)[slot] = null
            checkNotNull(page.payloads)[slot] = null
        }
        page.retained = page.retained and (1 shl slot).inv()
        if (page.retained == 0) discard(page)
    }

    private fun discard(page: Page) {
        if (page.capacity == 0) return
        allocatedBytes -= page.bytes
        page.values = EmptyIntArray
        page.admissions = longArrayOf()
        if (objectColumns) { page.propagators = emptyArray(); page.payloads = emptyArray() }
        page.retained = 0
        page.pending = 0
        allocationIdentity = Any()
        if (page.queued != 0) return
        page.queued = 1
        if (emptyTail == null) emptyHead = page else checkNotNull(emptyTail).nextEmpty = page
        emptyTail = page
    }

    private fun key(page: Page): Int = page.slot?.key ?: 0
    private fun unlinkingWork(page: Page): Long = when {
        page.retained != 0 -> 8L
        page.slot == null -> 12L
        else -> 24L
    }

    fun sweepDirectory(active: ReclamationPreparation) {
        while (true) {
            val page = emptyHead ?: return
            active.visit(unlinkingWork(page))
            active.visit(0L)
            unlinkEmpty(page)
            active.advanced()
        }
    }

    private fun unlinkEmpty(page: Page) {
        emptyHead = page.nextEmpty
        if (emptyHead == null) emptyTail = null
        page.nextEmpty = null
        page.queued = 0
        if (page.retained != 0) return
        val slot = page.slot
        if (slot == null) { check(primary === page); primary = null } else {
            check(directory.remove(slot, {}, {}))
            directoryIdentity = Any()
        }
        pageCount--
        allocationIdentity = Any()
        val cached = key(page) and cachedPages.lastIndex
        if (cachedPages[cached] === page) cachedPages[cached] = null
    }

    fun move(source: Int, target: Int): Long {
        if (source == target) return 1L
        var work = columns * 2L + if (objectColumns) 12L else 8L
        val from = checkNotNull(locate(source ushr PAGE_SHIFT) { work += it })
        val sourceSlot = source and PAGE_MASK
        check(from.retained and (1 shl sourceSlot) != 0)
        val targetSlot = target and PAGE_MASK
        val to = pageFor(target ushr PAGE_SHIFT, targetSlot + 1, { work += it }, { work += it })
        from.values.copyInto(to.values, targetSlot * columns, sourceSlot * columns, (sourceSlot + 1) * columns)
        to.admissions[targetSlot] = from.admissions[sourceSlot]
        if (objectColumns) {
            checkNotNull(to.propagators)[targetSlot] = checkNotNull(from.propagators)[sourceSlot]
            checkNotNull(to.payloads)[targetSlot] = checkNotNull(from.payloads)[sourceSlot]
        }
        to.retained = to.retained or (1 shl targetSlot)
        return work
    }

    fun truncateTo(count: Int): Long {
        require(count in 0..size)
        var work = 4L
        val retained = ((count.toLong() + PAGE_MASK) ushr PAGE_SHIFT).toInt()
        if (count == 0) primary?.let { discard(it); work += 8L }
        if (count > 0 && count and PAGE_MASK != 0) {
            val last = locate((count - 1) ushr PAGE_SHIFT) { work += it }
            if (last != null) {
                val required = count and PAGE_MASK
                last.retained = last.retained and ((1 shl required) - 1)
                last.pending = last.pending and ((1 shl required) - 1)
                if (last.retained == 0) { discard(last); work += 8L } else {
                    resize(last, required)
                    work += required * rowWork * 2L + 8L
                }
            }
        }
        directory.forEach({ work += it }) { index, page ->
            if (index >= retained) { discard(page); work += 8L }
        }
        var pending = retiredHead
        retiredHead = null
        retiredTail = null
        while (pending != null) {
            val next = pending.nextRetired
            pending.nextRetired = null
            if (pending.pending != 0) {
                if (retiredTail == null) retiredHead = pending else checkNotNull(retiredTail).nextRetired = pending
                retiredTail = pending
            }
            work += 8L
            pending = next
        }
        while (emptyHead != null) {
            val page = checkNotNull(emptyHead)
            work += unlinkingWork(page)
            unlinkEmpty(page)
        }
        size = count
        return work
    }

    fun prepareRemap(active: ReclamationPreparation) {
        val pages = ((size.toLong() + PAGE_MASK) ushr PAGE_SHIFT).coerceAtLeast(1L)
        val pageBytes = PAGE_SIZE * rowBytes + 12L
        if (objectColumns) active.atomic(size * 4L + (directory.size + 1L) * 2L,
            pages * (pageBytes + PAGE_SIZE * Long.SIZE_BYTES * 2L) * 4L + (directory.size + 1L) * Long.SIZE_BYTES)
        else active.atomic(size * (columns * 2L + 12L) + pages * pageBytes / Int.SIZE_BYTES * 2L +
            (directory.size + 1L) * 8L, pages * pageBytes * 4L + (directory.size + 1L) * Long.SIZE_BYTES)
    }

    fun sharingMemory(sample: SharingMemoryObservation, protectedRows: Boolean, protected: (Int) -> Boolean) =
        usingObservation(sample) { observeMemory(sample, protectedRows, protected) }

    private fun observeMemory(sample: SharingMemoryObservation, protectedRows: Boolean, protected: (Int) -> Boolean) {
        var protectedBytes = 0L
        var protectedReferences = 0L
        fun observe(index: Int, page: Page): Boolean {
            if (index != 0) {
                repeat(2) { sample.step() }
                cachedPages[index and cachedPages.lastIndex] = page
            }
            for (slot in 0 until page.capacity) {
                sample.step()
                if (page.retained and (1 shl slot) != 0 && protected((index shl PAGE_SHIFT) + slot)) {
                    protectedBytes += page.bytes + 12L
                    protectedReferences += page.references
                    return true
                }
            }
            return false
        }
        primary?.let { observe(0, it) }
        val retained = directory.protectedCapacity({ units -> repeat(units.toInt()) { sample.step() } }) {
            index, page -> observe(index, page)
        }
        sample.owned(this, 25L, protectedRows)
        sample.ownedReferences(this, 12L + cachedPages.size, protectedRows)
        sample.ownedPool(allocationIdentity, allocatedBytes + pageCount * 12L, protectedBytes)
        sample.ownedReferencesPool(allocationIdentity, pageCount * 7L +
            (if (objectColumns) allocatedBytes / rowBytes * 2L else 0L), protectedReferences)
        sample.ownedPool(directoryIdentity, directory.primitiveCapacityBytes,
            retained.first + if (protectedRows) 12L else 0L)
        sample.ownedReferencesPool(directoryIdentity, directory.referenceCapacity,
            retained.second + if (protectedRows) 1L else 0L)
    }

    private companion object {
        const val LBD = 0
        const val PERMANENT = 1
        const val TIER = 2
        const val STAGES = 4
        const val ORIGIN = 5
        const val GENERAL_CACHE_SIZE = 128
        const val NATIVE_CACHE_SIZE = 512
        const val INITIAL_CAPACITY = 8
        const val PAGE_SHIFT = 4
        const val PAGE_SIZE = 1 shl PAGE_SHIFT
        const val PAGE_MASK = PAGE_SIZE - 1
        fun capacityFor(required: Int): Int = 1 shl (Int.SIZE_BITS - (required - 1).countLeadingZeroBits())
    }
}
