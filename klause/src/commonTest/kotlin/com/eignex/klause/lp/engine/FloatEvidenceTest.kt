package com.eignex.klause.lp.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FloatEvidenceTest {
    @Test
    fun `a float deduction bound should not exceed the LP optimum and should stay within tolerance of it`() {
        val model = LpBuilder().apply {
            val x = addVar(0L, 9L, cost = 1L)
            val y = addVar(0L, 9L, cost = 1L)
            addRow(intArrayOf(x, y), longArrayOf(2L, 2L), Relation.GE, 3L)
        }.build(Sense.MINIMIZE)
        val result = assertNotNull(RevisedSimplex(model).solve())

        val bound = assertNotNull(FloatDeductionCertificate.of(model, result.duals)).lowerBound

        assertTrue(bound <= 1.5 && bound > 1.5 - 1e-9, "bound $bound")
    }

    @Test
    fun `a float deduction certificate should decline a reduced cost that needs an open side`() {
        val model = LpBuilder().apply {
            val x = addFreeVar(0L, null, cost = -1L)
            addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 1L)
        }.build(Sense.MINIMIZE)

        assertNull(FloatDeductionCertificate.of(model, doubleArrayOf(0.0)))
    }

    @Test
    fun `float reduced-cost fixing should allow exactly the movement the objective gap pays for`() {
        val model = LpBuilder().apply {
            val x = addVar(0L, 9L, cost = 1L)
            val y = addVar(0L, 9L, cost = 2L)
            addRow(intArrayOf(x, y), longArrayOf(1L, 1L), Relation.GE, 1L)
        }.build(Sense.MINIMIZE)
        val result = assertNotNull(RevisedSimplex(model).solve())
        val certificate = assertNotNull(FloatDeductionCertificate.of(model, result.duals))

        assertEquals(1, certificate.reducedCostSign(1))
        assertEquals(2L, certificate.fixSteps(1, 3L, 0L))
    }

    @Test
    fun `a float farkas ray should prove an infeasible system`() {
        val model = LpBuilder().apply {
            val x = addVar(0L, 9L)
            addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 3L)
            addRow(intArrayOf(x), longArrayOf(1L), Relation.LE, 2L)
        }.build(Sense.MINIMIZE)
        val simplex = RevisedSimplex(model)
        assertNull(simplex.solve())

        assertNotNull(floatFarkasRay(model, assertNotNull(simplex.infeasibleRay)))
    }

    @Test
    fun `a float farkas ray should not refute a feasible system`() {
        val model = LpBuilder().apply {
            val x = addVar(0L, 9L)
            addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 2L)
            addRow(intArrayOf(x), longArrayOf(1L), Relation.LE, 3L)
        }.build(Sense.MINIMIZE)

        assertNull(floatFarkasRay(model, doubleArrayOf(1.0, 1.0)))
        assertNull(floatFarkasRay(model, doubleArrayOf(1.0, -1.0)))
    }
}
