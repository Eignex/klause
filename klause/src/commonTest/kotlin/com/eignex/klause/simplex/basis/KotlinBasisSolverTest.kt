package com.eignex.klause.simplex.basis

import com.eignex.koblas.SparseMatrix
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KotlinBasisSolverTest {
    @Test
    fun `updated factor forks preserve independent sibling bases and lifetimes`() {
        val source = ftSource("dense", 8)
        val parentBasis = IntArray(8) { it }
        val parent = KotlinBasisSolver(source, fillFactor = 100.0)
        assertTrue(parent.refactorize(parentBasis))
        val entering = IndexedVector(8).also { it.scatterColumn(source, 9) }
        parent.ftran(entering)
        assertEquals(BasisUpdate.APPLIED, parent.update(1, 9, entering))
        parentBasis[1] = 9
        val offer = assertNotNull(parent.factorCopy)
        val first = assertNotNull(offer.create(parentBasis, IntArray(8) { -1 }, LuPivotPolicy(), offer.additionalWork))
        val second = assertNotNull(offer.create(parentBasis, IntArray(8) { -1 }, LuPivotPolicy(), offer.additionalWork))
        val firstBasis = parentBasis.copyOf()
        val secondBasis = parentBasis.copyOf()
        try {
            for ((owner, headings, slot) in listOf(
                Triple(parent, parentBasis, 2), Triple(first, firstBasis, 0), Triple(second, secondBasis, 3),
            )) {
                val spike = IndexedVector(8).also { it.scatterColumn(source, slot + 8) }
                owner.ftran(spike)
                assertEquals(BasisUpdate.APPLIED, owner.update(slot, slot + 8, spike))
                headings[slot] = slot + 8
            }
            for ((owner, headings) in listOf(parent to parentBasis, first to firstBasis, second to secondBasis)) {
                for (transpose in listOf(false, true)) {
                    val rhs = DoubleArray(8) { it + 1.0 }
                    val solved = IndexedVector(8).also { it.scatter(rhs) }
                    if (transpose) owner.btran(solved) else owner.ftran(solved)
                    assertTrue(ftResidual(source, headings, rhs, solved, transpose) <= 1e-10)
                }
            }
            assertTrue(first.refactorize(IntArray(8) { it }))
            first.close()
            assertTrue(parent.refactorize(IntArray(8) { it }))
            parent.close()
            for (transpose in listOf(false, true)) {
                val rhs = DoubleArray(8) { it + 1.0 }
                val solved = IndexedVector(8).also { it.scatter(rhs) }
                if (transpose) second.btran(solved) else second.ftran(solved)
                assertTrue(ftResidual(source, secondBasis, rhs, solved, transpose) <= 1e-10)
            }
        } finally {
            first.close()
            second.close()
            parent.close()
        }
    }

    @Test
    fun `copied owners rebuild different bases after input mutation and producer retirement`() {
        val source = ftSource("dense", 3)
        val original = ftSource("dense", 3)
        val parent = KotlinBasisSolver(source)
        val headings = intArrayOf(0, 1, 2)
        assertTrue(parent.refactorize(headings))
        val offer = assertNotNull(parent.factorCopy)
        val first = assertNotNull(offer.create(headings, IntArray(3) { -1 }, LuPivotPolicy(), offer.additionalWork))
        val second = assertNotNull(offer.create(headings, IntArray(3) { -1 }, LuPivotPolicy(), offer.additionalWork))
        source.values.fill(Double.NaN)
        parent.close()
        try {
            val alternative = intArrayOf(0, 4, 2)
            assertTrue(first.refactorize(headings))
            assertTrue(second.refactorize(alternative))
            first.close()
            for (transpose in listOf(false, true)) {
                val rhs = doubleArrayOf(1.0, 2.0, 3.0)
                val solved = IndexedVector(3).also { it.scatter(rhs) }
                if (transpose) second.btran(solved) else second.ftran(solved)
                assertTrue(ftResidual(original, alternative, rhs, solved, transpose) <= 1e-10)
            }
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun `live factor copies solve updated bases without rebuilding or charging the parent`() {
        val source = ftSource("dense", 8)
        val headings = IntArray(8) { it }
        KotlinBasisSolver(source, fillFactor = 100.0).use { parent ->
            assertTrue(parent.refactorize(headings))
            val spike = IndexedVector(8).also { it.scatterColumn(source, 9) }
            parent.ftran(spike)
            assertEquals(BasisUpdate.APPLIED, parent.update(1, 9, spike))
            headings[1] = 9
            val before = parent.basisOperationWork
            val offer = assertNotNull(parent.factorCopy)

            val privateSolver = offer.create(headings, IntArray(8) { -1 }, LuPivotPolicy(), offer.additionalWork)
            assertNotNull(privateSolver).use { child ->
                assertEquals(parent.updateCount, child.updateCount)
                assertEquals(parent.nnz, child.nnz)
                assertNull(child.ordering())
                assertNull(assertNotNull(child.basisWork).build)
                for (transpose in listOf(false, true)) {
                    val rhs = DoubleArray(8) { it + 1.0 }
                    val actual = IndexedVector(8).also { it.scatter(rhs) }
                    if (transpose) child.btran(actual) else child.ftran(actual)
                    assertTrue(ftResidual(source, headings, rhs, actual, transpose) <= 1e-10)
                }
                assertEquals(before, parent.basisOperationWork)
            }
        }
    }

    @Test
    fun `live factor copying costs less than rebuilding the same dense basis`() {
        val n = 24
        val source = ftSource("dense", n)
        val headings = IntArray(n) { it }
        KotlinBasisSolver(source, denseDimensionLimit = 0).use { parent ->
            assertTrue(parent.refactorize(headings))
            val offer = assertNotNull(parent.factorCopy)
            KotlinBasisSolver(source, denseDimensionLimit = 0).use { cold ->
                assertTrue(cold.refactorize(headings))
                // Source/workspace construction is funded separately from this factor-copy work.
                assertTrue(offer.additionalWork + 6L * n + 64L < assertNotNull(cold.basisWork.build).units)
                val privateSolver = offer.create(headings, IntArray(n) { -1 }, LuPivotPolicy(), offer.additionalWork)
                assertNotNull(privateSolver).use { child ->
                    assertEquals(cold.nnz, child.nnz)
                    for (transpose in listOf(false, true)) {
                        val rhs = DoubleArray(n) { it + 1.0 }
                        val actual = IndexedVector(n).also { it.scatter(rhs) }
                        if (transpose) child.btran(actual) else child.ftran(actual)
                        assertTrue(ftResidual(source, headings, rhs, actual, transpose) <= 1e-10)
                    }
                }
            }
        }
    }

    @Test
    fun `copied factors retain update limits and survive parent retirement`() {
        val source = ftSource("dense", 3)
        val headings = intArrayOf(0, 1, 2)
        val parent = KotlinBasisSolver(source, updateLimit = 2, fillFactor = 100.0)
        assertTrue(parent.refactorize(headings))
        val spike = IndexedVector(3).also { it.scatterColumn(source, 4) }
        parent.ftran(spike)
        assertEquals(BasisUpdate.APPLIED, parent.update(1, 4, spike))
        headings[1] = 4
        val offer = assertNotNull(parent.factorCopy)
        assertNotNull(offer.create(headings, IntArray(3) { -1 }, LuPivotPolicy(), offer.additionalWork)).use { child ->
            assertTrue(parent.refactorize(intArrayOf(0, 1, 2)))
            parent.close()
            val rhs = doubleArrayOf(1.0, 2.0, 3.0)
            val solved = IndexedVector(3).also { it.scatter(rhs) }
            child.ftran(solved)
            assertTrue(ftResidual(source, headings, rhs, solved, false) <= 1e-10)
            val entering = IndexedVector(3).also { it.scatterColumn(source, 3) }
            child.ftran(entering)
            assertEquals(BasisUpdate.REFACTORIZE, child.update(0, 3, entering))
            assertEquals(2, child.updateCount)
        }
    }

    @Test
    fun `private pivots and close preserve the parent factors and meters`() {
        val source = ftSource("dense", 3)
        val headings = intArrayOf(0, 1, 2)
        KotlinBasisSolver(source).use { parent ->
            assertTrue(parent.refactorize(headings))
            val before = parent.basisOperationWork
            val offer = assertNotNull(parent.factorCopy)
            val privateSolver = offer.create(headings, IntArray(3) { -1 }, LuPivotPolicy(), offer.additionalWork)
            assertNotNull(privateSolver).use { child ->
                val spike = IndexedVector(3).also { it.scatterColumn(source, 4) }
                child.ftran(spike)
                assertEquals(BasisUpdate.APPLIED, child.update(1, 4, spike))
                assertTrue(child.refactorize(intArrayOf(0, 4, 2)))
            }
            assertEquals(before, parent.basisOperationWork)
            assertEquals(0, parent.updateCount)
            for (transpose in listOf(false, true)) {
                val rhs = doubleArrayOf(1.0, 2.0, 3.0)
                val solved = IndexedVector(3).also { it.scatter(rhs) }
                if (transpose) parent.btran(solved) else parent.ftran(solved)
                assertTrue(ftResidual(source, headings, rhs, solved, transpose) <= 1e-10)
            }
        }
    }

    @Test
    fun `factor copies use the owned source after caller mutation`() {
        val source = ftSource("dense", 3)
        val original = ftSource("dense", 3)
        val headings = intArrayOf(0, 1, 2)
        KotlinBasisSolver(source).use { parent ->
            assertTrue(parent.refactorize(headings))
            val offer = assertNotNull(parent.factorCopy)
            source.values.fill(Double.NaN)
            val privateSolver = offer.create(headings, IntArray(3) { -1 }, LuPivotPolicy(), offer.additionalWork)
            assertNotNull(privateSolver).use { child ->
                parent.close()
                assertTrue(child.refactorize(headings))
                val rhs = doubleArrayOf(1.0, 2.0, 3.0)
                val solved = IndexedVector(3).also { it.scatter(rhs) }
                child.btran(solved)
                assertTrue(ftResidual(original, headings, rhs, solved, true) <= 1e-10)
            }
        }
    }

    @Test
    fun `factor copy admission rejects insufficient work storage and different keys`() {
        val source = ftSource("dense", 3)
        KotlinBasisSolver(source).use { parent ->
            val headings = intArrayOf(0, 1, 2)
            val units = IntArray(3) { -1 }
            assertTrue(parent.refactorize(headings))
            val before = parent.basisOperationWork
            val offer = assertNotNull(parent.factorCopy)

            assertNull(offer.create(headings, units, LuPivotPolicy(), offer.additionalWork - 1L))
            assertNull(offer.create(headings, units,
                LuPivotPolicy(maxEntries = offer.entries - 1L), offer.additionalWork))
            assertNull(offer.create(intArrayOf(0, 4, 2), units, LuPivotPolicy(), offer.additionalWork))
            assertNull(offer.create(headings, intArrayOf(0, -1, -1), LuPivotPolicy(), offer.additionalWork))
            assertEquals(before, parent.basisOperationWork)
        }
    }

    @Test
    fun `factor offers become stale after updates refactors and close`() {
        for (change in listOf("update", "refactor", "singular", "close")) {
            val parent = KotlinBasisSolver(ftSource("dense", 3))
            val headings = intArrayOf(0, 1, 2)
            assertNull(parent.factorCopy)
            assertTrue(parent.refactorize(headings))
            val offer = assertNotNull(parent.factorCopy)
            when (change) {
                "update" -> {
                    val spike = IndexedVector(3).also { it.scatterColumn(ftSource("dense", 3), 4) }
                    parent.ftran(spike)
                    assertEquals(BasisUpdate.APPLIED, parent.update(1, 4, spike))
                }
                "refactor" -> assertTrue(parent.refactorize(headings))
                "singular" -> assertFalse(parent.refactorize(intArrayOf(0, 0, 0)))
                else -> parent.close()
            }

            assertNull(offer.create(headings, IntArray(3) { -1 }, LuPivotPolicy(), offer.additionalWork))
            if (change == "singular" || change == "close") assertNull(parent.factorCopy)
            parent.close()
        }
    }

    @Test
    fun `cancellation during factor copying publishes no child and leaves the parent usable`() {
        val source = ftSource("dense", 3)
        KotlinBasisSolver(source).use { parent ->
            val headings = intArrayOf(0, 1, 2)
            val units = IntArray(3) { -1 }
            assertTrue(parent.refactorize(headings))
            val offer = assertNotNull(parent.factorCopy)
            var totalPolls = 0
            assertNotNull(offer.create(headings, units,
                LuPivotPolicy(cancelled = { totalPolls++; false }), offer.additionalWork)).close()
            for (stopAt in listOf(1, maxOf(1, totalPolls / 2), totalPolls).distinct()) {
                val before = parent.basisOperationWork
                var polls = 0
                val policy = LuPivotPolicy(cancelled = { ++polls >= stopAt })

                assertNull(offer.create(headings, units, policy, offer.additionalWork))

                assertEquals(before, parent.basisOperationWork)
                val rhs = doubleArrayOf(1.0, 2.0, 3.0)
                val solved = IndexedVector(3).also { it.scatter(rhs) }
                parent.ftran(solved)
                assertTrue(ftResidual(source, headings, rhs, solved, false) <= 1e-10)
            }
        }
    }

    @Test
    fun `a parent revision during copy cancellation polling prevents publication`() {
        KotlinBasisSolver(ftSource("dense", 3)).use { parent ->
            val headings = intArrayOf(0, 1, 2)
            assertTrue(parent.refactorize(headings))
            val offer = assertNotNull(parent.factorCopy)
            var polls = 0
            val policy = LuPivotPolicy(cancelled = {
                if (++polls == 3) assertTrue(parent.refactorize(headings))
                false
            })

            assertNull(offer.create(headings, IntArray(3) { -1 }, policy, offer.additionalWork))
            assertNotNull(parent.factorCopy)
        }
    }

    @Test
    fun `factor copies preserve repaired unit row keys`() {
        val source = SparseMatrix.ofColumns(3, 3, listOf(listOf(1 to 1.0), listOf(1 to 1.0), emptyList()))
        KotlinBasisSolver(source).use { parent ->
            val repair = assertNotNull(parent.refactorizeRepairing(intArrayOf(0, 1, 2)))
            val offer = assertNotNull(parent.factorCopy)
            val privateSolver = offer.create(repair.columns, repair.unitRows, LuPivotPolicy(), offer.additionalWork)
            assertNotNull(privateSolver).use { child ->
                for (transpose in listOf(false, true)) {
                    val expected = IndexedVector(3).also { it.scatter(doubleArrayOf(1.0, 2.0, 3.0)) }
                    val actual = IndexedVector(3).also { it.scatter(doubleArrayOf(1.0, 2.0, 3.0)) }
                    if (transpose) { parent.btran(expected); child.btran(actual) } else {
                        parent.ftran(expected); child.ftran(actual)
                    }
                    assertContentEquals(expected.toDoubleArray(), actual.toDoubleArray())
                }
            }
        }
    }

    @Test
    fun `a retained hint reduces private construction work including bounded import`() {
        val n = 24
        val source = ftSource("dense", n)
        val headings = IntArray(n) { it }
        KotlinBasisSolver(source, denseDimensionLimit = 0).use { parent ->
            assertTrue(parent.refactorize(headings))
            val before = parent.basisOperationWork
            val hint = assertNotNull(parent.orderHint())
            KotlinBasisSolver(source, denseDimensionLimit = 0).use { cold ->
                KotlinBasisSolver(source, denseDimensionLimit = 0, initialOrder = hint).use { seeded ->
                    assertTrue(cold.refactorize(headings))
                    assertTrue(seeded.refactorize(headings))
                    val coldBuild = assertNotNull(cold.basisWork.build)
                    val privateBuild = assertNotNull(seeded.basisWork.build)
                    val copiedPreparation = 24L * n + 64L

                    assertEquals(1L, privateBuild.reusedOrders)
                    assertTrue(privateBuild.units + copiedPreparation < coldBuild.units,
                        "private=$privateBuild capture=$copiedPreparation cold=$coldBuild")
                    for (transpose in listOf(false, true)) {
                        val rhs = DoubleArray(n) { it + 1.0 }
                        val expected = IndexedVector(n).also { it.scatter(rhs) }
                        val actual = IndexedVector(n).also { it.scatter(rhs) }
                        if (transpose) { cold.btran(expected); seeded.btran(actual) } else {
                            cold.ftran(expected); seeded.ftran(actual)
                        }
                        for (i in 0 until n) assertEquals(expected[i], actual[i], 1e-10)
                    }
                    assertEquals(before, parent.basisOperationWork)
                }
            }
        }
    }

    @Test
    fun `private order imports survive hint mutation and parent close`() {
        val source = ftSource("sparse", 8)
        val headings = IntArray(8) { it }
        val parent = KotlinBasisSolver(source)
        assertTrue(parent.refactorize(headings))
        val hint = assertNotNull(parent.orderHint())
        KotlinBasisSolver(source, initialOrder = hint).use { seeded ->
            hint.rowOrder.fill(-1)
            hint.columnOrder.fill(-1)
            assertNotNull(parent.orderHint())
            parent.close()

            assertTrue(seeded.refactorize(headings))

            assertEquals(1L, assertNotNull(seeded.basisWork.build).reusedOrders)
            val rhs = DoubleArray(8) { it + 1.0 }
            val solved = IndexedVector(8).also { it.scatter(rhs) }
            seeded.ftran(solved)
            assertTrue(ftResidual(source, headings, rhs, solved, transpose = false) <= 1e-10)
        }
    }

    @Test
    fun `updated bases offer hints without exporting exact order authority`() {
        val source = ftSource("dense", 3)
        KotlinBasisSolver(source).use { parent ->
            assertTrue(parent.refactorize(intArrayOf(0, 1, 2)))
            val spike = IndexedVector(3).also { it.scatterColumn(source, 4) }
            parent.ftran(spike)
            assertEquals(BasisUpdate.APPLIED, parent.update(1, 4, spike))

            assertNotNull(parent.orderHint())
            assertEquals(3, parent.orderHintDimension)
            assertNull(parent.ordering())
        }
    }

    @Test
    fun `a deficient imported hint falls back with its failed work retained`() {
        val source = SparseMatrix.ofColumns(2, 4, listOf(
            listOf(0 to 1.0), listOf(1 to 1.0), listOf(1 to 1.0), listOf(0 to 1.0, 1 to 1.0),
        ))
        val coldWork = KotlinBasisSolver(source, denseDimensionLimit = 0).use { cold ->
            assertTrue(cold.refactorize(intArrayOf(2, 3)))
            assertNotNull(cold.basisWork.build).units
        }
        KotlinBasisSolver(source, denseDimensionLimit = 0).use { parent ->
            assertTrue(parent.refactorize(intArrayOf(0, 1)))
            KotlinBasisSolver(source, denseDimensionLimit = 0, initialOrder = parent.orderHint()).use { seeded ->
                assertTrue(seeded.refactorize(intArrayOf(2, 3)))

                val build = assertNotNull(seeded.basisWork.build)
                assertEquals(1L, build.fallbacks)
                assertEquals(0L, build.reusedOrders)
                val rhs = IndexedVector(2).also { it.scatter(doubleArrayOf(1.0, 2.0)) }
                seeded.ftran(rhs)
                assertContentEquals(doubleArrayOf(1.0, 1.0), rhs.toDoubleArray())
                assertTrue(build.units > coldWork)
            }
        }
    }

    @Test
    fun `empty solves should retain zero support without visiting updated factors`() {
        val source = ftSource("dense", 3)
        KotlinBasisSolver(source).use { solver ->
            assertTrue(solver.refactorize(intArrayOf(0, 1, 2)))
            val spike = IndexedVector(3).also { it.scatterColumn(source, 4) }
            solver.ftran(spike)
            assertEquals(BasisUpdate.APPLIED, solver.update(1, 4, spike))
            for (transpose in listOf(false, true)) {
                val vector = IndexedVector(3)
                val before = if (transpose) solver.basisWork.btran else solver.basisWork.ftran

                if (transpose) solver.btran(vector, 1.0) else solver.ftran(vector, 1.0)

                assertContentEquals(DoubleArray(3), vector.toDoubleArray())
                val after = if (transpose) solver.basisWork.btran else solver.basisWork.ftran
                assertEquals(before.attempts + 1, after.attempts)
                assertEquals(before.successes + 1, after.successes)
                assertEquals(before.units, after.units)
                assertEquals(0L, assertNotNull(solver.lastSolveWork).units)
            }
        }
    }

    @Test
    fun `empty solves should require usable open factors`() {
        val source = ftSource("dense", 3)
        val solver = KotlinBasisSolver(source)
        val vector = IndexedVector(3)
        assertFailsWith<IllegalStateException> { solver.ftran(vector) }
        assertFailsWith<IllegalStateException> { solver.btran(vector) }
        assertTrue(solver.refactorize(intArrayOf(0, 1, 2)))
        assertFalse(solver.refactorize(intArrayOf(0, 0, 0)))
        assertFailsWith<IllegalStateException> { solver.ftran(vector) }
        assertFailsWith<IllegalStateException> { solver.btran(vector) }
        solver.close()
        assertFailsWith<IllegalStateException> { solver.ftran(vector) }
        assertFailsWith<IllegalStateException> { solver.btran(vector) }
    }

    @Test
    fun `declined solve work should be retained until the next build`() {
        val source = SparseMatrix.ofColumns(1, 1, listOf(listOf(0 to 1e-200)))
        val solver = KotlinBasisSolver(source, LuPivotPolicy(absoluteTolerance = 0.0))
        assertTrue(solver.refactorize(intArrayOf(0)))
        val vector = IndexedVector(1).also { it.store(0, 1e200) }
        assertFailsWith<BasisArithmeticException> { solver.ftran(vector) }
        val declined = solver.basisWork
        assertEquals(1, declined.ftran.declines)
        assertTrue(declined.ftran.units > 0)
        assertFalse(solver.basisOperationWork.complete)
        assertTrue(solver.basisOperationWork.ftran.units > 0)
        vector.scatter(doubleArrayOf(1e-200))
        solver.ftran(vector)

        assertTrue(solver.refactorize(intArrayOf(0)))
        assertEquals(BasisPhaseWork(), solver.basisWork.ftran)
        assertTrue(solver.basisOperationWork.ftran.units >= declined.ftran.units)
        solver.close()
    }

    @Test
    fun `seeded updates should solve permuted sparse bases in both directions`() {
        assertSeededUpdatesSolve("sparse", 8)
    }

    @Test
    fun `singular update should preserve the prior basis`() {
        val source = SparseMatrix.ofColumns(
            2,
            5,
            listOf(
                listOf(0 to 1.0),
                listOf(1 to 1.0),
                listOf(0 to 1.0, 1 to 1.0),
                listOf(0 to 1e-12),
                listOf(0 to Double.NaN),
            ),
        )
        val solver = KotlinBasisSolver(source)
        assertTrue(solver.refactorize(intArrayOf(0, 1)))
        val spike = IndexedVector(2).also { it.scatterColumn(source, 2) }
        solver.ftran(spike)
        assertEquals(BasisUpdate.APPLIED, solver.update(0, 2, spike))
        for (entering in listOf(1, 3, 4)) {
            spike.scatterColumn(source, entering)
            if (entering != 4) solver.ftran(spike)
            val before = solver.nnz

            assertEquals(BasisUpdate.SINGULAR, solver.update(0, entering, spike))

            assertEquals(1, solver.updateCount)
            assertEquals(before, solver.nnz)
            assertNull(solver.refactorizeReason)
            val rhs = doubleArrayOf(3.0, 4.0)
            for (transpose in listOf(false, true)) {
                val solved = IndexedVector(2).also { it.scatter(rhs) }
                if (transpose) solver.btran(solved) else solver.ftran(solved)
                assertEquals(0.0, sourceResidual(source, intArrayOf(2, 1), rhs, solved, transpose))
            }
        }
    }

    @Test
    fun `failed refactorization should invalidate factors until a successful rebuild`() {
        val source = SparseMatrix.ofColumns(2, 3, listOf(listOf(0 to 1.0), listOf(1 to 1.0), listOf(0 to 2.0)))
        val solver = KotlinBasisSolver(source)
        assertTrue(solver.refactorize(intArrayOf(0, 1)))
        val spike = IndexedVector(2).also { it.scatterColumn(source, 2) }
        solver.ftran(spike)
        assertEquals(BasisUpdate.APPLIED, solver.update(0, 2, spike))
        assertFalse(solver.refactorize(intArrayOf(2, 2)))
        assertTrue(solver.singular)
        assertEquals(0, solver.nnz)
        assertEquals(0.0, solver.rcond)
        assertFailsWith<IllegalStateException> { solver.ftran(spike) }
        assertFailsWith<IllegalStateException> { solver.btran(spike) }
        assertEquals(BasisUpdate.SINGULAR, solver.update(0, 0, spike))
        assertTrue(solver.refactorize(intArrayOf(1, 0)))
        assertEquals(0, solver.updateCount)
    }

    @Test
    fun `arithmetic failure should leave the argument intact and recover scratch`() {
        for ((pivot, rhs) in listOf(1e-200 to 1e200, 1e200 to 1e-200)) {
            val matrix = SparseMatrix.ofColumns(1, 1, listOf(listOf(0 to pivot)))
            val solver = KotlinBasisSolver(matrix, LuPivotPolicy(absoluteTolerance = 0.0))
            assertTrue(solver.refactorize(intArrayOf(0)))
            for (transpose in listOf(false, true)) {
                val vector = IndexedVector(1).also { it.store(0, rhs) }
                val before = if (transpose) solver.basisWork.btran else solver.basisWork.ftran
                assertFailsWith<BasisArithmeticException> {
                    if (transpose) solver.btran(vector) else solver.ftran(vector)
                }
                assertEquals(rhs, vector[0])
                val declined = if (transpose) solver.basisWork.btran else solver.basisWork.ftran
                assertEquals(before.attempts + 1, declined.attempts)
                assertEquals(before.successes, declined.successes)
                assertTrue(declined.units > before.units)
                vector.scatter(doubleArrayOf(pivot))
                if (transpose) solver.btran(vector) else solver.ftran(vector)
                assertEquals(1.0, vector[0])
                val recovered = if (transpose) solver.basisWork.btran else solver.basisWork.ftran
                assertEquals(before.attempts + 2, recovered.attempts)
                assertEquals(before.successes + 1, recovered.successes)
            }
        }
    }

    @Test
    fun `tiny update pivot should solve against the entering source column`() {
        val source = SparseMatrix.ofColumns(1, 2, listOf(listOf(0 to 1.0), listOf(0 to 1e-200)))
        val solver = KotlinBasisSolver(source, LuPivotPolicy(absoluteTolerance = 0.0))
        assertTrue(solver.refactorize(intArrayOf(0)))
        val spike = IndexedVector(1).also { it.scatterColumn(source, 1) }
        solver.ftran(spike)

        assertEquals(BasisUpdate.APPLIED, solver.update(0, 1, spike))

        for (transpose in listOf(false, true)) {
            val vector = IndexedVector(1).also { it.store(0, 1e-200) }
            if (transpose) solver.btran(vector) else solver.ftran(vector)
            assertEquals(1.0, vector[0])
            assertEquals(0.0, sourceResidual(source, intArrayOf(1), doubleArrayOf(1e-200), vector, transpose))
        }
    }

    @Test
    fun `unrepresentable spike normalization should decline without changing the basis`() {
        for ((pivot, offDiagonal) in listOf(1e-200 to 1e200, 1e200 to 1e-200, 1e-320 to 1.0)) {
            val source = SparseMatrix.ofColumns(
                2,
                3,
                listOf(
                    listOf(0 to 1.0),
                    listOf(1 to 1.0),
                    listOf(0 to pivot, 1 to offDiagonal),
                ),
            )
            val solver = KotlinBasisSolver(source, LuPivotPolicy(absoluteTolerance = 0.0))
            assertTrue(solver.refactorize(intArrayOf(0, 1)))
            val spike = IndexedVector(2).also { it.scatterColumn(source, 2) }
            solver.ftran(spike)

            assertEquals(BasisUpdate.SINGULAR, solver.update(0, 2, spike))

            assertEquals(0, solver.updateCount)
            val rhs = doubleArrayOf(2.0, 3.0)
            spike.scatter(rhs)
            solver.ftran(spike)
            assertContentEquals(rhs, spike.toDoubleArray())
        }
    }

    @Test
    fun `ordering snapshot should own its inputs and returned arrays`() {
        val source = ftSource("dense", 3)
        val solver = KotlinBasisSolver(source)
        assertTrue(solver.refactorize(intArrayOf(2, 0, 1)))
        val order = assertNotNull(solver.ordering())
        val rows = order.rows
        val slots = order.slots
        order.columns.fill(-99)
        order.unitRows.fill(-99)
        order.rows.fill(-99)
        order.slots.fill(-99)
        assertTrue(solver.refactorize(intArrayOf(0, 1, 2)))
        solver.close()

        assertContentEquals(intArrayOf(2, 0, 1), order.columns)
        assertContentEquals(intArrayOf(-1, -1, -1), order.unitRows)
        assertContentEquals(rows, order.rows)
        assertContentEquals(slots, order.slots)
        assertNull(solver.ordering())
        val inputs = intArrayOf(0)
        val copied = BasisOrdering(inputs, intArrayOf(-1), inputs, inputs)
        inputs[0] = 5
        assertContentEquals(intArrayOf(0), copied.rows)
        assertContentEquals(intArrayOf(0), copied.slots)
        assertContentEquals(intArrayOf(0), copied.columns)
    }

    @Test
    fun `ordering offer should decline after accepted updates and survive rejected ones`() {
        val source = ftSource("dense", 3)
        KotlinBasisSolver(source, updateLimit = 1).use { solver ->
            val headings = intArrayOf(0, 1, 2)
            assertNull(solver.ordering())
            assertTrue(solver.refactorize(headings))
            assertEquals(BasisUpdate.SINGULAR, solver.update(1, 4, IndexedVector(3)))
            assertNotNull(solver.ordering())
            val spike = IndexedVector(3).also { it.scatterColumn(source, 4) }
            solver.ftran(spike)

            assertEquals(BasisUpdate.REFACTORIZE, solver.update(1, 4, spike))
            assertNull(solver.ordering())
            headings[1] = 4
            assertTrue(solver.refactorize(headings))
            assertContentEquals(headings, assertNotNull(solver.ordering()).columns)
        }
    }

    @Test
    fun `repair should export logical columns and failed rebuild should retire the order`() {
        val source = SparseMatrix.ofColumns(3, 3, listOf(listOf(1 to 1.0), listOf(1 to 1.0), emptyList()))
        KotlinBasisSolver(source).use { solver ->
            val repair = assertNotNull(solver.refactorizeRepairing(intArrayOf(0, 1, 2)))
            val order = assertNotNull(solver.ordering())
            assertTrue(repair.repaired)
            assertContentEquals(repair.columns, order.columns)
            assertContentEquals(repair.unitRows, order.unitRows)

            assertFalse(solver.refactorize(intArrayOf(0, 1, 2)))
            assertNull(solver.ordering())
        }
    }

    private fun assertSeededUpdatesSolve(shape: String, n: Int) {
        val original = Array(n) { i ->
            DoubleArray(n) { j ->
                when {
                    shape == "unit" -> if (i == j) 1.0 else 0.0
                    i == j -> (n + 2).toDouble()
                    shape == "triangular" && i < j -> ((i + j) % 3 - 1).toDouble()
                    shape == "sparse" && (j == (i + 1) % n || i == (j + 1) % n) -> -1.0
                    shape == "spiked" && (i == 2 || j == 5) -> 1.0
                    shape == "dense" -> ((i * 3 + j * 5) % 7 - 3).toDouble()
                    else -> 0.0
                }
            }
        }
        val permutation = Random(23)
        val rows = (0 until n).shuffled(permutation)
        val slots = (0 until n).shuffled(permutation)
        val source = SparseMatrix.ofColumns(
            n,
            2 * n,
            List(2 * n) { j ->
                List(n) { i ->
                    val slot = j % n
                    val extra = if (j >= n && rows[i] == slots[slot]) 0.5 else 0.0
                    i to (original[rows[i]][slots[slot]] + extra)
                }
            },
        )
        var previousReports: List<BasisSolveWork>? = null
        repeat(3) {
            val solver = KotlinBasisSolver(source, updateLimit = 4, fillFactor = 100.0)
            val basis = IntArray(n) { n - 1 - it }
            assertTrue(solver.refactorize(basis))
            val reports = mutableListOf<BasisSolveWork>()
            val random = Random(71)
            for (step in 0..12) {
                for (dense in listOf(false, true)) {
                    val rhs = DoubleArray(n) {
                        when {
                            dense -> (it % 3 - 1).toDouble()
                            it == step % n -> 1.0
                            else -> 0.0
                        }
                    }
                    for (hint in listOf(0.0, 1.0)) {
                        for (transpose in listOf(false, true)) {
                            val vector = IndexedVector(n).also { it.scatter(rhs) }
                            if (transpose) solver.btran(vector, hint) else solver.ftran(vector, hint)
                            val residual = sourceResidual(source, basis, rhs, vector, transpose)
                            assertTrue(
                                residual <= 1e-11,
                                "$shape step=$step transpose=$transpose residual=$residual",
                            )
                            assertTrue(solver.solveQuality(rhs, vector, transpose).relativeResidual <= 1e-11)
                            assertEquals(vector.toDoubleArray().count { it != 0.0 }, vector.count)
                            reports.add(assertNotNull(solver.lastSolveWork))
                        }
                    }
                }
                if (step == 12) break
                val row = random.nextInt(n)
                val entering = (basis[row] + n) % (2 * n)
                val spike = IndexedVector(n).also { it.scatterColumn(source, entering) }
                solver.ftran(spike, 0.0)
                val outcome = solver.update(row, entering, spike, spike)
                assertEquals(if (step < 3) BasisUpdate.APPLIED else BasisUpdate.REFACTORIZE, outcome)
                basis[row] = entering
                spike.clear()
            }
            previousReports?.let { assertEquals(it, reports) }
            previousReports = reports
            solver.close()
        }
    }

    private fun sourceResidual(
        source: SparseMatrix,
        basis: IntArray,
        rhs: DoubleArray,
        solution: IndexedVector,
        transpose: Boolean,
    ): Double {
        var error = 0.0
        var scale = 1.0
        for (i in basis.indices) {
            var product = 0.0
            for (j in basis.indices) {
                product += (if (transpose) source[j, basis[i]] else source[i, basis[j]]) * solution[j]
            }
            error = max(error, abs(product - rhs[i]))
            scale = max(scale, max(abs(product), abs(rhs[i])))
        }
        return error / scale
    }
}
