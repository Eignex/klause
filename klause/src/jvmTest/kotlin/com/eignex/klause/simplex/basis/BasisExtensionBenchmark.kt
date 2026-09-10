package com.eignex.klause.simplex.basis

import com.eignex.koblas.SparseMatrix
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.math.max

private var extensionSink = 0.0

// Explicit basis-only benchmark entry point; ordinary test discovery never runs measurements.
fun main() {
    val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
    check(bean.isThreadAllocatedMemorySupported)
    bean.isThreadAllocatedMemoryEnabled = true
    for (shape in SHAPES) {
        for (arm in ARMS) measureExtension(shape, arm, bean)
    }
    repeat(REPETITIONS) { repetition ->
        for (shape in SHAPES) {
            for (arm in ARMS) {
                println("B4cbench repetition=$repetition ${measureExtension(shape, arm, bean)}")
            }
        }
    }
}

private fun measureExtension(shape: String, arm: String, bean: ThreadMXBean): String {
    val fixture = extensionFixture(shape)
    val old = KotlinBasisSolver(fixture.oldSource, updateLimit = 100, fillFactor = 100.0)
    val oldBasis = IntArray(DIMENSION) { DIMENSION - 1 - it }
    check(old.refactorize(oldBasis))
    repeat(UPDATES) { step ->
        val slot = (step * 5 + 2) % DIMENSION
        val entering = (oldBasis[slot] + DIMENSION) % fixture.oldSource.cols
        val spike = IndexedVector(DIMENSION).also { it.scatterColumn(fixture.oldSource, entering) }
        old.ftran(spike, 0.0)
        check(old.update(slot, entering, spike) != BasisUpdate.SINGULAR)
        oldBasis[slot] = entering
    }
    val headings = oldBasis.map { fixture.columnMap[it] }.toIntArray() + fixture.logicalHeadings
    val thread = Thread.currentThread().threadId()
    val beforeBytes = bean.getThreadAllocatedBytes(thread)
    val beforeNanos = System.nanoTime()
    val solver = if (arm == "transfer") {
        checkNotNull(
            old.extend(
                fixture.newSource,
                BasisExtension(oldBasis, IntArray(DIMENSION) { -1 }, fixture.rowMap, fixture.columnMap),
            ),
        ).solver
    } else {
        KotlinBasisSolver(fixture.newSource, updateLimit = 100, fillFactor = 100.0).also {
            check(it.refactorize(headings))
        }
    }
    val setupNanos = System.nanoTime() - beforeNanos
    val setupBytes = bean.getThreadAllocatedBytes(thread) - beforeBytes
    val rhs = DoubleArray(fixture.newSource.rows) { if (it % 7 == 2) 1.0 else 0.0 }
    var solveNanos = 0L
    var solveBytes = 0L
    var residual = 0.0
    for (transpose in listOf(false, true)) {
        val vector = IndexedVector(fixture.newSource.rows).also { it.scatter(rhs) }
        val solveBeforeBytes = bean.getThreadAllocatedBytes(thread)
        val solveBeforeNanos = System.nanoTime()
        if (transpose) solver.btran(vector, 0.0) else solver.ftran(vector, 0.0)
        solveNanos += System.nanoTime() - solveBeforeNanos
        solveBytes += bean.getThreadAllocatedBytes(thread) - solveBeforeBytes
        residual = max(residual, ftResidual(fixture.newSource, headings, rhs, vector, transpose))
        extensionSink += vector[0]
    }
    val build = checkNotNull(solver.basisWork?.build)
    val storage = solver.nnz
    solver.close()
    old.close()
    val tolerance = if (shape == "near") 1e-8 else 1e-11
    check(residual <= tolerance) { "$shape $arm residual=$residual" }
    return "shape=$shape arm=$arm residual=$residual setupNanos=$setupNanos setupBytes=$setupBytes " +
        "solveNanos=$solveNanos solveBytes=$solveBytes totalNanos=${setupNanos + solveNanos} " +
        "totalBytes=${setupBytes + solveBytes} buildKind=${build.kind} builds=${build.builds} " +
        "buildUnits=${build.units} installedBuildUnits=${build.installedBuildUnits ?: -1} " +
        "storage=$storage updates=$UPDATES"
}

private fun extensionFixture(shape: String): ExtensionFixture {
    val old = ftSource(shape, DIMENSION)
    val newRows = DIMENSION + APPENDED_ROWS
    val extensionRows = intArrayOf(1, 5, 9, 13)
    val isExtension = BooleanArray(newRows)
    for (row in extensionRows) isExtension[row] = true
    val rowMap = IntArray(DIMENSION)
    var oldRow = 0
    for (row in 0 until newRows) if (!isExtension[row]) rowMap[oldRow++] = row
    val columnMap = IntArray(old.cols) { (it * 5) % old.cols }
    val extraStart = old.cols
    val logicalStart = extraStart + APPENDED_ROWS
    val logicalColumns = IntArray(newRows) { logicalStart + it }
    val newColumns = logicalStart + newRows
    val oldColumnAtNew = IntArray(newColumns) { -1 }
    for (column in columnMap.indices) oldColumnAtNew[columnMap[column]] = column
    val logicalRow = IntArray(newColumns) { -1 }
    for (row in logicalColumns.indices) logicalRow[logicalColumns[row]] = row
    val source = SparseMatrix.ofColumns(
        newRows,
        newColumns,
        List(newColumns) { column ->
            when {
                oldColumnAtNew[column] >= 0 -> buildList {
                    val original = oldColumnAtNew[column]
                    old.forEachInColumn(original) { row, value -> add(rowMap[row] to value) }
                    for (row in extensionRows) {
                        if ((original + row) % 3 == 0) add(row to (original % 5 + 1).toDouble() / 16.0)
                    }
                }

                logicalRow[column] >= 0 -> listOf(logicalRow[column] to 1.0)

                else -> listOf(extensionRows[column - extraStart] to 1.0, rowMap[column - extraStart] to 0.25)
            }
        },
    )
    return ExtensionFixture(
        old,
        source,
        rowMap,
        columnMap,
        extensionRows.map { logicalColumns[it] }.toIntArray(),
    )
}

private data class ExtensionFixture(
    val oldSource: SparseMatrix,
    val newSource: SparseMatrix,
    val rowMap: IntArray,
    val columnMap: IntArray,
    val logicalHeadings: IntArray,
)

private const val DIMENSION = 16
private const val APPENDED_ROWS = 4
private const val UPDATES = 4
private const val REPETITIONS = 3
private val SHAPES = listOf("sparse", "spiked", "dense", "near")
private val ARMS = listOf("transfer", "rebuild")
