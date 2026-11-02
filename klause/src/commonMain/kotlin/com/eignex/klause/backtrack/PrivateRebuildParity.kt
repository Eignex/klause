package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationSession

internal class PrivateRebuildParity private constructor(
    val source: BakedProblem,
    val fixed: Array<Boolean?>,
    val newPins: Int,
    val rows: Int,
    val modeledBytes: Long,
) {
    companion object {
        private const val MAX_FACTORS = 32_768
        private const val COLUMN_BITS = 21
        private const val COLUMN_MASK = (1 shl COLUMN_BITS) - 1
        private const val EVEN_MASK = 0x69
        private const val ODD_MASK = 0x96
        private const val EMPTY = -1L

        fun capture(
            session: PropagationSession,
            byteLimit: Long,
            visit: (Long) -> Boolean,
            onDecline: (String) -> Unit = {},
            onWorkspace: (Long) -> Unit = {},
        ): PrivateRebuildParity? {
            if (!session.canSimplifyRoot) return null
            val source = session.problem as? BakedProblem ?: return null
            if (source.numIntVars != 0 || source.numRealVars != 0 || source.numBoolVars > COLUMN_MASK ||
                source.numFactors > MAX_FACTORS || source.numBoolVars < 3) { onDecline("Scope"); return null }
            val funded: (Long) -> Boolean = { work ->
                if (visit(work)) true else { onDecline("Partial"); false }
            }
            var size = 2
            while (size < source.numFactors * 2) size *= 2
            val tableBytes = size * 16L + source.numBoolVars * 16L
            if (tableBytes > byteLimit) { onDecline("Memory"); return null }
            if (!funded(size * 3L)) return null
            onWorkspace(tableBytes)
            val table = Cubes(size, funded)
            if (!table.collect(source)) return null
            val rowCount = table.countRows() ?: return null
            if (rowCount == 0) { onDecline("NoRows"); return null }
            val words = (source.numBoolVars + 63) ushr 6
            val bytes = tableBytes + rowCount * (words * 16L + 128L) + source.numBoolVars * 16L
            if (table.entries * 512L + bytes > byteLimit) { onDecline("Memory"); return null }
            if (!funded(source.numBoolVars * 3L + rowCount * (words + 2L))) return null
            onWorkspace(bytes)
            val fixed = Array(source.numBoolVars) { session.boolValue(it) }
            val matrix = Matrix(rowCount, source.numBoolVars, words, fixed, funded)
            if (!table.populate(matrix)) return null
            val pins = matrix.derive(onDecline) ?: return null
            if (pins == 0) { onDecline("NoPins"); return null }
            if (!funded(8L)) return null
            return PrivateRebuildParity(source, fixed, pins, rowCount, bytes)
        }

        private fun complete(mask: Int, parity: Int): Boolean {
            val pattern = if (parity == 0) EVEN_MASK else ODD_MASK
            return mask and pattern == pattern
        }

        private class Cubes(size: Int, private val visit: (Long) -> Boolean) {
            private val keys = LongArray(size) { EMPTY }
            private val masks = IntArray(size)
            var entries = 0L
                private set

            fun collect(source: BakedProblem): Boolean {
                entries = source.numBoolVars.toLong() + source.numFactors + 1L
                for (factor in source.factors) {
                    if (!visit(2L)) return false
                    val clause = factor as? Clause ?: return false
                    val literals = clause.literals
                    entries += literals.size
                    if (literals.size != 3) continue
                    if (!visit(24L)) return false
                    var a = literals[0]
                    var b = literals[1]
                    var c = literals[2]
                    if (a > b) { val swap = a; a = b; b = swap }
                    if (b > c) { val swap = b; b = c; c = swap }
                    if (a > b) { val swap = a; a = b; b = swap }
                    val left = Lit.variable(a)
                    val middle = Lit.variable(b)
                    val right = Lit.variable(c)
                    if (left == middle || middle == right) continue
                    if (right >= source.numBoolVars) return false
                    val key = (left.toLong() shl (COLUMN_BITS * 2)) or
                        (middle.toLong() shl COLUMN_BITS) or right.toLong()
                    val forbidden = (if (Lit.isPositive(a)) 0 else 1) or
                        (if (Lit.isPositive(b)) 0 else 2) or (if (Lit.isPositive(c)) 0 else 4)
                    var slot = (((key xor (key ushr 33)) * -7046029254386353131L) ushr
                        (64 - keys.size.countTrailingZeroBits())).toInt()
                    while (true) {
                        if (!visit(1L)) return false
                        if (keys[slot] == EMPTY || keys[slot] == key) break
                        slot = (slot + 1) and (keys.size - 1)
                    }
                    if (!visit(2L)) return false
                    keys[slot] = key
                    masks[slot] = masks[slot] or (1 shl forbidden)
                }
                return true
            }

            fun countRows(): Int? {
                var count = 0
                for (slot in keys.indices) {
                    if (!visit(4L)) return null
                    if (keys[slot] == EMPTY) continue
                    for (parity in 0..1) if (complete(masks[slot], parity)) count++
                }
                return count
            }

            fun populate(matrix: Matrix): Boolean {
                for (slot in keys.indices) {
                    if (!visit(4L)) return false
                    if (keys[slot] == EMPTY) continue
                    for (parity in 0..1) if (complete(masks[slot], parity)) {
                        if (!matrix.add(keys[slot], parity xor 1)) return false
                    }
                }
                return true
            }
        }

        private class Matrix(
            rows: Int,
            private val columns: Int,
            private val words: Int,
            private val fixed: Array<Boolean?>,
            private val visit: (Long) -> Boolean,
        ) {
            private val masks = Array(rows) { LongArray(words) }
            private val rhs = IntArray(rows)
            private var added = 0

            fun add(key: Long, parity: Int): Boolean {
                if (!visit(13L)) return false
                val row = added++
                rhs[row] = parity
                return column(row, (key ushr (COLUMN_BITS * 2)).toInt()) &&
                    column(row, ((key ushr COLUMN_BITS) and COLUMN_MASK.toLong()).toInt()) &&
                    column(row, (key and COLUMN_MASK.toLong()).toInt())
            }

            private fun column(row: Int, column: Int): Boolean {
                if (!visit(3L)) return false
                val value = fixed[column]
                if (value == null) {
                    masks[row][column ushr 6] = masks[row][column ushr 6] or (1L shl (column and 63))
                } else if (value) rhs[row] = rhs[row] xor 1
                return true
            }

            fun derive(onDecline: (String) -> Unit): Int? {
                var pivot = 0
                for (column in 0 until columns) {
                    if (!visit(1L)) return null
                    var selected = pivot
                    while (selected < masks.size) {
                        if (!visit(2L)) return null
                        if (has(selected, column)) break
                        selected++
                    }
                    if (selected == masks.size) continue
                    if (!visit(6L)) return null
                    val row = masks[pivot]
                    masks[pivot] = masks[selected]
                    masks[selected] = row
                    val parity = rhs[pivot]
                    rhs[pivot] = rhs[selected]
                    rhs[selected] = parity
                    if (!eliminate(pivot, column)) return null
                    pivot++
                    if (pivot == masks.size) break
                }
                return pins(onDecline)
            }

            private fun eliminate(pivot: Int, column: Int): Boolean {
                for (row in masks.indices) {
                    if (!visit(2L)) return false
                    if (row == pivot || !has(row, column)) continue
                    for (word in 0 until words) {
                        if (!visit(3L)) return false
                        masks[row][word] = masks[row][word] xor masks[pivot][word]
                    }
                    if (!visit(1L)) return false
                    rhs[row] = rhs[row] xor rhs[pivot]
                }
                return true
            }

            private fun pins(onDecline: (String) -> Unit): Int? {
                var pins = 0
                for (row in masks.indices) {
                    if (!visit(2L)) return null
                    var count = 0
                    var column = -1
                    for (word in 0 until words) {
                        if (!visit(2L)) return null
                        val bits = masks[row][word]
                        count += bits.countOneBits()
                        if (bits != 0L) column = word * 64 + bits.countTrailingZeroBits()
                        if (count > 1) break
                    }
                    if (count == 0 && rhs[row] != 0) { onDecline("RefutationDeferred"); return null }
                    if (count != 1) continue
                    if (!visit(2L)) return null
                    val value = rhs[row] != 0
                    if (fixed[column] == null) { fixed[column] = value; pins++ }
                    else if (fixed[column] != value) { onDecline("RefutationDeferred"); return null }
                }
                return pins
            }

            private fun has(row: Int, column: Int): Boolean =
                masks[row][column ushr 6] and (1L shl (column and 63)) != 0L
        }
    }
}
