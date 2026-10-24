package com.eignex.klause.lp.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PrimalPhaseOneRatioTest {
    @Test
    fun `long feasibility steps should pass improving bound crossings in either direction`() {
        for (fromUpper in listOf(false, true)) {
            val builder = LpBuilder()
            val x = builder.addVar(0L, 10L, cost = if (fromUpper) -1L else 1L)
            for (rhs in listOf(1L, 3L)) {
                builder.addRow(intArrayOf(x), longArrayOf(1L),
                    if (fromUpper) Relation.LE else Relation.GE, if (fromUpper) 10L - rhs else rhs)
            }
            val model = builder.build(Sense.MINIMIZE)
            val warm = Basis(intArrayOf(1, 2),
                arrayOf(if (fromUpper) VarStatus.AT_UPPER else VarStatus.AT_LOWER, VarStatus.BASIC, VarStatus.BASIC))
            for (longSteps in listOf(false, true)) {
                RevisedSimplex(model, scalingOptions = LpScalingOptions(enabled = false),
                    phaseOneLongSteps = longSteps).use { solver ->
                    val result = assertNotNull(solver.solvePrimal(warm))

                    assertEquals(if (fromUpper) 7.0 else 3.0, result.primal.single(), 1e-9)
                    assertEquals(if (longSteps) 1 else 2, result.pivots)
                }
            }
        }
    }

    @Test
    fun `a feasibility step should end at the entering columns opposite bound`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 2L)
        for (rhs in listOf(1L, 3L)) {
            builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, rhs)
        }
        val model = builder.build(Sense.MINIMIZE)

        val step = assertNotNull(PrimalPhaseOneRatio(2).choose(model,
            LpScalingView.create(model, LpScalingOptions(enabled = false)), intArrayOf(1, 2),
            doubleArrayOf(-1.0, -3.0), doubleArrayOf(-1.0, -1.0), 1.0, 2.0, 1e-7, LpWork()))

        assertEquals(2.0, step.length)
        assertEquals(-1, step.leaving)
    }

    @Test
    fun `passing a feasible bound should withhold a point when infeasibility stays positive`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 10L)
        repeat(2) { builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 3L) }
        builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.LE, 1L)
        RevisedSimplex(builder.build(Sense.MINIMIZE), iterationLimit = 8).use { solver ->
            val result = solver.solvePrimal()

            assertNull(result)
            assertEquals(LpFloatTermination.INFEASIBLE_CANDIDATE, solver.lastTermination)
        }
    }
}
