package com.eignex.klause.simplex.basis

import com.eignex.klause.util.Cancellation
import com.eignex.koblas.SparseMatrix
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KotlinBasisSolverRepairTest {
    @Test
    fun `repair retains a nonzero pivot at the absolute tolerance`() {
        val source = matrix(arrayOf(doubleArrayOf(1e-10)))
        KotlinBasisSolver(source).use { solver ->
            val repair = assertNotNull(solver.refactorizeRepairing(intArrayOf(0)))

            assertFalse(repair.repaired)
            assertBasisSolves(solver, source, repair.columns, repair.unitRows)
        }
    }

    @Test
    fun `cancellation at every repair boundary leaves no usable partial factors`() {
        val source = matrix(arrayOf(doubleArrayOf(2.0, 1.0), doubleArrayOf(1.0, 3.0)))
        var boundaries = 0
        KotlinBasisSolver(source).use { solver ->
            assertNotNull(
                solver.refactorizeRepairing(
                    intArrayOf(0, 1),
                    BasisRepairControl(
                        Cancellation {
                            boundaries++
                            false
                        },
                    ),
                ),
            )
        }

        for (boundary in 1..boundaries) {
            KotlinBasisSolver(source).use { solver ->
                assertTrue(solver.refactorize(intArrayOf(0, 1)))
                var polls = 0
                val control = BasisRepairControl(Cancellation { ++polls == boundary })

                val result = solver.refactorizeRepairing(intArrayOf(0, 1), control)

                assertNull(result, "boundary=$boundary")
                assertEquals(boundary, polls)
                assertEquals(BasisRepairStop.CANCELLED, control.stop)
                assertTrue(solver.singular)
                assertNull(solver.ordering())
                assertNull(solver.snapshot())
                assertFailsWith<IllegalStateException> { solver.ftran(IndexedVector(2)) }
                assertTrue(solver.refactorize(intArrayOf(0, 1)))
            }
        }
    }

    @Test
    fun `callbacks cannot access partial factors or mutate the admitted request`() {
        val source = matrix(arrayOf(doubleArrayOf(2.0, 1.0), doubleArrayOf(1.0, 3.0)))
        val requested = intArrayOf(0, 1)
        KotlinBasisSolver(source).use { solver ->
            val control = BasisRepairControl(
                Cancellation {
                    requested.fill(-1)
                    assertFailsWith<IllegalStateException> { solver.ordering() }
                    assertFailsWith<IllegalStateException> { solver.snapshot() }
                    assertFailsWith<IllegalStateException> { solver.refactorize(intArrayOf(0, 1)) }
                    assertFailsWith<IllegalStateException> { solver.ftran(IndexedVector(2)) }
                    assertFailsWith<IllegalStateException> { solver.close() }
                    false
                },
            )

            val repair = assertNotNull(solver.refactorizeRepairing(requested, control))

            assertFalse(repair.repaired)
            assertEquals(setOf(0, 1), repair.columns.toSet())
            assertBasisSolves(solver, source, repair.columns, repair.unitRows)
        }
    }

    @Test
    fun `a throwing cancellation callback invalidates factors and releases the owner`() {
        val source = matrix(arrayOf(doubleArrayOf(1.0)))
        var boundaries = 0
        KotlinBasisSolver(source).use { solver ->
            assertNotNull(
                solver.refactorizeRepairing(
                    intArrayOf(0),
                    BasisRepairControl(
                        Cancellation {
                            boundaries++
                            false
                        },
                    ),
                ),
            )
        }
        for (boundary in listOf(1, boundaries)) {
            KotlinBasisSolver(source).use { solver ->
                assertTrue(solver.refactorize(intArrayOf(0)))
                val primary = IllegalStateException("callback")
                var polls = 0

                val failure = assertFailsWith<IllegalStateException> {
                    solver.refactorizeRepairing(
                        intArrayOf(0),
                        BasisRepairControl(
                            Cancellation {
                                if (++polls == boundary) throw primary else false
                            },
                        ),
                    )
                }

                assertTrue(failure === primary)
                assertTrue(solver.singular)
                assertFalse(assertNotNull(solver.basisWork.build).successful)
                assertNull(solver.ordering())
                assertTrue(solver.refactorize(intArrayOf(0)))
            }
        }
    }

    @Test
    fun `finite repair work includes the completed attempt and cannot be refreshed`() {
        val source = matrix(arrayOf(doubleArrayOf(2.0, 1.0), doubleArrayOf(1.0, 3.0)))
        val full = BasisRepairControl()
        KotlinBasisSolver(source).use { solver ->
            assertNotNull(solver.refactorizeRepairing(intArrayOf(0, 1), full))
            assertEquals(full.spentWork, solver.basisOperationWork.units)
        }
        for (limit in listOf(0L, 1L, full.spentWork, full.spentWork + 1)) {
            KotlinBasisSolver(source).use { solver ->
                val control = BasisRepairControl(maxWork = limit)

                val repair = solver.refactorizeRepairing(intArrayOf(0, 1), control)

                assertEquals(limit > full.spentWork, repair != null)
                assertEquals(repair == null, solver.singular)
                assertEquals(control.spentWork, solver.basisOperationWork.units)
                if (repair == null) {
                    assertEquals(BasisRepairStop.WORK, control.stop)
                    val before = solver.basisOperationWork
                    assertNull(solver.refactorizeRepairing(intArrayOf(0, 1), control))
                    assertEquals(before.ftran, solver.basisOperationWork.ftran)
                    assertEquals(before.update, solver.basisOperationWork.update)
                }
            }
        }
    }

    @Test
    fun `repair reports the complete permuted source and logical basis`() {
        val source = SparseMatrix.ofColumns(
            3,
            3,
            listOf(
                listOf(1 to 1.0),
                listOf(1 to 1.0),
                emptyList(),
            ),
        )
        val solver = KotlinBasisSolver(source)

        val repair = assertNotNull(solver.refactorizeRepairing(intArrayOf(0, 1, 2)))

        assertContentEquals(intArrayOf(-1, 0, -1), repair.columns)
        assertContentEquals(intArrayOf(0, -1, 2), repair.unitRows)
        assertTrue(repair.repaired)
        assertBasisSolves(solver, source, repair.columns, repair.unitRows)
        repair.columns.fill(2)
        repair.unitRows.fill(-1)
        assertBasisSolves(solver, source, intArrayOf(-1, 0, -1), intArrayOf(0, -1, 2))
    }

    @Test
    fun `source columns enter repaired logical slots and match a fresh basis`() {
        val source = SparseMatrix.ofColumns(
            3,
            4,
            listOf(
                listOf(1 to 1.0),
                listOf(1 to 1.0),
                emptyList(),
                listOf(0 to 1.0, 1 to 1.0, 2 to 1.0),
            ),
        )
        val solver = KotlinBasisSolver(source, updateLimit = 100, fillFactor = 100.0)
        val repair = assertNotNull(solver.refactorizeRepairing(intArrayOf(0, 1, 2)))
        val columns = repair.columns.copyOf()
        val units = repair.unitRows.copyOf()
        val slot = units.indexOfFirst { it >= 0 }
        val spike = IndexedVector(3).also { it.scatterColumn(source, 3) }
        solver.ftran(spike, 0.0)

        assertEquals(BasisUpdate.APPLIED, solver.update(slot, 3, spike, spike))

        columns[slot] = 3
        units[slot] = -1
        assertBasisSolves(solver, source, columns, units)
        assertMatchesFresh(solver, source, columns, units)
    }

    @Test
    fun `repairs empty unit sparse dense and near singular groups`() {
        val cases = listOf(
            Triple(SparseMatrix.ofColumns(0, 0, emptyList()), intArrayOf(), false),
            Triple(matrix(arrayOf(doubleArrayOf(1.0, 0.0), doubleArrayOf(0.0, 1.0))), intArrayOf(0, 1), false),
            Triple(
                matrix(
                    arrayOf(
                        doubleArrayOf(4.0, -1.0, 0.0),
                        doubleArrayOf(-1.0, 4.0, -1.0),
                        doubleArrayOf(0.0, -1.0, 4.0),
                    ),
                ),
                intArrayOf(2, 0, 1),
                false,
            ),
            Triple(
                matrix(
                    arrayOf(
                        doubleArrayOf(5.0, 1.0, -2.0),
                        doubleArrayOf(2.0, 6.0, 1.0),
                        doubleArrayOf(-1.0, 2.0, 7.0),
                    ),
                ),
                intArrayOf(1, 2, 0),
                false,
            ),
            Triple(
                matrix(arrayOf(doubleArrayOf(1.0, 1.0), doubleArrayOf(1.0, 1.0 + 1e-12))),
                intArrayOf(0, 1),
                true,
            ),
        )
        for ((source, requested, shouldRepair) in cases) {
            val solver = KotlinBasisSolver(source)
            val repair = assertNotNull(solver.refactorizeRepairing(requested))

            assertBasisSolves(solver, source, repair.columns, repair.unitRows)
            assertMatchesFresh(solver, source, repair.columns, repair.unitRows)
            assertEquals(shouldRepair, repair.repaired)
            solver.close()
        }
    }

    @Test
    fun `rejected updates preserve a repaired basis and later updates recover`() {
        val source = SparseMatrix.ofColumns(
            2,
            4,
            listOf(
                emptyList(),
                listOf(1 to 1.0),
                listOf(0 to 1e-12),
                listOf(0 to 1.0, 1 to 1.0),
            ),
        )
        val solver = KotlinBasisSolver(source)
        val repair = assertNotNull(solver.refactorizeRepairing(intArrayOf(0, 1)))
        val columns = repair.columns.copyOf()
        val units = repair.unitRows.copyOf()
        val logical = units.indexOfFirst { it >= 0 }
        val rejected = IndexedVector(2).also { it.scatterColumn(source, 2) }
        solver.ftran(rejected)

        assertEquals(BasisUpdate.SINGULAR, solver.update(logical, 2, rejected))
        assertBasisSolves(solver, source, columns, units)

        val accepted = IndexedVector(2).also { it.scatterColumn(source, 3) }
        solver.ftran(accepted)
        assertEquals(BasisUpdate.APPLIED, solver.update(logical, 3, accepted))
        columns[logical] = 3
        units[logical] = -1
        assertBasisSolves(solver, source, columns, units)
    }
}

internal fun assertBasisSolves(solver: BasisSolver, source: SparseMatrix, columns: IntArray, unitRows: IntArray) {
    val rhs = DoubleArray(source.rows) { (it + 1).toDouble() }
    for (transpose in listOf(false, true)) {
        val vector = IndexedVector(source.rows).also { it.scatter(rhs) }
        if (transpose) solver.btran(vector, 0.0) else solver.ftran(vector, 0.0)
        val residual = describedResidual(source, columns, unitRows, rhs, vector, transpose)
        assertTrue(residual <= 1e-9, "transpose=$transpose residual=$residual")
        assertTrue(solver.solveQuality(rhs, vector, transpose).relativeResidual <= 1e-9)
        assertEquals(vector.toDoubleArray().count { it != 0.0 }, vector.count)
    }
}

internal fun assertMatchesFresh(solver: BasisSolver, source: SparseMatrix, columns: IntArray, unitRows: IntArray) {
    val augmented = withLogicalColumns(source)
    val headings = IntArray(source.rows) { if (columns[it] >= 0) columns[it] else source.cols + unitRows[it] }
    val fresh = KotlinBasisSolver(augmented)
    assertTrue(fresh.refactorize(headings))
    val rhs = DoubleArray(source.rows) { (it % 3 - 1).toDouble() }
    for (transpose in listOf(false, true)) {
        val actual = IndexedVector(source.rows).also { it.scatter(rhs) }
        val expected = IndexedVector(source.rows).also { it.scatter(rhs) }
        if (transpose) {
            solver.btran(actual)
            fresh.btran(expected)
        } else {
            solver.ftran(actual)
            fresh.ftran(expected)
        }
        for (i in rhs.indices) {
            assertTrue(abs(actual[i] - expected[i]) <= 1e-9 * max(1.0, abs(expected[i])))
        }
    }
    fresh.close()
}

private fun describedResidual(
    source: SparseMatrix,
    columns: IntArray,
    unitRows: IntArray,
    rhs: DoubleArray,
    solution: IndexedVector,
    transpose: Boolean,
): Double {
    var error = 0.0
    var scale = 1.0
    for (i in columns.indices) {
        var product = 0.0
        for (j in columns.indices) {
            val value = if (columns[j] >= 0) {
                source[i, columns[j]]
            } else if (i == unitRows[j]) {
                1.0
            } else {
                0.0
            }
            product += if (transpose) {
                val transposed = if (columns[i] >= 0) {
                    source[j, columns[i]]
                } else if (j == unitRows[i]) {
                    1.0
                } else {
                    0.0
                }
                transposed * solution[j]
            } else {
                value * solution[j]
            }
        }
        error = max(error, abs(product - rhs[i]))
        scale = max(scale, max(abs(product), abs(rhs[i])))
    }
    return error / scale
}

internal fun withLogicalColumns(source: SparseMatrix): SparseMatrix = SparseMatrix.ofColumns(
    source.rows,
    source.cols + source.rows,
    List(source.cols + source.rows) { column ->
        if (column >= source.cols) {
            listOf(column - source.cols to 1.0)
        } else {
            buildList { source.forEachInColumn(column) { row, value -> add(row to value) } }
        }
    },
)

private fun matrix(values: Array<DoubleArray>): SparseMatrix = SparseMatrix.ofColumns(
    values.size,
    values.firstOrNull()?.size ?: 0,
    List(values.firstOrNull()?.size ?: 0) { column ->
        values.indices.filter { values[it][column] != 0.0 }.map { it to values[it][column] }
    },
)
