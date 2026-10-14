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
    fun `a weaker objective elimination certificate fixes the opposite integer endpoint`() {
        val source = LpBuilder().apply {
            val x = addVar(0L, 10L)
            val r = addRealVar(0.0, 3.0, cost = 1.0)
            addRealRow(intArrayOf(x, r), doubleArrayOf(-3.0, 1.0), Relation.EQ, 0.0)
            addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 1L)
        }.build(Sense.MINIMIZE)
        val model = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())
        val additional = ArrayList<LpDeductionCertificate>()

        val certificate = assertNotNull(nodeDualCertificate(model, doubleArrayOf(1.5, -4.5),
            onAdditionalCertificate = { additional.add(it) }))

        assertEquals(BigFraction.ofLong(3L), certificate.exactObjectiveBound())
        assertEquals(BigFraction.ZERO, certificate.exactReducedCost(0))
        val opposite = additional.single { it.reducedCostSign(0) < 0 }
        assertEquals(assertNotNull(BigFraction.ofDouble(-10.5)), opposite.exactObjectiveBound())
        assertEquals(9L, opposite.fixStepsAtCutoff(0, BigFraction.ofLong(3L), 0L))
        assertEquals(BigFraction.ZERO, opposite.exactReducedCost(1))
    }

    @Test
    fun `endpoint repair supplies integral fixing without a continuous upper bound`() {
        val source = LpBuilder().apply {
            val x = addVar(0L, 3L)
            val r = addRealVar(0.0, null, cost = 1.0)
            addRealRow(intArrayOf(x, r), doubleArrayOf(0.5, 1.0), Relation.GE, 2.5)
        }.build(Sense.MINIMIZE)
        val model = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())

        val certificate = assertNotNull(nodeDualCertificate(model, doubleArrayOf(-2.0)))

        assertEquals(BigFraction.ONE, certificate.exactObjectiveBound())
        assertEquals(BigFraction.ZERO, certificate.exactReducedCost(1))
        assertEquals(0L, certificate.fixStepsAtCutoff(0, BigFraction.ONE, 0L))
        assertNull(certificate.fixSteps(1, 1L, 0L))
    }

    @Test
    fun `endpoint repair cannot acquire an absent logical endpoint`() {
        val source = LpBuilder().apply {
            val x = addVar(0L, 3L)
            val r = addRealVar(0.0, null, cost = -1.0)
            addRealRow(intArrayOf(x, r), doubleArrayOf(0.5, 1.0), Relation.GE, 2.5)
        }.build(Sense.MINIMIZE)
        val model = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())

        val certificate = nodeDualCertificate(model, doubleArrayOf(0.0))

        assertNull(certificate)
    }

    @Test
    fun `endpoint repair withholds a candidate coupling unbounded real coordinates`() {
        val source = LpBuilder().apply {
            val x = addVar(0L, 3L)
            val r = addRealVar(0.0, null, cost = 1.0)
            val q = addRealVar(0.0, null)
            addRealRow(intArrayOf(x, r, q), doubleArrayOf(0.5, 1.0, 1.0), Relation.GE, 2.5)
        }.build(Sense.MINIMIZE)
        val model = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())

        val certificate = nodeDualCertificate(model, doubleArrayOf(-2.0))

        assertNull(certificate)
    }

    @Test
    fun `directly integral mixed objectives retain lattice support without defining rows`() {
        val source = LpBuilder().apply {
            addVar(0L, 10L, cost = 2L)
            addRealVar(0.25, 1.25)
        }.build(Sense.MINIMIZE)
        val model = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())

        val certificate = assertNotNull(nodeDualCertificate(model, doubleArrayOf()))

        assertEquals(emptyList(), certificate.integralObjectiveRows()?.toList())
        assertEquals(BigFraction.ZERO, certificate.exactObjectiveBound())
    }

    @Test
    fun `combined dual costs fix an integral coordinate when either component leaves it free`() {
        val source = LpBuilder().apply {
            val x = addVar(0L, 1L, cost = 10L)
            val z = addVar(0L, 1L, cost = 100L)
            addRealVar(0.0, 1.0)
            addRealRow(intArrayOf(x, z), doubleArrayOf(4.0, 1000.0), Relation.GE, 1001.0)
        }.build(Sense.MINIMIZE)
        val model = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())
        val additional = ArrayList<LpDeductionCertificate>()

        val certificate = assertNotNull(nodeDualCertificate(model, doubleArrayOf(-2.5),
            onAdditionalCertificate = { additional.add(it) }))

        assertNull(certificate.fixSteps(0, 106L, 0L))
        assertTrue(additional.any { it.fixSteps(0, 106L, 0L) == 0L })
        assertTrue(additional.any { it.fixSteps(0, 106L, 0L)?.let { steps -> steps >= 1L } == true })
    }

    @Test
    fun `objective equalities cancel unsupported continuous reduced costs`() {
        val source = LpBuilder().apply {
            val x = addVar(0L, 1L)
            val r = addRealVar(0.0, null, cost = 1.0)
            addRealRow(intArrayOf(x, r), doubleArrayOf(-4.0, 1.0), Relation.EQ, 0.0)
        }.build(Sense.MINIMIZE)
        val model = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())
        val certificate = assertNotNull(nodeDualCertificate(model, doubleArrayOf(2.0)))

        assertEquals(BigFraction.ZERO, certificate.exactObjectiveBound())
        assertEquals(0L, certificate.fixSteps(0, 1L, 0L))
        assertEquals(BigFraction.ZERO, certificate.exactReducedCost(1))
    }

    @Test
    fun `node bounds retain a stronger complete objective equality candidate`() {
        val source = LpBuilder().apply {
            val x = addVar(0L, 1L)
            val r = addRealVar(0.0, 1.0, cost = 1.0)
            addRealRow(intArrayOf(x, r), doubleArrayOf(-1.0, 1.0), Relation.EQ, 0.0)
        }.build(Sense.MINIMIZE)
        val model = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())
        var additional: LpDeductionCertificate? = null

        val certificate = assertNotNull(nodeDualCertificate(model, doubleArrayOf(-1.0),
            onAdditionalCertificate = { if (additional == null) additional = it }))

        assertEquals(BigFraction.ZERO, certificate.exactObjectiveBound())
        assertEquals(BigFraction.ofLong(-1L), assertNotNull(additional).exactObjectiveBound())
    }

    @Test
    fun `cancellation at admission withholds an additional objective certificate`() {
        val source = LpBuilder().apply {
            val x = addVar(0L, 1L)
            val r = addRealVar(0.0, 4.0, cost = 1.0)
            addRealRow(intArrayOf(x, r), doubleArrayOf(-4.0, 1.0), Relation.EQ, 0.0)
        }.build(Sense.MINIMIZE)
        val model = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())
        var cancelled = false
        var published = false
        val observer = object : LpCertificationObserver {
            override fun observe(certifier: LpCertifier, success: Boolean, cost: LpCertifierCost) {
                cancelled = true
            }
            override fun observeExactInput(accepted: Boolean) = Unit
            override fun observeSolve(metrics: LpSolveMetrics, component: Boolean) = Unit
        }

        val certificate = nodeDualCertificate(model, doubleArrayOf(0.0), Cancellation { cancelled }, observer,
            onAdditionalCertificate = { published = true })

        assertNull(certificate)
        assertFalse(published)
    }

    @Test
    fun `objective equality certificates fix coordinates with zero LP reduced cost`() {
        val source = LpBuilder().apply {
            val x = addVar(0L, 1L)
            val z = addVar(0L, 1L)
            val r = addRealVar(1.0, 100.0, cost = 1.0)
            addRealRow(intArrayOf(x, z, r), doubleArrayOf(-4.0, -1.0, 1.0), Relation.EQ, 0.0)
        }.build(Sense.MINIMIZE)
        val model = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())
        var objectiveCertificate: LpDeductionCertificate? = null

        val certificate = assertNotNull(nodeDualCertificate(model, doubleArrayOf(0.0),
            onAdditionalCertificate = { if (objectiveCertificate == null) objectiveCertificate = it }))

        assertEquals(BigFraction.ONE, certificate.exactObjectiveBound())
        assertEquals(BigFraction.ZERO, certificate.exactReducedCost(0))
        val objectiveCert = assertNotNull(objectiveCertificate)
        assertEquals(0L, objectiveCert.fixSteps(0, 1L, 0L))
        assertEquals(BigFraction.ZERO, objectiveCert.exactReducedCost(2))
    }

    @Test
    fun `defining equalities expose integral reduced costs at a degenerate continuous objective seat`() {
        for (sign in listOf(-1.0, 1.0)) {
            val source = LpBuilder().apply {
                val x = addVar(0L, 10L)
                val r = addRealVar(if (sign > 0.0) 0.0 else -3.0,
                    if (sign > 0.0) 3.0 else 0.0, cost = sign)
                addRealRow(intArrayOf(x, r), doubleArrayOf(-3.0 * sign, 1.0), Relation.EQ, 0.0)
            }.build(Sense.MINIMIZE)
            val model = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())

            val certificate = assertNotNull(nodeDualCertificate(model, doubleArrayOf(0.0),
                eliminateContinuousObjective = true))

            assertEquals(1L, certificate.fixSteps(0, 3L, 0L))
            assertEquals(BigFraction.ZERO, certificate.exactReducedCost(1))
            assertNull(certificate.fixSteps(1, 3L, 0L))
        }
    }

    @Test
    fun `mixed rational bounds remain available without an integral objective lattice`() {
        val source = LpBuilder().apply {
            val x = addVar(0L, 10L)
            val free = addRealVar(null, null)
            val objective = addRealVar(0.0, 10.0, cost = 1.0)
            addRealRow(intArrayOf(x, free), doubleArrayOf(3.0, -3.0), Relation.EQ, 3.0)
            addRealRow(intArrayOf(free, objective), doubleArrayOf(1.0, -1.0), Relation.EQ, 0.0)
        }.build(Sense.MINIMIZE)
        val model = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())
        val duals = doubleArrayOf(-1.0 / 3.0, -1.0)
        val certificate = assertNotNull(nodeDualCertificate(model, duals))

        val lower = certifiedTightObjectiveLowerBound(model, duals, certificate, null, ProductionLpCertificationPolicy)

        assertEquals(-1.0, lower)
    }

    @Test
    fun `mixed duals require exact cancellation on free continuous columns`() {
        for (coefficient in listOf(-3.0, -3.0000000001)) {
            val source = LpBuilder().apply {
                val x = addVar(0L, 10L, cost = 1L)
                val r = addRealVar(null, null)
                addRealRow(intArrayOf(x, r), doubleArrayOf(3.0, coefficient), Relation.EQ, 3.0)
                addRealRow(intArrayOf(r), doubleArrayOf(1.0), Relation.EQ, 0.0)
            }.build(Sense.MINIMIZE)
            val model = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())

            val certificate = assertNotNull(nodeDualCertificate(model, doubleArrayOf(1.0 / 3.0, 1.0)))

            assertEquals(1L, certificate.objectiveBoundCeil(0L))
            assertEquals(BigFraction.ZERO, certificate.exactReducedCost(1))
        }
    }

    @Test
    fun `scoped logical sides cannot silently supply an objective lattice`() {
        val source = assertNotNull(LpBuilder().apply {
            val x = addVar(0L, 10L)
            val r = addRealVar(0.0, 10.0, cost = 1.0)
            addRealRow(intArrayOf(x, r), doubleArrayOf(-1.0, 1.0), Relation.EQ, 0.0)
        }.build(Sense.MINIMIZE).authoritativeModel())
        val slack = source.column(source.n)
        val premises = ExactLpPremises(emptyList(), listOf(0))
        val bounds = ExactLpBounds(
            slack.bounds.lower?.copy(premises = premises), slack.bounds.upper?.copy(premises = premises),
        )
        val model = assertNotNull(LpExactState(source.withBoundColumns(
            List(source.numVars) { if (it == source.n) slack.copy(bounds = bounds) else source.column(it) },
        )).toWorkingModel())

        var published = false
        val certificate = assertNotNull(nodeDualCertificate(model, doubleArrayOf(0.0),
            onAdditionalCertificate = { published = true }))

        assertNull(certificate.integralObjectiveRows())
        assertFalse(published)
    }

    @Test
    fun `oversized authoritative arithmetic withholds mixed deductions`() {
        val source = assertNotNull(LpBuilder().apply {
            addRealVar(0.0, 1.0, cost = 1.0)
        }.build(Sense.MINIMIZE).authoritativeModel())
        val wide = BigFraction.ofLong(Long.MAX_VALUE) * BigFraction.ofLong(Long.MAX_VALUE) * BigFraction.ofLong(8L)
        val objective = ExactLpObjective(listOf(ExactLpNumber.of(wide)))
        val model = assertNotNull(LpExactState(source.withObjective(objective)).toWorkingModel())

        val certificate = nodeDualCertificate(model, doubleArrayOf())

        assertNull(certificate)
    }

    @Test
    fun `bounded continuous objective definitions retain their integral equality support`() {
        val source = LpBuilder().apply {
            val x = addVar(0L, 10L)
            val r = addRealVar(0.0, 10.0, cost = 1.0)
            addRealRow(intArrayOf(x, r), doubleArrayOf(-1.0, 1.0), Relation.EQ, 0.0)
        }.build(Sense.MINIMIZE)
        val model = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())

        val certificate = assertNotNull(nodeDualCertificate(model, doubleArrayOf(0.0)))

        assertEquals(listOf(0), certificate.integralObjectiveRows()?.toList())
        assertFalse(certificate.dualNonzeroRow(0))
        assertNull(certificate.fixSteps(1, 3L, 0L))
    }

    @Test
    fun `fractional continuous objective definitions retain a rational cutoff`() {
        val source = LpBuilder().apply {
            val x = addVar(0L, 10L)
            val r = addRealVar(0.0, 10.0, cost = 1.0)
            addRealRow(intArrayOf(x, r), doubleArrayOf(-0.5, 1.0), Relation.EQ, 0.0)
        }.build(Sense.MINIMIZE)
        val model = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())

        val certificate = assertNotNull(nodeDualCertificate(model, doubleArrayOf(0.0)))

        assertNull(certificate.integralObjectiveRows())
    }

    @Test
    fun `continuous objective inequalities cannot supply an integral equality`() {
        val source = LpBuilder().apply {
            val x = addVar(0L, 10L)
            val r = addRealVar(0.0, 10.0, cost = 1.0)
            addRealRow(intArrayOf(x, r), doubleArrayOf(-1.0, 1.0), Relation.GE, 0.0)
        }.build(Sense.MINIMIZE)
        val model = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())

        val certificate = assertNotNull(nodeDualCertificate(model, doubleArrayOf(0.0)))

        assertNull(certificate.integralObjectiveRows())
    }

    @Test
    fun `objective integrality retains source shifts scale and external constants`() {
        for (constant in listOf(5.0, 5.25)) {
            val source = assertNotNull(LpBuilder().apply {
                val x = addVar(3L, 10L)
                val r = addRealVar(3.0, 10.0, cost = 2.0)
                addRealRow(intArrayOf(x, r), doubleArrayOf(-1.0, 1.0), Relation.EQ, 0.0)
            }.build(Sense.MINIMIZE).authoritativeModel())
            val objective = ExactLpObjective(
                List(source.numVars) { source.objective.cost(it) }, source.objective.constant,
                scale = ExactLpNumber.of(2L),
                externalConstant = ExactLpNumber.of(assertNotNull(BigFraction.ofDouble(constant))),
            )
            val model = assertNotNull(LpExactState(source.withObjective(objective)).toWorkingModel())

            val certificate = assertNotNull(nodeDualCertificate(model, doubleArrayOf(0.0)))

            assertEquals(if (constant == 5.0) listOf(0) else null, certificate.integralObjectiveRows()?.toList())
            assertEquals(assertNotNull(BigFraction.ofDouble(3.0 + constant)), certificate.exactObjectiveBound())
        }
    }

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
