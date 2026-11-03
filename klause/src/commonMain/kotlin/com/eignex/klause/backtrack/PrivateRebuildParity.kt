package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.Xor
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.EmptyLongArray

internal class PrivateRebuildParity private constructor(
    val source: BakedProblem,
    val fixed: Array<Boolean?>,
    val newPins: Int,
    val rows: Int,
    val modeledBytes: Long,
    val basis: PrivateRebuildAffine?,
    val triangularBasis: PrivateRebuildParityRows?,
    private val sourceKeys: LongArray,
    private val sourceRhs: IntArray,
    val sourceRowLiterals: Long,
    val coveredSource: BooleanArray,
    val coveredClauses: Int,
) {
    val affine: PrivateRebuildAffine? get() = basis.takeIf { coveredClauses == source.numFactors }

    val sourceRowCount: Int get() = sourceKeys.size

    fun materializeSourceRows(cancellation: Cancellation): Array<Factor>? {
        cancellation.charge(sourceKeys.size * 2L + 8L)
        if (cancellation()) return null
        val result = ArrayList<Factor>(sourceKeys.size)
        for (row in sourceKeys.indices) {
            val key = sourceKeys[row]
            val width = if (key and COLUMN_MASK.toLong() == COLUMN_MASK.toLong()) 2 else 3
            cancellation.charge(width * width.toLong() + width * 16L + 16L)
            if (cancellation()) return null
            val literals = IntArray(width) { index ->
                val column = when (index) {
                    0 -> (key ushr (COLUMN_BITS * 2)).toInt()
                    1 -> ((key ushr COLUMN_BITS) and COLUMN_MASK.toLong()).toInt()
                    else -> (key and COLUMN_MASK.toLong()).toInt()
                }
                Lit.make(column, true)
            }
            result.add(Xor(literals, sourceRhs[row]))
        }
        return result.toTypedArray()
    }

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
            val tableBytes = size * 20L + source.numFactors * 8L + source.numBoolVars * 16L
            if (tableBytes > byteLimit) { onDecline("Memory"); return null }
            if (!funded(size * 4L + source.numFactors)) return null
            onWorkspace(tableBytes)
            val table = Cubes(size, source.numFactors, funded, onDecline)
            if (!table.collect(source, session)) return null
            val rowCount = table.countRows() ?: return null
            if (rowCount == 0) { onDecline("NoRows"); return null }
            val covered = table.coverage() ?: return null
            val sourceRows = if (table.coveredClauses == source.numFactors) 0 else rowCount
            val words = (source.numBoolVars + 63) ushr 6
            val rowWords = (rowCount + 63) ushr 6
            val indexBytes = source.numBoolVars * (rowWords * 8L + 8L)
            val bytes = tableBytes + rowCount * (words * 16L + 128L) + source.numBoolVars * 16L + indexBytes +
                sourceRows * 25L + if (sourceRows == 0) 0L else 64L
            if (table.entries * 512L + bytes > byteLimit) { onDecline("Memory"); return null }
            val initializationWork = source.numBoolVars * (rowWords + 4L) +
                rowCount * (words + 3L) + sourceRows * 3L
            if (!funded(initializationWork)) return null
            onWorkspace(bytes)
            val fixed = Array(source.numBoolVars) { session.boolValue(it) }
            val matrix = Matrix(rowCount, source.numBoolVars, words, fixed, sourceRows != 0, funded)
            val sourceKeys = if (sourceRows == 0) EmptyLongArray else LongArray(sourceRows)
            val sourceRhs = if (sourceRows == 0) EmptyIntArray else IntArray(sourceRows)
            if (!table.populate(matrix, sourceKeys, sourceRhs)) return null
            val pins = matrix.derive(onDecline) ?: return null
            if (pins == 0 && matrix.basis == null) { onDecline("NoPins"); return null }
            if (!funded(16L)) return null
            return PrivateRebuildParity(source, fixed, pins, rowCount, bytes, matrix.basis, matrix.triangularBasis,
                sourceKeys, sourceRhs, table.sourceRowLiterals, covered, table.coveredClauses)
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
            factors: Int,
            private val visit: (Long) -> Boolean,
            private val onDecline: (String) -> Unit,
        ) {
            private val keys = LongArray(size) { EMPTY }
            private val masks = IntArray(size)
            private val coveredMasks = IntArray(size)
            private val tags = IntArray(factors) { -1 }
            var entries = 0L
                private set

            var coveredClauses = 0
                private set

            var sourceRowLiterals = 0L
                private set

            fun collect(source: BakedProblem, session: PropagationSession): Boolean {
                entries = source.numBoolVars.toLong() + source.numFactors + 1L
                for (index in source.factors.indices) {
                    if (!visit(4L)) return false
                    val factor = source.factors[index]
                    if (factor !is Clause) { onDecline("Scope"); return false }
                    val literals = factor.literals
                    entries += literals.size
                    if (factor.tautological) { tags[index] = -2; continue }
                    if (literals.size == 1) {
                        if (!visit(5L)) return false
                        val literal = literals[0]
                        val variable = Lit.variable(literal)
                        if (variable >= source.numBoolVars) { onDecline("Scope"); return false }
                        if (session.boolValue(variable)?.let { Lit.evaluate(literal, it) } == true) tags[index] = -2
                        else onDecline("AffineCoverageUnfixedUnit")
                        continue
                    }
                    if (literals.size !in 2..3) {
                        if (!visit(literals.size + 1L)) return false
                        if (!factor.allLiteralsBool(source.numBoolVars)) { onDecline("Scope"); return false }
                        onDecline("AffineCoverageWidth${literals.size.coerceAtMost(8)}")
                        continue
                    }
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
                    if (left == middle || middle == right) { onDecline("AffineCoverageRepeatedColumn"); continue }
                    if (left >= source.numBoolVars || middle >= source.numBoolVars ||
                        (literals.size == 3 && right >= source.numBoolVars)) {
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
                    if (!visit(3L)) return false
                    keys[slot] = key
                    masks[slot] = masks[slot] or (1 shl forbidden)
                    tags[index] = (slot shl 3) or forbidden
                }
                return true
            }

            fun countRows(): Int? {
                var count = 0
                for (slot in keys.indices) {
                    if (!visit(12L)) return null
                    if (keys[slot] == EMPTY) continue
                    var covered = 0
                    for (parity in 0..1) if (complete(keys[slot], masks[slot], parity)) {
                        count++
                        covered = covered or pattern(keys[slot], parity)
                    }
                    coveredMasks[slot] = covered
                    if (masks[slot] and covered != masks[slot]) {
                        val binary = keys[slot] and COLUMN_MASK.toLong() == COLUMN_MASK.toLong()
                        val kind = if (binary) "Binary" else "Ternary"
                        onDecline("AffineCoverage${if (covered == 0) "Incomplete" else "Extra"}$kind")
                    }
                }
                return count
            }

            fun coverage(): BooleanArray? {
                if (!visit(tags.size * 4L + 8L)) return null
                val result = BooleanArray(tags.size)
                var count = 0
                for (index in tags.indices) {
                    val tag = tags[index]
                    val covered = tag == -2 || (tag >= 0 &&
                        (coveredMasks[tag ushr 3] and (1 shl (tag and 7))) != 0)
                    result[index] = covered
                    if (covered) count++
                }
                coveredClauses = count
                return result
            }

            fun populate(matrix: Matrix, sourceKeys: LongArray, sourceRhs: IntArray): Boolean {
                var row = 0
                for (slot in keys.indices) {
                    if (!visit(4L)) return false
                    if (keys[slot] == EMPTY) continue
                    for (parity in 0..1) if (complete(keys[slot], masks[slot], parity)) {
                        if (!matrix.add(keys[slot], parity xor 1)) return false
                        if (sourceKeys.isEmpty()) continue
                        if (!visit(4L)) return false
                        sourceKeys[row] = keys[slot]
                        sourceRhs[row++] = parity xor 1
                        sourceRowLiterals += if (keys[slot] and COLUMN_MASK.toLong() == COLUMN_MASK.toLong()) 2 else 3
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
            private val retainRows: Boolean,
            private val visit: (Long) -> Boolean,
        ) {
            private val masks = Array(rows) { LongArray(words) }
            private val rowWords = (rows + 63) ushr 6
            private val columnRows = Array(columns) { LongArray(rowWords) }
            private val rhs = IntArray(rows)
            private val pivotColumns = IntArray(rows)
            private var added = 0
            var basis: PrivateRebuildAffine? = null
                private set
            var triangularBasis: PrivateRebuildParityRows? = null
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
                if (!visit(5L)) return false
                val value = fixed[column]
                if (value == null) {
                    masks[row][column ushr 6] = masks[row][column ushr 6] or (1L shl (column and 63))
                    columnRows[column][row ushr 6] = columnRows[column][row ushr 6] or (1L shl (row and 63))
                } else if (value) rhs[row] = rhs[row] xor 1
                return true
            }

            fun derive(onDecline: (String) -> Unit): Int? {
                var pivot = 0
                for (column in columns - 1 downTo 0) {
                    if (!visit(1L)) return null
                    val selected = select(column, pivot) ?: return null
                    if (selected == masks.size) continue
                    if (!swapRows(pivot, selected)) return null
                    if (!visit(1L)) return null
                    pivotColumns[pivot] = column
                    if (!eliminate(pivot, column)) return null
                    pivot++
                    if (pivot == masks.size) break
                }
                return pins(pivot, onDecline)
            }

            private fun select(column: Int, first: Int): Int? {
                val candidates = columnRows[column]
                val firstWord = first ushr 6
                for (word in firstWord until rowWords) {
                    if (!visit(2L)) return null
                    val bits = if (word == firstWord) candidates[word] and (-1L shl (first and 63))
                    else candidates[word]
                    if (bits != 0L) return word * 64 + bits.countTrailingZeroBits()
                }
                return masks.size
            }

            private fun swapRows(first: Int, second: Int): Boolean {
                if (first == second) return true
                for (word in 0 until words) {
                    if (!visit(2L)) return false
                    var changed = masks[first][word] xor masks[second][word]
                    while (changed != 0L) {
                        if (!visit(6L)) return false
                        val column = word * 64 + changed.countTrailingZeroBits()
                        changed = changed and (changed - 1L)
                        val rows = columnRows[column]
                        rows[first ushr 6] = rows[first ushr 6] xor (1L shl (first and 63))
                        rows[second ushr 6] = rows[second ushr 6] xor (1L shl (second and 63))
                    }
                }
                if (!visit(6L)) return false
                val row = masks[first]
                masks[first] = masks[second]
                masks[second] = row
                val parity = rhs[first]
                rhs[first] = rhs[second]
                rhs[second] = parity
                return true
            }

            private fun eliminate(pivot: Int, column: Int): Boolean {
                val first = pivot + 1
                val firstWord = first ushr 6
                for (word in firstWord until rowWords) {
                    if (!visit(2L)) return false
                    var bits = columnRows[column][word]
                    if (word == firstWord) bits = bits and (-1L shl (first and 63))
                    while (bits != 0L) {
                        if (!visit(2L)) return false
                        val row = word * 64 + bits.countTrailingZeroBits()
                        bits = bits and (bits - 1L)
                        if (!subtract(row, pivot, column)) return false
                    }
                }
                return true
            }

            private fun subtract(row: Int, pivot: Int, column: Int): Boolean {
                for (word in 0..(column ushr 6)) {
                    if (!visit(3L)) return false
                    val changed = masks[pivot][word]
                    masks[row][word] = masks[row][word] xor changed
                    if (!toggleColumns(row, word, changed)) return false
                }
                if (!visit(1L)) return false
                rhs[row] = rhs[row] xor rhs[pivot]
                return true
            }

            private fun toggleColumns(row: Int, word: Int, changed: Long): Boolean {
                var bits = changed
                while (bits != 0L) {
                    if (!visit(4L)) return false
                    val column = word * 64 + bits.countTrailingZeroBits()
                    bits = bits and (bits - 1L)
                    val rows = columnRows[column]
                    rows[row ushr 6] = rows[row ushr 6] xor (1L shl (row and 63))
                }
                return true
            }

            private fun pins(rank: Int, onDecline: (String) -> Unit): Int? {
                for (row in rank until masks.size) {
                    if (!visit(2L)) return null
                    if (rhs[row] != 0) { onDecline("RefutationDeferred"); return null }
                }
                if (retainRows && rank > 0) {
                    if (!visit(rank * 4L + 16L)) return null
                    // Back substitution mutates the constants; retain the forward system separately.
                    triangularBasis = PrivateRebuildParityRows(Array(rank) { masks[it] }, rhs.copyOf(rank))
                }
                if (!visit(columns * 3L)) return null
                if (!visit(rank.toLong())) return null
                val coordinates = IntArray(columns) { -1 }
                for (row in 0 until rank) coordinates[pivotColumns[row]] = row
                var free = 0
                for (column in 0 until columns) {
                    if (coordinates[column] >= 0) continue
                    coordinates[column] = if (fixed[column] == null) -(++free) else Int.MIN_VALUE
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
                if (rank > 0) {
                    if (!visit(16L)) return null
                    basis = PrivateRebuildAffine(fixed, coordinates, pivotColumns, rhs, dependencies, rank, free)
                }
                return pins
            }

            private fun expand(
                row: Int,
                pivot: Int,
                coordinates: IntArray,
                dependencies: Array<LongArray>,
            ): Boolean {
                for (word in 0..(pivot ushr 6)) {
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
        }
    }
}
