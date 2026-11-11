package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpFloatingBoundTest {
    @Test
    fun `column pricing should retain scaled source objective units`() {
        val source = ExactLpModel(listOf(emptyList()), emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(ExactLpNumber.of(0L)),
                ExactLpSide(ExactLpNumber.of(4L))))), emptyList(),
            ExactLpObjective(listOf(ExactLpNumber.of(3L)), ExactLpNumber.of(0L), ExactLpNumber.of(2L)))
        val model = assertNotNull(LpExactState(source).toWorkingModel())

        val result = floatingReducedCost(model, doubleArrayOf(), 0, 100L)

        assertTrue(assertNotNull(result.lower) <= 1.5)
        assertTrue(assertNotNull(result.upper) >= 1.5)
        assertTrue(assertNotNull(result.lower) > 1.49)
        assertNull(result.stop)
    }

    @Test
    fun `column pricing should enclose a numerical reduced cost`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 10L, cost = 2L)
        builder.addRow(intArrayOf(x), longArrayOf(3L), Relation.LE, 5L)

        val result = floatingReducedCost(builder.build(Sense.MINIMIZE), doubleArrayOf(0.5), x, 100L)

        assertEquals(0.5, result.lower)
        assertEquals(0.5, result.upper)
        assertNull(result.stop)
    }

    @Test
    fun `column pricing should retain work without publishing an incomplete reduced cost`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 10L, cost = 2L)
        builder.addRow(intArrayOf(x), longArrayOf(3L), Relation.LE, 5L)

        val result = floatingReducedCost(builder.build(Sense.MINIMIZE), doubleArrayOf(0.5), x, 2L)

        assertNull(result.lower)
        assertNull(result.upper)
        assertEquals(LpFloatingBoundStop.WORK, result.stop)
        assertEquals(2L, result.work)
    }

    @Test
    fun `dual bounds should include shifted objective constants`() {
        val model = LpBuilder().apply { addVar(-3L, 4L, cost = 2L) }.build(Sense.MINIMIZE)

        val result = floatingObjectiveLowerBound(model, doubleArrayOf())

        assertEquals(-6.0, result.bound)
        assertNull(result.stop)
    }

    @Test
    fun `a complete dual should establish the constrained lower bound`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 3L, cost = 1L)
        builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 2L)

        val result = floatingObjectiveLowerBound(builder.build(Sense.MINIMIZE), doubleArrayOf(-1.0))

        assertEquals(2.0, result.bound)
    }

    @Test
    fun `negative reduced costs should use the finite upper side`() {
        val model = LpBuilder().apply { addVar(1L, 4L, cost = -2L) }.build(Sense.MINIMIZE)

        val result = floatingObjectiveLowerBound(model, doubleArrayOf())

        assertEquals(-8.0, result.bound)
    }

    @Test
    fun `rounded operations should not overstate a binary projection optimum`() {
        val builder = LpBuilder()
        val x = builder.addRealVar(0.0, 1.0, cost = 0.1)
        builder.addRealRow(intArrayOf(x), doubleArrayOf(0.3), Relation.EQ, 0.2)
        val optimum = assertNotNull(BigFraction.ofDouble(0.1)) * assertNotNull(BigFraction.ofDouble(0.2)) *
            assertNotNull(BigFraction.ofDouble(0.3)).reciprocal()

        val result = floatingObjectiveLowerBound(builder.build(Sense.MINIMIZE), doubleArrayOf(1.0 / 3.0))

        val bound = assertNotNull(result.bound)
        assertTrue(assertNotNull(BigFraction.ofDouble(bound)) <= optimum)
        assertTrue(bound > 0.06)
    }

    @Test
    fun `an unbounded lagrangian should withhold its bound`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 3L)
        builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.LE, 1L)

        val result = floatingObjectiveLowerBound(builder.build(Sense.MINIMIZE), doubleArrayOf(1.0))

        assertNull(result.bound)
        assertEquals(LpFloatingBoundStop.UNBOUNDED, result.stop)
        assertTrue(result.work > 0L)
    }

    @Test
    fun `probe caps should not supply finite bound premises`() {
        val model = LpBuilder().apply { addFreeVar(null, null, cost = -1L) }.build(Sense.MINIMIZE)

        val result = floatingObjectiveLowerBound(model, doubleArrayOf())

        assertNull(result.bound)
        assertEquals(LpFloatingBoundStop.UNBOUNDED, result.stop)
    }

    @Test
    fun `a free zero cost direction should preserve a finite bound`() {
        val model = LpBuilder().apply { addFreeVar(null, null) }.build(Sense.MINIMIZE)

        val result = floatingObjectiveLowerBound(model, doubleArrayOf())

        assertEquals(0.0, result.bound)
    }

    @Test
    fun `exhausted work should retain spent work without a partial bound`() {
        val model = LpBuilder().apply { addVar(0L, 3L, cost = 1L) }.build(Sense.MINIMIZE)

        val result = floatingObjectiveLowerBound(model, doubleArrayOf(), workLimit = 2L)

        assertNull(result.bound)
        assertEquals(LpFloatingBoundStop.WORK, result.stop)
        assertEquals(2L, result.work)
    }

    @Test
    fun `cancellation should withhold a bound`() {
        val model = LpBuilder().apply { addVar(0L, 3L, cost = 1L) }.build(Sense.MINIMIZE)

        val result = floatingObjectiveLowerBound(model, doubleArrayOf(), cancellation = Cancellation { true })

        assertNull(result.bound)
        assertEquals(LpFloatingBoundStop.CANCELLED, result.stop)
    }

    @Test
    fun `nonfinite duals should retain their inspection cost and withhold a bound`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 3L)
        builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.LE, 1L)
        val model = builder.build(Sense.MINIMIZE)

        for (dual in listOf(Double.NaN, Double.POSITIVE_INFINITY)) {
            val result = floatingObjectiveLowerBound(model, doubleArrayOf(dual))

            assertNull(result.bound)
            assertEquals(LpFloatingBoundStop.NON_FINITE, result.stop)
            assertTrue(result.work > 0L)
        }
    }

    @Test
    fun `retained objective scaling should return minimized source units`() {
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(ExactLpNumber.of(2L)),
                ExactLpSide(ExactLpNumber.of(4L))))),
            emptyList(),
            ExactLpObjective(listOf(ExactLpNumber.of(3L)), ExactLpNumber.of(4L),
                ExactLpNumber.of(2L), ExactLpNumber.of(3L)),
        )
        val model = assertNotNull(LpExactState(source).toWorkingModel())

        val result = floatingObjectiveLowerBound(model, doubleArrayOf())

        assertTrue(assertNotNull(result.bound) <= 8.0)
        assertTrue(assertNotNull(result.bound) > 7.99)
    }
}
