package com.eignex.klause.lp.lattice

import com.eignex.util.arrays.binarySearchInt
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BIG_ZERO
import com.eignex.util.numbers.BigInt
import com.eignex.util.numbers.isZero
import com.eignex.util.numbers.negate
import com.eignex.util.numbers.plus
import com.eignex.util.numbers.times
import com.eignex.util.numbers.unaryMinus

// Sparse because equality blocks hold a few terms per row over many columns. [index] is strictly ascending, [value]
// index-aligned, and no stored value is zero.
internal class SparseIntRow(val index: IntArray, val value: Array<BigInt>) {

    // [Int.MAX_VALUE] when the row is zero.
    val lead: Int get() = if (index.isEmpty()) Int.MAX_VALUE else index[0]

    // The pivot in a lower-triangular form; -1 when zero.
    val trail: Int get() = if (index.isEmpty()) -1 else index[index.size - 1]

    val isZero: Boolean get() = index.isEmpty()

    operator fun get(column: Int): BigInt {
        val position = index.binarySearchInt(column)
        return if (position >= 0) value[position] else BIG_ZERO
    }

    companion object {
        val Zero = SparseIntRow(IntArray(0), emptyArray())
    }
}

internal fun sparseIntRow(entries: Map<Int, BigInt>): SparseIntRow {
    val kept = entries.entries.filter { !it.value.isZero() }.sortedBy { it.key }
    if (kept.isEmpty()) return SparseIntRow.Zero
    return SparseIntRow(IntArray(kept.size) { kept[it].key }, Array(kept.size) { kept[it].value })
}

// `V` starts as the identity and is touched only by column operations, so column-major storage makes a swap a
// pointer exchange, and an untouched column stays null until first access so only moved columns cost anything.
internal class UnimodularTransform(val size: Int) {
    private val columns = arrayOfNulls<MutableMap<Int, BigInt>>(size)
    private val operations = ArrayList<UnimodularColumnOperation>()

    private fun column(j: Int): MutableMap<Int, BigInt> = columns[j] ?: HashMap<Int, BigInt>(2)
        .also {
            it[j] = BIG_ONE
            columns[j] = it
        }

    operator fun get(i: Int, j: Int): BigInt = column(j)[i] ?: BIG_ZERO

    fun swap(x: Int, y: Int) {
        if (x == y) return
        val cx = column(x)
        val cy = column(y)
        columns[x] = cy
        columns[y] = cx
        operations.add(UnimodularColumnOperation.Swap(x, y))
    }

    fun negate(j: Int) {
        val c = column(j)
        for (row in c.keys.toList()) c[row] = -c.getValue(row)
        operations.add(UnimodularColumnOperation.Negate(j))
    }

    fun addMultiple(target: Int, source: Int, factor: BigInt) {
        if (target == source || factor.isZero()) return
        val src = column(source)
        val dst = column(target)
        for ((row, v) in src) {
            val next = (dst[row] ?: BIG_ZERO) + factor * v
            if (next.isZero()) dst.remove(row) else dst[row] = next
        }
        operations.add(UnimodularColumnOperation.AddMultiple(target, source, factor))
    }

    fun inverse(): UnimodularTransform {
        val result = UnimodularTransform(size)
        for (operation in operations.asReversed()) {
            when (operation) {
                is UnimodularColumnOperation.Swap -> result.swap(operation.x, operation.y)

                is UnimodularColumnOperation.Negate -> result.negate(operation.column)

                is UnimodularColumnOperation.AddMultiple -> {
                    result.addMultiple(operation.target, operation.source, operation.factor.negate())
                }
            }
        }
        return result
    }

    // Does not materialise untouched identity columns.
    inline fun forEachEntry(action: (row: Int, col: Int, value: BigInt) -> Unit) {
        for (j in 0 until size) {
            val c = columnOrNull(j)
            if (c == null) action(j, j, BIG_ONE) else for ((i, v) in c) action(i, j, v)
        }
    }

    @PublishedApi
    internal fun columnOrNull(j: Int): Map<Int, BigInt>? = columns[j]

    val nonZeroCount: Int get() = (0 until size).sumOf { columns[it]?.size ?: 1 }
}

private sealed interface UnimodularColumnOperation {
    data class Swap(val x: Int, val y: Int) : UnimodularColumnOperation

    data class Negate(val column: Int) : UnimodularColumnOperation

    data class AddMultiple(val target: Int, val source: Int, val factor: BigInt) : UnimodularColumnOperation
}

// Column and row views of the same entries: the Hermite reduction searches rows and acts by column operations, and
// either alone would make the other a full scan per step.
internal class SparseIntColumns(val rows: Int, val cols: Int, source: List<SparseIntRow>) {
    private val columns = arrayOfNulls<MutableMap<Int, BigInt>>(cols)

    val rowSupport = Array(rows) { HashSet<Int>() }

    init {
        for (i in source.indices) {
            val row = source[i]
            for (k in row.index.indices) {
                val j = row.index[k]
                if (j >= cols) continue
                cell(j)[i] = row.value[k]
                rowSupport[i].add(j)
            }
        }
    }

    private fun cell(j: Int): MutableMap<Int, BigInt> = columns[j] ?: HashMap<Int, BigInt>().also { columns[j] = it }

    operator fun get(i: Int, j: Int): BigInt = columns[j]?.get(i) ?: BIG_ZERO

    fun swap(x: Int, y: Int) {
        if (x == y) return
        val cx = columns[x]
        val cy = columns[y]
        val kx = cx?.keys ?: emptySet()
        val ky = cy?.keys ?: emptySet()
        for (i in kx) {
            if (i !in ky) {
                rowSupport[i].remove(x)
                rowSupport[i].add(y)
            }
        }
        for (i in ky) {
            if (i !in kx) {
                rowSupport[i].remove(y)
                rowSupport[i].add(x)
            }
        }
        columns[x] = cy
        columns[y] = cx
    }

    fun negate(j: Int) {
        val c = columns[j] ?: return
        for (i in c.keys.toList()) c[i] = -c.getValue(i)
    }

    fun addMultiple(target: Int, source: Int, factor: BigInt) {
        if (target == source || factor.isZero()) return
        val src = columns[source] ?: return
        val dst = cell(target)
        for ((i, v) in src) {
            val next = (dst[i] ?: BIG_ZERO) + factor * v
            if (next.isZero()) {
                dst.remove(i)
                rowSupport[i].remove(target)
            } else {
                dst[i] = next
                rowSupport[i].add(target)
            }
        }
    }

    fun toRows(): List<SparseIntRow> = List(rows) { i ->
        val cs = rowSupport[i].toIntArray()
        cs.sort()
        SparseIntRow(cs, Array(cs.size) { this[i, cs[it]] })
    }

    val nonZeroCount: Int get() = (0 until cols).sumOf { columns[it]?.size ?: 0 }
}
