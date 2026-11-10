package com.eignex.klause.solver.pipeline

import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourcePremise
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JointRepairNeighborhoodTest {
    @Test
    fun `a cited eligible pin should seed only the next connected proposal`() {
        val columns = (0..3).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
        val source = SourceProofContext(SourceScope(SourceRevision("refuted-pins", "exact")), columns.toSet(),
            mapOf("edge" to SourcePremise(SourceInequality(mapOf(columns[2] to BigFraction.ONE,
                columns[3] to BigFraction.ONE), BigFraction.ONE))))
        val repair = assertNotNull(JointRepairNeighborhood.prepare(source, 4, 0, Cancellation.Never,
            sourceIntegers = intArrayOf(0, 2, 3)))
        val centers = List(4) { BigFraction.ZERO }
        val pinned = repair.propose(centers, booleanArrayOf(), 2, 0, BigFraction.ONE, null)
        val ordinary = repair.propose(centers, booleanArrayOf(), 2, 1, BigFraction.ONE, null)
        val funding = SourceLpFunding(1_000, 1_000).also { it.grant(1_000) }
        assertTrue(repair.guideRefutation(linkedSetOf(
            SourceDerivation.Assumption(columns[1], false), SourceDerivation.Assumption(columns[2], true)),
            pinned, SourceLpBudget(funding = funding), Cancellation.Never))

        val guided = repair.propose(centers, booleanArrayOf(), 2, 1, BigFraction.ONE, null)

        assertEquals(mapOf(0 to BigFraction.ZERO), guided.lower)
        assertEquals(guided.lower, guided.upper)
        assertEquals(ordinary, repair.propose(centers, booleanArrayOf(), 2, 1, BigFraction.ONE, null))
        assertTrue(funding.spent > 0)
        assertTrue(funding.allocated > 0)
    }

    @Test
    fun `stale or unpaid refutation guidance should retain the ordinary proposal`() {
        val columns = (0..2).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
        val source = SourceProofContext(SourceScope(SourceRevision("refuted-fallback", "exact")),
            columns.toSet(), emptyMap())
        for (variant in listOf("center", "cutoff", "cancelled", "work", "allocation", "unpinned")) {
            val repair = assertNotNull(JointRepairNeighborhood.prepare(source, 3, 0, Cancellation.Never))
            val centers = List(3) { BigFraction.ZERO }
            val pinned = repair.propose(centers, booleanArrayOf(), 2, 0, BigFraction.ONE, null)
            val funding = SourceLpFunding(if (variant == "work") 0 else 1_000,
                if (variant == "allocation") 0 else 1_000).also { it.grant(1_000) }
            repair.guideRefutation(setOf(SourceDerivation.Assumption(columns[2], false)),
                if (variant == "unpinned") pinned.copy(upper = emptyMap()) else pinned,
                SourceLpBudget(funding = funding), Cancellation { variant == "cancelled" })
            val current = if (variant == "center") listOf(BigFraction.ZERO, BigFraction.ZERO, BigFraction.ONE)
                else centers
            val cutoff = if (variant == "cutoff") BigFraction.ONE else null

            val proposed = repair.propose(current, booleanArrayOf(), 2, 1, BigFraction.ONE, cutoff)

            assertEquals(mapOf(1 to current[1], 2 to current[2]), proposed.lower)
            assertEquals(proposed.lower, proposed.upper)
        }
    }

    @Test
    fun `paid guidance should select a connected pair while retaining unrelated pins and cutoff`() {
        val columns = (0..4).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
        val source = SourceProofContext(SourceScope(SourceRevision("shared-connected", "exact")), columns.toSet(),
            mapOf("left" to SourcePremise(SourceInequality(mapOf(columns[0] to BigFraction.ONE,
                columns[2] to BigFraction.ONE), BigFraction.ONE)),
                "right" to SourcePremise(SourceInequality(mapOf(columns[3] to BigFraction.ONE,
                    columns[4] to BigFraction.ONE), BigFraction.ONE))))
        val funding = SourceLpFunding(100_000, 100_000).also { it.grant(100_000) }
        val budget = SourceLpBudget(funding = funding)
        val guidance = LinearRepairGuidance()
        guidance.remember(source, mapOf(columns[1] to 100.0, columns[2] to 3.0, columns[3] to 5.0),
            budget, Cancellation.Never)
        val repair = assertNotNull(JointRepairNeighborhood.prepare(source, 5, 0, Cancellation.Never,
            sourceIntegers = intArrayOf(0, 2, 3, 4)))
        val centers = listOf(BigFraction.ZERO, BigFraction.ZERO, BigFraction.ZERO,
            BigFraction.ONE, BigFraction.ONE)
        assertTrue(repair.guide(guidance.find(source), centers, budget, Cancellation.Never))

        val condition = repair.propose(centers, booleanArrayOf(true), 2, 1, BigFraction.ONE, BigFraction.ONE)

        assertEquals(mapOf(0 to BigFraction.ZERO, 2 to BigFraction.ZERO), condition.lower)
        assertEquals(condition.lower, condition.upper)
        assertEquals(mapOf(0 to true), condition.bools)
        assertEquals(BigFraction.ONE, condition.cutoff)
        assertEquals(2L, repair.selectedIntegers)
    }

    @Test
    fun `declined guidance should restore the ordinary proposal after a ranked proposal`() {
        val columns = (0..2).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
        val source = SourceProofContext(SourceScope(SourceRevision("shared-fallback", "exact")), columns.toSet(),
            mapOf("edge" to SourcePremise(SourceInequality(mapOf(columns[0] to BigFraction.ONE,
                columns[1] to BigFraction.ONE), BigFraction.ONE))))
        val retained = SourceLpFunding(100_000, 100_000).also { it.grant(100_000) }
        val guidance = LinearRepairGuidance()
        guidance.remember(source, mapOf(columns[2] to 2.0), SourceLpBudget(funding = retained), Cancellation.Never)
        val centers = List(3) { BigFraction.ZERO }

        for (variant in listOf("missing", "work", "allocation", "cancelled", "mid-ranking")) {
            val repair = assertNotNull(JointRepairNeighborhood.prepare(source, 3, 0, Cancellation.Never))
            val ordinary = repair.propose(centers, booleanArrayOf(), 2, 1, BigFraction.ONE, null)
            assertTrue(repair.guide(guidance.find(source), centers, SourceLpBudget(funding = retained),
                Cancellation.Never))
            assertEquals(mapOf(0 to BigFraction.ZERO, 1 to BigFraction.ZERO),
                repair.propose(centers, booleanArrayOf(), 2, 1, BigFraction.ONE, null).lower)
            val funding = SourceLpFunding(if (variant == "work") 0 else 100_000,
                if (variant == "allocation") 0 else 100_000).also { it.grant(100_000) }
            var checks = 0

            assertFalse(repair.guide(if (variant == "missing") null else guidance.find(source), centers,
                SourceLpBudget(funding = funding), Cancellation {
                    variant == "cancelled" || (variant == "mid-ranking" && ++checks == 3)
                }))

            assertEquals(ordinary, repair.propose(centers, booleanArrayOf(), 2, 1, BigFraction.ONE, null))
        }
    }

    @Test
    fun `ranking should debit the consuming workers work and allocation`() {
        val integer = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
        val source = SourceProofContext(SourceScope(SourceRevision("shared-cost", "exact")), setOf(integer),
            emptyMap())
        val producer = SourceLpFunding(1000, 1000).also { it.grant(1000) }
        val guidance = LinearRepairGuidance()
        guidance.remember(source, mapOf(integer to 2.0), SourceLpBudget(funding = producer), Cancellation.Never)
        val consumer = SourceLpFunding(1000, 1000).also { it.grant(1000) }
        val repair = assertNotNull(JointRepairNeighborhood.prepare(source, 1, 0, Cancellation.Never))

        assertTrue(repair.guide(guidance.find(source), listOf(BigFraction.ZERO),
            SourceLpBudget(funding = consumer), Cancellation.Never))

        assertTrue(consumer.spent > 0)
        assertTrue(consumer.allocated > 0)
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
