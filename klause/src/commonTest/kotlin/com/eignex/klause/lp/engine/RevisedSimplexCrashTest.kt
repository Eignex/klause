package com.eignex.klause.lp.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RevisedSimplexCrashTest {
    @Test
    fun `triangular crash reaches the certified cold optimum with fewer pivots`() {
        val builder = LpBuilder()
        repeat(8) {
            val column = builder.addVar(0L, 10L)
            builder.addRow(intArrayOf(column), longArrayOf(1L), Relation.GE, 1L)
        }
        val objective = builder.addVar(0L, 4L, cost = 1L)
        val model = builder.build(Sense.MINIMIZE)
        val crash = assertNotNull(triangularCrashBasis(model).basis)

        val cold = assertNotNull(RevisedSimplex(model).solve())
        val crashed = assertNotNull(RevisedSimplex(model).solve(crash))

        assertEquals(cold.objective, crashed.objective, 1e-9)
        assertEquals(0.0, crashed.primal[objective], 1e-9)
        assertTrue(crashed.pivots < cold.pivots, "crash ${crashed.pivots}, cold ${cold.pivots}")
        assertTrue(crashed.warmStarted)
    }

    @Test
    fun `singular and malformed hints fall back to a valid cold basis`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 3L, cost = 1L)
        val y = builder.addVar(0L, 3L)
        builder.addRow(intArrayOf(x, y), longArrayOf(1L, 1L), Relation.GE, 1L)
        builder.addRow(intArrayOf(x, y), longArrayOf(2L, 2L), Relation.GE, 2L)
        val model = builder.build(Sense.MINIMIZE)
        val statuses = Array(model.numVars) { VarStatus.AT_LOWER }
        statuses[x] = VarStatus.BASIC
        statuses[y] = VarStatus.BASIC

        val result = assertNotNull(RevisedSimplex(model).solve(Basis(intArrayOf(x, y), statuses)))

        val cold = assertNotNull(RevisedSimplex(model).solve())
        assertEquals(cold.objective, result.objective, 1e-9)
        assertTrue(result.warmStarted)
    }

}
