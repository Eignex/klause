package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.IntBounds
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourcePremise
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class JointRepairNeighborhoodTest {
    @Test
    fun `full signed integer ranges should retain their scaled disagreement`() {
        val columns = (0..1).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
        val source = SourceProofContext(SourceScope(SourceRevision("signed-width", "exact")),
            columns.toSet(), emptyMap())
        val bounds = IntBounds.fromModelBounds(longArrayOf(Long.MIN_VALUE, 0),
            longArrayOf(Long.MAX_VALUE, 1), null, null)
        val repair = assertNotNull(JointRepairNeighborhood.prepare(source, 2, 0, Cancellation.Never,
            integerBounds = bounds))
        val funding = SourceLpFunding(100_000, 100_000).also { it.grant(100_000) }
        val budget = SourceLpBudget(funding = funding)
        val guidance = LinearRepairGuidance()
        guidance.remember(source, mapOf(columns[0] to Long.MAX_VALUE.toDouble(), columns[1] to 0.75),
            budget, Cancellation.Never)
        val centers = listOf(BigFraction.ofLong(Long.MIN_VALUE), BigFraction.ZERO)

        repair.guide(assertNotNull(guidance.find(source)), centers, budget, Cancellation.Never)
        val condition = repair.propose(centers, booleanArrayOf(), 2, 1, BigFraction.ONE, null)

        assertEquals(mapOf(1 to BigFraction.ZERO), condition.lower)
    }

    @Test
    fun `shared guidance should compare displacements in integer range units`() {
        val columns = (0..2).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
        val source = SourceProofContext(SourceScope(SourceRevision("scaled-disagreement", "exact")),
            columns.toSet(), emptyMap())
        val bounds = IntBounds.fromModelBounds(LongArray(3), longArrayOf(1000, 1, 1), null, null)
        val repair = assertNotNull(JointRepairNeighborhood.prepare(source, 3, 0, Cancellation.Never,
            integerBounds = bounds))
        val funding = SourceLpFunding(100_000, 100_000).also { it.grant(100_000) }
        val budget = SourceLpBudget(funding = funding)
        val guidance = LinearRepairGuidance()
        guidance.remember(source, mapOf(columns[0] to 0.0, columns[1] to 0.0, columns[2] to 0.5),
            budget, Cancellation.Never)
        val centers = listOf(BigFraction.ofLong(500), BigFraction.ONE, BigFraction.ZERO)

        repair.guide(assertNotNull(guidance.find(source)), centers, budget, Cancellation.Never)
        val condition = repair.propose(centers, booleanArrayOf(), 2, 1, BigFraction.ONE, null)

        assertEquals(mapOf(0 to BigFraction.ofLong(500), 2 to BigFraction.ZERO), condition.lower)
    }

    @Test
    fun `fixed columns should not attract shared guidance through numerical drift`() {
        val columns = (0..1).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
        val source = SourceProofContext(SourceScope(SourceRevision("fixed-disagreement", "exact")),
            columns.toSet(), emptyMap())
        val bounds = IntBounds.fromModelBounds(LongArray(2), longArrayOf(0, 1), null, null)
        val repair = assertNotNull(JointRepairNeighborhood.prepare(source, 2, 0, Cancellation.Never,
            integerBounds = bounds))
        val funding = SourceLpFunding(100_000, 100_000).also { it.grant(100_000) }
        val budget = SourceLpBudget(funding = funding)
        val guidance = LinearRepairGuidance()
        guidance.remember(source, mapOf(columns[0] to 0.5, columns[1] to 0.25), budget, Cancellation.Never)
        val centers = List(2) { BigFraction.ZERO }

        repair.guide(assertNotNull(guidance.find(source)), centers, budget, Cancellation.Never)
        val condition = repair.propose(centers, booleanArrayOf(), 2, 1, BigFraction.ONE, null)

        assertEquals(mapOf(0 to BigFraction.ZERO), condition.lower)
    }

    @Test
    fun `foreign integer bound dimensions should decline before neighborhood preparation`() {
        val columns = (0..2).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
        val source = SourceProofContext(SourceScope(SourceRevision("foreign-bounds", "exact")),
            columns.toSet(), emptyMap())
        val bounds = IntBounds.fromModelBounds(LongArray(2), LongArray(2) { 1 }, null, null)

        assertNull(JointRepairNeighborhood.prepare(source, 3, 0, Cancellation.Never, integerBounds = bounds))
    }

    @Test
    fun `shared guidance should rank source inputs by their distance from the checked center`() {
        val columns = (0..3).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
        val source = SourceProofContext(SourceScope(SourceRevision("shared-seeds", "exact")),
            columns.toSet(), emptyMap())
        val funding = SourceLpFunding(100_000, 100_000).also { it.grant(100_000) }
        val budget = SourceLpBudget(funding = funding)
        val guidance = LinearRepairGuidance()
        guidance.remember(source, mapOf(columns[0] to 0.25, columns[1] to 100.0, columns[2] to 0.0),
            budget, Cancellation.Never)
        val repair = assertNotNull(JointRepairNeighborhood.prepare(source, 4, 0, Cancellation.Never,
            sourceIntegers = intArrayOf(0, 2, 3)))
        val centers = listOf(BigFraction.ZERO, BigFraction.ZERO, BigFraction.ONE, BigFraction.ZERO)

        repair.guide(assertNotNull(guidance.find(source)), centers, budget, Cancellation.Never)
        val condition = repair.propose(centers, booleanArrayOf(), 2, 1, BigFraction.ONE, null)

        assertEquals(mapOf(0 to BigFraction.ZERO, 3 to BigFraction.ZERO), condition.lower)
    }

    @Test
    fun `cancelled or unfunded guidance should retain the ordinary seed order`() {
        val columns = (0..2).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
        val source = SourceProofContext(SourceScope(SourceRevision("shared-decline", "exact")),
            columns.toSet(), emptyMap())
        val retained = SourceLpFunding(1000, 1000).also { it.grant(1000) }
        val guidance = LinearRepairGuidance()
        guidance.remember(source, mapOf(columns[2] to 0.0), SourceLpBudget(funding = retained), Cancellation.Never)
        val centers = listOf(BigFraction.ZERO, BigFraction.ZERO, BigFraction.ONE)

        for (limit in listOf("work", "allocation", "cancelled")) {
            val funding = SourceLpFunding(if (limit == "work") 0 else 1000,
                if (limit == "allocation") 0 else 1000).also { it.grant(1000) }
            val repair = assertNotNull(JointRepairNeighborhood.prepare(source, 3, 0, Cancellation.Never))
            repair.guide(assertNotNull(guidance.find(source)), centers, SourceLpBudget(funding = funding),
                Cancellation { limit == "cancelled" })

            val condition = repair.propose(centers, booleanArrayOf(), 2, 1, BigFraction.ONE, null)

            assertEquals(mapOf(1 to BigFraction.ZERO, 2 to BigFraction.ONE), condition.lower)
        }
    }

    @Test
    fun `initial repair should leave integer backed real columns outside the source complement`() {
        val columns = (0..2).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
        val source = SourceProofContext(SourceScope(SourceRevision("backings", "exact")), columns.toSet(), emptyMap())
        val repair = assertNotNull(JointRepairNeighborhood.prepare(source, 3, 0, Cancellation.Never,
            sourceIntegers = intArrayOf(0, 2)))

        val condition = repair.propose(List(3) { BigFraction.ONE }, booleanArrayOf(), 1, 0,
            BigFraction.ONE, null)

        assertEquals(mapOf(0 to BigFraction.ONE, 2 to BigFraction.ONE), condition.lower)
        assertEquals(condition.lower, condition.upper)
    }

    @Test
    fun `integer backed real bridges should connect source inputs without consuming their quota`() {
        val columns = (0..2).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
        val source = SourceProofContext(SourceScope(SourceRevision("backing-bridge", "exact")), columns.toSet(),
            mapOf("left" to SourcePremise(SourceInequality(mapOf(columns[0] to BigFraction.ONE,
                columns[1] to BigFraction.ONE), BigFraction.ONE)),
                "right" to SourcePremise(SourceInequality(mapOf(columns[1] to BigFraction.ONE,
                    columns[2] to BigFraction.ONE), BigFraction.ONE))))
        val repair = assertNotNull(JointRepairNeighborhood.prepare(source, 3, 0, Cancellation.Never,
            sourceIntegers = intArrayOf(0, 2)))

        val condition = repair.propose(List(3) { BigFraction.ZERO }, booleanArrayOf(), 2, 1,
            BigFraction.ONE, null)

        assertEquals(emptyMap(), condition.lower)
        assertEquals(2L, repair.selectedIntegers)
    }

    @Test
    fun `fractional integer backed reals should not displace source input seeds`() {
        val columns = (0..3).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
        val source = SourceProofContext(SourceScope(SourceRevision("backing-seeds", "exact")),
            columns.toSet(), emptyMap())
        val repair = assertNotNull(JointRepairNeighborhood.prepare(source, 4, 0, Cancellation.Never,
            sourceIntegers = intArrayOf(0, 2, 3)))
        repair.guide(mapOf(columns[0] to 0.25, columns[1] to 0.5))

        val condition = repair.propose(List(4) { BigFraction.ZERO }, booleanArrayOf(), 2, 1,
            BigFraction.ONE, null)

        assertEquals(mapOf(2 to BigFraction.ZERO, 3 to BigFraction.ZERO), condition.lower)
    }

    @Test
    fun `an integer indicator should connect its trigger with the guarded residual`() {
        val trigger = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
        val x = SourceCoordinate(SourceCoordinate.Kind.Integer, 1)
        val real = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
        val source = SourceProofContext(SourceScope(SourceRevision("indicator", "exact")), setOf(trigger, x, real),
            mapOf("guarded" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE, real to BigFraction.ONE),
                BigFraction.ONE), emptyMap(), mapOf(trigger to BigFraction.ZERO))))
        val repair = assertNotNull(JointRepairNeighborhood.prepare(source, 2, 1, Cancellation.Never))

        val condition = repair.propose(List(3) { BigFraction.ZERO }, booleanArrayOf(), 2, 1,
            BigFraction.ONE, null)

        assertEquals(emptyMap(), condition.lower)
        assertEquals(emptyMap(), condition.upper)
    }

    @Test
    fun `oversized coordinate indexes should decline before allocation`() {
        val source = SourceProofContext(SourceScope(SourceRevision("large", "exact")), emptySet(), emptyMap())

        assertNull(JointRepairNeighborhood.prepare(source, 1_048_577, 0, Cancellation.Never))
    }

    @Test
    fun `repair should free related integers through a real bridge and retain unrelated pins`() {
        val x = SourceCoordinate(SourceCoordinate.Kind.Integer, 1)
        val y = SourceCoordinate(SourceCoordinate.Kind.Integer, 2)
        val z = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
        val real = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
        val source = SourceProofContext(SourceScope(SourceRevision("bridge", "exact")), setOf(x, y, z, real),
            mapOf("x-real" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE, real to BigFraction.ONE),
                BigFraction.ONE)),
                "real-y" to SourcePremise(SourceInequality(mapOf(real to BigFraction.ONE, y to BigFraction.ONE),
                    BigFraction.ONE))))
        val repair = assertNotNull(JointRepairNeighborhood.prepare(source, 3, 1, Cancellation.Never))
        repair.guide(mapOf(y to 0.5, x to 1.0, z to 2.0, real to 0.5))

        val condition = repair.propose(List(4) { BigFraction.ZERO }, booleanArrayOf(), 2, 1,
            BigFraction.ONE, BigFraction.ONE)

        assertEquals(mapOf(0 to BigFraction.ZERO), condition.lower)
        assertEquals(condition.lower, condition.upper)
        assertEquals(BigFraction.ONE, condition.cutoff)
        assertEquals(2L, repair.selectedIntegers)
    }

    @Test
    fun `initial rounding should pin all integer centers and leave continuous coordinates free`() {
        val x = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
        val real = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
        val source = SourceProofContext(SourceScope(SourceRevision("rounding", "exact")), setOf(x, real), emptyMap())
        val repair = assertNotNull(JointRepairNeighborhood.prepare(source, 1, 1, Cancellation.Never))

        val condition = repair.propose(listOf(BigFraction.ONE, BigFraction.ZERO), booleanArrayOf(true),
            1, 0, BigFraction.ONE, null)

        assertEquals(mapOf(0 to BigFraction.ONE), condition.lower)
        assertEquals(condition.lower, condition.upper)
        assertEquals(mapOf(0 to true), condition.bools)
    }

    @Test
    fun `cancelled connectivity preparation should withhold a partial index`() {
        val source = SourceProofContext(SourceScope(SourceRevision("cancelled", "exact")), emptySet(),
            mapOf("empty" to SourcePremise(SourceInequality(emptyMap(), BigFraction.ZERO))))

        assertNull(JointRepairNeighborhood.prepare(source, 0, 0, Cancellation { true }))
    }
}
