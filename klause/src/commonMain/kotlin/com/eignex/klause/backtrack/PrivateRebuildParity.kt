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
    val affine: PrivateRebuildAffine?,
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
            val table = Cubes(size, funded, onDecline)
            if (!table.collect(source, session)) return null
            val rowCount = table.countRows() ?: return null
            if (rowCount == 0) { onDecline("NoRows"); return null }
            val words = (source.numBoolVars + 63) ushr 6
            val bytes = tableBytes + rowCount * (words * 16L + 128L) + source.numBoolVars * 16L
            if (table.entries * 512L + bytes > byteLimit) { onDecline("Memory"); return null }
            if (!funded(source.numBoolVars * 3L + rowCount * (words + 3L))) return null
            onWorkspace(bytes)
            val fixed = Array(source.numBoolVars) { session.boolValue(it) }
            val matrix = Matrix(rowCount, source.numBoolVars, words, fixed, funded)
            if (!table.populate(matrix)) return null
            val pins = matrix.derive(onDecline, table.completeSource) ?: return null
            if (pins == 0 && matrix.affine == null) { onDecline("NoPins"); return null }
            if (!funded(8L)) return null
            return PrivateRebuildParity(source, fixed, pins, rowCount, bytes, matrix.affine)
        }

        private fun pattern(key: Long, parity: Int): Int {
            val binary = key and COLUMN_MASK.toLong() == COLUMN_MASK.toLong()
            return if (binary) { if (parity == 0) 0x9 else 0x6 }
            else if (parity == 0) EVEN_MASK else ODD_MASK
        }

        private fun complete(key: Long, mask: Int, parity: Int): Boolean {
            val pattern = pattern(key, parity)
            return mask and pattern == pattern
        }

        private class Cubes(
            size: Int,
            private val visit: (Long) -> Boolean,
            private val onDecline: (String) -> Unit,
        ) {
            private val keys = LongArray(size) { EMPTY }
            private val masks = IntArray(size)
            var entries = 0L
                private set

            var completeSource = true
                private set

            fun collect(source: BakedProblem, session: PropagationSession): Boolean {
                entries = source.numBoolVars.toLong() + source.numFactors + 1L
                for (factor in source.factors) {
                    if (!visit(2L)) return false
                    if (factor !is Clause) { onDecline("Scope"); return false }
                    val literals = factor.literals
                    entries += literals.size
                    if (factor.tautological) continue
                    if (literals.size == 1) {
                        if (!visit(3L)) return false
                        val literal = literals[0]
                        val variable = Lit.variable(literal)
                        if (variable >= source.numBoolVars ||
                            session.boolValue(variable)?.let { Lit.evaluate(literal, it) } != true) {
                            completeSource = false
                        }
                        continue
                    }
                    if (literals.size !in 2..3) { completeSource = false; continue }
                    if (!visit(24L)) return false
                    var a = literals[0]
                    var b = literals[1]
                    var c = if (literals.size == 3) literals[2] else Int.MAX_VALUE
                    if (a > b) { val swap = a; a = b; b = swap }
                    if (b > c) { val swap = b; b = c; c = swap }
                    if (a > b) { val swap = a; a = b; b = swap }
                    val left = Lit.variable(a)
                    val middle = Lit.variable(b)
                    val right = if (literals.size == 3) Lit.variable(c) else COLUMN_MASK
                    if (left == middle || middle == right) { completeSource = false; continue }
                    if (middle >= source.numBoolVars || (literals.size == 3 && right >= source.numBoolVars)) {
                        onDecline("Scope")
                        return false
                    }
                    val key = (left.toLong() shl (COLUMN_BITS * 2)) or
                        (middle.toLong() shl COLUMN_BITS) or right.toLong()
                    val forbidden = (if (Lit.isPositive(a)) 0 else 1) or
                        (if (Lit.isPositive(b)) 0 else 2) or
                        (if (literals.size == 2 || Lit.isPositive(c)) 0 else 4)
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
                    if (!visit(8L)) return null
                    if (keys[slot] == EMPTY) continue
                    var covered = 0
                    for (parity in 0..1) if (complete(keys[slot], masks[slot], parity)) {
                        count++
                        covered = covered or pattern(keys[slot], parity)
                    }
                    if (masks[slot] and covered != masks[slot]) completeSource = false
                }
                return count
            }

            fun populate(matrix: Matrix): Boolean {
                for (slot in keys.indices) {
                    if (!visit(4L)) return false
                    if (keys[slot] == EMPTY) continue
                    for (parity in 0..1) if (complete(keys[slot], masks[slot], parity)) {
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
            private val pivotColumns = IntArray(rows)
            private var added = 0
            var affine: PrivateRebuildAffine? = null
                private set

            fun add(key: Long, parity: Int): Boolean {
                if (!visit(13L)) return false
                val row = added++
                rhs[row] = parity
                return column(row, (key ushr (COLUMN_BITS * 2)).toInt()) &&
                    column(row, ((key ushr COLUMN_BITS) and COLUMN_MASK.toLong()).toInt()) &&
                    (key and COLUMN_MASK.toLong() == COLUMN_MASK.toLong() ||
                        column(row, (key and COLUMN_MASK.toLong()).toInt()))
            }

            private fun column(row: Int, column: Int): Boolean {
                if (!visit(3L)) return false
                val value = fixed[column]
                if (value == null) {
                    masks[row][column ushr 6] = masks[row][column ushr 6] or (1L shl (column and 63))
                } else if (value) rhs[row] = rhs[row] xor 1
                return true
            }

            fun derive(onDecline: (String) -> Unit, completeSource: Boolean): Int? {
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
                    if (!visit(1L)) return null
                    pivotColumns[pivot] = column
                    if (!eliminate(pivot, column)) return null
                    pivot++
                    if (pivot == masks.size) break
                }
                return pins(pivot, onDecline, completeSource)
            }

            private fun eliminate(pivot: Int, column: Int): Boolean {
                for (row in pivot + 1 until masks.size) {
                    if (!visit(2L)) return false
                    if (!has(row, column)) continue
                    for (word in (column ushr 6) until words) {
                        if (!visit(3L)) return false
                        masks[row][word] = masks[row][word] xor masks[pivot][word]
                    }
                    if (!visit(1L)) return false
                    rhs[row] = rhs[row] xor rhs[pivot]
                }
                return true
            }

            private fun pins(rank: Int, onDecline: (String) -> Unit, completeSource: Boolean): Int? {
                for (row in rank until masks.size) {
                    if (!visit(2L)) return null
                    if (rhs[row] != 0) { onDecline("RefutationDeferred"); return null }
                }
                if (!visit(columns * 3L)) return null
                val coordinates = IntArray(columns)
                var free = 0
                var pivot = 0
                for (column in 0 until columns) {
                    coordinates[column] = if (pivot < rank && pivotColumns[pivot] == column) pivot++
                    else if (fixed[column] == null) -(++free) else Int.MIN_VALUE
                }
                val freeWords = (free + 63) ushr 6
                if (!visit(rank * (freeWords + 1L))) return null
                val dependencies = Array(rank) { LongArray(freeWords) }
                var pins = 0
                for (row in rank - 1 downTo 0) {
                    if (!visit(2L)) return null
                    val column = pivotColumns[row]
                    if (!expand(row, column, coordinates, dependencies)) return null
                    var forced = true
                    for (word in 0 until freeWords) {
                        if (!visit(2L)) return null
                        if (dependencies[row][word] != 0L) forced = false
                    }
                    if (!forced) continue
                    if (!visit(2L)) return null
                    fixed[column] = rhs[row] != 0
                    pins++
                }
                if (completeSource && rank > 0) {
                    if (!visit(16L)) return null
                    affine = PrivateRebuildAffine(fixed, coordinates, pivotColumns, rhs, dependencies, rank, free)
                }
                return pins
            }

            private fun expand(
                row: Int,
                pivot: Int,
                coordinates: IntArray,
                dependencies: Array<LongArray>,
            ): Boolean {
                for (word in (pivot ushr 6) until words) {
                    if (!visit(2L)) return false
                    var bits = masks[row][word]
                    if (word == (pivot ushr 6)) bits = bits and (1L shl (pivot and 63)).inv()
                    while (bits != 0L) {
                        if (!visit(5L)) return false
                        val column = word * 64 + bits.countTrailingZeroBits()
                        bits = bits and (bits - 1)
                        val coordinate = coordinates[column]
                        if (coordinate < 0) {
                            val free = -coordinate - 1
                            dependencies[row][free ushr 6] = dependencies[row][free ushr 6] xor
                                (1L shl (free and 63))
                        } else {
                            for (index in dependencies[row].indices) {
                                if (!visit(3L)) return false
                                dependencies[row][index] = dependencies[row][index] xor dependencies[coordinate][index]
                            }
                            if (!visit(1L)) return false
                            rhs[row] = rhs[row] xor rhs[coordinate]
                        }
                    }
                }
                return true
            }

            private fun has(row: Int, column: Int): Boolean =
                masks[row][column ushr 6] and (1L shl (column and 63)) != 0L
        }
    }
}
