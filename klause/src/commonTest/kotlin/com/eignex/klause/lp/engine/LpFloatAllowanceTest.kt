package com.eignex.klause.lp.engine

import com.eignex.klause.lp.bounding.trailModel
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LpFloatAllowanceTest {
    @Test
    fun `work allowances change between retained invocations and leave construction limits intact`() {
        val builder = LpBuilder()
        repeat(4) {
            val x = builder.addVar(0, 4, cost = 1)
            builder.addRow(intArrayOf(x), longArrayOf(1), Relation.GE, 1)
        }
        RevisedSimplex(builder.build(Sense.MINIMIZE), workLimit = 1L).use { solver ->
            val first = assertNotNull(solver.resolveBounds(LpFloatAllowance(100_000L, 0)))
            assertTrue(first.optimal)
            assertEquals(4.0, first.objective)

            assertFalse(solver.resolveBounds(LpFloatAllowance(1L, 0))?.optimal == true)
            assertTrue(solver.lastWorkOps >= 1L)
            assertTrue(assertNotNull(solver.resolveBounds(LpFloatAllowance(0L, 0))).optimal)

            assertFalse(solver.resolveBounds()?.optimal == true)
            assertTrue(solver.lastWorkOps >= 1L)
        }
    }

    @Test
    fun `iteration allowances can grow and shrink across objective revisions`() {
        val builder = LpBuilder()
        repeat(4) {
            val x = builder.addVar(0, 4, cost = 1)
            builder.addRow(intArrayOf(x), longArrayOf(1), Relation.GE, 1)
        }
        val source = assertNotNull(builder.build(Sense.MINIMIZE).trailModel())
        val state = LpExactState(source)
        RevisedSimplex(assertNotNull(state.toWorkingModel())).use { solver ->
            assertFalse(solver.resolveBounds(LpFloatAllowance(0L, 1))?.optimal == true)
            assertTrue(solver.lastPivots <= 1)
            assertTrue(assertNotNull(solver.resolveBounds(LpFloatAllowance(0L, 100))).optimal)
            val objective = ExactLpObjective(List(8) { ExactLpNumber.of(if (it < 4) -1L else 0L) })
            val revised = LpExactState(source.copy(objective = objective), objectiveRevision = 1L)
            assertTrue(solver.adopt(revised))

            assertFalse(solver.resolveBounds(LpFloatAllowance(0L, 1))?.optimal == true)
            assertTrue(solver.lastPivots <= 1)
            val result = assertNotNull(solver.resolveBounds(LpFloatAllowance(0L, 100)))

            assertTrue(result.optimal)
            assertEquals(-16.0, result.objective)
        }
    }

    @Test
    fun `cancelled and throwing invocations restore construction allowances`() {
        for (throws in listOf(false, true)) {
            val builder = LpBuilder()
            val x = builder.addVar(0, 4, cost = 1)
            builder.addRow(intArrayOf(x), longArrayOf(1), Relation.GE, 1)
            var stopped = false
            val failure = IllegalStateException("cancel callback failed")
            val cancellation = Cancellation {
                if (stopped && throws) throw failure
                stopped
            }
            RevisedSimplex(builder.build(Sense.MINIMIZE), cancellation, workLimit = 1L).use { solver ->
                stopped = true

                if (throws) {
                    assertSame(failure, assertFailsWith<IllegalStateException> {
                        solver.resolveBounds(LpFloatAllowance(0L, 0))
                    })
                } else {
                    assertNull(solver.resolveBounds(LpFloatAllowance(0L, 0)))
                }
                stopped = false

                assertFalse(solver.resolveBounds()?.optimal == true)
                assertTrue(solver.lastWorkOps >= 1L)
            }
        }
    }

    @Test
    fun `a warm hint and explicit allowance are rejected before preparation`() {
        val builder = LpBuilder()
        builder.addVar(0, 1)
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).trailModel()))
        LpScopedSolver(state).use { owner ->
            assertFailsWith<IllegalArgumentException> {
                owner.solveFloat(Basis(intArrayOf(), arrayOf(VarStatus.AT_LOWER)), allowance = LpFloatAllowance(1L, 1))
            }

            assertSame(state, owner.state)
            assertEquals(0L, owner.metrics.createdOwners)
            assertEquals(LpSolveMetrics(), owner.lastMetrics)
        }
    }

    @Test
    fun `primal feasibility and optimization share the invocation iteration allowance`() {
        val builder = LpBuilder()
        repeat(4) {
            val x = builder.addVar(0, 4, cost = 1)
            builder.addRow(intArrayOf(x), longArrayOf(1), Relation.GE, 0)
        }
        val source = assertNotNull(builder.build(Sense.MINIMIZE).trailModel())
        val initial = LpExactState(source)
        val changed = ExactLpModel(
            List(4) { source.entries(it) }, List(4) { ExactLpNumber.of(-1L) },
            List(8) { source.column(it) }, List(4) { source.row(it) },
            ExactLpObjective(List(8) { ExactLpNumber.of(if (it < 4) -1L else 0L) }),
        )
        RevisedSimplex(assertNotNull(initial.toWorkingModel())).use { solver ->
            assertEquals(0.0, assertNotNull(solver.resolveBounds()).objective)
            assertTrue(solver.adopt(LpExactState(changed, boundRevision = 1L, objectiveRevision = 1L)))

            val limited = solver.resolveBounds(LpFloatAllowance(0L, 5))

            assertFalse(limited?.optimal == true)
            assertTrue(solver.lastPivots <= 5)
            val completed = assertNotNull(solver.resolveBounds(LpFloatAllowance(0L, 100)))
            assertTrue(completed.optimal)
            assertEquals(-16.0, completed.objective)
        }
    }

}
