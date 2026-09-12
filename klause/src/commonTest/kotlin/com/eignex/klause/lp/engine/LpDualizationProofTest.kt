package com.eignex.klause.lp.engine

import com.eignex.klause.lp.bounding.trailModel
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.BigRationalConflict
import com.eignex.klause.simplex.exact.ExactSimplexBound
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpDualizationProofTest {
    @Test
    fun `upper only structural and ranged logical costs retain objective units and origin`() {
        val one = ExactLpNumber.of(1L)
        val source = LpExactState(ExactLpModel(
            listOf(listOf(ExactLpEntry(0, one))), listOf(ExactLpNumber.of(5L)),
            listOf(
                ExactLpColumn(ExactLpBounds(upper = ExactLpSide(ExactLpNumber.of(4L))), origin = ExactLpNumber.of(10L), integral = false),
                ExactLpColumn(ExactLpBounds(ExactLpSide(one), ExactLpSide(ExactLpNumber.of(3L))), integral = false),
            ),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(ExactLpNumber.of(-3L), ExactLpNumber.of(2L)), ExactLpNumber.of(7L), ExactLpNumber.of(2L), ExactLpNumber.of(5L), Sense.MAXIMIZE),
        ))
        val attempt = LpRootDualizationAttempt(LpDualizationOptions(enabled = true, minRowColumnRatio = 1))

        assertNotNull(attempt.solve(source))
        val result = assertNotNull(certifyDualizedSource(assertNotNull(source.toWorkingModel()), attempt))

        assertEquals(listOf(BigFraction.ofLong(14L)), result.exactPrimal)
        val x = assertNotNull(result.exactPrimal).single() - BigFraction.ofLong(10L)
        val slack = BigFraction.ofLong(5L) - x
        assertTrue(x <= BigFraction.ofLong(4L))
        assertTrue(slack >= BigFraction.ONE && slack <= BigFraction.ofLong(3L))
        val value = (BigFraction.ofLong(-3L) * x + BigFraction.ofLong(2L) * slack + BigFraction.ofLong(7L)) * BigFraction.ofLong(2L).reciprocal() + BigFraction.ofLong(5L)
        assertEquals(value, result.lowerBound)
        assertEquals(BigFraction.ofLong(7L) * BigFraction.ofLong(2L).reciprocal(), value)
    }

    @Test
    fun `a dual recession maps to an independently contradictory source row`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 10L)
        builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.LE, -1L)
        val source = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).trailModel()))
        val transform = LpDualization.create(source, LpDualizationOptions(), LpDualizationMeter(10000L, 4096, Cancellation.Never))
        // Lower x, upper x, lower logical.
        val direction = listOf(BigFraction.ONE, BigFraction.ZERO, BigFraction.ONE)

        val conflict = assertNotNull(transform.sourceConflict(direction)?.conflict)

        assertEquals(listOf(0), conflict.rows.toList())
        val rho = conflict.multipliers.single()
        assertTrue(rho.signum() > 0)
        assertTrue(rho * BigFraction.ofLong(-1L) < BigFraction.ZERO)
        assertTrue(conflict.bounds.contains(ExactSimplexBound(0, false)))
        assertTrue(conflict.bounds.contains(ExactSimplexBound(1, false)))
    }

    @Test
    fun `a source Farkas certificate gives dual unboundedness only with a feasible dual point`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 10L)
        builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.LE, -1L)
        val source = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).trailModel()))
        val transform = LpDualization.create(source, LpDualizationOptions(), LpDualizationMeter(10000L, 4096, Cancellation.Never))
        val conflict = BigRationalConflict(intArrayOf(0), listOf(BigFraction.ONE), listOf(ExactSimplexBound(0, false), ExactSimplexBound(1, false)))
        val point = ExactLpWitness(List(3) { BigFraction.ZERO }, BigFraction.ZERO)

        val result = assertNotNull(transform.dualUnboundedness(conflict, point))

        val ray = result.direction
        assertEquals(BigFraction.ZERO, ray[0] - ray[1] - ray[2])
        assertTrue(BigFraction.ofLong(10L) * ray[1] - ray[2] < BigFraction.ZERO)
        assertTrue(ray.all { it.signum() >= 0 })
        assertNull(transform.dualUnboundedness(conflict, ExactLpWitness(List(3) { BigFraction.ONE }, BigFraction.ZERO)))
    }

    @Test
    fun `dual infeasibility needs a separate source point to prove unboundedness`() {
        val zero = ExactLpNumber.of(0L)
        val minusOne = ExactLpNumber.of(-1L)
        val source = LpExactState(ExactLpModel(
            listOf(listOf(ExactLpEntry(0, minusOne))), listOf(zero),
            List(2) { ExactLpColumn(ExactLpBounds(ExactLpSide(zero))) },
            listOf(ExactLpRow()), ExactLpObjective(listOf(minusOne, zero)),
        ))
        val transform = LpDualization.create(source, LpDualizationOptions(), LpDualizationMeter(10000L, 4096, Cancellation.Never))
        val conflict = BigRationalConflict(intArrayOf(0), listOf(BigFraction.ONE), List(2) { ExactSimplexBound(it, false) })

        val result = assertNotNull(transform.sourceUnboundedness(conflict, ExactLpWitness(listOf(BigFraction.ZERO), BigFraction.ZERO)))

        assertEquals(listOf(BigFraction.ONE), result.direction)
        assertEquals(BigFraction.ZERO, result.direction.single().negated() + result.direction.single())
        assertTrue(result.direction.single().negated() < BigFraction.ZERO)
        assertNull(transform.sourceUnboundedness(conflict, ExactLpWitness(listOf(BigFraction.ofLong(-1L)), BigFraction.ONE)))
        assertNotNull(transform.dualConflict(listOf(BigFraction.ONE))?.conflict)
    }

    @Test
    fun `a fractional integer source point cannot justify MILP unboundedness`() {
        val zero = ExactLpNumber.of(0L)
        val minusOne = ExactLpNumber.of(-1L)
        val source = LpExactState(ExactLpModel(
            listOf(listOf(ExactLpEntry(0, minusOne))), listOf(zero),
            List(2) { ExactLpColumn(ExactLpBounds(ExactLpSide(zero))) },
            listOf(ExactLpRow()), ExactLpObjective(listOf(minusOne, zero)),
        ))
        val transform = LpDualization.create(source, LpDualizationOptions(), LpDualizationMeter(10000L, 4096, Cancellation.Never))
        val conflict = BigRationalConflict(intArrayOf(0), listOf(BigFraction.ONE), List(2) { ExactSimplexBound(it, false) })

        val result = transform.sourceUnboundedness(conflict, ExactLpWitness(listOf(BigFraction.ofLong(2L).reciprocal()), BigFraction.ZERO))

        assertNull(result)
    }

    @Test
    fun `mapped packages retain source authority and obey the source policy`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 8L, cost = 2L)
        repeat(10) { builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 3L) }
        val source = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).trailModel()))
        val attempt = LpRootDualizationAttempt(LpDualizationOptions(enabled = true))
        assertNotNull(attempt.solve(source))

        val refused = assertNotNull(certifyDualizedSource(assertNotNull(source.toWorkingModel()), attempt, LpCertificationPolicy { _, _ -> false }))
        val foreign = certifyDualizedSource(assertNotNull(LpExactState(source.model).toWorkingModel()), attempt)

        assertEquals(LpVerdict.INDETERMINATE, refused.verdict)
        assertNull(foreign)
        assertNull(certifyDualizedSource(assertNotNull(source.toWorkingModel()), attempt, cancellation = Cancellation { true }))
    }
}
