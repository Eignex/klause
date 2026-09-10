package com.eignex.klause.simplex.basis

import com.eignex.koblas.SparseMatrix
import com.eignex.koblas.Workspace

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

internal data class BasisExtensionVerification(val state: BasisExtensionState?, val units: Long)

internal fun verifyBasisExtension(
    oldSource: SparseMatrix,
    newSource: SparseMatrix,
    acceptedColumns: IntArray,
    acceptedUnitRows: IntArray,
    request: BasisExtension,
): BasisExtensionState? = inspectBasisExtension(
    oldSource,
    newSource,
    acceptedColumns,
    acceptedUnitRows,
    request,
).state

internal fun inspectBasisExtension(
    oldSource: SparseMatrix,
    newSource: SparseMatrix,
    acceptedColumns: IntArray,
    acceptedUnitRows: IntArray,
    request: BasisExtension,
): BasisExtensionVerification {
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
    if (!requestedBasis.columns.contentEquals(acceptedColumns)) return BasisExtensionVerification(null, 0)
    if (!requestedBasis.unitRows.contentEquals(acceptedUnitRows)) return BasisExtensionVerification(null, 0)

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
    val crossRows = IntArray(extensionRows.size)
    val crossValues = DoubleArray(extensionRows.size)
    val crossByOldColumn = Array(oldSource.cols) { BasisSlice(IntArray(0), DoubleArray(0)) }
    for (oldColumn in 0 until oldSource.cols) {
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
        if (!compatible) return BasisExtensionVerification(null, validationEntries)
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
    val state = BasisExtensionState(
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
    return BasisExtensionVerification(state, validationEntries)
}

internal fun buildExtendedCache(
    old: BasisSolveCache,
    oldSource: SparseMatrix,
    extension: BasisExtensionState,
    threshold: Double,
    workspace: Workspace,
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
    val initialCrossEntries = initialCross.sumOf { it.count }
    val upperTranspose = extendedTranspose(oldFactors.upperTranspose, initialCross, offset)
    val factors = LuFactors(initialColumns, initialUnits, symbolic, lower, upper, lowerTranspose, upperTranspose)
    val currentUpper = extendedSlices(oldFt.upper.columns, currentCross, offset)
    val currentCrossEntries = currentCross.sumOf { it.count }
    val currentTranspose = extendedTransposeSlices(oldFt.transpose.columns, currentCross, offset)
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
        factors.lower.nnz.toLong() * 2 + factors.lowerTranspose.nnz.toLong() * 2,
        saturatedAdd(
            initialEntries.toLong() * 2 + initialCrossEntries,
            saturatedAdd(
                currentEntries.toLong() * 2 + currentCrossEntries,
                oldFt.transformEntries.toLong(),
            ),
        ),
    )
    val units = saturatedAdd(
        extension.validationEntries,
        saturatedAdd(
            crossEntries * 2 + initialCrossEntries + currentCrossEntries,
            copiedEntries,
        ),
    )
    return BasisSolveCache.transfer(factors, ft, threshold, workspace) to units
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
    val oldPointers = old.copyColumnPointers()
    val pointers = IntArray(old.cols + offset + 1)
    for (column in 0 until offset) pointers[column + 1] = column + 1
    for (column in 0 until old.cols) {
        pointers[column + offset + 1] = pointers[column + offset] +
            cross[column].count + oldPointers[column + 1] - oldPointers[column]
    }
    val rows = IntArray(pointers.last())
    val values = DoubleArray(rows.size)
    for (column in 0 until offset) {
        rows[column] = column
        values[column] = 1.0
    }
    for (column in 0 until old.cols) {
        var position = pointers[column + offset]
        val top = cross[column]
        for (k in 0 until top.count) {
            rows[position] = top.indices[top.offset + k]
            values[position++] = top.values[top.offset + k]
        }
        old.forEachInColumn(column) { row, value ->
            rows[position] = row + offset
            values[position++] = value
        }
    }
    return SparseMatrix.wrap(old.rows + offset, old.cols + offset, pointers, rows, values)
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

private fun extendedTranspose(old: SparseMatrix, cross: Array<BasisSlice>, offset: Int): SparseMatrix {
    val crossCounts = IntArray(offset)
    for (slice in cross) {
        for (k in 0 until slice.count) crossCounts[slice.indices[slice.offset + k]]++
    }
    val oldPointers = old.copyColumnPointers()
    val pointers = IntArray(old.cols + offset + 1)
    for (column in 0 until offset) pointers[column + 1] = pointers[column] + crossCounts[column] + 1
    for (column in 0 until old.cols) {
        pointers[column + offset + 1] = pointers[column + offset] + oldPointers[column + 1] - oldPointers[column]
    }
    val rows = IntArray(pointers.last())
    val values = DoubleArray(rows.size)
    val positions = pointers.copyOf()
    for (column in 0 until offset) {
        rows[positions[column]] = column
        values[positions[column]++] = 1.0
    }
    for (sourceColumn in cross.indices) {
        val slice = cross[sourceColumn]
        for (k in 0 until slice.count) {
            val column = slice.indices[slice.offset + k]
            rows[positions[column]] = sourceColumn + offset
            values[positions[column]++] = slice.values[slice.offset + k]
        }
    }
    for (column in 0 until old.cols) {
        old.forEachInColumn(column) { row, value ->
            rows[positions[column + offset]] = row + offset
            values[positions[column + offset]++] = value
        }
    }
    return SparseMatrix.wrap(old.rows + offset, old.cols + offset, pointers, rows, values)
}

private fun extendedTransposeSlices(old: Array<BasisSlice>, cross: Array<BasisSlice>, offset: Int): Array<BasisSlice> {
    val crossCounts = IntArray(offset)
    for (slice in cross) {
        for (k in 0 until slice.count) {
            crossCounts[slice.indices[slice.offset + k]]++
        }
    }
    val result = Array(old.size + offset) { column ->
        val count = if (column < offset) crossCounts[column] + 1 else old[column - offset].count
        BasisSlice(IntArray(count), DoubleArray(count))
    }
    val positions = IntArray(offset)
    for (column in 0 until offset) {
        result[column].indices[0] = column
        result[column].values[0] = 1.0
        positions[column] = 1
    }
    for (sourceColumn in cross.indices) {
        val slice = cross[sourceColumn]
        for (k in 0 until slice.count) {
            val column = slice.indices[slice.offset + k]
            result[column].indices[positions[column]] = sourceColumn + offset
            result[column].values[positions[column]++] = slice.values[slice.offset + k]
        }
    }
    for (column in old.indices) {
        val source = old[column]
        val target = result[column + offset]
        for (k in 0 until source.count) {
            target.indices[k] = source.indices[source.offset + k] + offset
            target.values[k] = source.values[source.offset + k]
        }
    }
    return result
}

private fun IntArray.isInjectionInto(size: Int): Boolean {
    val seen = BooleanArray(size)
    for (value in this) {
        if (value !in 0 until size || seen[value]) return false
        seen[value] = true
    }
    return true
}
