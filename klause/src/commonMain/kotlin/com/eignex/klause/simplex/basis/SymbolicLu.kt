package com.eignex.klause.simplex.basis

// Pivot coordinates satisfy B(rowOrder(i), columnOrder(j)) = (L U)(i, j).
// Columns name slots in the ordered basis, not columns of the fixed source matrix.
internal class SymbolicLu(val rowOrder: IntArray, val columnOrder: IntArray) {
    val rowPosition = inverse(rowOrder)
    val columnPosition = inverse(columnOrder)

    init {
        require(rowOrder.size == columnOrder.size)
    }

    private fun inverse(order: IntArray): IntArray {
        val position = IntArray(order.size) { -1 }
        for (i in order.indices) {
            require(order[i] in order.indices && position[order[i]] == -1)
            position[order[i]] = i
        }
        return position
    }
}

// Intrusive count buckets keep singleton discovery independent of the basis dimension.
internal class LuCountBuckets(dimension: Int) {
    private val first = IntArray(dimension + 1) { -1 }
    private val next = IntArray(dimension) { -1 }
    private val previous = IntArray(dimension) { -1 }
    private val count = IntArray(dimension) { -1 }

    fun first(size: Int): Int = first[size]

    fun next(index: Int): Int = next[index]

    fun move(index: Int, size: Int) {
        if (count[index] == size) return
        remove(index)
        count[index] = size
        val head = first[size]
        next[index] = head
        if (head >= 0) previous[head] = index
        first[size] = index
    }

    fun remove(index: Int) {
        val size = count[index]
        if (size < 0) return
        val before = previous[index]
        val after = next[index]
        if (before < 0) first[size] = after else next[before] = after
        if (after >= 0) previous[after] = before
        previous[index] = -1
        next[index] = -1
        count[index] = -1
    }
}
