package com.eignex.klause.lp.optimization

import com.eignex.klause.lp.optimization.AggregateCutTestSource.right
import com.eignex.klause.lp.optimization.AggregateCutTestSource.left
import com.eignex.klause.lp.optimization.AggregateCutTestSource.scope
import com.eignex.klause.lp.optimization.AggregateCutTestSource.real
import com.eignex.klause.lp.optimization.AggregateCutTestSource.y
import com.eignex.klause.lp.optimization.AggregateCutTestSource.x
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceEvidenceVerifier
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourcePremise
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.shl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinearAuthorityAggregateContinuationTest {
    @Test
    fun `point separation should cancel a third source row within its configured row limit`() {
        val otherReal = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val source = SourceProofContext(scope, setOf(x, y, real, otherReal), linkedMapOf(
            "left" to SourcePremise(SourceInequality(mapOf(real to BigFraction.ONE,
                x to BigFraction.ofLong(-2)), BigFraction.ZERO)),
            "right" to SourcePremise(SourceInequality(mapOf(otherReal to BigFraction.ONE,
                y to BigFraction.ofLong(-2)), BigFraction.ZERO)),
            "demand" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE,
                otherReal to BigFraction.MINUS_ONE), BigFraction.ofLong(-3))),
            "lower" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "other lower" to SourcePremise(SourceInequality(mapOf(otherReal to BigFraction.MINUS_ONE),
                BigFraction.ZERO)),
        ))
        val point = mapOf(x to 0.75, y to 0.75, real to 1.5, otherReal to 1.5)

        val unbounded = SourceProofContext(scope, source.coordinates,
            source.premises.filterKeys { it != "lower" && it != "other lower" })
        for (context in listOf(source, unbounded)) {
            for (rows in listOf(2, 3, 4)) {
                val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(context, ExecutionControl.Never,
                    maximumCandidates = 1,
                    limits = LinearAuthorityAggregateCuts.pointSourcePairLimits.copy(rows = rows), point = point)

                if (rows == 2) assertTrue(cuts.isEmpty()) else {
                    val cut = cuts.single()
                    assertEquals(SourceInequality(mapOf(x to BigFraction.MINUS_ONE, y to BigFraction.MINUS_ONE),
                        BigFraction.ofLong(-2)), cut.inequality)
                    assertEquals(setOf(0, 1, 2), cut.owners)
                    assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(context).verify(
                        SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
                }
            }
        }
    }

    @Test
    fun `reused incidence buckets should leave production allocation for a checked cut`() {
        for (rowCount in listOf(1200, 1800)) {
            val otherReal = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
            val premises = linkedMapOf(
                "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                    real to BigFraction.ONE), BigFraction.ofLong(3))),
                "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(2),
                    real to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            )
            repeat(rowCount) { index ->
                premises["loose $index"] = SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE,
                    real to BigFraction.ONE, otherReal to BigFraction.ONE), BigFraction.ofLong(100)))
            }
            val source = SourceProofContext(scope, setOf(x, y, real, otherReal), premises)
            val observations = mutableMapOf<String, Long>()
            val limits = LinearAuthorityAggregateCuts.pointSourcePairLimits

            val cut = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never,
                observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count },
                maximumCandidates = 1, limits = limits,
                point = mapOf(x to 0.75, y to 0.75, real to 1.5, otherReal to 0.0)).single()

            assertEquals(SourceInequality(mapOf(x to BigFraction.ONE, y to BigFraction.ONE), BigFraction.ONE),
                cut.inequality)
            assertEquals(setOf(0, 1), cut.owners)
            assertTrue((observations["AggregateWork"] ?: 0L) <= limits.work)
            assertTrue((observations["AggregateAllocation"] ?: 0L) <= limits.allocation)
            assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
                SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
        }
    }

    @Test
    fun `cancelling incidence indexing should withhold unstarted proposals`() {
        val otherReal = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val source = SourceProofContext(scope, setOf(x, y, real, otherReal), linkedMapOf(
            "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                real to BigFraction.ONE), BigFraction.ofLong(3))),
            "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(2),
                real to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "loose" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE,
                real to BigFraction.ONE, otherReal to BigFraction.ONE), BigFraction.ofLong(100))),
        ))
        val observations = mutableMapOf<String, Long>()
        var stopped = false

        val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl { stopped },
            observe = { name, count ->
                observations[name] = (observations[name] ?: 0L) + count
                if (name == "AggregateIncidenceReferences") stopped = true
            }, point = mapOf(x to 0.75, y to 0.75, real to 1.5, otherReal to 0.0))

        assertTrue(stopped)
        assertTrue(cuts.isEmpty())
        assertNull(observations["AggregatePairAttempts"])
    }

    @Test
    fun `a retained pair should leave one continuation for another source combination`() {
        val z = SourceCoordinate(SourceCoordinate.Kind.Integer, 2)
        val otherReal = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val firstReal = SourceCoordinate(SourceCoordinate.Kind.Real, 2)
        val source = SourceProofContext(scope, setOf(x, y, z, real, otherReal, firstReal), linkedMapOf(
            "first" to SourcePremise(SourceInequality(mapOf(firstReal to BigFraction.ONE,
                z to BigFraction.ofLong(-2)), BigFraction.ZERO)),
            "first demand" to SourcePremise(SourceInequality(mapOf(firstReal to BigFraction.MINUS_ONE),
                BigFraction.MINUS_ONE)),
            "left" to SourcePremise(SourceInequality(mapOf(real to BigFraction.ONE,
                x to BigFraction.ofLong(-2)), BigFraction.ZERO)),
            "right" to SourcePremise(SourceInequality(mapOf(otherReal to BigFraction.ONE,
                y to BigFraction.ofLong(-2)), BigFraction.ZERO)),
            "demand" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE,
                otherReal to BigFraction.MINUS_ONE), BigFraction.ofLong(-3))),
        ))
        val observations = mutableMapOf<String, Long>()
        val limits = LinearAuthorityAggregateCuts.pointSourcePairLimits

        val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never,
            observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count },
            limits = limits, point = mapOf(x to 0.75, y to 0.75, z to 0.5,
                real to 1.5, otherReal to 1.5, firstReal to 1.0))

        assertEquals(listOf(
            SourceInequality(mapOf(z to BigFraction.MINUS_ONE), BigFraction.MINUS_ONE),
            SourceInequality(mapOf(x to BigFraction.MINUS_ONE, y to BigFraction.MINUS_ONE), BigFraction.ofLong(-2)),
        ), cuts.map { it.inequality })
        assertEquals(listOf(setOf(0, 1), setOf(2, 3, 4)), cuts.map { it.owners })
        assertEquals(1L, observations["AggregateRetainedContinuation"])
        assertTrue((observations["AggregateWork"] ?: 0L) <= limits.work)
        assertTrue((observations["AggregateAllocation"] ?: 0L) <= limits.allocation)
        for (cut in cuts) {
            assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
                SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
        }
    }

    @Test
    fun `a later continuation should not duplicate the retained source inequality`() {
        val residual = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val source = SourceProofContext(scope, setOf(x, y, real, residual), linkedMapOf(
            "left" to SourcePremise(SourceInequality(left.coefficients + (residual to BigFraction.ONE), left.rhs)),
            "right" to SourcePremise(right),
            "lower" to SourcePremise(SourceInequality(mapOf(residual to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "upper" to SourcePremise(SourceInequality(mapOf(residual to BigFraction.ONE), BigFraction.ONE)),
        ))
        val observations = mutableMapOf<String, Long>()
        val limits = LinearAuthorityAggregateCuts.pointSourcePairLimits

        val cut = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never,
            observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count },
            limits = limits, point = mapOf(x to 0.75, y to 0.75, real to 1.5, residual to 0.0)).single()

        assertEquals(SourceInequality(mapOf(x to BigFraction.ONE, y to BigFraction.ONE), BigFraction.ONE),
            cut.inequality)
        assertEquals(setOf(0, 1, 2), cut.owners)
        assertEquals(1L, observations["AggregateRetainedContinuationDuplicate"])
        assertTrue((observations["AggregateWork"] ?: 0L) <= limits.work)
        assertTrue((observations["AggregateAllocation"] ?: 0L) <= limits.allocation)
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
            SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
    }

    @Test
    fun `a continued proof should not retain additional owners for an existing inequality`() {
        val otherReal = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val source = SourceProofContext(scope, setOf(x, y, real, otherReal), linkedMapOf(
            "left" to SourcePremise(left), "right" to SourcePremise(right),
            "bridge" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE,
                otherReal to BigFraction.ONE), BigFraction.ZERO)),
            "reverse" to SourcePremise(SourceInequality(mapOf(real to BigFraction.ONE,
                otherReal to BigFraction.MINUS_ONE), BigFraction.ofLong(10).reciprocal())),
        ))
        val observations = mutableMapOf<String, Long>()

        val cut = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never,
            observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count },
            limits = LinearAuthorityAggregateCuts.pointSourcePairLimits,
            point = mapOf(x to 0.75, y to 0.75, real to 1.5, otherReal to 1.5)).single()

        assertEquals(SourceInequality(mapOf(x to BigFraction.ONE, y to BigFraction.ONE), BigFraction.ONE),
            cut.inequality)
        assertEquals(setOf(0, 1), cut.owners)
        assertEquals(1L, observations["AggregateRetainedContinuationDuplicate"])
        assertNull(observations["AggregateContinuationInverseSkipped"])
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
            SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
    }

    @Test
    fun `an inverse source row should leave continuation for a different four row cut`() {
        val z = SourceCoordinate(SourceCoordinate.Kind.Integer, 2)
        val bridge = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val otherReal = SourceCoordinate(SourceCoordinate.Kind.Real, 2)
        val firstReal = SourceCoordinate(SourceCoordinate.Kind.Real, 3)
        val source = SourceProofContext(scope, setOf(x, y, z, real, bridge, otherReal, firstReal), linkedMapOf(
            "first" to SourcePremise(SourceInequality(mapOf(firstReal to BigFraction.ONE,
                z to BigFraction.ofLong(-2)), BigFraction.ZERO)),
            "first demand" to SourcePremise(SourceInequality(mapOf(firstReal to BigFraction.MINUS_ONE),
                BigFraction.MINUS_ONE)),
            "left" to SourcePremise(SourceInequality(mapOf(real to BigFraction.ONE,
                x to BigFraction.ofLong(-2)), BigFraction.ZERO)),
            "bridge" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE,
                bridge to BigFraction.ONE), BigFraction.ZERO)),
            "reverse" to SourcePremise(SourceInequality(mapOf(real to BigFraction.ONE,
                bridge to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "right" to SourcePremise(SourceInequality(mapOf(otherReal to BigFraction.ONE,
                y to BigFraction.ofLong(-2)), BigFraction.ZERO)),
            "demand" to SourcePremise(SourceInequality(mapOf(bridge to BigFraction.MINUS_ONE,
                otherReal to BigFraction.MINUS_ONE), BigFraction.ofLong(-3))),
        ))
        val observations = mutableMapOf<String, Long>()
        val limits = LinearAuthorityAggregateCuts.pointSourcePairLimits

        val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never,
            observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count }, limits = limits,
            point = mapOf(x to 0.75, y to 0.75, z to 0.5, real to 1.5, bridge to 1.5,
                otherReal to 1.5, firstReal to 1.0))

        assertEquals(listOf(
            SourceInequality(mapOf(z to BigFraction.MINUS_ONE), BigFraction.MINUS_ONE),
            SourceInequality(mapOf(x to BigFraction.MINUS_ONE, y to BigFraction.MINUS_ONE), BigFraction.ofLong(-2)),
        ), cuts.map { it.inequality })
        assertEquals(listOf(setOf(0, 1), setOf(2, 3, 5, 6)), cuts.map { it.owners })
        assertEquals(1L, observations["AggregateContinuationInverseSkipped"])
        assertEquals(1L, observations["AggregateRetainedContinuation"])
        assertTrue((observations["AggregateWork"] ?: 0L) <= limits.work)
        assertTrue((observations["AggregateAllocation"] ?: 0L) <= limits.allocation)
        for (cut in cuts) {
            assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
                SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
        }
    }

    @Test
    fun `an oversized continuation should leave room for a compatible source row`() {
        val z = SourceCoordinate(SourceCoordinate.Kind.Integer, 2)
        val bridge = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val otherReal = SourceCoordinate(SourceCoordinate.Kind.Real, 2)
        val firstReal = SourceCoordinate(SourceCoordinate.Kind.Real, 3)
        val extra = SourceCoordinate(SourceCoordinate.Kind.Real, 4)
        val otherExtra = SourceCoordinate(SourceCoordinate.Kind.Real, 5)
        val source = SourceProofContext(scope, setOf(x, y, z, real, bridge, otherReal, firstReal, extra, otherExtra),
            linkedMapOf(
                "first" to SourcePremise(SourceInequality(mapOf(firstReal to BigFraction.ONE,
                    z to BigFraction.ofLong(-2)), BigFraction.ZERO)),
                "first demand" to SourcePremise(SourceInequality(mapOf(firstReal to BigFraction.MINUS_ONE),
                    BigFraction.MINUS_ONE)),
                "left" to SourcePremise(SourceInequality(mapOf(real to BigFraction.ONE,
                    x to BigFraction.ofLong(-2)), BigFraction.ZERO)),
                "bridge" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE,
                    bridge to BigFraction.ONE), BigFraction.ZERO)),
                "oversized" to SourcePremise(SourceInequality(mapOf(bridge to BigFraction.MINUS_ONE,
                    otherReal to BigFraction.MINUS_ONE, extra to BigFraction.ONE, otherExtra to BigFraction.ONE),
                    BigFraction.ofLong(-3))),
                "right" to SourcePremise(SourceInequality(mapOf(otherReal to BigFraction.ONE,
                    y to BigFraction.ofLong(-2)), BigFraction.ZERO)),
                "demand" to SourcePremise(SourceInequality(mapOf(bridge to BigFraction.MINUS_ONE,
                    otherReal to BigFraction.MINUS_ONE), BigFraction.ofLong(-3))),
            ))
        val observations = mutableMapOf<String, Long>()
        val limits = LinearAuthorityAggregateCuts.pointSourcePairLimits.copy(support = 5)

        val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never,
            observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count }, limits = limits,
            point = mapOf(x to 0.75, y to 0.75, z to 0.5, real to 1.5, bridge to 1.5,
                otherReal to 1.5, firstReal to 1.0, extra to 0.0, otherExtra to 0.0))

        assertEquals(listOf(
            SourceInequality(mapOf(z to BigFraction.MINUS_ONE), BigFraction.MINUS_ONE),
            SourceInequality(mapOf(x to BigFraction.MINUS_ONE, y to BigFraction.MINUS_ONE), BigFraction.ofLong(-2)),
        ), cuts.map { it.inequality })
        assertEquals(listOf(setOf(0, 1), setOf(2, 3, 5, 6)), cuts.map { it.owners })
        assertTrue((observations["AggregateContinuationSupportSkipped"] ?: 0L) > 0L)
        assertNull(observations["AggregateSupportLimit"])
        assertTrue((observations["AggregateWork"] ?: 0L) <= limits.work)
        assertTrue((observations["AggregateAllocation"] ?: 0L) <= limits.allocation)
        for (cut in cuts) {
            assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
                SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
        }
    }

    @Test
    fun `continuation support should include cancelled source coordinates`() {
        val z = SourceCoordinate(SourceCoordinate.Kind.Integer, 2)
        val bridge = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val otherReal = SourceCoordinate(SourceCoordinate.Kind.Real, 2)
        val firstReal = SourceCoordinate(SourceCoordinate.Kind.Real, 3)
        val source = SourceProofContext(scope, setOf(x, y, z, real, bridge, otherReal, firstReal), linkedMapOf(
            "first" to SourcePremise(SourceInequality(mapOf(firstReal to BigFraction.ONE,
                z to BigFraction.ofLong(-2)), BigFraction.ZERO)),
            "first demand" to SourcePremise(SourceInequality(mapOf(firstReal to BigFraction.MINUS_ONE),
                BigFraction.MINUS_ONE)),
            "left" to SourcePremise(SourceInequality(mapOf(real to BigFraction.ONE,
                x to BigFraction.ofLong(-2)), BigFraction.ZERO)),
            "bridge" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE,
                bridge to BigFraction.ONE), BigFraction.ZERO)),
            "right" to SourcePremise(SourceInequality(mapOf(otherReal to BigFraction.ONE,
                y to BigFraction.ofLong(-2)), BigFraction.ZERO)),
            "demand" to SourcePremise(SourceInequality(mapOf(bridge to BigFraction.MINUS_ONE,
                otherReal to BigFraction.MINUS_ONE), BigFraction.ofLong(-3))),
        ))
        val observations = mutableMapOf<String, Long>()
        val limits = LinearAuthorityAggregateCuts.pointSourcePairLimits.copy(support = 4)

        val cut = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never,
            observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count }, limits = limits,
            point = mapOf(x to 0.75, y to 0.75, z to 0.5, real to 1.5, bridge to 1.5,
                otherReal to 1.5, firstReal to 1.0)).single()

        assertEquals(SourceInequality(mapOf(z to BigFraction.MINUS_ONE), BigFraction.MINUS_ONE), cut.inequality)
        assertEquals(setOf(0, 1), cut.owners)
        assertTrue((observations["AggregateContinuationSupportSkipped"] ?: 0L) > 0L)
        assertNull(observations["AggregateSupportLimit"])
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
            SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
    }

    @Test
    fun `cancelling support screening should retain the completed source cut`() {
        val z = SourceCoordinate(SourceCoordinate.Kind.Integer, 2)
        val bridge = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val otherReal = SourceCoordinate(SourceCoordinate.Kind.Real, 2)
        val firstReal = SourceCoordinate(SourceCoordinate.Kind.Real, 3)
        val source = SourceProofContext(scope, setOf(x, y, z, real, bridge, otherReal, firstReal), linkedMapOf(
            "first" to SourcePremise(SourceInequality(mapOf(firstReal to BigFraction.ONE,
                z to BigFraction.ofLong(-2)), BigFraction.ZERO)),
            "first demand" to SourcePremise(SourceInequality(mapOf(firstReal to BigFraction.MINUS_ONE),
                BigFraction.MINUS_ONE)),
            "left" to SourcePremise(SourceInequality(mapOf(real to BigFraction.ONE,
                x to BigFraction.ofLong(-2)), BigFraction.ZERO)),
            "bridge" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE,
                bridge to BigFraction.ONE), BigFraction.ZERO)),
            "right" to SourcePremise(SourceInequality(mapOf(otherReal to BigFraction.ONE,
                y to BigFraction.ofLong(-2)), BigFraction.ZERO)),
            "demand" to SourcePremise(SourceInequality(mapOf(bridge to BigFraction.MINUS_ONE,
                otherReal to BigFraction.MINUS_ONE), BigFraction.ofLong(-3))),
        ))
        val observations = mutableMapOf<String, Long>()
        val limits = LinearAuthorityAggregateCuts.pointSourcePairLimits.copy(support = 4)
        var stopped = false

        val cut = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl { stopped },
            observe = { name, count ->
                observations[name] = (observations[name] ?: 0L) + count
                if (name == "AggregateContinuationSupportSkipped") stopped = true
            }, limits = limits, point = mapOf(x to 0.75, y to 0.75, z to 0.5, real to 1.5,
                bridge to 1.5, otherReal to 1.5, firstReal to 1.0)).single()

        assertTrue(stopped)
        assertEquals(SourceInequality(mapOf(z to BigFraction.MINUS_ONE), BigFraction.MINUS_ONE), cut.inequality)
        assertEquals(setOf(0, 1), cut.owners)
        assertTrue((observations["AggregateWork"] ?: 0L) <= limits.work)
        assertTrue((observations["AggregateAllocation"] ?: 0L) <= limits.allocation)
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
            SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
    }

    @Test
    fun `opposite source rows should remain eligible when their required weights differ`() {
        val z = SourceCoordinate(SourceCoordinate.Kind.Integer, 2)
        val bridge = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val firstReal = SourceCoordinate(SourceCoordinate.Kind.Real, 2)
        val source = SourceProofContext(scope, setOf(x, y, z, real, bridge, firstReal), linkedMapOf(
            "first" to SourcePremise(SourceInequality(mapOf(firstReal to BigFraction.ONE,
                z to BigFraction.ofLong(-2)), BigFraction.ZERO)),
            "first demand" to SourcePremise(SourceInequality(mapOf(firstReal to BigFraction.MINUS_ONE),
                BigFraction.MINUS_ONE)),
            "left" to SourcePremise(SourceInequality(mapOf(real to BigFraction.ONE, bridge to BigFraction.ONE,
                x to BigFraction.ofLong(-2)), BigFraction.ZERO)),
            "bridge" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE,
                bridge to BigFraction.ONE), BigFraction.ZERO)),
            "reverse" to SourcePremise(SourceInequality(mapOf(real to BigFraction.ONE,
                bridge to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "demand" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE,
                y to BigFraction.ofLong(-2)), BigFraction.ofLong(-13) * BigFraction.ofLong(4).reciprocal())),
        ))
        val observations = mutableMapOf<String, Long>()

        val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never,
            observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count },
            limits = LinearAuthorityAggregateCuts.pointSourcePairLimits,
            point = mapOf(x to 0.75, y to 1.25, z to 0.5, real to 0.75, bridge to 0.75, firstReal to 1.0))

        assertEquals(listOf(
            SourceInequality(mapOf(z to BigFraction.MINUS_ONE), BigFraction.MINUS_ONE),
            SourceInequality(mapOf(x to BigFraction.MINUS_ONE, y to BigFraction.ofLong(-2)), BigFraction.ofLong(-4)),
        ), cuts.map { it.inequality })
        assertEquals(listOf(setOf(0, 1), setOf(2, 3, 4, 5)), cuts.map { it.owners })
        assertNull(observations["AggregateContinuationInverseSkipped"])
        for (cut in cuts) {
            assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
                SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
        }
    }

    @Test
    fun `cancelling an inverse comparison should retain the completed source cut`() {
        val z = SourceCoordinate(SourceCoordinate.Kind.Integer, 2)
        val bridge = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val otherReal = SourceCoordinate(SourceCoordinate.Kind.Real, 2)
        val firstReal = SourceCoordinate(SourceCoordinate.Kind.Real, 3)
        val source = SourceProofContext(scope, setOf(x, y, z, real, bridge, otherReal, firstReal), linkedMapOf(
            "first" to SourcePremise(SourceInequality(mapOf(firstReal to BigFraction.ONE,
                z to BigFraction.ofLong(-2)), BigFraction.ZERO)),
            "first demand" to SourcePremise(SourceInequality(mapOf(firstReal to BigFraction.MINUS_ONE),
                BigFraction.MINUS_ONE)),
            "left" to SourcePremise(SourceInequality(mapOf(real to BigFraction.ONE,
                x to BigFraction.ofLong(-2)), BigFraction.ZERO)),
            "bridge" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE,
                bridge to BigFraction.ONE), BigFraction.ZERO)),
            "reverse" to SourcePremise(SourceInequality(mapOf(real to BigFraction.ONE,
                bridge to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "right" to SourcePremise(SourceInequality(mapOf(otherReal to BigFraction.ONE,
                y to BigFraction.ofLong(-2)), BigFraction.ZERO)),
            "demand" to SourcePremise(SourceInequality(mapOf(bridge to BigFraction.MINUS_ONE,
                otherReal to BigFraction.MINUS_ONE), BigFraction.ofLong(-3))),
        ))
        val observations = mutableMapOf<String, Long>()
        var stopped = false

        val cut = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl { stopped },
            observe = { name, count ->
                observations[name] = (observations[name] ?: 0L) + count
                if (name == "AggregateContinuationInverseSkipped") stopped = true
            }, limits = LinearAuthorityAggregateCuts.pointSourcePairLimits,
            point = mapOf(x to 0.75, y to 0.75, z to 0.5, real to 1.5, bridge to 1.5,
                otherReal to 1.5, firstReal to 1.0)).single()

        assertTrue(stopped)
        assertEquals(SourceInequality(mapOf(z to BigFraction.MINUS_ONE), BigFraction.MINUS_ONE), cut.inequality)
        assertEquals(setOf(0, 1), cut.owners)
        assertTrue((observations["AggregateWork"] ?: 0L) > 0L)
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
            SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
    }

    @Test
    fun `equal continued inequalities should retain different supporting owners`() {
        val firstReal = SourceCoordinate(SourceCoordinate.Kind.Real, 2)
        val otherReal = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val source = SourceProofContext(scope, setOf(x, y, real, otherReal, firstReal), linkedMapOf(
            "first" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                firstReal to BigFraction.ONE), BigFraction.ofLong(3))),
            "first complement" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(2),
                firstReal to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                real to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(2),
                otherReal to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "sum" to SourcePremise(SourceInequality(mapOf(real to BigFraction.ONE,
                otherReal to BigFraction.ONE), BigFraction.ofLong(3))),
        ))

        val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never,
            limits = LinearAuthorityAggregateCuts.pointSourcePairLimits,
            point = mapOf(x to 0.75, y to 0.75, real to 1.5, otherReal to 1.5, firstReal to 1.5))

        val inequality = SourceInequality(mapOf(x to BigFraction.ONE, y to BigFraction.ONE), BigFraction.ONE)
        assertEquals(listOf(inequality, inequality), cuts.map { it.inequality })
        assertEquals(listOf(setOf(0, 1), setOf(2, 3, 4)), cuts.map { it.owners })
        for (cut in cuts) {
            assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
                SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
        }
    }

    @Test
    fun `cancelling a later continuation should retain the completed source cut`() {
        val z = SourceCoordinate(SourceCoordinate.Kind.Integer, 2)
        val otherReal = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val firstReal = SourceCoordinate(SourceCoordinate.Kind.Real, 2)
        val source = SourceProofContext(scope, setOf(x, y, z, real, otherReal, firstReal), linkedMapOf(
            "first" to SourcePremise(SourceInequality(mapOf(firstReal to BigFraction.ONE,
                z to BigFraction.ofLong(-2)), BigFraction.ZERO)),
            "first demand" to SourcePremise(SourceInequality(mapOf(firstReal to BigFraction.MINUS_ONE),
                BigFraction.MINUS_ONE)),
            "left" to SourcePremise(SourceInequality(mapOf(real to BigFraction.ONE,
                x to BigFraction.ofLong(-2)), BigFraction.ZERO)),
            "right" to SourcePremise(SourceInequality(mapOf(otherReal to BigFraction.ONE,
                y to BigFraction.ofLong(-2)), BigFraction.ZERO)),
            "demand" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE,
                otherReal to BigFraction.MINUS_ONE), BigFraction.ofLong(-3))),
        ))
        val observations = mutableMapOf<String, Long>()
        var stopped = false

        val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl { stopped },
            observe = { name, count ->
                observations[name] = (observations[name] ?: 0L) + count
                if (name == "AggregateContinuationScored") stopped = true
            }, point = mapOf(x to 0.75, y to 0.75, z to 0.5,
                real to 1.5, otherReal to 1.5, firstReal to 1.0))

        assertTrue(stopped)
        val cut = cuts.single()
        assertEquals(SourceInequality(mapOf(z to BigFraction.MINUS_ONE), BigFraction.MINUS_ONE), cut.inequality)
        assertEquals(setOf(0, 1), cut.owners)
        assertTrue((observations["AggregateWork"] ?: 0L) > 0L)
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
            SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
    }

    @Test
    fun `a full candidate pool should bypass retained pair continuation`() {
        val z = SourceCoordinate(SourceCoordinate.Kind.Integer, 2)
        val otherReal = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val firstReal = SourceCoordinate(SourceCoordinate.Kind.Real, 2)
        val source = SourceProofContext(scope, setOf(x, y, z, real, otherReal, firstReal), linkedMapOf(
            "first" to SourcePremise(SourceInequality(mapOf(firstReal to BigFraction.ONE,
                z to BigFraction.ofLong(-2)), BigFraction.ZERO)),
            "first demand" to SourcePremise(SourceInequality(mapOf(firstReal to BigFraction.MINUS_ONE),
                BigFraction.MINUS_ONE)),
            "left" to SourcePremise(SourceInequality(mapOf(real to BigFraction.ONE,
                x to BigFraction.ofLong(-2)), BigFraction.ZERO)),
            "right" to SourcePremise(SourceInequality(mapOf(otherReal to BigFraction.ONE,
                y to BigFraction.ofLong(-2)), BigFraction.ZERO)),
            "demand" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE,
                otherReal to BigFraction.MINUS_ONE), BigFraction.ofLong(-3))),
        ))
        val observations = mutableMapOf<String, Long>()

        val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never,
            observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count },
            maximumCandidates = 1, point = mapOf(x to 0.75, y to 0.75, z to 0.5,
                real to 1.5, otherReal to 1.5, firstReal to 1.0))

        assertEquals(SourceInequality(mapOf(z to BigFraction.MINUS_ONE), BigFraction.MINUS_ONE),
            cuts.single().inequality)
        assertNull(observations["AggregateRetainedContinuation"])
        assertNull(observations["AggregateContinuationScored"])
    }

    @Test
    fun `a tight continuation should separate before loose rows exhaust the production allowance`() {
        val otherReal = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val premises = linkedMapOf(
            "left" to SourcePremise(SourceInequality(mapOf(real to BigFraction.ONE,
                x to BigFraction.ofLong(-2)), BigFraction.ZERO)),
            "demand" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE,
                otherReal to BigFraction.MINUS_ONE), BigFraction.ofLong(-3))),
        )
        repeat(512) { index ->
            premises["loose $index"] = SourcePremise(SourceInequality(mapOf(otherReal to BigFraction.ONE,
                y to BigFraction.ofLong(-2)), BigFraction.ofLong(100)))
        }
        premises["tight"] = SourcePremise(SourceInequality(mapOf(otherReal to BigFraction.ONE,
            y to BigFraction.ofLong(-2)), BigFraction.ZERO))
        val source = SourceProofContext(scope, setOf(x, y, real, otherReal), premises)
        val observations = mutableMapOf<String, Long>()
        val limits = LinearAuthorityAggregateCuts.pointSourcePairLimits

        val cut = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never,
            observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count },
            maximumCandidates = 1, limits = limits,
            point = mapOf(x to 0.75, y to 0.75, real to 1.5, otherReal to 1.5)).single()

        assertEquals(SourceInequality(mapOf(x to BigFraction.MINUS_ONE, y to BigFraction.MINUS_ONE),
            BigFraction.ofLong(-2)), cut.inequality)
        assertEquals(setOf(0, 1, 514), cut.owners)
        assertTrue((observations["AggregateContinuationScored"] ?: 0L) > 0L)
        assertTrue((observations["AggregateWork"] ?: 0L) <= limits.work)
        assertTrue((observations["AggregateAllocation"] ?: 0L) <= limits.allocation)
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
            SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
    }

    @Test
    fun `cancelling continuation scoring should withhold its unfinished choice`() {
        val otherReal = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val source = SourceProofContext(scope, setOf(x, y, real, otherReal), linkedMapOf(
            "left" to SourcePremise(SourceInequality(mapOf(real to BigFraction.ONE,
                x to BigFraction.ofLong(-2)), BigFraction.ZERO)),
            "demand" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE,
                otherReal to BigFraction.MINUS_ONE), BigFraction.ofLong(-3))),
            "loose" to SourcePremise(SourceInequality(mapOf(otherReal to BigFraction.ONE,
                y to BigFraction.ofLong(-2)), BigFraction.ofLong(100))),
            "tight" to SourcePremise(SourceInequality(mapOf(otherReal to BigFraction.ONE,
                y to BigFraction.ofLong(-2)), BigFraction.ZERO)),
        ))
        val observations = mutableMapOf<String, Long>()
        var stopped = false

        val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl { stopped },
            observe = { name, count ->
                observations[name] = (observations[name] ?: 0L) + count
                if (name == "AggregateContinuationScored") stopped = true
            }, limits = LinearAuthorityAggregateCuts.pointSourcePairLimits,
            point = mapOf(x to 0.75, y to 0.75, real to 1.5, otherReal to 1.5))

        assertTrue(stopped)
        assertTrue(cuts.isEmpty())
        assertNull(observations["AggregateContinuationComplete"])
        assertNull(observations["AggregateExtendedAttempts"])
    }

    @Test
    fun `point separation should require a fourth original row when three leave a continuous residual`() {
        val z = SourceCoordinate(SourceCoordinate.Kind.Integer, 2)
        val otherReal = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val lastReal = SourceCoordinate(SourceCoordinate.Kind.Real, 2)
        val source = SourceProofContext(scope, setOf(x, y, z, real, otherReal, lastReal), linkedMapOf(
            "left" to SourcePremise(SourceInequality(mapOf(real to BigFraction.ONE,
                x to BigFraction.ofLong(-2)), BigFraction.ZERO)),
            "middle" to SourcePremise(SourceInequality(mapOf(otherReal to BigFraction.ONE,
                y to BigFraction.ofLong(-2)), BigFraction.ZERO)),
            "right" to SourcePremise(SourceInequality(mapOf(lastReal to BigFraction.ONE,
                z to BigFraction.ofLong(-2)), BigFraction.ZERO)),
            "demand" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE,
                otherReal to BigFraction.MINUS_ONE, lastReal to BigFraction.MINUS_ONE), BigFraction.ofLong(-5))),
        ))
        val point = mapOf(x to 5.0 / 6, y to 5.0 / 6, z to 5.0 / 6,
            real to 5.0 / 3, otherReal to 5.0 / 3, lastReal to 5.0 / 3)

        for (rows in listOf(2, 3, 4)) {
            val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never,
                maximumCandidates = 1,
                limits = LinearAuthorityAggregateCuts.pointSourcePairLimits.copy(rows = rows), point = point)

            if (rows < 4) assertTrue(cuts.isEmpty()) else {
                val cut = cuts.single()
                assertEquals(SourceInequality(mapOf(x to BigFraction.MINUS_ONE, y to BigFraction.MINUS_ONE,
                    z to BigFraction.MINUS_ONE), BigFraction.ofLong(-3)), cut.inequality)
                assertEquals(setOf(0, 1, 2, 3), cut.owners)
                assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
                    SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
            }
        }
    }

    @Test
    fun `interrupting an extended derivation should withhold its partial proposal`() {
        val otherReal = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val source = SourceProofContext(scope, setOf(x, y, real, otherReal), linkedMapOf(
            "left" to SourcePremise(SourceInequality(mapOf(real to BigFraction.ONE,
                x to BigFraction.ofLong(-2)), BigFraction.ZERO)),
            "right" to SourcePremise(SourceInequality(mapOf(otherReal to BigFraction.ONE,
                y to BigFraction.ofLong(-2)), BigFraction.ZERO)),
            "demand" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE,
                otherReal to BigFraction.MINUS_ONE), BigFraction.ofLong(-3))),
            "lower" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "other lower" to SourcePremise(SourceInequality(mapOf(otherReal to BigFraction.MINUS_ONE),
                BigFraction.ZERO)),
        ))
        var stopped = false

        val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl { stopped },
            observe = { name, _ -> if (name == "AggregateExtendedAttempts") stopped = true },
            limits = LinearAuthorityAggregateCuts.pointSourcePairLimits,
            point = mapOf(x to 0.75, y to 0.75, real to 1.5, otherReal to 1.5))

        assertTrue(stopped)
        assertTrue(cuts.isEmpty())
    }

    @Test
    fun `a source row should support multiple differently weighted pairs`() {
        val z = SourceCoordinate(SourceCoordinate.Kind.Integer, 2)
        val weightedLeft = SourceInequality(mapOf(x to BigFraction.ofLong(2), real to BigFraction.ofLong(3)),
            BigFraction.ofLong(3))
        val weightedRight = SourceInequality(mapOf(y to BigFraction.ofLong(4), real to BigFraction.ofLong(-2)),
            BigFraction.ZERO)
        val otherRight = SourceInequality(mapOf(z to BigFraction.ofLong(2), real to BigFraction.MINUS_ONE),
            BigFraction.ZERO)
        val source = SourceProofContext(scope, setOf(x, y, z, real), linkedMapOf(
            "left" to SourcePremise(weightedLeft), "right" to SourcePremise(weightedRight),
            "other" to SourcePremise(otherRight)))

        val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never)

        assertEquals(setOf(
            SourceInequality(mapOf(x to BigFraction.ONE, y to BigFraction.ofLong(3)), BigFraction.ONE),
            SourceInequality(mapOf(x to BigFraction.ONE, z to BigFraction.ofLong(3)), BigFraction.ONE),
        ), cuts.map { it.inequality }.toSet())
        for (cut in cuts) {
            assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
                SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
        }
    }

    @Test
    fun `an unavailable point coordinate should withhold complete proposal coverage`() {
        val source = SourceProofContext(scope, setOf(x, y, real), mapOf(
            "left" to SourcePremise(left), "right" to SourcePremise(right)))
        val observations = mutableMapOf<String, Long>()

        LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never,
            observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count },
            point = mapOf(x to 0.75, real to 1.5))

        assertEquals(1L, observations["AggregatePairIncomplete"])
    }

    @Test
    fun `source pair preparation should retain its containing work and storage limits`() {
        val source = SourceProofContext(scope, setOf(x, y, real), mapOf(
            "left" to SourcePremise(left), "right" to SourcePremise(right)))

        for (limits in listOf(LinearAuthorityAggregateLimits(work = 1),
            LinearAuthorityAggregateLimits(allocation = 1))) {
            val observations = mutableMapOf<String, Long>()
            val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never,
                observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count }, limits = limits)

            assertTrue(cuts.isEmpty())
            assertTrue((observations["AggregateWork"] ?: 0L) <= limits.work)
            assertTrue((observations["AggregateAllocation"] ?: 0L) <= limits.allocation)
        }
    }
}
