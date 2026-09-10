package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.BasisExtension
import com.eignex.klause.simplex.basis.BasisSolver
import com.eignex.klause.simplex.basis.IndexedVector
import com.eignex.klause.simplex.basis.KotlinBasisSolver
import com.eignex.klause.simplex.basis.assertBasisSolves
import com.eignex.koblas.SparseMatrix
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BasisExtensionAdapterTest {
    @Test
    fun `adapter transfers append and reports owner and source headings separately`() {
        val oldMatrix = identityWithLogicals(2)
        val old = KotlinBasisSolver(oldMatrix)
        val owner = assertNotNull(old.refactorizeRepairing(intArrayOf(0, 0)))
        val newMatrix = identityWithLogicals(3)
        val request = BasisExtension(owner.columns, owner.unitRows, intArrayOf(0, 1), intArrayOf(0, 1, 3, 4))
        val intended = IntArray(3) { slot ->
            when {
                slot == 2 -> 5
                owner.columns[slot] >= 0 -> owner.columns[slot]
                else -> 3 + owner.unitRows[slot]
            }
        }

        val replacement = assertNotNull(
            BasisExtensionAdapter().replacement(old, newMatrix, intended, intArrayOf(3, 4, 5), request),
        )

        assertTrue(replacement.transferred)
        assertFalse(replacement.transferArithmeticDeclined)
        assertContentEquals(intended, replacement.sourceHeadings)
        assertTrue(replacement.ownerBasis.unitRows.any { it >= 0 })
        assertBasisSolves(
            replacement.solver,
            newMatrix,
            replacement.ownerBasis.columns,
            replacement.ownerBasis.unitRows,
        )
        old.close()
        assertBasisSolves(
            replacement.solver,
            newMatrix,
            replacement.ownerBasis.columns,
            replacement.ownerBasis.unitRows,
        )
    }

    @Test
    fun `adapter falls back when extension is unavailable or incompatible`() {
        val oldMatrix = identityWithLogicals(2)
        val old = object : BasisSolver by KotlinBasisSolver(oldMatrix) {}
        assertTrue(old.refactorize(intArrayOf(0, 1)))
        val newMatrix = identityWithLogicals(3)
        val intended = intArrayOf(0, 1, 2)

        val replacement = assertNotNull(
            BasisExtensionAdapter().replacement(
                old,
                newMatrix,
                intended,
                intArrayOf(3, 4, 5),
                BasisExtension(intArrayOf(0, 1), intArrayOf(-1, -1), intArrayOf(0, 1), intArrayOf(0, 1, 2, 3)),
            ),
        )

        assertFalse(replacement.transferred)
        assertContentEquals(intended, replacement.ownerBasis.columns)
        assertBasisSolves(replacement.solver, newMatrix, intended, IntArray(3) { -1 })
    }

    @Test
    fun `physical pop tombstone and compaction use fresh replacements`() {
        val three = identityWithLogicals(3)
        var current: BasisSolver = KotlinBasisSolver(three).also { assertTrue(it.refactorize(intArrayOf(0, 1, 2))) }
        val adapter = BasisExtensionAdapter()
        val tombstoned = assertNotNull(
            adapter.replacement(current, three, intArrayOf(0, 1, 5), intArrayOf(3, 4, 5)),
        )
        assertFalse(tombstoned.transferred)
        current.close()
        current = tombstoned.solver
        val compactedMatrix = identityWithLogicals(2)
        val compacted = assertNotNull(
            adapter.replacement(current, compactedMatrix, intArrayOf(0, 1), intArrayOf(2, 3)),
        )
        assertFalse(compacted.transferred)
        current.close()
        current = compacted.solver
        val empty = SparseMatrix.ofColumns(0, 0, emptyList())
        val popped = assertNotNull(adapter.replacement(current, empty, IntArray(0), IntArray(0)))
        assertFalse(popped.transferred)
        current.close()
        popped.solver.ftran(IndexedVector(0), 0.0)
    }

    @Test
    fun `logical mappings reject scaled wrong row extra and stored zero columns`() {
        val old = KotlinBasisSolver(identityWithLogicals(1)).also { assertTrue(it.refactorize(intArrayOf(0))) }
        val bad = listOf(
            SparseMatrix.ofColumns(1, 1, listOf(listOf(0 to 2.0))) to intArrayOf(0),
            SparseMatrix.ofColumns(2, 2, listOf(listOf(1 to 1.0), listOf(0 to 1.0))) to intArrayOf(0, 1),
            SparseMatrix.ofColumns(2, 2, listOf(listOf(0 to 1.0, 1 to 1.0), listOf(1 to 1.0))) to intArrayOf(0, 1),
            SparseMatrix.wrap(2, 2, intArrayOf(0, 2, 3), intArrayOf(0, 1, 1), doubleArrayOf(1.0, 0.0, 1.0)) to
                intArrayOf(0, 1),
        )

        for ((matrix, logicals) in bad) {
            assertFailsWith<IllegalArgumentException> {
                BasisExtensionAdapter().replacement(
                    old,
                    matrix,
                    IntArray(matrix.rows) { logicals[it] },
                    logicals,
                )
            }
        }
    }

    @Test
    fun `typed transfer arithmetic declines to fresh while unrelated failures propagate`() {
        val oldMatrix = identityWithLogicals(1)
        val old = KotlinBasisSolver(oldMatrix).also { assertTrue(it.refactorize(intArrayOf(0))) }
        val newMatrix = SparseMatrix.ofColumns(
            2,
            4,
            listOf(
                listOf(0 to 1.0, 1 to Double.POSITIVE_INFINITY),
                listOf(0 to 1.0),
                listOf(0 to 1.0),
                listOf(1 to 1.0),
            ),
        )
        val request = BasisExtension(intArrayOf(0), intArrayOf(-1), intArrayOf(0), intArrayOf(0, 1))

        val replacement = assertNotNull(
            BasisExtensionAdapter().replacement(old, newMatrix, intArrayOf(2, 3), intArrayOf(2, 3), request),
        )

        assertFalse(replacement.transferred)
        assertTrue(replacement.transferArithmeticDeclined)
        val unrelated = object : BasisSolver by old {
            override fun extend(matrix: SparseMatrix, extension: BasisExtension) = error("unrelated")
        }
        assertFailsWith<IllegalStateException> {
            BasisExtensionAdapter().replacement(unrelated, newMatrix, intArrayOf(2, 3), intArrayOf(2, 3), request)
        }
    }

    @Test
    fun `failed fresh construction closes the rejected owner and preserves old`() {
        val oldMatrix = identityWithLogicals(1)
        val old = KotlinBasisSolver(oldMatrix).also { assertTrue(it.refactorize(intArrayOf(0))) }
        var made: TrackingBasisSolver? = null
        val singular = SparseMatrix.ofColumns(1, 2, listOf(emptyList(), listOf(0 to 1.0)))
        val adapter = BasisExtensionAdapter { matrix ->
            TrackingBasisSolver(
                KotlinBasisSolver(matrix),
            ).also { made = it }
        }

        assertNull(adapter.replacement(old, singular, intArrayOf(0), intArrayOf(1)))

        assertTrue(assertNotNull(made).closed)
        assertBasisSolves(old, oldMatrix, intArrayOf(0), intArrayOf(-1))
    }
}

private class TrackingBasisSolver(private val delegate: BasisSolver) : BasisSolver by delegate {
    var closed = false
        private set

    override fun close() {
        closed = true
        delegate.close()
    }
}

private fun identityWithLogicals(n: Int): SparseMatrix = SparseMatrix.ofColumns(
    n,
    2 * n,
    List(2 * n) { column -> listOf((column % n) to 1.0) },
)
