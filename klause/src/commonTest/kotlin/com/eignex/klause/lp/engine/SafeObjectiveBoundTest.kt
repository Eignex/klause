package com.eignex.klause.lp.engine

import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.RevisedSimplex
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.lp.engine.exactVariableBound
import com.eignex.klause.lp.engine.safeObjectiveLowerBound
import com.eignex.klause.lp.engine.safeVariableBound
import com.eignex.klause.lp.engine.tightVariableBound
import com.eignex.klause.simplex.exact.BigFraction
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The Neumaier–Shcherbina safe bound must never exceed the true optimum (#567 component 3): a
 *  sound lower bound on `min cᵀz`, validated against the exact [DualSimplex]. */
class SafeObjectiveBoundTest {
    @Test
    fun `supplied legacy certificates cannot preserve a bound after its objective changes`() {
        val model = LpBuilder().apply {
            val x = addVar(0L, 9L, cost = 1L)
            val y = addVar(0L, 9L, cost = 1L)
            addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 3L)
            addRow(intArrayOf(y), longArrayOf(1L), Relation.GE, 4L)
        }.build(Sense.MINIMIZE)
        val certificate = assertNotNull(integerCertify(model, doubleArrayOf(-1.0, -1.0)))
        assertEquals(7L, certificate.objectiveBoundCeil(0L))

        model.cost[0] = 0L
        val current = doubleArrayOf(0.0, -1.0)
        val bound = assertNotNull(tightObjectiveLowerBound(model, current, certificate))

        assertTrue(bound <= 4.0 && bound > 3.0)
        assertEquals(bound, certifiedTightObjectiveLowerBound(model, current, certificate, null, ProductionLpCertificationPolicy))
    }


    @Test
    fun `retained variable bounds follow nested endpoints and rollback in source coordinates`() {
        for (maximize in listOf(false, true)) {
            val source = LpBuilder().apply { addVar(3L, 9L, cost = if (maximize) -1L else 1L) }
                .build(Sense.MINIMIZE)
            val trail = LpBoundTrail(assertNotNull(source.authoritativeModel()))
            assertTrue(trail.push())
            assertTrue(trail.assertBound(0, maximize, ExactLpSide(ExactLpNumber.of(if (maximize) 4L else 2L)), 7L))
            assertTrue(trail.push())
            assertTrue(trail.assertBound(0, maximize, ExactLpSide(ExactLpNumber.of(3L)), 8L))

            for ((depth, expected) in listOf(2 to 6L, 1 to if (maximize) 7L else 5L, 0 to if (maximize) 9L else 3L)) {
                assertTrue(trail.pop(depth))
                val model = assertNotNull(trail.state.toWorkingModel())
                val result = assertNotNull(RevisedSimplex(model).solvePrimal())

                assertEquals(expected, model.safeVariableBound(result, 0, maximize))
                assertEquals(expected, model.exactVariableBound(result, 0, maximize))
                assertEquals(expected, model.tightVariableBound(result, 0, maximize))
            }
        }
    }

    @Test
    fun `retained variable bounds certify fractional rows on an integer source lattice`() {
        for (maximize in listOf(false, true)) {
            val source = LpBuilder().apply {
                val x = addVar(3L, 9L, cost = if (maximize) -1L else 1L)
                addRealRow(intArrayOf(x), doubleArrayOf(0.5), if (maximize) Relation.LE else Relation.GE, 2.75)
            }.build(Sense.MINIMIZE)
            val model = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())
            val result = assertNotNull(RevisedSimplex(model).solvePrimal())

            assertEquals(null, integerCertify(model, result.duals))
            assertEquals(if (maximize) 5L else 6L, model.exactVariableBound(result, 0, maximize))
            assertEquals(if (maximize) 5L else 6L, model.tightVariableBound(result, 0, maximize))
        }
    }

    @Test
    fun `retained variable bounds divide objective scaling before integer rounding`() {
        val source = assertNotNull(LpBuilder().apply { addVar(3L, 9L, cost = 2L) }
            .build(Sense.MINIMIZE).authoritativeModel())
        val objective = ExactLpObjective(
            listOf(ExactLpNumber.of(2L)), source.objective.constant, scale = ExactLpNumber.of(2L),
        )
        val trail = LpBoundTrail(source.copy(objective = objective))
        assertTrue(trail.assertBound(0, false, ExactLpSide(ExactLpNumber.of(assertNotNull(BigFraction.ofDouble(1.5))), true), 7L))
        val model = assertNotNull(trail.state.toWorkingModel())
        val result = assertNotNull(RevisedSimplex(model).solvePrimal())

        assertEquals(null, integerCertify(model, result.duals))
        assertEquals(5L, model.exactVariableBound(result, 0, maximize = false))
        assertEquals(5L, model.safeVariableBound(result, 0, maximize = false))
    }

    @Test
    fun `supplied retained certificates contribute integer ceilings only to their current state`() {
        val source = assertNotNull(LpBuilder().apply {
            val x = addVar(0L, 3L, cost = 1L)
            addRow(intArrayOf(x), longArrayOf(2L), Relation.EQ, 1L)
        }.build(Sense.MINIMIZE).authoritativeModel())
        val trail = LpBoundTrail(source)
        val model = assertNotNull(trail.state.toWorkingModel())
        val duals = doubleArrayOf(0.5)
        val certificate = assertNotNull(integerCertify(model, duals))

        assertEquals(1.0, tightObjectiveLowerBound(model, duals, certificate))
        assertEquals(1.0, certifiedTightObjectiveLowerBound(model, duals, certificate, null, ProductionLpCertificationPolicy))
        assertTrue(trail.replaceObjective(ExactLpObjective(listOf(ExactLpNumber.of(0L), ExactLpNumber.of(0L)))))
        val unpriced = assertNotNull(trail.state.toWorkingModel())
        assertEquals(0.0, tightObjectiveLowerBound(unpriced, doubleArrayOf(0.0), certificate))
        assertEquals(0.0, certifiedTightObjectiveLowerBound(unpriced, doubleArrayOf(0.0), certificate, null, ProductionLpCertificationPolicy))
    }

    private fun randomModel(m: Int, n: Int, rng: Random): LpModel {
        val b = LpBuilder()
        repeat(n) { b.addVar(0L, rng.nextLong(2, 9), cost = rng.nextLong(-6, 7)) }
        val cols = IntArray(n) { it }
        repeat(m) {
            val vals = LongArray(n) { rng.nextLong(-4, 5) }
            b.addRow(cols, vals, Relation.LE, rng.nextLong(3, 25))
        }
        return b.build(Sense.MINIMIZE)
    }

    @Test
    fun `safe bound never exceeds the exact optimum`() {
        val rng = Random(20260615)
        var finite = 0
        var total = 0
        repeat(1200) {
            val model = randomModel(rng.nextInt(3, 10), rng.nextInt(3, 10), rng)
            val opt = exactLpOptimum(model)
            if (opt.isNaN()) return@repeat
            val rev = RevisedSimplex(model).solve() ?: return@repeat
            total++
            val safe = safeObjectiveLowerBound(model, rev.duals) ?: return@repeat
            finite++
            assertTrue(safe <= opt + 1e-6, "UNSOUND safe bound $safe > optimum $opt")
        }
        assertTrue(total > 300, "covered only $total instances")
        // The bound should be usefully tight (finite) on the large majority of instances.
        assertTrue(finite >= total * 4 / 5, "safe bound was finite on only $finite/$total")
    }

    @Test
    fun `a slack multiplier off by float noise still yields a bound`() {
        // min -x subject to x <= 4, x in [0, 10]: the row's exact multiplier is -1, and a slack carries
        // no upper, so a multiplier a hair the other side of its own reduced cost used to cost the bound.
        val b = LpBuilder()
        val x = b.addVar(0L, 10L, cost = -1L)
        b.addRow(intArrayOf(x), longArrayOf(1L), Relation.LE, 4L)
        val model = b.build(Sense.MINIMIZE)
        val optimum = exactLpOptimum(model)

        val bound = assertNotNull(
            safeObjectiveLowerBound(model, doubleArrayOf(1e-12)),
            "a multiplier off by 1e-12 must not cost the whole bound",
        )

        assertTrue(bound <= optimum + 1e-6, "UNSOUND repaired bound $bound > optimum $optimum")
    }

    @Test
    fun `exact and projected variable bounds retain finite support on an open column`() {
        // maximize x subject to x <= 5, x open above (a free column at the ±∞ probe upper).
        val b = LpBuilder()
        val x = b.addFreeVar(0L, null, cost = -1L)
        b.addRow(intArrayOf(x), longArrayOf(1L), Relation.LE, 5L)
        val model = b.build(Sense.MINIMIZE)
        val result = assertNotNull(RevisedSimplex(model).solvePrimal())

        val exact = model.exactVariableBound(result, x, maximize = true)
        val safe = assertNotNull(model.safeVariableBound(result, x, maximize = true))
        val tight = assertNotNull(model.tightVariableBound(result, x, maximize = true))

        assertEquals(5L, exact, "exact bound should be the true max")
        assertEquals(5L, tight, "tight bound should match the exact bound")
        assertTrue(safe >= 5L, "safe bound must stay sound")
        assertTrue(tight <= safe, "tight bound must not exceed the looser safe bound")
    }

    @Test
    fun `continuous objective bounds retain their fractional units`() {
        val model = LpBuilder().apply {
            val x = addRealVar(0.0, 1.0, cost = 1.0)
            addRealRow(intArrayOf(x), doubleArrayOf(2.0), Relation.EQ, 1.0)
        }.build(Sense.MINIMIZE)
        val duals = doubleArrayOf(0.5)

        assertEquals(null, model.exactObjectiveLowerBoundCeil(duals))
        assertEquals(null, rationalizedDualLowerBoundCeil(model, duals))
        assertEquals(0.5, tightObjectiveLowerBound(model, duals))
        assertEquals(0.5, certifiedTightObjectiveLowerBound(model, duals, null, ProductionLpCertificationPolicy))
    }

    @Test
    fun `safe bound rounds a wide exact constant downward`() {
        val constant = 9007199254740995L
        val model = LpBuilder().apply { addVar(constant, constant, cost = 1L) }.build(Sense.MINIMIZE)

        val bound = assertNotNull(safeObjectiveLowerBound(model, doubleArrayOf()))

        assertTrue(assertNotNull(BigFraction.ofDouble(bound)) <= BigFraction.ofLong(constant))
        assertEquals(9007199254740994.0, bound)
    }

    @Test
    fun `safe bound checks negative subnormal objective support exactly`() {
        val model = LpBuilder().apply { addRealVar(0.0, 1.0, cost = -Double.MIN_VALUE) }.build(Sense.MINIMIZE)

        val bound = assertNotNull(safeObjectiveLowerBound(model, doubleArrayOf()))

        assertTrue(assertNotNull(BigFraction.ofDouble(bound)) <= assertNotNull(BigFraction.ofDouble(-Double.MIN_VALUE)))
    }

    @Test
    fun `source objective lattice includes the exact coordinate origin`() {
        val model = LpBuilder().apply {
            addVar(0L, 1L, cost = 1L)
            addRealVar(0.0, 1.0)
        }.build(Sense.MINIMIZE)
        model.doubleView!!.loShift[0] = 0.5

        assertEquals(1L, model.exactObjectiveLowerBoundCeil(doubleArrayOf()))
    }

    @Test
    fun `safe bound declines nonfinite or malformed inputs`() {
        val model = LpBuilder().apply {
            val x = addRealVar(0.0, 1.0)
            addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.EQ, 0.5)
        }.build(Sense.MINIMIZE)

        for (dual in listOf(doubleArrayOf(), doubleArrayOf(Double.NaN), doubleArrayOf(Double.POSITIVE_INFINITY))) {
            assertEquals(null, safeObjectiveLowerBound(model, dual))
        }
        model.doubleView!!.upper[0] = -1.0
        assertEquals(null, safeObjectiveLowerBound(model, doubleArrayOf(0.0)))
    }

    @Test
    fun `supplied certificate policy respects the source objective lattice`() {
        val model = LpBuilder().apply {
            val x = addVar(0L, 1L, cost = 1L)
            addVar(0L, 1L)
            addRow(intArrayOf(x), longArrayOf(2L), Relation.EQ, 1L)
        }.build(Sense.MINIMIZE)
        model.colContinuous[0] = true
        model.colContinuous[1] = true
        val duals = doubleArrayOf(0.5)
        val certificate = assertNotNull(integerCertify(model, duals))

        assertEquals(null, integerDualLowerBoundCeil(model, duals))
        assertEquals(
            0.5,
            certifiedTightObjectiveLowerBound(model, duals, certificate, null, ProductionLpCertificationPolicy),
        )
        assertEquals(0.5, tightObjectiveLowerBound(model, duals, certificate))
        model.colContinuous[0] = false
        assertEquals(
            1.0,
            certifiedTightObjectiveLowerBound(model, duals, certificate, null, ProductionLpCertificationPolicy),
        )
        assertEquals(1.0, tightObjectiveLowerBound(model, duals, certificate))
    }

    @Test
    fun `safe bound rounds a negative wide constant downward`() {
        val constant = -9007199254740993L
        val model = LpBuilder().apply { addVar(constant, constant, cost = 1L) }.build(Sense.MINIMIZE)

        val bound = assertNotNull(safeObjectiveLowerBound(model, doubleArrayOf()))

        assertTrue(assertNotNull(BigFraction.ofDouble(bound)) <= BigFraction.ofLong(constant))
        assertEquals(-9007199254740994.0, bound)
    }

    @Test
    fun `safe bound declines an overflowing exact projection`() {
        val model = LpBuilder().apply {
            addRealVar(
                0.0,
                Double.MAX_VALUE,
                cost = -Double.MAX_VALUE,
            )
        }.build(Sense.MINIMIZE)

        assertEquals(null, safeObjectiveLowerBound(model, doubleArrayOf()))
    }
}
