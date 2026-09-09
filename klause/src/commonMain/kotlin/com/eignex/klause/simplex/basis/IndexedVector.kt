package com.eignex.klause.simplex.basis

import com.eignex.koblas.SparseMatrix

// Dense values with unique sparse support. Explicit zeros stay stored until clear; arrays never escape.
internal class IndexedVector(val size: Int) {
    init {
        require(size >= 0)
    }

    private val values = DoubleArray(size)
    private val indices = IntArray(size)
    private val stored = BooleanArray(size)
    var count: Int = 0
        private set
    val density: Double get() = if (size == 0) 0.0 else count.toDouble() / size

    operator fun get(i: Int): Double = values[i]

    fun forEachStored(block: (Int, Double) -> Unit) {
        for (k in 0 until count) {
            val i = indices[k]
            block(i, values[i])
        }
    }

    fun store(i: Int, value: Double) {
        require(i in 0 until size)
        require(!stored[i]) { "position $i is already stored" }
        stored[i] = true
        indices[count++] = i
        values[i] = value
    }

    fun clear() {
        forEachStored { i, _ ->
            values[i] = 0.0
            stored[i] = false
        }
        count = 0
    }

    fun unit(i: Int) {
        require(i in 0 until size)
        clear()
        store(i, 1.0)
    }

    fun scatterColumn(matrix: SparseMatrix, column: Int) {
        require(matrix.rows == size && column in 0 until matrix.cols)
        clear()
        matrix.forEachInColumn(column) { i, value -> if (value != 0.0) store(i, value) }
    }

    fun scatter(dense: DoubleArray) {
        require(dense.size == size)
        clear()
        for (i in dense.indices) if (dense[i] != 0.0) store(i, dense[i])
    }

    fun gather(out: DoubleArray): DoubleArray {
        require(out.size == size)
        out.fill(0.0)
        forEachStored { i, value -> out[i] = value }
        return out
    }

    fun toDoubleArray(): DoubleArray = gather(DoubleArray(size))
}
