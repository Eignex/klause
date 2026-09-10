package com.eignex.klause.simplex.basis

import com.eignex.koblas.SparseMatrix

internal class BasisExtension(
    columns: IntArray,
    unitRows: IntArray,
    oldRowsInNew: IntArray,
    oldColumnsInNew: IntArray,
) {
    private val acceptedBasis = BasisRepair(columns, unitRows)
    private val rows = oldRowsInNew.copyOf()
    private val sourceColumns = oldColumnsInNew.copyOf()
    val basis: BasisRepair get() = BasisRepair(acceptedBasis.columns, acceptedBasis.unitRows)
    val oldRowsInNew: IntArray get() = rows.copyOf()
    val oldColumnsInNew: IntArray get() = sourceColumns.copyOf()

    init {
        require(columns.size == oldRowsInNew.size)
    }
}

internal class BasisExtensionResult(val solver: BasisSolver, columns: IntArray, unitRows: IntArray) {
    val basis = BasisRepair(columns, unitRows)
}

internal data class BasisExtensionState(
    val rows: IntArray,
    val columns: IntArray,
    val newRows: IntArray,
    val oldBasisColumns: IntArray,
    val oldBasisUnitRows: IntArray,
    val basisColumns: IntArray,
    val basisUnitRows: IntArray,
    val crossByOldColumn: Array<BasisSlice>,
    val validationEntries: Long,
)

internal fun verifyBasisExtension(
    oldSource: SparseMatrix,
    newSource: SparseMatrix,
    acceptedColumns: IntArray,
    acceptedUnitRows: IntArray,
    request: BasisExtension,
): BasisExtensionState? {
    val oldRows = request.oldRowsInNew
    val oldColumns = request.oldColumnsInNew
    val requestedBasis = request.basis
    require(oldRows.size == oldSource.rows)
    require(oldColumns.size == oldSource.cols)
    require(requestedBasis.columns.size == oldSource.rows)
    require(newSource.rows >= oldSource.rows)
    require(newSource.cols >= oldSource.cols)
    require(oldRows.isInjectionInto(newSource.rows))
    require(oldColumns.isInjectionInto(newSource.cols))
    require(requestedBasis.columns.all { it == -1 || it in 0 until oldSource.cols })
    if (!requestedBasis.columns.contentEquals(acceptedColumns)) return null
    if (!requestedBasis.unitRows.contentEquals(acceptedUnitRows)) return null

    val oldRowAtNew = IntArray(newSource.rows) { -1 }
    for (oldRow in oldRows.indices) oldRowAtNew[oldRows[oldRow]] = oldRow
    val extensionRows = IntArray(newSource.rows - oldSource.rows)
    var next = 0
    for (row in 0 until newSource.rows) {
        if (oldRowAtNew[row] < 0) extensionRows[next++] = row
    }
    val extensionPosition = IntArray(newSource.rows) { -1 }
    for (position in extensionRows.indices) extensionPosition[extensionRows[position]] = position
    var validationEntries = 0L
    val expected = BooleanArray(oldSource.rows)
    val expectedBits = LongArray(oldSource.rows)
    val crossByOldColumn = Array(oldSource.cols) { BasisSlice(IntArray(0), DoubleArray(0)) }
    for (oldColumn in 0 until oldSource.cols) {
        val crossRows = IntArray(extensionRows.size)
        val crossValues = DoubleArray(extensionRows.size)
        var crossCount = 0
        oldSource.forEachInColumn(oldColumn) { row, value ->
            expected[row] = true
            expectedBits[row] = value.toRawBits()
            validationEntries = saturatedAdd(validationEntries, 1)
        }
        var compatible = true
        newSource.forEachInColumn(oldColumns[oldColumn]) { newRow, value ->
            validationEntries = saturatedAdd(validationEntries, 1)
            val oldRow = oldRowAtNew[newRow]
            if (oldRow >= 0) {
                if (!expected[oldRow] || expectedBits[oldRow] != value.toRawBits()) compatible = false
                expected[oldRow] = false
            } else if (value != 0.0) {
                crossRows[crossCount] = extensionPosition[newRow]
                crossValues[crossCount++] = value
            }
        }
        oldSource.forEachInColumn(oldColumn) { row, _ ->
            validationEntries = saturatedAdd(validationEntries, 1)
            if (expected[row]) compatible = false
            expected[row] = false
        }
        if (!compatible) return null
        crossByOldColumn[oldColumn] = BasisSlice(
            crossRows.copyOf(crossCount),
            crossValues.copyOf(crossCount),
        )
    }

    val columns = IntArray(newSource.rows) { -1 }
    val unitRows = IntArray(newSource.rows) { -1 }
    for (slot in acceptedColumns.indices) {
        if (acceptedColumns[slot] >= 0) {
            columns[slot] = oldColumns[acceptedColumns[slot]]
        } else {
            unitRows[slot] = oldRows[acceptedUnitRows[slot]]
        }
    }
    for (offset in extensionRows.indices) unitRows[oldSource.rows + offset] = extensionRows[offset]
    return BasisExtensionState(
        oldRows,
        oldColumns,
        extensionRows,
        acceptedColumns.copyOf(),
        acceptedUnitRows.copyOf(),
        columns,
        unitRows,
        crossByOldColumn,
        validationEntries,
    )
}

internal fun buildExtendedCache(
    old: BasisSolveCache,
    oldSource: SparseMatrix,
    extension: BasisExtensionState,
    threshold: Double,
): Pair<BasisSolveCache, Long> {
    val offset = extension.newRows.size
    val oldFactors = old.factors
    val oldFt = old.ft
    val symbolic = SymbolicLu(
        extension.newRows + IntArray(oldFactors.symbolic.rowOrder.size) {
            extension.rows[oldFactors.symbolic.rowOrder[it]]
        },
        IntArray(offset) { oldSource.rows + it } + IntArray(oldFactors.symbolic.columnOrder.size) {
            oldFactors.symbolic.columnOrder[it]
        },
    )
    val initialColumns = mappedHeadings(oldFactors.basisColumns, extension.columns, oldSource.rows, offset)
    val initialUnits = mappedUnits(oldFactors.basisUnitRows, extension.rows, oldSource.rows, extension.newRows)
    val initialCross = crossBlock(
        extension.crossByOldColumn,
        oldFactors.basisColumns,
        oldFactors.basisUnitRows,
        oldFactors.symbolic.columnOrder,
    )
    val currentCross = crossBlock(
        extension.crossByOldColumn,
        extension.oldBasisColumns,
        extension.oldBasisUnitRows,
        oldFactors.symbolic.columnOrder,
    )
    val lower = shiftedSquare(oldFactors.lower, offset)
    val lowerTranspose = shiftedSquare(oldFactors.lowerTranspose, offset)
    val upper = extendedUpper(oldFactors.upper, initialCross, offset)
    val upperTranspose = transpose(upper)
    val factors = LuFactors(initialColumns, initialUnits, symbolic, lower, upper, lowerTranspose, upperTranspose)
    val currentUpper = extendedSlices(oldFt.upper.columns, currentCross, offset)
    val currentTranspose = transposeSlices(currentUpper)
    val currentEntries = currentUpper.sumOf { it.count }
    val initialEntries = upper.nnz
    val ft = oldFt.extensionState(
        currentUpper,
        currentTranspose,
        initialEntries,
        currentEntries,
        offset,
    )
    val crossEntries = extension.crossByOldColumn.sumOf { it.count.toLong() }
    val copiedEntries = saturatedAdd(
        factors.lower.nnz.toLong() * 2 + factors.lowerTranspose.nnz.toLong() * 2 + oldFactors.upper.nnz,
        saturatedAdd(
            initialEntries.toLong() * 5,
            saturatedAdd(currentEntries.toLong() * 2, oldFt.transformEntries.toLong()),
        ),
    )
    val units = saturatedAdd(
        extension.validationEntries,
        saturatedAdd(crossEntries * 2, copiedEntries),
    )
    return BasisSolveCache.transfer(factors, ft, threshold) to units
}

private fun mappedHeadings(old: IntArray, columnMap: IntArray, oldDimension: Int, extension: Int): IntArray =
    IntArray(oldDimension + extension) { slot ->
        if (slot < oldDimension && old[slot] >= 0) columnMap[old[slot]] else -1
    }

private fun mappedUnits(old: IntArray, rowMap: IntArray, oldDimension: Int, extensionRows: IntArray): IntArray =
    IntArray(oldDimension + extensionRows.size) { slot ->
        when {
            slot >= oldDimension -> extensionRows[slot - oldDimension]
            old[slot] >= 0 -> rowMap[old[slot]]
            else -> -1
        }
    }

private fun crossBlock(
    crossByOldColumn: Array<BasisSlice>,
    columns: IntArray,
    unitRows: IntArray,
    columnOrder: IntArray,
): Array<BasisSlice> = Array(columnOrder.size) { orderedColumn ->
    val slot = columnOrder[orderedColumn]
    if (unitRows[slot] >= 0) {
        BasisSlice(IntArray(0), DoubleArray(0))
    } else {
        crossByOldColumn[columns[slot]].also { cross ->
            for (k in 0 until cross.count) basisFinite(cross.values[cross.offset + k])
        }
    }
}

private fun shiftedSquare(matrix: SparseMatrix, offset: Int): SparseMatrix {
    val oldPointers = matrix.copyColumnPointers()
    val pointers = IntArray(matrix.cols + offset + 1)
    for (column in 0..matrix.cols) pointers[column + offset] = oldPointers[column]
    val oldRows = matrix.copyRowIndices()
    val rows = IntArray(matrix.nnz) { oldRows[it] + offset }
    val values = matrix.values.copyOf()
    return SparseMatrix.wrap(matrix.rows + offset, matrix.cols + offset, pointers, rows, values)
}

private fun extendedUpper(old: SparseMatrix, cross: Array<BasisSlice>, offset: Int): SparseMatrix {
    val oldColumns = slices(old)
    val slices = Array(old.cols + offset) { column ->
        if (column < offset) {
            BasisSlice(intArrayOf(column), doubleArrayOf(1.0))
        } else {
            val original = oldColumns[column - offset]
            combine(cross[column - offset], original, offset)
        }
    }
    return matrix(slices)
}

private fun extendedSlices(old: Array<BasisSlice>, cross: Array<BasisSlice>, offset: Int): Array<BasisSlice> =
    Array(old.size + offset) { column ->
        if (column < offset) {
            BasisSlice(intArrayOf(column), doubleArrayOf(1.0))
        } else {
            combine(cross[column - offset], old[column - offset], offset)
        }
    }

private fun combine(top: BasisSlice, bottom: BasisSlice, offset: Int): BasisSlice {
    val indices = IntArray(top.count + bottom.count)
    val values = DoubleArray(indices.size)
    for (k in 0 until top.count) {
        indices[k] = top.indices[top.offset + k]
        values[k] = top.values[top.offset + k]
    }
    for (k in 0 until bottom.count) {
        indices[top.count + k] = bottom.indices[bottom.offset + k] + offset
        values[top.count + k] = bottom.values[bottom.offset + k]
    }
    return BasisSlice(indices, values)
}

private fun slices(matrix: SparseMatrix): Array<BasisSlice> {
    val pointers = matrix.copyColumnPointers()
    val rows = matrix.copyRowIndices()
    val values = matrix.values.copyOf()
    return Array(matrix.cols) { column ->
        BasisSlice(rows, values, pointers[column], pointers[column + 1] - pointers[column])
    }
}

private fun matrix(columns: Array<BasisSlice>): SparseMatrix {
    val pointers = IntArray(columns.size + 1)
    for (column in columns.indices) pointers[column + 1] = pointers[column] + columns[column].count
    val rows = IntArray(pointers.last())
    val values = DoubleArray(rows.size)
    for (column in columns.indices) {
        val slice = columns[column]
        for (k in 0 until slice.count) {
            rows[pointers[column] + k] = slice.indices[slice.offset + k]
            values[pointers[column] + k] = slice.values[slice.offset + k]
        }
    }
    return SparseMatrix.wrap(columns.size, columns.size, pointers, rows, values)
}

private fun transpose(matrix: SparseMatrix): SparseMatrix = matrix(transposeSlices(slices(matrix)))

private fun transposeSlices(columns: Array<BasisSlice>): Array<BasisSlice> {
    val counts = IntArray(columns.size)
    for (column in columns) for (k in 0 until column.count) counts[column.indices[column.offset + k]]++
    val indices = Array(columns.size) { IntArray(counts[it]) }
    val values = Array(columns.size) { DoubleArray(counts[it]) }
    counts.fill(0)
    for (column in columns.indices) {
        val slice = columns[column]
        for (k in 0 until slice.count) {
            val row = slice.indices[slice.offset + k]
            val position = counts[row]++
            indices[row][position] = column
            values[row][position] = slice.values[slice.offset + k]
        }
    }
    return Array(columns.size) { BasisSlice(indices[it], values[it]) }
}

private fun IntArray.isInjectionInto(size: Int): Boolean {
    val seen = BooleanArray(size)
    for (value in this) {
        if (value !in 0 until size || seen[value]) return false
        seen[value] = true
    }
    return true
}
