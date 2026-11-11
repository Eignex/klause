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

        assertTrue(bound <= 1.5 && bound > 1.5 - 1e-5, "bound $bound")
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

    @Test
    fun `a float deduction bound at an integral optimum should not round up past it`() {
        val model = LpBuilder().apply {
            val x = addVar(0L, 9L, cost = 1L)
            addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 3L)
        }.build(Sense.MINIMIZE)
        val result = assertNotNull(RevisedSimplex(model).solve())

        val certificate = assertNotNull(FloatDeductionCertificate.of(model, result.duals))

        assertTrue(certificate.lowerBound < 3.0 && certificate.lowerBound > 3.0 - 1e-5)
        assertEquals(3L, certificate.objectiveBoundCeil(0L))
    }

    @Test
    fun `a float farkas ray should not drop an open column it meets`() {
        val model = LpBuilder().apply {
            val x = addVar(0L, 1L)
            val z = addOpenAboveVar(0L)
            addRow(intArrayOf(x, z), longArrayOf(1L, 1L), Relation.GE, 1_000_000_000L)
            addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 1L)
        }.build(Sense.MINIMIZE)

        assertNull(floatFarkasRay(model, doubleArrayOf(1e-7, 1.0)))
    }

    @Test
    fun `a rounded float farkas ray should never rest on an open side`() {
        val model = LpBuilder().apply {
            val x = addVar(0L, 1L)
            val z = addOpenAboveVar(0L)
            addRow(intArrayOf(z, x), longArrayOf(1L, -1L), Relation.GE, 1L)
            addRow(intArrayOf(z), longArrayOf(1L), Relation.LE, 0L)
        }.build(Sense.MINIMIZE)
        val base = listOf(doubleArrayOf(1.0, 1.0), doubleArrayOf(1.0, -1.0)).first { floatFarkasRay(model, it) != null }

        val rays = listOf(1.0 + 1e-9, 1.0 - 1e-9).map { floatFarkasRay(model, doubleArrayOf(base[0], base[1] * it)) }

        for (ray in rays.filterNotNull().filter { it.isNotEmpty() }) {
            var open = 0L
            model.forEachInColumn(1) { row, a -> open += ray[row] * a }
            assertTrue(open <= 0L)
        }
    }

    @Test
    fun `a tiny reduced cost on an open side should not raise the bound past the optimum`() {
        val model = LpBuilder().apply {
            addVar(0L, 10L, cost = 1L)
            val z = addOpenAboveVar(0L)
            val u = addVar(0L, 100_000_000L)
            addRow(intArrayOf(z, u), longArrayOf(1L, -1L), Relation.EQ, 100_000_000L)
        }.build(Sense.MINIMIZE)

        val bound = FloatDeductionCertificate.of(model, doubleArrayOf(5e-8))?.lowerBound

        assertTrue(bound == null || bound <= 0.0, "bound $bound")
    }

    @Test
    fun `a reduced cost beyond tolerance on an open side should be priced at the bound its row implies`() {
        val model = LpBuilder().apply {
            addVar(0L, 10L, cost = 1L)
            val z = addOpenAboveVar(0L)
            val u = addVar(0L, 100_000_000L)
            addRow(intArrayOf(z, u), longArrayOf(1L, -1L), Relation.EQ, 100_000_000L)
        }.build(Sense.MINIMIZE)

        val certificate = assertNotNull(FloatDeductionCertificate.of(model, doubleArrayOf(0.5)))

        assertTrue(certificate.lowerBound <= 0.0)
        assertTrue(certificate.dualNonzeroRow(0))
        assertEquals(3, certificate.extraCitedSides(2))
    }
}
