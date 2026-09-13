package com.eignex.klause.lp.bounding

import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.addExact
import com.eignex.klause.util.mulExact

internal class LpProofRows private constructor(
    val source: LpProofRowIndex?,
    val transformed: LpProofRowIndex?,
) {
    fun unchanged(cancellation: Cancellation): Boolean {
        if (cancellation()) return false
        // A callback between these comparisons could invalidate an already checked owner.
        return source?.unchanged() != false && transformed?.unchanged() != false
    }

    companion object {
        const val MAX_PRIMITIVE_BYTES: Long = 64L * 1024 * 1024

        fun create(
            source: LpModel,
            transformed: LpModel,
            cancellation: Cancellation,
            maxPrimitiveBytes: Long = MAX_PRIMITIVE_BYTES,
        ): LpProofRows {
            val sourceShape = proofRowShape(source, cancellation)
            val transformedShape = proofRowShape(transformed, cancellation)
            val bytes = addExact(sourceShape?.bytes ?: 0, transformedShape?.bytes ?: 0)
            if (bytes > maxPrimitiveBytes) return LpProofRows(null, null)
            return LpProofRows(
                sourceShape?.let { LpProofRowIndex.create(source, it, cancellation) },
                transformedShape?.let { LpProofRowIndex.create(transformed, it, cancellation) },
            )
        }
    }
}

internal class LpProofRowIndex private constructor(
    private val model: LpModel,
    private val pointers: IntArray,
    private val sourceRows: IntArray,
    private val sourceValues: LongArray,
    private val offsets: IntArray,
    private val columns: IntArray,
    private val values: LongArray,
) {
    fun coefficients(row: Int, cancellation: Cancellation): Map<Int, Long> {
        val result = HashMap<Int, Long>()
        for (entry in offsets[row] until offsets[row + 1]) {
            checkProofRows(cancellation)
            result[columns[entry]] = values[entry]
        }
        return result
    }

    fun forEachCoefficient(
        row: Int,
        cancellation: Cancellation,
        consume: (Int, Long) -> Boolean,
    ): Boolean {
        var entry = offsets[row]
        val end = offsets[row + 1]
        while (entry < end) {
            checkProofRows(cancellation)
            val column = columns[entry]
            var value = values[entry++]
            while (entry < end && columns[entry] == column) {
                checkProofRows(cancellation)
                value = values[entry++]
            }
            if (value != 0L && !consume(column, value)) return false
        }
        return true
    }

    fun unchanged(): Boolean {
        if (model.n != pointers.size - 1 || model.m != offsets.size - 1 ||
            model.csc.colPtr.size < pointers.size
        ) {
            return false
        }
        for (column in pointers.indices) if (model.csc.colPtr[column] != pointers[column]) return false
        val start = pointers[0]
        if (model.csc.rowIdx.size < pointers.last() || model.csc.colVal.size < pointers.last()) return false
        for (entry in sourceRows.indices) {
            if (model.csc.rowIdx[start + entry] != sourceRows[entry] ||
                model.csc.colVal[start + entry] != sourceValues[entry]
            ) {
                return false
            }
        }
        return true
    }

    companion object {
        fun create(model: LpModel, shape: LpProofRowShape, cancellation: Cancellation): LpProofRowIndex {
            val pointers = IntArray(model.n + 1)
            for (column in pointers.indices) {
                checkProofRows(cancellation)
                pointers[column] = model.csc.colPtr[column]
            }
            if (pointers[0] != shape.start || pointers.last() != shape.end) throw LpProofRowsInvalidated()
            for (column in 0 until model.n) {
                checkProofRows(cancellation)
                if (pointers[column] > pointers[column + 1] || pointers[column] < shape.start) {
                    throw LpProofRowsInvalidated()
                }
            }
            val size = shape.end - shape.start
            val sourceRows = IntArray(size)
            val sourceValues = LongArray(size)
            val offsets = IntArray(model.m + 1)
            for (entry in 0 until size) {
                checkProofRows(cancellation)
                val row = model.csc.rowIdx[shape.start + entry]
                if (row !in 0 until model.m) throw LpProofRowsInvalidated()
                sourceRows[entry] = row
                sourceValues[entry] = model.csc.colVal[shape.start + entry]
                offsets[row + 1]++
            }
            val cursors = IntArray(model.m)
            for (row in 0 until model.m) {
                checkProofRows(cancellation)
                offsets[row + 1] += offsets[row]
                cursors[row] = offsets[row]
            }
            val columns = IntArray(size)
            val values = LongArray(size)
            for (column in 0 until model.n) {
                checkProofRows(cancellation)
                for (entry in pointers[column] - shape.start until pointers[column + 1] - shape.start) {
                    checkProofRows(cancellation)
                    val target = cursors[sourceRows[entry]]++
                    columns[target] = column
                    values[target] = sourceValues[entry]
                }
            }
            return LpProofRowIndex(model, pointers, sourceRows, sourceValues, offsets, columns, values)
        }
    }
}

internal data class LpProofRowShape(val start: Int, val end: Int, val bytes: Long)

private fun proofRowShape(model: LpModel, cancellation: Cancellation): LpProofRowShape? {
    checkProofRows(cancellation)
    if (model.n < 0 || model.m < 0 || model.n >= model.csc.colPtr.size || model.m == Int.MAX_VALUE) return null
    val limit = minOf(model.csc.rowIdx.size, model.csc.colVal.size)
    val start = model.csc.colPtr[0]
    if (start !in 0..limit) return null
    var end = start
    for (column in 1..model.n) {
        checkProofRows(cancellation)
        val next = model.csc.colPtr[column]
        if (next !in end..limit) return null
        end = next
    }
    for (entry in start until end) {
        checkProofRows(cancellation)
        if (model.csc.rowIdx[entry] !in 0 until model.m) return null
    }
    val bytes = addExact(
        addExact(mulExact(24L, (end - start).toLong()), mulExact(4L, model.n.toLong() + 1)),
        addExact(mulExact(8L, model.m.toLong()), 4L),
    )
    return LpProofRowShape(start, end, bytes)
}

internal class LpProofRowsCancelled : RuntimeException()

internal class LpProofRowsInvalidated : RuntimeException()

private fun checkProofRows(cancellation: Cancellation) {
    if (cancellation()) throw LpProofRowsCancelled()
}
