package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.BasisExtension
import com.eignex.klause.simplex.basis.BasisSolver
import com.eignex.klause.simplex.basis.BasisUpdate
import com.eignex.klause.simplex.basis.IndexedVector
import com.eignex.klause.simplex.basis.KotlinBasisSolver
import com.eignex.klause.simplex.basis.assertBasisSolves
import com.eignex.klause.simplex.basis.assertMatchesFresh
import com.eignex.koblas.SparseMatrix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BasisReplacementAcceptanceTest {
    @Test
    fun `published replacement composes repair transfer updates restore and refactorization`() {
        val oldMatrix = SparseMatrix.ofColumns(
            2,
            4,
            listOf(
                emptyList(),
                listOf(1 to 1.0),
                listOf(0 to 1.0, 1 to 1.0),
                listOf(0 to 1.0, 1 to -1.0),
            ),
        )
        val old = KotlinBasisSolver(oldMatrix, updateLimit = 100, fillFactor = 100.0)
        val repaired = assertNotNull(old.refactorizeRepairing(intArrayOf(0, 1)))
        assertTrue(repaired.repaired)
        val oldSnapshot = assertNotNull(old.snapshot())
        val rowMap = intArrayOf(2, 0)
        val columnMap = intArrayOf(1, 3, 5, 6)
        val logicalColumns = intArrayOf(0, 2, 4)
        val newMatrix = embeddedMatrix(oldMatrix, 3, 7, rowMap, columnMap, logicalColumns)
        val intended = repaired.columns.mapIndexed { slot, column ->
            if (column >= 0) columnMap[column] else logicalColumns[rowMap[repaired.unitRows[slot]]]
        }.toIntArray() + logicalColumns[1]
        val replacement = assertNotNull(
            BasisExtensionAdapter().replacement(
                old,
                newMatrix,
                intended,
                logicalColumns,
                BasisExtension(repaired.columns, repaired.unitRows, rowMap, columnMap),
            ),
        )
        assertTrue(replacement.transferred)
        assertFalse(replacement.transferArithmeticDeclined)
        assertBasisSolves(old, oldMatrix, repaired.columns, repaired.unitRows)

        var current = replacement.solver
        var sourceHeadings = replacement.sourceHeadings.copyOf()
        var ownerColumns = replacement.ownerBasis.columns.copyOf()
        var ownerUnitRows = replacement.ownerBasis.unitRows.copyOf()
        assertSame(replacement.solver, current)
        assertFalse(current.restore(oldSnapshot))
        assertBasisSolves(current, newMatrix, ownerColumns, ownerUnitRows)
        oldSnapshot.close()
        old.close()
        assertBasisSolves(current, newMatrix, ownerColumns, ownerUnitRows)

        replace(current, newMatrix, slot = 2, entering = 5)
        sourceHeadings[2] = 5
        ownerColumns[2] = 5
        ownerUnitRows[2] = -1
        assertBasisSolves(current, newMatrix, ownerColumns, ownerUnitRows)
        assertMatchesFresh(current, newMatrix, ownerColumns, ownerUnitRows)
        val replacementSnapshot = assertNotNull(current.snapshot())
        val snapshotSourceHeadings = sourceHeadings.copyOf()
        val snapshotOwnerColumns = ownerColumns.copyOf()
        val snapshotOwnerUnitRows = ownerUnitRows.copyOf()

        replace(current, newMatrix, slot = 0, entering = 6)
        sourceHeadings[0] = 6
        ownerColumns[0] = 6
        ownerUnitRows[0] = -1
        assertBasisSolves(current, newMatrix, ownerColumns, ownerUnitRows)

        assertTrue(current.restore(replacementSnapshot))
        sourceHeadings = snapshotSourceHeadings
        ownerColumns = snapshotOwnerColumns
        ownerUnitRows = snapshotOwnerUnitRows
        replacementSnapshot.close()
        assertBasisSolves(current, newMatrix, ownerColumns, ownerUnitRows)
        assertMatchesFresh(current, newMatrix, ownerColumns, ownerUnitRows)

        assertTrue(current.refactorize(sourceHeadings))
        ownerColumns = sourceHeadings.copyOf()
        ownerUnitRows = IntArray(sourceHeadings.size) { -1 }
        assertBasisSolves(current, newMatrix, ownerColumns, ownerUnitRows)
        assertMatchesFresh(current, newMatrix, ownerColumns, ownerUnitRows)
        current.close()
    }

    @Test
    fun `declined replacements retain the accepted owner and close rejected owners`() {
        val oldMatrix = SparseMatrix.ofColumns(1, 1, listOf(listOf(0 to 1.0)))
        val old = KotlinBasisSolver(oldMatrix).also { assertTrue(it.refactorize(intArrayOf(0))) }
        val extendedMatrix = SparseMatrix.ofColumns(
            2,
            4,
            listOf(
                listOf(0 to 1.0, 1 to Double.POSITIVE_INFINITY),
                listOf(0 to 1.0),
                listOf(0 to 1.0),
                listOf(1 to 1.0),
            ),
        )
        val made = mutableListOf<B5aTrackingBasisSolver>()
        var freshAttempts = 0
        val adapter = BasisExtensionAdapter { matrix ->
            freshAttempts++
            B5aTrackingBasisSolver(KotlinBasisSolver(matrix)).also(made::add)
        }
        val replacement = assertNotNull(
            adapter.replacement(
                old,
                extendedMatrix,
                intArrayOf(2, 3),
                intArrayOf(2, 3),
                BasisExtension(intArrayOf(0), intArrayOf(-1), intArrayOf(0), intArrayOf(0)),
            ),
        )

        assertFalse(replacement.transferred)
        assertTrue(replacement.transferArithmeticDeclined)
        assertEquals(1, freshAttempts)
        assertFalse(made.single().closed)
        assertBasisSolves(old, oldMatrix, intArrayOf(0), intArrayOf(-1))
        val current = replacement.solver
        old.close()
        assertBasisSolves(current, extendedMatrix, intArrayOf(2, 3), intArrayOf(-1, -1))

        var unavailableTransfers = 0
        val unavailable = object : BasisSolver by current {
            override fun extend(matrix: SparseMatrix, extension: BasisExtension) = run {
                unavailableTransfers++
                null
            }
        }
        val singularMatrix = SparseMatrix.ofColumns(
            2,
            5,
            listOf(emptyList(), emptyList(), emptyList(), listOf(0 to 1.0), listOf(1 to 1.0)),
        )
        val rejected = adapter.replacement(
            unavailable,
            singularMatrix,
            intArrayOf(0, 4),
            intArrayOf(3, 4),
            BasisExtension(intArrayOf(2, 3), intArrayOf(-1, -1), intArrayOf(0, 1), intArrayOf(0, 1, 2, 3)),
        )

        assertNull(rejected)
        assertEquals(1, unavailableTransfers)
        assertEquals(2, freshAttempts)
        assertTrue(made.last().closed)
        assertFalse(made.first().closed)
        assertBasisSolves(current, extendedMatrix, intArrayOf(2, 3), intArrayOf(-1, -1))
        current.close()
        assertTrue(made.first().closed)
    }

    private fun replace(solver: BasisSolver, matrix: SparseMatrix, slot: Int, entering: Int) {
        val spike = IndexedVector(matrix.rows).also { it.scatterColumn(matrix, entering) }
        solver.ftran(spike, 0.0)
        assertTrue(solver.update(slot, entering, spike, spike) != BasisUpdate.SINGULAR)
    }
}

private class B5aTrackingBasisSolver(private val delegate: BasisSolver) : BasisSolver by delegate {
    var closed = false
        private set

    override fun close() {
        closed = true
        delegate.close()
    }
}

private fun embeddedMatrix(
    old: SparseMatrix,
    rows: Int,
    columns: Int,
    rowMap: IntArray,
    columnMap: IntArray,
    logicalColumns: IntArray,
): SparseMatrix {
    val oldColumnAt = IntArray(columns) { -1 }
    for (oldColumn in columnMap.indices) oldColumnAt[columnMap[oldColumn]] = oldColumn
    val logicalRowAt = IntArray(columns) { -1 }
    for (row in logicalColumns.indices) logicalRowAt[logicalColumns[row]] = row
    return SparseMatrix.ofColumns(
        rows,
        columns,
        List(columns) { column ->
            when {
                oldColumnAt[column] >= 0 -> buildList {
                    val oldColumn = oldColumnAt[column]
                    old.forEachInColumn(oldColumn) { oldRow, value -> add(rowMap[oldRow] to value) }
                    add(1 to (oldColumn + 1).toDouble())
                }

                logicalRowAt[column] >= 0 -> listOf(logicalRowAt[column] to 1.0)

                else -> emptyList()
            }
        },
    )
}
