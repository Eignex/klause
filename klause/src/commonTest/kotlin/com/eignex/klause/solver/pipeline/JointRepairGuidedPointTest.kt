package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JointRepairGuidedPointTest {
    @Test
    fun `guided points should preserve integer pins and discard exact center authority`() {
        val model = Problem(0, intBounds = IntBounds.fromModelBounds(LongArray(3), LongArray(3) { 1 }, null, null),
            factors = emptyArray(), numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))
        val source = assertNotNull(model.linearAuthorityContext(LinearObjective(intCoefficients = longArrayOf(1, 1, 1)),
            ObjectiveSense.MINIMIZE, Cancellation.Never))
        val center = Sample(booleanArrayOf(), longArrayOf(1, 1, 1), doubleArrayOf(1.0),
            exactReals = listOf(BigFraction.ONE))
        val funding = SourceLpFunding(100_000, 100_000).also { it.grant(100_000) }
        val budget = SourceLpBudget(funding = funding)
        val guidance = LinearRepairGuidance()
        assertTrue(guidance.remember(source, (0..2).associate {
            SourceCoordinate(SourceCoordinate.Kind.Integer, it) to 0.0
        } + (SourceCoordinate(SourceCoordinate.Kind.Real, 0) to 0.25), budget, Cancellation.Never))
        val condition = ExactNeighborhoodCondition(emptyMap(), mapOf(0 to BigFraction.ONE),
            mapOf(0 to BigFraction.ONE), BigFraction.ofLong(3))

        val point = assertNotNull(JointRepairGuidedPoint.propose(model, center, condition,
            assertNotNull(guidance.find(source)), budget, Cancellation.Never))

        assertContentEquals(longArrayOf(1, 0, 0), point.ints)
        assertContentEquals(doubleArrayOf(0.25), point.reals)
        assertNull(point.exactReals)
        assertNull(point.witnessCertificate)
    }

    @Test
    fun `cancelled or unfunded point construction should decline`() {
        val model = Problem(0, intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(1), null, null),
            factors = emptyArray(), numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))
        val source = assertNotNull(model.linearAuthorityContext(LinearObjective(intCoefficients = longArrayOf(1)),
            ObjectiveSense.MINIMIZE, Cancellation.Never))
        val retained = SourceLpFunding(100_000, 100_000).also { it.grant(100_000) }
        val guidance = LinearRepairGuidance()
        assertTrue(guidance.remember(source, mapOf(SourceCoordinate(SourceCoordinate.Kind.Integer, 0) to 0.0),
            SourceLpBudget(funding = retained), Cancellation.Never))
        val center = Sample(booleanArrayOf(), longArrayOf(1), doubleArrayOf(1.0))
        val condition = ExactNeighborhoodCondition(emptyMap(), emptyMap(), emptyMap(), BigFraction.ONE)

        for (limit in listOf("work", "allocation", "cancelled")) {
            val funding = SourceLpFunding(if (limit == "work") 0 else 100_000,
                if (limit == "allocation") 0 else 100_000).also { it.grant(100_000) }
            val point = JointRepairGuidedPoint.propose(model, center, condition,
                assertNotNull(guidance.find(source)), SourceLpBudget(funding = funding),
                Cancellation { limit == "cancelled" })

            assertNull(point)
        }
    }
}
