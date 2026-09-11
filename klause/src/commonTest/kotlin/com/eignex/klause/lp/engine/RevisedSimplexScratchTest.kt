package com.eignex.klause.lp.engine

import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RevisedSimplexScratchTest {
    @Test
    fun `retained optimal duals survive objective adoption and zero cost solves`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val minusOne = ExactLpNumber.of(-1L)
        val box = ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(10L)))
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, minusOne))),
            listOf(minusOne),
            listOf(ExactLpColumn(box), ExactLpColumn(ExactLpBounds(ExactLpSide(zero)))),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(one, zero)),
        )
        val trail = LpBoundTrail(source)
        RevisedSimplex(assertNotNull(trail.state.toWorkingModel())).use { solver ->
            val first = assertNotNull(solver.solve())
            val retained = first.duals.copyOf()
            assertTrue(retained.any { it != 0.0 })
            assertTrue(trail.replaceObjective(ExactLpObjective(listOf(zero, zero))))
            assertTrue(solver.adopt(trail.state, Cancellation.Never))

            val second = assertNotNull(solver.resolveBounds())
            second.duals.fill(19.0)
            val third = assertNotNull(solver.resolveBounds())

            assertContentEquals(retained, first.duals)
            assertContentEquals(doubleArrayOf(0.0), third.duals)
            assertEquals(0.0, third.objective)
        }
    }

    @Test
    fun `retained truncated duals do not alias later solve output`() {
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
