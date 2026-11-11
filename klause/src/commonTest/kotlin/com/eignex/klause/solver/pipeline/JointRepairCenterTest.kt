package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.util.Cancellation
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.shl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JointRepairCenterTest {
    @Test
    fun `cited bound sides should suggest legal integer center steps`() {
        val model = Problem(0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(1), null, null), factors = emptyArray())
        for (upper in listOf(false, true)) {
            val before = if (upper) BigFraction.ZERO else BigFraction.ONE
            val condition = ExactNeighborhoodCondition(emptyMap(), mapOf(0 to before), mapOf(0 to before))
            val funding = SourceLpFunding(1_000_000, 1_000_000)
            val policy = JointRepairCenter(model, SourceLpBudget(funding = funding), Cancellation.Never) { true }
            val assumption = SourceDerivation.Assumption(SourceCoordinate(SourceCoordinate.Kind.Integer, 0), upper)
            val centers = mutableListOf(before)

            val step = assertNotNull(policy.suggest(setOf(assumption), condition, condition))
            val applied = policy.apply(step, centers, booleanArrayOf(), condition, Cancellation.Never)

            assertTrue(applied)
            assertEquals(if (upper) BigFraction.ONE else BigFraction.ZERO, centers.single())
            assertTrue(funding.spent > 0 && funding.allocated > 0)
            assertTrue(funding.paused)
            assertFalse(funding.exhausted)
            assertEquals(0L, funding.availableWork)
        }
    }

    @Test
    fun `blocked bound sides should not suggest an illegal center`() {
        val model = Problem(0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), null, null), factors = emptyArray())
        val policy = JointRepairCenter(model, SourceLpBudget(funding = SourceLpFunding(1_000_000, 1_000_000)),
            Cancellation.Never) { true }
        val condition = ExactNeighborhoodCondition(emptyMap(),
            mapOf(0 to BigFraction.ZERO), mapOf(0 to BigFraction.ZERO))

        for (upper in listOf(false, true)) {
            val assumption = SourceDerivation.Assumption(SourceCoordinate(SourceCoordinate.Kind.Integer, 0), upper)
            assertNull(policy.suggest(setOf(assumption), condition, condition))
        }
    }

    @Test
    fun `ineligible integer pins should not suggest a center step`() {
        val model = Problem(0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(1), null, null), factors = emptyArray())
        val policy = JointRepairCenter(model, SourceLpBudget(funding = SourceLpFunding(1_000_000, 1_000_000)),
            Cancellation.Never) { false }
        val condition = ExactNeighborhoodCondition(emptyMap(),
            mapOf(0 to BigFraction.ZERO), mapOf(0 to BigFraction.ZERO))
        val assumption = SourceDerivation.Assumption(SourceCoordinate(SourceCoordinate.Kind.Integer, 0), true)

        assertNull(policy.suggest(setOf(assumption), condition, condition))
    }

    @Test
    fun `another proposal context should not consume a retained step`() {
        val model = Problem(0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(1), null, null), factors = emptyArray())
        val policy = JointRepairCenter(model, SourceLpBudget(funding = SourceLpFunding(1_000_000, 1_000_000)),
            Cancellation.Never) { true }
        val condition = ExactNeighborhoodCondition(emptyMap(),
            mapOf(0 to BigFraction.ZERO), mapOf(0 to BigFraction.ZERO))
        val assumption = SourceDerivation.Assumption(SourceCoordinate(SourceCoordinate.Kind.Integer, 0), true)
        val step = assertNotNull(policy.suggest(setOf(assumption), condition, condition))
        val centers = mutableListOf(BigFraction.ZERO)

        assertFalse(policy.apply(step, centers, booleanArrayOf(), condition.copy(), Cancellation.Never))
        assertEquals(listOf(BigFraction.ZERO), centers)
    }

    @Test
    fun `changed pinned center values should leave a retained step unused`() {
        val model = Problem(0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), longArrayOf(1, 1), null, null),
            factors = emptyArray())
        val policy = JointRepairCenter(model, SourceLpBudget(funding = SourceLpFunding(1_000_000, 1_000_000)),
            Cancellation.Never) { true }
        val pins = mapOf(0 to BigFraction.ZERO, 1 to BigFraction.ZERO)
        val condition = ExactNeighborhoodCondition(emptyMap(), pins, pins)
        val assumption = SourceDerivation.Assumption(SourceCoordinate(SourceCoordinate.Kind.Integer, 0), true)
        val step = assertNotNull(policy.suggest(setOf(assumption), condition, condition))
        val centers = mutableListOf(BigFraction.ZERO, BigFraction.ONE)

        assertFalse(policy.apply(step, centers, booleanArrayOf(), condition, Cancellation.Never))
        assertEquals(listOf(BigFraction.ZERO, BigFraction.ONE), centers)
    }

    @Test
    fun `changed Boolean pins should leave a retained step unused`() {
        val model = Problem(1,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(1), null, null), factors = emptyArray())
        val policy = JointRepairCenter(model, SourceLpBudget(funding = SourceLpFunding(1_000_000, 1_000_000)),
            Cancellation.Never) { true }
        val condition = ExactNeighborhoodCondition(mapOf(0 to true),
            mapOf(0 to BigFraction.ZERO), mapOf(0 to BigFraction.ZERO))
        val assumption = SourceDerivation.Assumption(SourceCoordinate(SourceCoordinate.Kind.Integer, 0), true)
        val step = assertNotNull(policy.suggest(setOf(assumption), condition, condition))
        val centers = mutableListOf(BigFraction.ZERO)

        assertFalse(policy.apply(step, centers, booleanArrayOf(false), condition, Cancellation.Never))
        assertEquals(listOf(BigFraction.ZERO), centers)
    }

    @Test
    fun `cancelled center updates should preserve their stored coordinates`() {
        val model = Problem(0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(1), null, null), factors = emptyArray())
        for (ownerCancellation in listOf(false, true)) {
            var stopped = false
            val policy = JointRepairCenter(model, SourceLpBudget(funding = SourceLpFunding(1_000_000, 1_000_000)),
                Cancellation { stopped }) { true }
            val condition = ExactNeighborhoodCondition(emptyMap(),
                mapOf(0 to BigFraction.ZERO), mapOf(0 to BigFraction.ZERO))
            val assumption = SourceDerivation.Assumption(SourceCoordinate(SourceCoordinate.Kind.Integer, 0), true)
            val step = assertNotNull(policy.suggest(setOf(assumption), condition, condition))
            val centers = mutableListOf(BigFraction.ZERO)
            stopped = ownerCancellation

            assertFalse(policy.apply(step, centers, booleanArrayOf(), condition, Cancellation { !ownerCancellation }))
            assertEquals(listOf(BigFraction.ZERO), centers)
        }
    }

    @Test
    fun `exhausted funding should withhold a retained center step`() {
        val model = Problem(0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(1), null, null), factors = emptyArray())
        val funding = SourceLpFunding(1_000_000, 1_000_000)
        val policy = JointRepairCenter(model, SourceLpBudget(funding = funding), Cancellation.Never) { true }
        val condition = ExactNeighborhoodCondition(emptyMap(),
            mapOf(0 to BigFraction.ZERO), mapOf(0 to BigFraction.ZERO))
        val assumption = SourceDerivation.Assumption(SourceCoordinate(SourceCoordinate.Kind.Integer, 0), true)
        val step = assertNotNull(policy.suggest(setOf(assumption), condition, condition))
        val centers = mutableListOf(BigFraction.ZERO)
        funding.charge(funding.remainingWork)

        assertFalse(policy.apply(step, centers, booleanArrayOf(), condition, Cancellation.Never))
        assertEquals(listOf(BigFraction.ZERO), centers)
        assertEquals(0L, funding.availableWork)
    }

    @Test
    fun `cutoff refutations should not suggest advisory center moves`() {
        val model = Problem(0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(1), null, null), factors = emptyArray())
        val policy = JointRepairCenter(model, SourceLpBudget(funding = SourceLpFunding(1_000_000, 1_000_000)),
            Cancellation.Never) { true }
        val condition = ExactNeighborhoodCondition(emptyMap(),
            mapOf(0 to BigFraction.ZERO), mapOf(0 to BigFraction.ZERO), BigFraction.ONE)
        val assumption = SourceDerivation.Assumption(SourceCoordinate(SourceCoordinate.Kind.Integer, 0), true)

        assertNull(policy.suggest(setOf(assumption), condition, condition))
    }

    @Test
    fun `oversized integer advice should not allocate a center change`() {
        val model = Problem(0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(1), null, null), factors = emptyArray())
        val funding = SourceLpFunding(1_000_000, 1_000_000)
        val policy = JointRepairCenter(model, SourceLpBudget(funding = funding), Cancellation.Never) { true }
        val value = BigFraction.of(bigIntOf(1) shl 4096, BIG_ONE)
        val condition = ExactNeighborhoodCondition(emptyMap(), mapOf(0 to value), mapOf(0 to value))
        val assumption = SourceDerivation.Assumption(SourceCoordinate(SourceCoordinate.Kind.Integer, 0), true)

        assertNull(policy.suggest(setOf(assumption), condition, condition))
        assertTrue(funding.spent < 1000)
    }
}
