package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RevisedSimplexSingletonStartTest {
    @Test
    fun `a prepared singleton opening should retain the checked optimum in either objective direction`() {
        for (cost in listOf(-1L, 1L)) {
            val builder = LpBuilder()
            val x = builder.addVar(0L, 3L)
            val z = builder.addVar(0L, 10L, cost = cost)
            builder.addRow(intArrayOf(x, z), longArrayOf(-1L, 1L), Relation.EQ, 0L)
            builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 2L)
            val bounded = assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel())
            val exact = bounded.copy(columns = List(bounded.numVars) { column ->
                if (column == z) bounded.column(column).copy(bounds = ExactLpBounds()) else bounded.column(column)
            })
            val model = assertNotNull(LpExactState(exact).toWorkingModel())
            for (singleton in listOf(false, true)) {
                RevisedSimplex(model, singletonStart = singleton).use { solver ->
                    assertNotNull(solver.prepareLogicals(Cancellation.Never))

                    val result = assertNotNull(solver.resolveBounds())
                    val checked = verifyExactBasis(model, result.basis)

                    val value = if (cost < 0L) 3L else 2L
                    assertEquals(List(2) { BigFraction.ofLong(value) }, assertNotNull(checked.witness).primal)
                    assertEquals(BigFraction.ofLong(value * cost), assertNotNull(checked.bound).value)
                }
            }
        }
    }

    @Test
    fun `a singleton opening should consume its invocation pivot allowance`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val model = ExactLpModel(listOf(listOf(ExactLpEntry(0, one))), listOf(one),
            listOf(ExactLpColumn(ExactLpBounds()), ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero)))),
            listOf(ExactLpRow()), ExactLpObjective(listOf(one, zero)))
        val working = assertNotNull(LpExactState(model).toWorkingModel())
        RevisedSimplex(working).use { solver ->
            assertNotNull(solver.prepareLogicals(Cancellation.Never))

            val result = solver.resolveBounds(LpFloatAllowance(1_000_000L, 1))

            assertNull(result)
            assertEquals(1, solver.lastPivots)
            assertEquals(LpFloatTermination.PIVOTS, solver.lastTermination)
            assertNull(solver.solvedExactState)
        }
    }
}
