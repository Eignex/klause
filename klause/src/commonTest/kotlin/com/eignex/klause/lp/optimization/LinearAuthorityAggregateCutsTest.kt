package com.eignex.klause.lp.optimization

import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCondition
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceEvidenceVerifier
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourcePremise
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.shl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinearAuthorityAggregateCutsTest {
    private val x = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
    private val y = SourceCoordinate(SourceCoordinate.Kind.Integer, 1)
    private val real = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
    private val scope = SourceScope(SourceRevision("aggregation", "exact-affine"))
    private val left = SourceInequality(mapOf(x to BigFraction.ofLong(2), real to BigFraction.ONE),
        BigFraction.ofLong(3))
    private val right = SourceInequality(mapOf(y to BigFraction.ofLong(2), real to BigFraction.MINUS_ONE),
        BigFraction.ZERO)

    @Test
    fun `cancelling a continuous coordinate should produce a checked integer cut`() {
        val source = SourceProofContext(scope, setOf(x, y, real), mapOf(
            "left" to SourcePremise(left), "right" to SourcePremise(right)))

        val cut = assertNotNull(LinearAuthorityAggregateCuts.derive(listOf(
            LinearAuthorityAggregateTerm(0, "left", left, BigFraction.ONE),
            LinearAuthorityAggregateTerm(1, "right", right, BigFraction.ONE),
        ), Cancellation.Never))

        assertEquals(SourceInequality(mapOf(x to BigFraction.ONE, y to BigFraction.ONE), BigFraction.ONE),
            cut.inequality)
        assertEquals(setOf(0, 1), cut.owners)
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
            SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
    }

    @Test
    fun `unequal positive source weights should cancel a continuous coordinate exactly`() {
        val weightedLeft = SourceInequality(mapOf(x to BigFraction.ofLong(2), real to BigFraction.ofLong(3)),
            BigFraction.ofLong(3))
        val weightedRight = SourceInequality(mapOf(y to BigFraction.ofLong(4), real to BigFraction.ofLong(-2)),
            BigFraction.ZERO)
        val source = SourceProofContext(scope, setOf(x, y, real), mapOf(
            "left" to SourcePremise(weightedLeft), "right" to SourcePremise(weightedRight)))

        val cut = assertNotNull(LinearAuthorityAggregateCuts.derive(listOf(
            LinearAuthorityAggregateTerm(0, "left", weightedLeft, BigFraction.ofLong(2)),
            LinearAuthorityAggregateTerm(1, "right", weightedRight, BigFraction.ofLong(3)),
        ), Cancellation.Never))

        assertEquals(SourceInequality(mapOf(x to BigFraction.ONE, y to BigFraction.ofLong(3)), BigFraction.ONE),
            cut.inequality)
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
            SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
    }

    @Test
    fun `aggregate candidates should retain activation premises from every source row`() {
        val bool = SourceCoordinate(SourceCoordinate.Kind.Boolean, 0)
        val pin = SourceCoordinate(SourceCoordinate.Kind.Integer, 2)
        val source = SourceProofContext(scope, setOf(x, y, real, bool, pin), mapOf(
            "left" to SourcePremise(left, mapOf(0 to true)),
            "right" to SourcePremise(right, emptyMap(), mapOf(pin to BigFraction.ONE))))
        val cut = assertNotNull(LinearAuthorityAggregateCuts.derive(listOf(
            LinearAuthorityAggregateTerm(0, "left", left, BigFraction.ONE),
            LinearAuthorityAggregateTerm(1, "right", right, BigFraction.ONE),
        ), Cancellation.Never))
        val established = SourceCondition(bools = mapOf(0 to true),
            lower = mapOf(pin to BigFraction.ONE), upper = mapOf(pin to BigFraction.ONE))
        for (condition in listOf(established, SourceCondition(lower = established.lower, upper = established.upper),
            SourceCondition(bools = established.bools, lower = established.lower), established)) {
            val scoped = source.atScope(scope.copy(condition = condition))

            val check = SourceEvidenceVerifier(scoped).verify(
                SourceEvidence(scoped.scope, SourceClaim.Consequence(cut.inequality), cut.derivation))

            if (condition == established) assertEquals(SourceEvidenceCheck.Checked, check)
            else assertIs<SourceEvidenceCheck.Rejected>(check)
        }
    }

    @Test
    fun `strict aggregate endpoints should round below an integral right side`() {
        for ((leftStrict, rightStrict) in listOf(true to false, false to true)) {
            val strictLeft = SourceInequality(left.coefficients, BigFraction.ofLong(4), leftStrict)
            val strictRight = SourceInequality(right.coefficients, right.rhs, rightStrict)

            val cut = assertNotNull(LinearAuthorityAggregateCuts.derive(listOf(
                LinearAuthorityAggregateTerm(0, "left", strictLeft, BigFraction.ONE),
                LinearAuthorityAggregateTerm(1, "right", strictRight, BigFraction.ONE),
            ), Cancellation.Never))

            assertEquals(BigFraction.ONE, cut.inequality.rhs)
        }
    }

    @Test
    fun `nonpositive weights should supply no aggregate candidate`() {
        for (weight in listOf(BigFraction.MINUS_ONE, BigFraction.ZERO)) {
            val cut = LinearAuthorityAggregateCuts.derive(listOf(
                LinearAuthorityAggregateTerm(0, "left", left, BigFraction.ONE),
                LinearAuthorityAggregateTerm(1, "right", right, weight),
            ), Cancellation.Never)

            assertNull(cut)
        }
    }

    @Test
    fun `uncancelled continuous activity should supply no integer rounding candidate`() {
        val cut = LinearAuthorityAggregateCuts.derive(listOf(
            LinearAuthorityAggregateTerm(0, "left", left, BigFraction.ONE),
            LinearAuthorityAggregateTerm(1, "right", right, BigFraction.ofLong(2)),
        ), Cancellation.Never)

        assertNull(cut)
    }

    @Test
    fun `aggregate admission should bound source count support proof work and allocation`() {
        val terms = listOf(
            LinearAuthorityAggregateTerm(0, "left", left, BigFraction.ONE),
            LinearAuthorityAggregateTerm(1, "right", right, BigFraction.ONE),
            LinearAuthorityAggregateTerm(2, "third",
                SourceInequality(mapOf(x to BigFraction.ofLong(2)), BigFraction.ZERO), BigFraction.ONE),
        )
        assertNotNull(LinearAuthorityAggregateCuts.derive(terms, Cancellation.Never))
        for (limits in listOf(LinearAuthorityAggregateLimits(rows = 2),
            LinearAuthorityAggregateLimits(support = 1), LinearAuthorityAggregateLimits(support = 2),
            LinearAuthorityAggregateLimits(proofBytes = 1),
            LinearAuthorityAggregateLimits(work = 1),
            LinearAuthorityAggregateLimits(allocation = 1))) {
            val cut = LinearAuthorityAggregateCuts.derive(terms, Cancellation.Never, limits = limits)

            assertNull(cut)
        }
    }

    @Test
    fun `aggregate arithmetic should decline intermediate scalar growth`() {
        val large = BigFraction.of(BIG_ONE shl 127, BIG_ONE)
        val row = SourceInequality(mapOf(x to large), BigFraction.ONE)
        val observations = linkedMapOf<String, Long>()

        val cut = LinearAuthorityAggregateCuts.derive(listOf(
            LinearAuthorityAggregateTerm(0, "left", row, large),
            LinearAuthorityAggregateTerm(1, "right", row, BigFraction.ONE),
        ), Cancellation.Never, { key, value -> observations[key] = (observations[key] ?: 0L) + value },
            LinearAuthorityAggregateLimits(scalarBits = 128))

        assertNull(cut)
        assertEquals(1L, observations["AggregateScalarLimit"])
        assertTrue(assertNotNull(observations["AggregateWork"]) > 0L)
        assertTrue(assertNotNull(observations["AggregateAllocation"]) > 0L)
    }

    @Test
    fun `large cancelling coefficients should retain their exact source proof`() {
        val large = BigFraction.of(BIG_ONE shl 127, BIG_ONE)
        val largeLeft = SourceInequality(mapOf(x to BigFraction.ofLong(2), real to large), BigFraction.ofLong(3))
        val largeRight = SourceInequality(mapOf(y to BigFraction.ofLong(2), real to large.negated()), BigFraction.ZERO)
        val source = SourceProofContext(scope, setOf(x, y, real), mapOf(
            "left" to SourcePremise(largeLeft), "right" to SourcePremise(largeRight)))

        val cut = assertNotNull(LinearAuthorityAggregateCuts.derive(listOf(
            LinearAuthorityAggregateTerm(0, "left", largeLeft, BigFraction.ONE),
            LinearAuthorityAggregateTerm(1, "right", largeRight, BigFraction.ONE),
        ), Cancellation.Never))

        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
            SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
    }

    @Test
    fun `repeated declined aggregates should retain every failed attempt cost`() {
        val observations = linkedMapOf<String, Long>()
        val terms = listOf(
            LinearAuthorityAggregateTerm(0, "left", left, BigFraction.ONE),
            LinearAuthorityAggregateTerm(1, "right", right, BigFraction.ofLong(2)),
        )
        LinearAuthorityAggregateCuts.derive(terms, Cancellation.Never,
            { key, value -> observations[key] = (observations[key] ?: 0L) + value })
        val first = observations.toMap()

        val cut = LinearAuthorityAggregateCuts.derive(terms, Cancellation.Never,
            { key, value -> observations[key] = (observations[key] ?: 0L) + value })

        assertNull(cut)
        assertTrue(assertNotNull(first["AggregateWork"]) > 0L)
        assertEquals(first.mapValues { (_, value) -> value * 2L }, observations)
    }

    @Test
    fun `cancelled aggregation should retain no candidate and report completed work`() {
        var polls = 0
        val observations = linkedMapOf<String, Long>()

        val cut = LinearAuthorityAggregateCuts.derive(listOf(
            LinearAuthorityAggregateTerm(0, "left", left, BigFraction.ONE),
            LinearAuthorityAggregateTerm(1, "right", right, BigFraction.ONE),
        ), Cancellation { ++polls > 8 },
            { key, value -> observations[key] = (observations[key] ?: 0L) + value })

        assertNull(cut)
        assertEquals(1L, observations["AggregateStopped"])
        assertTrue(assertNotNull(observations["AggregateWork"]) > 0L)
    }

    @Test
    fun `aggregation should stop under cumulative source funding without renewing it`() {
        val funding = SourceLpFunding(20L, 1_048_576L).apply { grant(20L) }
        val budget = SourceLpBudget(funding = funding)
        val terms = listOf(
            LinearAuthorityAggregateTerm(0, "left", left, BigFraction.ONE),
            LinearAuthorityAggregateTerm(1, "right", right, BigFraction.ONE),
        )
        val stop = Cancellation { funding.paused }
        val observe: (String, Long) -> Unit = { stage, count ->
            if (stage == "AggregateWork") budget.observe(preparation = count)
            if (stage == "AggregateAllocation") funding.charge(0L, count)
        }
        val first = LinearAuthorityAggregateCuts.derive(terms, stop, observe)
        val spent = funding.spent

        val repeated = LinearAuthorityAggregateCuts.derive(terms, stop, observe)

        assertNull(first)
        assertNull(repeated)
        assertTrue(spent >= 20L)
        assertEquals(spent, funding.spent)
    }
}
