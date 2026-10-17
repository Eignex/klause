package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.BasisSolver
import com.eignex.klause.simplex.basis.IndexedVector
import com.eignex.klause.simplex.basis.KotlinBasisSolver
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RevisedSimplexScratchTest {
    @Test
    fun `preparing logical and warm bases should preserve the first dual optimum`() {
        for (cost in listOf(0L, 1L)) {
            val model = LpBuilder().apply {
                addVar(0L, 4L, cost = cost)
                addRow(intArrayOf(0), longArrayOf(1L), Relation.GE, 2L)
            }.build(Sense.MINIMIZE)
            val state = LpExactState(assertNotNull(model.authoritativeModel()))
            RevisedSimplex(assertNotNull(state.toWorkingModel())).use { solver ->
                val logical = assertNotNull(solver.prepareLogicals(Cancellation.Never))
                assertNotNull(solver.prepareBasis(logical, Cancellation.Never))

                val result = assertNotNull(solver.resolveBounds())

                assertEquals(2.0 * cost, result.objective)
                assertContentEquals(doubleArrayOf(2.0), result.primal)
            }
        }
    }

    @Test
    fun `a cancelled first dual query should permit a later complete query`() {
        val model = LpBuilder().apply {
            addVar(0L, 4L, cost = 1L)
            addRow(intArrayOf(0), longArrayOf(1L), Relation.GE, 2L)
        }.build(Sense.MINIMIZE)
        val state = LpExactState(assertNotNull(model.authoritativeModel()))
        var cancelled = false
        val token = Cancellation { cancelled }
        RevisedSimplex(assertNotNull(state.toWorkingModel()), cancellation = token).use { solver ->
            assertNotNull(solver.prepareLogicals(token))
            cancelled = true
            assertNull(solver.resolveBounds())
            assertEquals(LpFloatTermination.CANCELLED, solver.lastTermination)

            cancelled = false
            val result = assertNotNull(solver.resolveBounds())

            assertEquals(2.0, result.objective)
            assertContentEquals(doubleArrayOf(2.0), result.primal)
        }
    }

    @Test
    fun `bound adoption across dual and primal queries should preserve each optimum`() {
        val model = LpBuilder().apply {
            addVar(0L, 3L, cost = 1L)
            addVar(0L, 3L, cost = 2L)
            addRow(intArrayOf(0, 1), longArrayOf(1L, 1L), Relation.GE, 3L)
        }.build(Sense.MINIMIZE)
        val state = LpExactState(assertNotNull(model.authoritativeModel()))
        RevisedSimplex(assertNotNull(state.toWorkingModel())).use { solver ->
            assertNotNull(solver.prepareLogicals(Cancellation.Never))
            val first = assertNotNull(solver.resolveBounds())
            assertEquals(3.0, first.objective)
            val tightened = LpExactState(
                assertNotNull(model.rebind(longArrayOf(0L, 0L), longArrayOf(1L, 3L)).authoritativeModel()),
                boundRevision = 1L,
            )
            assertTrue(solver.adopt(tightened, Cancellation.Never))
            assertEquals(5.0, assertNotNull(solver.resolveBounds()).objective)
            val restored = LpExactState(assertNotNull(model.authoritativeModel()), boundRevision = 2L)
            assertTrue(solver.adopt(restored, Cancellation.Never))

            val primal = assertNotNull(solver.solvePrimal())
            val repeated = assertNotNull(solver.resolveBounds())

            assertEquals(3.0, primal.objective)
            assertContentEquals(doubleArrayOf(3.0, 0.0), repeated.primal)
            assertContentEquals(doubleArrayOf(3.0, 0.0), first.primal)
        }
    }

    @Test
    fun `exact adoption should retire an old ray before the next solve`() {
        val model = LpBuilder().apply {
            addVar(0L, 1L, cost = 1L)
            addRow(intArrayOf(0), longArrayOf(1L), Relation.GE, 2L)
        }.build(Sense.MINIMIZE)
        val initial = LpExactState(assertNotNull(model.authoritativeModel()))
        RevisedSimplex(assertNotNull(initial.toWorkingModel())).use { solver ->
            assertNull(solver.solve())
            assertNotNull(solver.infeasibleRay)

            val next = LpExactState(
                assertNotNull(model.rebind(longArrayOf(0L), longArrayOf(3L)).authoritativeModel()),
                boundRevision = 1L,
            )
            assertTrue(solver.adopt(next, Cancellation.Never))

            assertNull(solver.infeasibleRay)
            assertNull(solver.infeasibleBasis)
            assertEquals(2.0, assertNotNull(solver.resolveBounds()).objective)
        }
    }

    @Test
    fun `an arbitrary solve failure should preserve cleanup order and clear result artifacts`() {
        val model = LpBuilder().apply {
            addVar(0L, 3L, cost = 1L)
            addRow(intArrayOf(0), longArrayOf(1L), Relation.GE, 2L)
        }.build(Sense.MINIMIZE)
        val primary = IllegalStateException("solve")
        val cleanup = IllegalStateException("cleanup")
        var fail = false
        val solver = RevisedSimplex(model, basisSolverFactory = { matrix ->
            val delegate = KotlinBasisSolver(matrix)
            object : BasisSolver by delegate {
                override fun ftran(x: IndexedVector, expectedDensity: Double) {
                    if (fail) throw primary
                    delegate.ftran(x, expectedDensity)
                }
                override fun close() {
                    delegate.close()
                    if (fail) throw cleanup
                }
            }
        })
        assertNotNull(solver.solve())
        fail = true

        val failure = assertFailsWith<IllegalStateException> { solver.resolveBounds() }

        assertSame(primary, failure)
        assertSame(cleanup, failure.suppressedExceptions.single())
        assertNull(solver.infeasibleRay)
        assertNull(solver.infeasibleBasis)
        assertTrue(solver.gomoryCuts(1).isEmpty())
        assertTrue(solver.mirCuts(1).isEmpty())
        fail = false
        assertEquals(2.0, assertNotNull(solver.solve()).objective)
        solver.close()
    }

    @Test
    fun `retained truncated duals should not alias later solve output`() {
        val builder = LpBuilder()
        val x = IntArray(4) { builder.addVar(0L, 10L, cost = 1L) }
        builder.addRow(intArrayOf(x[0], x[1]), longArrayOf(1L, 1L), Relation.GE, 3L)
        builder.addRow(intArrayOf(x[1], x[2]), longArrayOf(1L, 1L), Relation.GE, 4L)
        builder.addRow(intArrayOf(x[2], x[3]), longArrayOf(1L, 1L), Relation.GE, 5L)
        builder.addRow(intArrayOf(x[0], x[3]), longArrayOf(1L, 1L), Relation.GE, 2L)
        RevisedSimplex(builder.build(Sense.MINIMIZE), iterationLimit = 1).use { solver ->
            val first = assertNotNull(solver.solve())
            assertTrue(!first.optimal)
            val retained = first.duals.copyOf()

            val second = assertNotNull(solver.solve())
            second.duals.fill(23.0)

            assertContentEquals(retained, first.duals)
        }
    }
}
