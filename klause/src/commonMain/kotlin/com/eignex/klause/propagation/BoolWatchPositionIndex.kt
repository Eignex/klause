package com.eignex.klause.propagation

import com.eignex.klause.util.MutableLongIntIndex
import com.eignex.util.collections.IntArrayList

internal class BoolWatchPositionIndex(private val baseLimit: Int = 0) {
    internal interface Positions {
        val firstKey: Int
        val firstValue: Int
        val secondKey: Int
        val secondValue: Int
    }
    private class Directory {
        val entries = arrayOfNulls<Any>(16)
        var live = 0
    }

    private class Row : Positions {
        var first = -1L
        var second = -1L
        override val firstKey: Int get() = (first ushr 32).toInt()
        override val firstValue: Int get() = first.toInt()
        override val secondKey: Int get() = (second ushr 32).toInt()
        override val secondValue: Int get() = second.toInt()
        var overflow: MutableLongIntIndex? = null
    }

    private var baseRows = emptyArray<Row?>()
    private var reservationRows = emptyArray<Row?>()
    private var reservationFirst = 0
    private var reservationLive = 0
    private var root: Directory? = null
    private var depth = 0
    private var directories = 0
    private var rows = 0
    private var overflowBytes = 0L
    private var overflowReferences = 0L
    private var reservationStart = 0L
    private var reservationEnd = 0L
    var size = 0
        private set
    var lastWork = 0L
        private set
    val primitiveCapacityBytes: Long get() = 68L + directories * 4L + rows * 16L + overflowBytes
    val referenceCapacity: Long get() =
        3L + baseRows.size + reservationRows.size + directories * 17L + rows + overflowReferences

    fun reserve(first: Int, count: Int): Long {
        require(first >= baseLimit && count >= 0 && reservationRows.isEmpty())
        lastWork = 2L
        reservationFirst = first
        reservationRows = arrayOfNulls(minOf(count.toLong(), Int.MAX_VALUE.toLong() + 1L - first).toInt())
        reservationLive = 0
        lastWork += reservationRows.size
        reservationStart = first.toLong()
        reservationEnd = minOf(first.toLong() + count, Int.MAX_VALUE.toLong() + 1L)
        for (factor in reservationStart until reservationEnd) ensureRow(factor.toInt())
        return lastWork
    }

    fun releaseReservations(budget: ReclamationPreparation) {
        while (reservationStart < reservationEnd) {
            repeat(depth + 2) { budget.visit() }
            budget.visit(0L)
            val row = findRow(reservationStart.toInt())
            if (row != null && row.firstKey < 0 && row.secondKey < 0 && row.overflow == null) {
                val bound = 16L + (depth + 1L) * 7L
                if (!budget.supports(bound + depth + 3L, 0L)) return
                repeat(bound.toInt()) { budget.visit() }
                budget.visit(0L)
                lastWork = 0L
                dropRow(reservationStart.toInt())
            } else {
                budget.visit()
                budget.visit(0L)
            }
            reservationStart++
            budget.advanced()
        }
    }

    fun getOrDefault(key: Long, default: Int): Int {
        lastWork = 0L
        if (key.toInt() < 0) return default
        val row = findRow((key ushr 32).toInt(), true) ?: return default
        return read(row, key.toInt(), default, true)
    }

    fun put(key: Long, value: Int): Long {
        require(value >= 0 && key.toInt() >= 0 && key ushr 32 <= Int.MAX_VALUE.toLong())
        lastWork = 0L
        val row = ensureRow((key ushr 32).toInt())
        val literal = key.toInt()
        lastWork += 4L
        when {
            row.firstKey == literal -> row.first = pack(literal, value)
            row.secondKey == literal -> row.second = pack(literal, value)
            row.firstKey < 0 -> { row.first = pack(literal, value); size++ }
            row.secondKey < 0 -> { row.second = pack(literal, value); size++ }
            else -> {
                val index = row.overflow ?: MutableLongIntIndex().also {
                    row.overflow = it
                    overflowBytes += it.primitiveCapacityBytes
                    overflowReferences += it.referenceCapacity
                    lastWork += 6L
                }
                val before = index.size
                val bytes = index.primitiveCapacityBytes
                val references = index.referenceCapacity
                lastWork += index.put(literal.toLong(), value)
                overflowBytes += index.primitiveCapacityBytes - bytes
                overflowReferences += index.referenceCapacity - references
                size += index.size - before
            }
        }
        return lastWork
    }

    fun putPair(factor: Int, firstLiteral: Int, firstPosition: Int, secondLiteral: Int, secondPosition: Int): Long {
        require(factor >= 0 && firstLiteral >= 0 && secondLiteral >= 0 && firstLiteral != secondLiteral)
        require(firstPosition >= 0 && secondPosition >= 0)
        lastWork = 0L
        val row = ensureRow(factor)
        check(row.firstKey < 0 && row.secondKey < 0 && row.overflow == null)
        row.first = pack(firstLiteral, firstPosition)
        row.second = pack(secondLiteral, secondPosition)
        size += 2
        lastWork += 3L
        return lastWork
    }

    fun updatePair(factor: Int, firstLiteral: Int, firstPosition: Int, secondLiteral: Int, secondPosition: Int): Long {
        lastWork = 0L
        val row = checkNotNull(findRow(factor, true))
        if (hasInlinePosition(row, firstLiteral) && hasInlinePosition(row, secondLiteral)) {
            update(row, firstLiteral, firstPosition)
            update(row, secondLiteral, secondPosition)
        } else {
            val lookup = lastWork
            val firstWork = put((factor.toLong() shl 32) or firstLiteral.toLong(), firstPosition)
            val secondWork = put((factor.toLong() shl 32) or secondLiteral.toLong(), secondPosition)
            lastWork = lookup + firstWork + secondWork
        }
        return lastWork
    }

    fun detachInline(factor: Int, pairExpected: Boolean, postings: Array<IntArrayList>): Positions? {
        lastWork = 0L
        val row = findRow(factor, true) ?: return null
        if (row.overflow != null || row.firstKey < 0 || (pairExpected && row.secondKey < 0)) return null
        fun valid(literal: Int, position: Int): Boolean = literal in postings.indices &&
            position in 0 until postings[literal].size && postings[literal][position] == factor
        if (!valid(row.firstKey, row.firstValue) ||
            (row.secondKey >= 0 && !valid(row.secondKey, row.secondValue))) return null
        size -= if (row.secondKey >= 0) 2 else 1
        lastWork++
        dropRow(factor)
        return row
    }

    fun remove(key: Long): Boolean {
        val before = size
        removeAndGet(key, -1)
        return size < before
    }

    fun removeAndGet(key: Long, default: Int): Int {
        lastWork = 0L
        val factor = (key ushr 32).toInt()
        if (key.toInt() < 0) return default
        val row = findRow(factor, true) ?: return default
        return remove(factor, row, key.toInt(), default)
    }

    fun removeAndUpdate(
        key: Long,
        updateKey: Long?,
        updateValue: Int,
        fund: (Long) -> Unit,
        ready: () -> Unit,
    ): Boolean {
        require(key != updateKey && updateValue >= 0)
        val removed = fundedRow(key, fund)
        val updated = updateKey?.let { fundedRow(it, fund) ?: return false }
        val removedOverflow = removed?.let { row -> row.overflow?.takeIf { !hasInlinePosition(row, key.toInt()) } }
        val updatedOverflow = updated?.let { row ->
            row.overflow?.takeIf { !hasInlinePosition(row, updateKey!!.toInt()) }
        }
        fund(16L + (depth + 1L) * 7L +
            (removedOverflow?.let { it.readWork + it.mutationWork } ?: 0L) +
            (updatedOverflow?.mutationWork ?: 0L))
        ready()
        lastWork = 0L
        if (updated != null) update(updated, updateKey!!.toInt(), updateValue)
        if (removed != null) remove((key ushr 32).toInt(), removed, key.toInt(), -1)
        return true
    }

    private fun pack(literal: Int, position: Int): Long =
        (literal.toLong() shl 32) or (position.toLong() and 0xFFFFFFFFL)

    private fun fundedRow(key: Long, fund: (Long) -> Unit): Row? {
        fund(depth + 2L)
        if (key.toInt() < 0) return null
        val row = findRow((key ushr 32).toInt()) ?: return null
        fund(2L + (row.overflow?.readWork ?: 0L))
        return row.takeIf { read(it, key.toInt(), -1) >= 0 }
    }

    private fun hasInlinePosition(row: Row, literal: Int): Boolean = row.firstKey == literal || row.secondKey == literal

    private fun read(row: Row, literal: Int, default: Int, record: Boolean = false): Int {
        if (record) lastWork += 2L
        return when (literal) {
            row.firstKey -> row.firstValue
            row.secondKey -> row.secondValue
            else -> {
                val index = row.overflow ?: return default
                if (record) lastWork += index.readWork
                index.getOrDefault(literal.toLong(), default)
            }
        }
    }

    private fun update(row: Row, literal: Int, value: Int) {
        lastWork++
        when (literal) {
            row.firstKey -> row.first = pack(literal, value)
            row.secondKey -> row.second = pack(literal, value)
            else -> lastWork += checkNotNull(row.overflow).put(literal.toLong(), value)
        }
    }

    private fun remove(factor: Int, row: Row, literal: Int, default: Int): Int {
        lastWork += 2L
        val previous = when (literal) {
            row.firstKey -> row.firstValue.also { row.first = -1L }
            row.secondKey -> row.secondValue.also { row.second = -1L }
            else -> {
                val index = row.overflow ?: return default
                val bytes = index.primitiveCapacityBytes
                val references = index.referenceCapacity
                val removed = index.removeAndGet(literal.toLong(), -1)
                lastWork += index.lastMutationWork
                if (removed < 0) return default
                overflowBytes += index.primitiveCapacityBytes - bytes
                overflowReferences += index.referenceCapacity - references
                if (index.size == 0) {
                    overflowBytes -= index.primitiveCapacityBytes
                    overflowReferences -= index.referenceCapacity
                    row.overflow = null
                }
                removed
            }
        }
        size--
        if (row.firstKey < 0 && row.secondKey < 0 && row.overflow == null) {
            dropRow(factor)
        }
        return previous
    }

    private fun findRow(factor: Int, record: Boolean = false): Row? {
        if (factor in 0 until baseLimit) {
            if (record) lastWork++
            return baseRows.getOrNull(factor)
        }
        val reserved = factor.toLong() - reservationFirst
        if (reserved >= 0 && reserved < reservationRows.size) {
            if (record) lastWork++
            return reservationRows[reserved.toInt()]
        }
        if (factor < 0 || factor.toLong() ushr ((depth + 1) * 4) != 0L) return null
        var node = root ?: return null
        for (level in depth downTo 1) {
            if (record) lastWork++
            node = node.entries[(factor ushr (level * 4)) and 15] as? Directory ?: return null
        }
        if (record) lastWork++
        return node.entries[factor and 15] as? Row
    }

    private fun ensureRow(factor: Int): Row {
        if (factor < baseLimit) {
            if (baseRows.isEmpty()) { baseRows = arrayOfNulls(baseLimit); lastWork += baseLimit + 1L }
            lastWork++
            return baseRows[factor] ?: Row().also { baseRows[factor] = it; rows++; lastWork += 8L }
        }
        val reserved = factor.toLong() - reservationFirst
        if (reserved >= 0 && reserved < reservationRows.size) {
            lastWork++
            return reservationRows[reserved.toInt()] ?: Row().also {
                reservationRows[reserved.toInt()] = it
                rows++
                reservationLive++
                lastWork += 8L
            }
        }
        if (root == null) {
            depth = 0
            while (factor.toLong() ushr ((depth + 1) * 4) != 0L) depth++
            root = Directory()
            directories++
            lastWork += 18L
        }
        while (factor.toLong() ushr ((depth + 1) * 4) != 0L) {
            val next = Directory()
            next.entries[0] = root
            next.live = 1
            root = next
            depth++
            directories++
            lastWork += 20L
        }
        var node = checkNotNull(root)
        for (level in depth downTo 1) {
            lastWork++
            val slot = (factor ushr (level * 4)) and 15
            var child = node.entries[slot] as? Directory
            if (child == null) {
                child = Directory()
                node.entries[slot] = child
                node.live++
                directories++
                lastWork += 20L
            }
            node = child
        }
        lastWork++
        val slot = factor and 15
        return node.entries[slot] as? Row ?: Row().also {
            node.entries[slot] = it
            node.live++
            rows++
            lastWork += 8L
        }
    }

    private fun dropRow(factor: Int) {
        if (factor < baseLimit) { baseRows[factor] = null; rows--; lastWork += 2L; return }
        val reserved = factor.toLong() - reservationFirst
        if (reserved >= 0 && reserved < reservationRows.size) {
            reservationRows[reserved.toInt()] = null
            rows--
            reservationLive--
            lastWork += 3L
            if (reservationLive == 0) { reservationRows = emptyArray(); lastWork++ }
            return
        }
        removeRow(checkNotNull(root), factor, depth)
        while (depth > 0 && root?.live == 1 && root?.entries?.get(0) is Directory) {
            root = root?.entries?.get(0) as Directory
            depth--
            directories--
            lastWork += 3L
        }
        if (root?.live == 0) { root = null; depth = 0; directories--; lastWork += 3L }
    }

    private fun removeRow(node: Directory, factor: Int, level: Int) {
        lastWork++
        val slot = (factor ushr (level * 4)) and 15
        if (level == 0) {
            node.entries[slot] = null
            node.live--
            rows--
            lastWork += 3L
        } else {
            val child = checkNotNull(node.entries[slot] as? Directory)
            removeRow(child, factor, level - 1)
            if (child.live == 0) {
                node.entries[slot] = null
                node.live--
                directories--
                lastWork += 3L
            }
        }
    }
}
