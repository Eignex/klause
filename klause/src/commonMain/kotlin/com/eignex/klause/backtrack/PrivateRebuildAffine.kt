package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Xor
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.EmptyIntArray

internal class PrivateRebuildAffine(
    private val fixed: Array<Boolean?>,
    private val coordinates: IntArray,
    private val pivots: IntArray,
    private val rhs: IntArray,
    private val dependencies: Array<LongArray>,
    val rank: Int,
    val freeColumns: Int,
) {
    private val words = (freeColumns + 63) ushr 6
    val reconstructionWork: Long = coordinates.size * 24L + rank * (words * 8L + 8L)

    fun privateValue(column: Int): Boolean? = when {
        coordinates[column] == Int.MIN_VALUE -> fixed[column]
        coordinates[column] >= 0 -> false
        else -> null
    }

    fun reconstruct(sample: Sample): Sample {
        val free = LongArray(words)
        val point = BooleanArray(coordinates.size)
        for (column in coordinates.indices) {
            val coordinate = coordinates[column]
            if (coordinate == Int.MIN_VALUE) point[column] = checkNotNull(fixed[column])
            else if (coordinate < 0) {
                val index = -coordinate - 1
                val value = sample.boolValue(column)
                point[column] = value
                if (value) free[index ushr 6] = free[index ushr 6] or (1L shl (index and 63))
            }
        }
        for (row in 0 until rank) {
            var value = rhs[row]
            for (word in 0 until words) {
                val parity = (dependencies[row][word] and free[word]).countOneBits() and 1
                value = value xor parity
            }
            point[pivots[row]] = value != 0
        }
        return Sample(point, longArrayOf())
    }

    fun preferredFreeCoordinates(cancellation: Cancellation): IntArray? {
        if (coordinates.size * 2L > PROPAGATION_WORK_PER_NODE) return EmptyIntArray
        cancellation.charge(coordinates.size * 2L + freeColumns + 8L)
        if (cancellation()) return null
        val result = IntArray(freeColumns)
        for (column in coordinates.indices) {
            val coordinate = coordinates[column]
            if (coordinate < 0 && coordinate != Int.MIN_VALUE) result[-coordinate - 1] = column
        }
        return result
    }

    internal class RowPlan(
        private val forward: BooleanArray,
        literals: Long,
        triangularRows: Int,
    ) {
        var literals: Long = literals
            private set
        var triangularRows: Int = triangularRows
            private set

        fun usesTriangular(row: Int): Boolean = forward[row]

        fun preferExpanded(row: Int, extraLiterals: Int) {
            check(forward[row] && extraLiterals > 0)
            forward[row] = false
            literals += extraLiterals
            triangularRows--
        }
    }

    fun planRows(
        cancellation: Cancellation,
        triangular: PrivateRebuildParityRows?,
        onDecline: (String) -> Unit,
    ): RowPlan? {
        cancellation.charge(rank * 2L + 16L)
        if (cancellation()) return null
        val forward = BooleanArray(rank)
        var literals = 0L
        var triangularRows = 0
        for (row in 0 until rank) {
            cancellation.charge(words * 2L + 4L)
            if (cancellation()) return null
            val expandedWidth = 1 + dependencies[row].sumOf { it.countOneBits() }
            val forwardWidth = if (triangular == null) null else {
                triangular.rowWidth(row, cancellation) ?: return null
            }
            cancellation.charge(8L)
            if (cancellation()) return null
            val useForward = forwardWidth != null && forwardWidth <= 64 &&
                (expandedWidth > 64 || forwardWidth < expandedWidth)
            val width = if (useForward) checkNotNull(forwardWidth) else expandedWidth
            if (width > 64) { onDecline("ParityRowWidth"); return null }
            forward[row] = useForward
            if (useForward) triangularRows++
            literals += width
        }
        return RowPlan(forward, literals, triangularRows)
    }

    fun preferExpandedRows(
        cancellation: Cancellation,
        plan: RowPlan,
        triangular: PrivateRebuildParityRows?,
        extraLiterals: Long,
    ): Boolean {
        var remaining = extraLiterals
        if (remaining <= 0L) return true
        for (row in 0 until rank) {
            cancellation.charge(1L)
            if (cancellation()) return false
            if (!plan.usesTriangular(row)) continue
            cancellation.charge(words * 2L + 4L)
            if (cancellation()) return false
            val width = 1 + dependencies[row].sumOf { it.countOneBits() }
            if (width > 64) continue
            val forwardWidth = checkNotNull(triangular).rowWidth(row, cancellation) ?: return false
            val extra = width - forwardWidth
            cancellation.charge(8L)
            if (cancellation()) return false
            if (extra > remaining) continue
            plan.preferExpanded(row, extra)
            remaining -= extra
            if (remaining == 0L) return true
        }
        return true
    }

    fun materializeRows(
        cancellation: Cancellation,
        plan: RowPlan,
        triangular: PrivateRebuildParityRows?,
    ): Array<Factor>? {
        cancellation.charge(coordinates.size * 2L + rank + 8L)
        if (cancellation()) return null
        val free = IntArray(freeColumns)
        for (column in coordinates.indices) {
            val coordinate = coordinates[column]
            if (coordinate < 0 && coordinate != Int.MIN_VALUE) free[-coordinate - 1] = column
        }
        val result = ArrayList<Factor>(rank)
        for (row in 0 until rank) {
            cancellation.charge(2L)
            if (cancellation()) return null
            if (plan.usesTriangular(row)) {
                result.add(checkNotNull(triangular).materializeRow(row, cancellation) ?: return null)
                continue
            }
            cancellation.charge(words * 2L + 4L)
            if (cancellation()) return null
            val width = 1 + dependencies[row].sumOf { it.countOneBits() }
            if (width > 64) return null
            // The factor builds its distinct-coordinate set; the projection retains unit coefficients.
            cancellation.charge(width * width.toLong() + width * 16L + 16L)
            if (cancellation()) return null
            val literals = IntArray(width)
            literals[0] = Lit.make(pivots[row], true)
            var next = 1
            for (word in 0 until words) {
                var bits = dependencies[row][word]
                while (bits != 0L) {
                    val index = word * 64 + bits.countTrailingZeroBits()
                    literals[next++] = Lit.make(free[index], true)
                    bits = bits and (bits - 1)
                }
            }
            result.add(Xor(literals, rhs[row]))
        }
        return result.toTypedArray()
    }
}
