package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
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
                    phaseOneLongSteps = longSteps, phaseOneIncrementalValues = false).use { solver ->
                    val result = assertNotNull(solver.solvePrimal(warm))

                    assertEquals(if (fromUpper) 7.0 else 3.0, result.primal.single(), 1e-9)
                    assertEquals(if (longSteps) 1 else 2, result.pivots)
                }
            }
        }
    }

    @Test
    fun `incremental feasibility values should retain the checked optimum across refreshes and rebuilds`() {
        val builder = LpBuilder()
        repeat(9) {
            val x = builder.addVar(0L, 4L, cost = 1L)
            for (rhs in listOf(1L, 3L)) {
                builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, rhs)
            }
        }
        val model = builder.build(Sense.MINIMIZE)
        for (incremental in listOf(false, true)) {
            for (updates in listOf(1, 64)) {
                RevisedSimplex(model, refactorUpdateLimit = updates,
                    phaseOneIncrementalValues = incremental).use { solver ->
                    val result = assertNotNull(solver.solvePrimal())
                    val checked = verifyExactBasis(model, result.basis)

                    assertEquals(List(9) { BigFraction.ofLong(3L) }, assertNotNull(checked.witness).primal)
                    assertEquals(BigFraction.ofLong(27L), assertNotNull(checked.bound).value)
                }
            }
        }
    }

    @Test
    fun `incremental feasibility values should retain bound flips before the next pivot`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 1L)
        val y = builder.addVar(0L, 3L, cost = 1L)
        builder.addRow(intArrayOf(x, y), longArrayOf(1L, 1L), Relation.GE, 2L)
        RevisedSimplex(builder.build(Sense.MINIMIZE)).use { solver ->
            val result = assertNotNull(solver.solvePrimal())

            assertEquals(listOf(1.0, 1.0), result.primal.toList())
            assertEquals(1.0, result.objective, 1e-9)
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
