package com.eignex.klause.simplex.basis

import com.eignex.koblas.SparseMatrix
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KotlinBasisSolverExtensionTest {
    @Test
    fun `extension preserves an updated basis through interleaved row and column identities`() {
        val oldSource = ftSource("dense", 3)
        val old = KotlinBasisSolver(oldSource, updateLimit = 100, fillFactor = 100.0)
        val oldBasis = intArrayOf(2, 0, 1)
        assertTrue(old.refactorize(oldBasis))
        replace(old, oldSource, oldBasis, slot = 1, entering = 4)
        val oldSnapshot = assertNotNull(old.snapshot())
        val rowMap = intArrayOf(2, 0, 4)
        val columnMap = intArrayOf(4, 0, 7, 2, 8, 5)
        val newSource = embeddedSource(oldSource, 5, 14, rowMap, columnMap, intArrayOf(9, 10, 11, 12, 13))
        val request = BasisExtension(oldBasis, IntArray(3) { -1 }, rowMap, columnMap)

        val result = assertNotNull(old.extend(newSource, request))

        assertContentEquals(intArrayOf(7, 8, 0, -1, -1), result.basis.columns)
        assertContentEquals(intArrayOf(-1, -1, -1, 1, 3), result.basis.unitRows)
        assertEquals(BasisBuildKind.EXTENSION, assertNotNull(result.solver.basisWork?.build).kind)
        assertEquals(0, result.solver.basisWork?.workSinceBuild)
        assertBasisSolves(result.solver, newSource, result.basis.columns, result.basis.unitRows)
        assertMatchesFresh(result.solver, newSource, result.basis.columns, result.basis.unitRows)
        assertFalse(result.solver.restore(oldSnapshot))

        old.close()
        assertBasisSolves(result.solver, newSource, result.basis.columns, result.basis.unitRows)
        val columns = result.basis.columns.copyOf()
        val units = result.basis.unitRows.copyOf()
        replace(result.solver as KotlinBasisSolver, newSource, columns, slot = 3, entering = 1)
        units[3] = -1
        assertMatchesFresh(result.solver, newSource, columns, units)
        val snapshot = assertNotNull(result.solver.snapshot())
        replace(result.solver, newSource, columns, slot = 4, entering = 3)
        units[4] = -1
        assertTrue(result.solver.restore(snapshot))
        assertMatchesFresh(
            result.solver,
            newSource,
            columns.copyOf().also { it[4] = -1 },
            intArrayOf(-1, -1, -1, -1, 3),
        )
    }

    @Test
    fun `extension preserves repaired source and unit headings`() {
        val oldSource = SparseMatrix.ofColumns(
            3,
            4,
            listOf(
                listOf(1 to 1.0),
                listOf(1 to 1.0),
                emptyList(),
                listOf(0 to 1.0, 1 to 1.0, 2 to 1.0),
            ),
        )
        val old = KotlinBasisSolver(oldSource)
        val repaired = assertNotNull(old.refactorizeRepairing(intArrayOf(0, 1, 2)))
        val rows = intArrayOf(3, 0, 2)
        val columns = intArrayOf(5, 1, 4, 0)
        val extendedSource = embeddedSource(oldSource, 4, 10, rows, columns, intArrayOf(6, 7, 8, 9))

        val result = assertNotNull(
            old.extend(extendedSource, BasisExtension(repaired.columns, repaired.unitRows, rows, columns)),
        )

        val expectedColumns = repaired.columns.map { if (it >= 0) columns[it] else -1 }.toIntArray() + intArrayOf(-1)
        val expectedUnits = repaired.unitRows.map { if (it >= 0) rows[it] else -1 }.toIntArray() + intArrayOf(1)
        assertContentEquals(expectedColumns, result.basis.columns)
        assertContentEquals(expectedUnits, result.basis.unitRows)
        assertBasisSolves(result.solver, extendedSource, expectedColumns, expectedUnits)
        assertMatchesFresh(result.solver, extendedSource, expectedColumns, expectedUnits)
        assertNotNull(
            result.solver.refactorizeRepairing(
                IntArray(4) { if (expectedColumns[it] >= 0) expectedColumns[it] else 0 },
            ),
        )
    }

    @Test
    fun `incompatible structure and headings leave the old accepted state intact`() {
        val oldSource = ftSource("sparse", 3)
        val old = KotlinBasisSolver(oldSource)
        val basis = intArrayOf(0, 1, 2)
        assertTrue(old.refactorize(basis))
        val rows = intArrayOf(2, 0, 3)
        val columns = intArrayOf(4, 1, 7, 0, 6, 3)
        val compatible = embeddedSource(oldSource, 4, 12, rows, columns, intArrayOf(8, 9, 10, 11))
        val changed = changedCoefficient(compatible, rows[1], columns[1])

        assertNull(old.extend(changed, BasisExtension(basis, IntArray(3) { -1 }, rows, columns)))
        assertNull(old.extend(compatible, BasisExtension(intArrayOf(0, 2, 1), IntArray(3) { -1 }, rows, columns)))
        assertBasisSolves(old, oldSource, basis, IntArray(3) { -1 })
        assertFailsWith<IllegalArgumentException> {
            old.extend(
                compatible,
                BasisExtension(basis, IntArray(3) { -1 }, rows, columns.copyOf().also { it[1] = it[0] }),
            )
        }
        assertBasisSolves(old, oldSource, basis, IntArray(3) { -1 })
    }

    @Test
    fun `mapped nonbasic structure compares raw IEEE bits`() {
        val firstNaN = Double.fromBits(0x7ff8000000000001L)
        val secondNaN = Double.fromBits(0x7ff8000000000002L)
        val oldSource = SparseMatrix.wrap(
            1,
            4,
            intArrayOf(0, 1, 2, 3, 3),
            intArrayOf(0, 0, 0),
            doubleArrayOf(1.0, firstNaN, -0.0),
        )
        val old = KotlinBasisSolver(oldSource)
        assertTrue(old.refactorize(intArrayOf(0)))
        val request = BasisExtension(
            intArrayOf(0),
            intArrayOf(-1),
            intArrayOf(0),
            intArrayOf(0, 1, 2, 3),
        )

        val identical = assertNotNull(old.extend(oldSource, request))
        identical.solver.close()
        val differentNaN = mappedBitsSource(secondNaN, -0.0, storedTail = false)
        val differentZero = mappedBitsSource(firstNaN, 0.0, storedTail = false)
        val differentStructure = mappedBitsSource(firstNaN, -0.0, storedTail = true)

        assertNull(old.extend(differentNaN, request))
        assertNull(old.extend(differentZero, request))
        assertNull(old.extend(differentStructure, request))
        assertBasisSolves(old, oldSource, intArrayOf(0), intArrayOf(-1))
    }

    @Test
    fun `extension preserves sparse dense and near singular solves`() {
        for (shape in listOf("sparse", "dense", "near")) {
            val oldSource = ftSource(shape, 5)
            val old = KotlinBasisSolver(oldSource)
            val basis = intArrayOf(4, 1, 3, 0, 2)
            assertTrue(old.refactorize(basis))
            val rows = intArrayOf(2, 6, 0, 4, 1)
            val columns = intArrayOf(8, 0, 11, 3, 14, 5, 16, 7, 18, 9)
            val newSource = embeddedSource(oldSource, 7, 27, rows, columns, IntArray(7) { 20 + it })

            val result = assertNotNull(
                old.extend(newSource, BasisExtension(basis, IntArray(5) { -1 }, rows, columns)),
            )

            assertBasisSolves(result.solver, newSource, result.basis.columns, result.basis.unitRows)
            assertMatchesFresh(result.solver, newSource, result.basis.columns, result.basis.unitRows)
            old.close()
            result.solver.close()
        }
    }

    @Test
    fun `extension copies request source and factor state without mutable aliases`() {
        val oldSource = ftSource("spiked", 3)
        val old = KotlinBasisSolver(oldSource)
        val basis = intArrayOf(0, 1, 2)
        assertTrue(old.refactorize(basis))
        val rows = intArrayOf(1, 3, 0)
        val columns = intArrayOf(5, 0, 7, 2, 8, 4)
        val logicals = intArrayOf(9, 10, 11, 12)
        val newSource = embeddedSource(oldSource, 4, 13, rows, columns, logicals)
        val request = BasisExtension(basis, IntArray(3) { -1 }, rows, columns)
        rows.fill(0)
        columns.fill(0)

        val result = assertNotNull(old.extend(newSource, request))

        newSource.values.fill(Double.NaN)
        basis.fill(5)
        assertBasisSolves(
            result.solver,
            embeddedSource(oldSource, 4, 13, intArrayOf(1, 3, 0), intArrayOf(5, 0, 7, 2, 8, 4), logicals),
            result.basis.columns,
            result.basis.unitRows,
        )
    }

    @Test
    fun `zero dimension extensions cover empty and positive targets`() {
        val emptySource = SparseMatrix.ofColumns(0, 0, emptyList())
        val empty = KotlinBasisSolver(emptySource)
        assertTrue(empty.refactorize(IntArray(0)))

        val same = assertNotNull(
            empty.extend(emptySource, BasisExtension(IntArray(0), IntArray(0), IntArray(0), IntArray(0))),
        )
        assertEquals(0, same.solver.n)
        assertEquals(BasisBuildKind.EXTENSION, same.solver.basisWork?.build?.kind)
        assertEquals(0, same.solver.basisWork?.build?.units)

        val positive = SparseMatrix.ofColumns(
            2,
            2,
            listOf(listOf(0 to 1.0), listOf(1 to 1.0)),
        )
        val grown = assertNotNull(
            empty.extend(positive, BasisExtension(IntArray(0), IntArray(0), IntArray(0), IntArray(0))),
        )
        assertContentEquals(intArrayOf(-1, -1), grown.basis.columns)
        assertContentEquals(intArrayOf(0, 1), grown.basis.unitRows)
        assertBasisSolves(grown.solver, positive, grown.basis.columns, grown.basis.unitRows)
    }

    private fun replace(solver: KotlinBasisSolver, source: SparseMatrix, basis: IntArray, slot: Int, entering: Int) {
        val spike = IndexedVector(source.rows).also { it.scatterColumn(source, entering) }
        solver.ftran(spike, 0.0)
        assertTrue(solver.update(slot, entering, spike) != BasisUpdate.SINGULAR)
        basis[slot] = entering
    }
}

private fun mappedBitsSource(nan: Double, zero: Double, storedTail: Boolean): SparseMatrix = SparseMatrix.wrap(
    1,
    4,
    if (storedTail) intArrayOf(0, 1, 2, 3, 4) else intArrayOf(0, 1, 2, 3, 3),
    if (storedTail) intArrayOf(0, 0, 0, 0) else intArrayOf(0, 0, 0),
    if (storedTail) doubleArrayOf(1.0, nan, zero, 0.0) else doubleArrayOf(1.0, nan, zero),
)

private fun embeddedSource(
    old: SparseMatrix,
    newRows: Int,
    newColumns: Int,
    rowMap: IntArray,
    columnMap: IntArray,
    logicalColumns: IntArray,
): SparseMatrix {
    val reverse = IntArray(newColumns) { -1 }
    for (oldColumn in columnMap.indices) reverse[columnMap[oldColumn]] = oldColumn
    val logicalRow = IntArray(newColumns) { -1 }
    for (row in logicalColumns.indices) logicalRow[logicalColumns[row]] = row
    val oldAtNew = BooleanArray(newRows)
    for (row in rowMap) oldAtNew[row] = true
    return SparseMatrix.ofColumns(
        newRows,
        newColumns,
        List(newColumns) { column ->
            when {
                reverse[column] >= 0 -> buildList {
                    old.forEachInColumn(reverse[column]) { row, value -> add(rowMap[row] to value) }
                    for (row in 0 until newRows) {
                        if (!oldAtNew[row]) add(row to (reverse[column] + row + 1).toDouble() / 8.0)
                    }
                }

                logicalRow[column] >= 0 -> listOf(logicalRow[column] to 1.0)

                else -> if (newRows <= 1) {
                    listOf(0 to 0.25)
                } else {
                    listOf(column % newRows to 0.25, (column + 1) % newRows to 0.125)
                }
            }
        },
    )
}

private fun changedCoefficient(source: SparseMatrix, row: Int, column: Int): SparseMatrix = SparseMatrix.ofColumns(
    source.rows,
    source.cols,
    List(source.cols) { j ->
        buildList {
            source.forEachInColumn(j) { i, value -> add(i to if (i == row && j == column) value + 0.125 else value) }
        }
    },
)
