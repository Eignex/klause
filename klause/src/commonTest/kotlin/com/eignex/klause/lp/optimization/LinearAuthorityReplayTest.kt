package com.eignex.klause.lp.optimization

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.engine.CutFamily
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceEvidenceVerifier
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourcePremise
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinearAuthorityReplayTest {
    private val x = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
    private val y = SourceCoordinate(SourceCoordinate.Kind.Integer, 1)
    private val r = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
    private val s = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
    private val half = BigFraction.ofLong(2).reciprocal()
    private val source = SourceProofContext(
        SourceScope(SourceRevision("captured-replay", "exact-affine"),
            objective = SourceObjective("x+2y", ObjectiveSense.MINIMIZE)),
        setOf(x, y, r, s), mapOf(
            "x-plus" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE, r to BigFraction.ONE),
                half.negated())),
            "x-minus" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE, r to BigFraction.MINUS_ONE),
                half.negated())),
            "y-plus" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE, s to BigFraction.ONE),
                half.negated())),
            "y-minus" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE, s to BigFraction.MINUS_ONE),
                half.negated())),
        ), mapOf(x to BigFraction.ONE, y to BigFraction.ofLong(2)),
    )
    private val cuts = listOf(
        LinearAuthorityCut(0, SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.MINUS_ONE),
            SourceDerivation.IntegerRounding(SourceDerivation.Combination(listOf(
                SourceDerivation.Combination.Term(half, SourceDerivation.Premise("x-plus")),
                SourceDerivation.Combination.Term(half, SourceDerivation.Premise("x-minus")),
            ))), owners = setOf(0, 1), family = CutFamily.SOURCE_AGGREGATION),
        LinearAuthorityCut(2, SourceInequality(mapOf(y to BigFraction.MINUS_ONE), BigFraction.MINUS_ONE),
            SourceDerivation.IntegerRounding(SourceDerivation.Combination(listOf(
                SourceDerivation.Combination.Term(half, SourceDerivation.Premise("y-plus")),
                SourceDerivation.Combination.Term(half, SourceDerivation.Premise("y-minus")),
            ))), owners = setOf(2, 3), family = CutFamily.SOURCE_AGGREGATION),
    )

    @Test
    fun `replay masks should target supplied captured cuts after their owner closes`() {
        val capture = LinearAuthorityCapture(1, 10_000L, 1_000_000L, Cancellation.Never)
        LinearAuthority(SourceLpBudget(), retainLp = true, additionalCuts = cuts).use {
            it.capture = capture
            it.advance(source, Cancellation.Never, cuts = true, cutMask = setOf(0))
        }
        val original = capture.records.single()
        assertEquals(2, original.cuts.size)

        for ((mask, expected) in listOf(
            emptySet<Int>() to BigFraction.ofLong(3) * half,
            setOf(0) to BigFraction.ofLong(2),
            setOf(1) to BigFraction.ofLong(5) * half,
            setOf(0, 1) to BigFraction.ofLong(3),
        )) {
            val replay = LinearAuthorityReplay.run(original, mask, 1_000_000L, 4_000_000L, Cancellation.Never)

            val bound = assertNotNull(replay.strongestBound)
            val replayed = assertNotNull(replay.query)
            assertEquals(SourceValue.Rational(expected), (bound.claim as SourceClaim.Bound).value)
            assertEquals(1, replay.operations)
            assertEquals(mask, replayed.cuts.filter { it.installed }.mapTo(HashSet()) { it.id })
            assertTrue(replayed.cuts.all { it.family == CutFamily.SOURCE_AGGREGATION })
            assertEquals(SourceEvidenceCheck.Checked,
                SourceEvidenceVerifier(source.withLinearAuthority()).verify(bound))
        }
    }

    @Test
    fun `replay should decline masks outside the captured pool`() {
        val capture = LinearAuthorityCapture(1, 10_000L, 1_000_000L, Cancellation.Never)
        LinearAuthority(SourceLpBudget(), retainLp = true, additionalCuts = cuts).use {
            it.capture = capture
            it.advance(source, Cancellation.Never, cuts = true, cutMask = setOf(0))
        }

        for (mask in listOf(setOf(-1), setOf(2))) {
            val replay = LinearAuthorityReplay.run(capture.records.single(), mask,
                1_000_000L, 4_000_000L, Cancellation.Never)

            assertNotNull(replay.decline)
            assertNull(replay.query)
            assertEquals(0, replay.operations)
        }
    }

    @Test
    fun `replay should decline an incomplete captured pool`() {
        val capture = LinearAuthorityCapture(1, 10_000L, 1_000_000L, Cancellation.Never)
        LinearAuthority(SourceLpBudget(), retainLp = true, additionalCuts = cuts).use {
            it.capture = capture
            it.advance(source, Cancellation.Never, cuts = true, cutMask = setOf(0))
        }
        val original = capture.records.single()

        for (incomplete in listOf(
            original.copy(projection = LinearAuthorityFeedbackRequest(
                cuts = LinearAuthorityFeedbackRequest.Cuts.Installed)),
            original.copy(cuts = original.cuts.map { it.copy(family = null) }),
        )) {
            val replay = LinearAuthorityReplay.run(incomplete, emptySet(),
                1_000_000L, 4_000_000L, Cancellation.Never)

            assertNotNull(replay.decline)
            assertNull(replay.query)
            assertEquals(0, replay.operations)
        }
    }

    @Test
    fun `replay should decline before importing beyond its allowance`() {
        val capture = LinearAuthorityCapture(1, 10_000L, 1_000_000L, Cancellation.Never)
        LinearAuthority(SourceLpBudget(), retainLp = true, additionalCuts = cuts).use {
            it.capture = capture
            it.advance(source, Cancellation.Never, cuts = true, cutMask = setOf(0))
        }

        for ((work, allocation) in listOf(1L to 4_000_000L, 1_000_000L to 1L)) {
            val replay = LinearAuthorityReplay.run(capture.records.single(), setOf(0), work, allocation,
                Cancellation.Never)

            assertNotNull(replay.decline)
            assertNull(replay.query)
            assertEquals(0, replay.operations)
            assertTrue(replay.work <= work && replay.allocation <= allocation)
        }
    }

    @Test
    fun `replay should recheck captured derivations instead of trusting prior check flags`() {
        val capture = LinearAuthorityCapture(1, 10_000L, 1_000_000L, Cancellation.Never)
        LinearAuthority(SourceLpBudget(), retainLp = true, additionalCuts = cuts).use {
            it.capture = capture
            it.advance(source, Cancellation.Never, cuts = true, cutMask = setOf(0))
        }
        val original = capture.records.single()
        val invalid = original.copy(cuts = original.cuts.map {
            if (it.id == 0) it.copy(derivation = SourceDerivation.Premise("missing"), checked = true) else it
        })

        val replay = LinearAuthorityReplay.run(invalid, setOf(0), 1_000_000L, 4_000_000L, Cancellation.Never)

        val bound = replay.strongestBound
        if (bound != null) {
            assertEquals(SourceValue.Rational(BigFraction.ofLong(3) * half), (bound.claim as SourceClaim.Bound).value)
            assertEquals(SourceEvidenceCheck.Checked,
                SourceEvidenceVerifier(source.withLinearAuthority()).verify(bound))
        }
        assertTrue(replay.query?.cuts?.none { it.id == 0 && it.installed } != false)
    }
}
