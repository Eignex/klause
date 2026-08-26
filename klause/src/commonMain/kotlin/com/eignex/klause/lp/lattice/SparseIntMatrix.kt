package com.eignex.klause.lp.lattice

import com.ionspin.kotlin.bignum.integer.BigInteger

/**
 * One row of an exact integer matrix, held as its non-zero entries alone.
 *
 * The equality block of a real model is overwhelmingly empty — a few terms per row out of tens of
 * thousands of columns — so the dense form costs `rows · cols` big-integer slots for a matrix whose
 * content is linear in the number of terms. Every step of the echelon-Hermite chain reads rows through
 * this type instead, which is what lets the chain run at all on a model with six-figure column counts.
 *
 * [index] is strictly ascending and [value] is index-aligned; no stored value is zero.
 */
internal class SparseIntRow(val index: IntArray, val value: Array<BigInteger>) {

    /** The lowest column the row mentions, or [Int.MAX_VALUE] when the row is zero. */
    val lead: Int get() = if (index.isEmpty()) Int.MAX_VALUE else index[0]

    /** The highest column the row mentions — its pivot in a lower-triangular form — or -1 when zero. */
    val trail: Int get() = if (index.isEmpty()) -1 else index[index.size - 1]

    val isZero: Boolean get() = index.isEmpty()

    operator fun get(column: Int): BigInteger {
        var lo = 0
        var hi = index.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = index[mid]
            when {
                c < column -> lo = mid + 1
                c > column -> hi = mid - 1
                else -> return value[mid]
            }
        }
        return BigInteger.ZERO
    }

    companion object {
        val Zero = SparseIntRow(IntArray(0), emptyArray())
    }
}

/** The row over [entries] `column -> coefficient`, dropping the zeros and sorting what is left. */
internal fun sparseIntRow(entries: Map<Int, BigInteger>): SparseIntRow {
    val kept = entries.entries.filter { !it.value.isZero() }.sortedBy { it.key }
    if (kept.isEmpty()) return SparseIntRow.Zero
    return SparseIntRow(IntArray(kept.size) { kept[it].key }, Array(kept.size) { kept[it].value })
}

/**
 * The unimodular `V` of `x = V·y`, stored column-major and sparse.
 *
 * `V` starts as the identity and is only ever touched by *column* operations, so a column no operation
 * reached still holds its single identity entry. Storing it column-major therefore costs one entry per
 * untouched column rather than a full row, and a column swap is a pointer exchange rather than a sweep
 * down the whole matrix. An untouched column is left null and materialised on first access, so a
 * transform over a hundred thousand columns of which a few thousand move costs only what moved.
 */
internal class UnimodularTransform(val size: Int) {
    private val columns = arrayOfNulls<MutableMap<Int, BigInteger>>(size)

    private fun column(j: Int): MutableMap<Int, BigInteger> = columns[j] ?: HashMap<Int, BigInteger>(2)
        .also {
            it[j] = BigInteger.ONE
            columns[j] = it
        }

    /** Entry `V(i, j)`. */
    operator fun get(i: Int, j: Int): BigInteger = column(j)[i] ?: BigInteger.ZERO

    fun swap(x: Int, y: Int) {
        if (x == y) return
        val cx = column(x)
        val cy = column(y)
        columns[x] = cy
        columns[y] = cx
    }

    fun negate(j: Int) {
        val c = column(j)
        for (row in c.keys.toList()) c[row] = -c.getValue(row)
    }

    /** `column(target) += factor · column(source)`. */
    fun addMultiple(target: Int, source: Int, factor: BigInteger) {
        if (target == source || factor.isZero()) return
        val src = column(source)
        val dst = column(target)
        for ((row, v) in src) {
            val next = (dst[row] ?: BigInteger.ZERO) + factor * v
            if (next.isZero()) dst.remove(row) else dst[row] = next
        }
    }

    /** Visit every non-zero `V(row, col)` once, without materialising the untouched identity columns. */
    inline fun forEachEntry(action: (row: Int, col: Int, value: BigInteger) -> Unit) {
        for (j in 0 until size) {
            val c = columnOrNull(j)
            if (c == null) action(j, j, BigInteger.ONE) else for ((i, v) in c) action(i, j, v)
        }
    }

    /** The stored column, or null where it is still the untouched identity column. */
    @PublishedApi
    internal fun columnOrNull(j: Int): Map<Int, BigInteger>? = columns[j]

    /** Stored non-zeros, counting an untouched identity column as the one entry it stands for. */
    val nonZeroCount: Int get() = (0 until size).sumOf { columns[it]?.size ?: 1 }
}

/**
 * An exact integer matrix carrying both a column view and a row view of the same entries.
 *
 * The Hermite reduction needs both: it searches a *row* for its smallest non-zero entry, and it acts by
 * *column* operations. Keeping only one view would make the other a full scan of the matrix per step,
 * which on a six-figure column count is the whole cost. Both views are updated by every operation, so
 * each costs the entries it actually changes and nothing more.
 */
internal class SparseIntColumns(val rows: Int, val cols: Int, source: List<SparseIntRow>) {
    private val columns = arrayOfNulls<MutableMap<Int, BigInteger>>(cols)

    /** Per row, the columns it holds a non-zero in. */
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

    private fun cell(j: Int): MutableMap<Int, BigInteger> =
        columns[j] ?: HashMap<Int, BigInteger>().also { columns[j] = it }

    operator fun get(i: Int, j: Int): BigInteger = columns[j]?.get(i) ?: BigInteger.ZERO

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

    /** `column(target) += factor · column(source)`. */
    fun addMultiple(target: Int, source: Int, factor: BigInteger) {
        if (target == source || factor.isZero()) return
        val src = columns[source] ?: return
        val dst = cell(target)
        for ((i, v) in src) {
            val next = (dst[i] ?: BigInteger.ZERO) + factor * v
            if (next.isZero()) {
                dst.remove(i)
                rowSupport[i].remove(target)
            } else {
                dst[i] = next
                rowSupport[i].add(target)
            }
        }
    }

    /** The rows of the matrix as it now stands, each in ascending column order. */
    fun toRows(): List<SparseIntRow> = List(rows) { i ->
        val cs = rowSupport[i].toIntArray()
        cs.sort()
        SparseIntRow(cs, Array(cs.size) { this[i, cs[it]] })
    }

    val nonZeroCount: Int get() = (0 until cols).sumOf { columns[it]?.size ?: 0 }
}
