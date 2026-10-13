package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MixedDualBoundTest {
    @Test
    fun `subnormal rational cutoffs retain exact integral fixing and wide arithmetic charges`() {
        val source = LpBuilder().apply {
            addVar(0L, 10L, cost = 1L)
            addRealVar(0.0, 1.0, cost = 1.0)
        }.build(Sense.MINIMIZE)
        val model = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())
        val certificate = assertNotNull(nodeDualCertificate(model, doubleArrayOf()))
        val ordinary = assertNotNull(BigFraction.ofDouble(0.5))
        val subnormal = assertNotNull(BigFraction.ofDouble(Double.MIN_VALUE))

        assertEquals(0L, certificate.fixStepsAtCutoff(0, subnormal, 0L))
        assertNull(certificate.fixStepsAtCutoff(1, subnormal, 0L))
        assertTrue(certificate.rationalFixingWork(0, subnormal) > certificate.rationalFixingWork(0, ordinary))
    }

    @Test
    fun `mixed certificates use fractional continuous endpoints without rounding the objective`() {
        val source = LpBuilder().apply {
            addVar(0L, 10L, cost = 1L)
            addRealVar(0.25, 1.25, cost = 0.5)
        }.build(Sense.MINIMIZE)
        val model = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())

        val certificate = assertNotNull(nodeDualCertificate(model, doubleArrayOf()))

        assertEquals(BigFraction.ofLong(1) * BigFraction.ofLong(8).reciprocal(), certificate.exactObjectiveBound())
        assertEquals(1L, certificate.objectiveBoundCeil(0L))
        assertEquals(2L, certificate.fixStepsAtCutoff(0, BigFraction.ofLong(3), 0L))
        assertNull(certificate.fixSteps(1, 3L, 0L))
    }

    @Test
    fun `mixed certificates select the supported endpoint for both reduced cost signs`() {
        for (sign in listOf(-1L, 1L)) {
            val source = LpBuilder().apply {
                addVar(0L, 10L, cost = sign)
                addRealVar(0.25, 1.25, cost = sign.toDouble())
            }.build(Sense.MINIMIZE)
            val model = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())

            val certificate = assertNotNull(nodeDualCertificate(model, doubleArrayOf()))

            val endpoint = if (sign > 0L) 0.25 else -11.25
            assertEquals(assertNotNull(BigFraction.ofDouble(endpoint)), certificate.exactObjectiveBound())
            assertEquals(sign.toInt(), certificate.reducedCostSign(1))
        }
    }

    @Test
    fun `mixed certificates retain fractional row coefficients and all contributing row weights`() {
        val source = LpBuilder().apply {
            val x = addVar(0L, 10L, cost = 1L)
            val r = addRealVar(0.0, 2.0)
            addRealRow(intArrayOf(x, r), doubleArrayOf(0.5, 1.0), Relation.GE, 1.0)
        }.build(Sense.MINIMIZE)
        val model = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())

        val certificate = assertNotNull(nodeDualCertificate(model, doubleArrayOf(-1.0)))

        assertEquals(BigFraction.ofLong(-1L), certificate.exactObjectiveBound())
        assertEquals(assertNotNull(BigFraction.ofDouble(0.5)), certificate.exactReducedCost(0))
        assertTrue(certificate.dualNonzeroRow(0))
        assertEquals(4L, certificate.fixSteps(0, 1L, 0L))
    }

    @Test
    fun `mixed certificates decline support on absent continuous endpoints`() {
        for (cost in listOf(-1.0, 1.0)) {
            val source = assertNotNull(LpBuilder().apply {
                addRealVar(0.0, 2.0, cost = cost)
            }.build(Sense.MINIMIZE).authoritativeModel())
            val column = source.column(0).copy(bounds = ExactLpBounds(
                lower = if (cost > 0.0) null else source.column(0).bounds.lower,
                upper = if (cost < 0.0) null else source.column(0).bounds.upper,
            ))
            val model = assertNotNull(LpExactState(source.withBoundColumns(listOf(column))).toWorkingModel())

            assertNull(nodeDualCertificate(model, doubleArrayOf()))
        }
    }

    @Test
    fun `mixed certificates retain source objective scale and external constant`() {
        val source = assertNotNull(LpBuilder().apply {
            addVar(0L, 10L, cost = 2L)
            addRealVar(0.25, 1.25, cost = 1.0)
        }.build(Sense.MINIMIZE).authoritativeModel())
        val objective = ExactLpObjective(
            List(source.numVars) { source.objective.cost(it) }, source.objective.constant,
            scale = ExactLpNumber.of(2L), externalConstant = ExactLpNumber.of(5L),
        )
        val model = assertNotNull(LpExactState(source.withObjective(objective)).toWorkingModel())

        val certificate = assertNotNull(nodeDualCertificate(model, doubleArrayOf()))

        assertEquals(assertNotNull(BigFraction.ofDouble(5.125)), certificate.exactObjectiveBound())
        assertEquals(BigFraction.ONE, certificate.exactReducedCost(0))
        assertEquals(1L, certificate.fixSteps(0, 7L, 0L))
    }

    @Test
    fun `mixed certificates cannot cross a bound rollback`() {
        val source = assertNotNull(LpBuilder().apply {
            addVar(0L, 10L, cost = 1L)
            addRealVar(0.25, 1.25, cost = 0.5)
        }.build(Sense.MINIMIZE).authoritativeModel())
        val trail = LpBoundTrail(source)
        assertTrue(trail.push())
        assertTrue(trail.assertBound(0, false, ExactLpSide(ExactLpNumber.of(2L)), 7L))
        val child = assertNotNull(trail.state.toWorkingModel())
        val certificate = assertNotNull(nodeDualCertificate(child, doubleArrayOf()))
        assertTrue(trail.pop(0))

        assertFalse(certificate.belongsTo(assertNotNull(trail.state.toWorkingModel())))
        assertTrue(certificate.belongsTo(child))
    }

    @Test
    fun `cancelled mixed certification withholds deductions`() {
        val source = LpBuilder().apply { addRealVar(0.25, 1.25, cost = 0.5) }.build(Sense.MINIMIZE)
        val model = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())

        assertNull(nodeDualCertificate(model, doubleArrayOf(), Cancellation { true }))
    }
}
