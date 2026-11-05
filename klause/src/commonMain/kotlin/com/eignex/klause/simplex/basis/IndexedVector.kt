package com.eignex.klause.simplex.basis

import com.eignex.klause.util.SparseSlices
import com.eignex.koblas.SparseMatrix
import com.eignex.koblas.koblas
import kotlin.math.abs
import kotlin.math.sqrt

// Smallest normal double; smaller square sums need scaling to retain precision.
private const val MIN_NORMAL = 2.2250738585072014e-308

// Dense values with unique sparse support. Explicit zeros stay stored until clear; arrays never escape.
internal class IndexedVector(val size: Int) {
    init {
        require(size >= 0)
    }

    private val values = DoubleArray(size)
    private val indices = IntArray(size)
    private val stored = IntArray(size)
    var count: Int = 0
        private set
    val density: Double get() = if (size == 0) 0.0 else count.toDouble() / size

    operator fun get(i: Int): Double = values[i]

    @Suppress("LongParameterList")
    fun dot(indices: IntArray, indexOffset: Int, source: DoubleArray, valueOffset: Int, count: Int): Double =
        koblas.dot(indices, indexOffset, source, valueOffset, count, values)

    fun nrm2(): Double {
        var squares = 0.0
        var k = 0
        if (count >= 64) {
            // Independent sums avoid serialising every indexed load behind one addition chain.
            var squares1 = 0.0
            var squares2 = 0.0
            var squares3 = 0.0
            while (k < count - 3) {
                val value = values[indices[k]]
                val value1 = values[indices[k + 1]]
                val value2 = values[indices[k + 2]]
                val value3 = values[indices[k + 3]]
                squares += value * value
                squares1 += value1 * value1
                squares2 += value2 * value2
                squares3 += value3 * value3
                k += 4
            }
            squares += squares1 + squares2 + squares3
        }
        while (k < count) {
            val value = values[indices[k]]
            squares += value * value
            k++
        }
        if (squares.isFinite() && squares >= MIN_NORMAL) return sqrt(squares)
        var maximum = 0.0
        for (i in 0 until count) {
            val magnitude = abs(values[indices[i]])
            if (magnitude > maximum) maximum = magnitude
        }
        if (maximum == 0.0 || maximum.isInfinite()) return sqrt(squares)
        var scaledSquares = 0.0
        for (i in 0 until count) {
            val scaled = values[indices[i]] / maximum
            scaledSquares += scaled * scaled
        }
        return maximum * sqrt(scaledSquares)
    }

    inline fun forEachStored(block: (Int, Double) -> Unit) {
        for (k in 0 until count) {
            val i = indices[k]
            block(i, values[i])
        }
    }

    fun store(i: Int, value: Double) {
        require(i in 0 until size)
        require(stored[i] == 0) { "position $i is already stored" }
        stored[i] = 1
        indices[count++] = i
        values[i] = value
    }

    fun clear() {
        SparseSlices.clearTouched(indices, 0, count, values, stored)
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

    // The input is canonical sparse support: indices are unique and values are nonzero.
    fun scatterStored(indices: IntArray, indexOffset: Int, source: DoubleArray, valueOffset: Int, count: Int) {
        clear()
        this.count = SparseSlices.scatterAxpy(
            1.0, indices, indexOffset, source, valueOffset, count,
            values, stored, 1, this.indices, 0, 0,
        )
    }

    fun scatter(dense: DoubleArray) {
        require(dense.size == size)
        clear()
        for (i in dense.indices) if (dense[i] != 0.0) store(i, dense[i])
    }

    fun gather(out: DoubleArray): DoubleArray {
        require(out.size == size)
        values.copyInto(out)
        return out
    }

    fun toDoubleArray(): DoubleArray = gather(DoubleArray(size))
}
