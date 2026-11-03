package com.eignex.klause.propagation

import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.MutableIntObjectIndex

internal class IntEventOverflow {
    private val entries = MutableIntObjectIndex<IntArrayList>()
    private var pending: MutableIntObjectIndex.Slot<IntArrayList>? = null
    private var event = 0L
    private var position = 0
    private var sweeping = -1
    private var swept = 0
    private var deferredWork = 0L
    private var deferredScratch = 0L

    val size: Int get() = entries.size
    val primitiveCapacityBytes: Long get() = 36L + entries.primitiveCapacityBytes + size * 4L
    val referenceCapacity: Long get() = 2L + entries.referenceCapacity + size
    val lookupWork: Long get() = entries.lastWork

    operator fun get(key: Int): IntArrayList? = entries[key]

    fun subscribe(key: Int, factor: Int): Long {
        val before = entries.size
        val posting = entries.getOrPut(key) { IntArrayList(1) }
        var work = entries.lastWork + if (entries.size > before) 4L else 0L
        val count = posting.size
        work += 2L + if (count == posting.backingData.size) count * 3L + 1L else 0L
        posting.add(factor)
        return work
    }

    fun forEach(visit: (Long) -> Unit = {}, action: (Int, IntArrayList) -> Unit) {
        entries.forEach(visit, action)
    }

    fun remap(remap: IntArray, base: Int): Pair<IntEventOverflow?, Long> {
        val replacement = IntEventOverflow()
        var work = 16L
        forEach({ work += it }) { key, posting ->
            var write = 0
            for (index in 0 until posting.size) {
                work += 3L
                val factor = posting[index]
                val next = if (factor < base) factor else remap.getOrNull(factor - base)?.let {
                    if (it < 0) null else base + it
                }
                if (next != null) { posting[write++] = next; work += 2L }
            }
            posting.truncateTo(write)
            work++
            if (posting.backingData.size > write.coerceAtLeast(1)) work += write.coerceAtLeast(1) * 2L + 1L
            posting.trim()
            if (write > 0) work += replacement.entries.put(key, posting)
        }
        return replacement.takeUnless { it.size == 0 } to work
    }

    fun sweep(retired: Int, budget: ReclamationPreparation, isRetired: (Int) -> Boolean) {
        if (sweeping < 0) {
            if (swept == retired && (deferredWork == 0L || !budget.supports(deferredWork, deferredScratch))) return
            budget.visit(8L)
            budget.visit(0L)
            sweeping = retired
            deferredWork = 0L
            deferredScratch = 0L
        }
        while (true) {
            val slot = pending ?: entries.firstAtOrAfter(event) { budget.visit(it) }?.also {
                budget.visit(2L)
                budget.visit(0L)
                pending = it
                budget.advanced()
            }
            if (slot == null) {
                budget.visit(4L)
                budget.visit(0L)
                swept = sweeping
                sweeping = -1
                event = 0L
                position = 0
                budget.advanced()
                return
            }
            val posting = slot.value
            if (position < posting.size) {
                budget.visit(4L)
                if (isRetired(posting[position])) {
                    budget.visit(8L)
                    budget.visit(0L)
                    posting.removeAt(position)
                } else position++
                budget.advanced()
            } else if (posting.size == 0) {
                check(entries.remove(slot, { budget.visit(it + 4L) }, { budget.visit(0L) }))
                advance(slot.key, budget)
            } else {
                finishPosting(slot.key, posting, budget)
            }
        }
    }

    private fun finishPosting(key: Int, posting: IntArrayList, budget: ReclamationPreparation) {
        val count = posting.size.toLong()
        if (posting.backingData.size > count) {
            val work = 7L + count * 2L
            val scratch = count * Int.SIZE_BYTES
            if (budget.supports(work, scratch)) {
                budget.visit(work, scratch)
                budget.visit(0L)
                posting.trim()
            } else {
                val record = deferredWork == 0L || work < deferredWork
                budget.visit(if (record) 8L else 4L)
                budget.visit(0L)
                if (record) { deferredWork = work; deferredScratch = scratch }
            }
        } else {
            budget.visit(4L)
            budget.visit(0L)
        }
        advance(key, budget)
    }

    private fun advance(key: Int, budget: ReclamationPreparation) {
        event = key.toLong() + 1L
        position = 0
        pending = null
        budget.advanced()
    }
}
