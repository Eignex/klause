package com.eignex.klause.lp.optimization

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.engine.CutFamily
import com.eignex.klause.lp.optimization.AggregateCutTestSource.real
import com.eignex.klause.lp.optimization.AggregateCutTestSource.scope
import com.eignex.klause.lp.optimization.AggregateCutTestSource.x
import com.eignex.klause.lp.optimization.AggregateCutTestSource.y
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCondition
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceEvidenceVerifier
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourcePremise
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinearAuthorityIntegerSidesTest {
    @Test
    fun `integer side substitution should strengthen the checked numerical bound`() {
        val source = SourceProofContext(scope.copy(objective = SourceObjective("sum", ObjectiveSense.MINIMIZE)),
            setOf(x, y, real), linkedMapOf(
                "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(3),
                    real to BigFraction.ONE), BigFraction.ofLong(2))),
                "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(2),
                    real to BigFraction.MINUS_ONE), BigFraction.ofLong(2))),
                "lower" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.ZERO)),
                "upper" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ONE)),
            ), mapOf(x to BigFraction.ofLong(-2), y to BigFraction.MINUS_ONE))

        val result = LinearAuthority(SourceLpBudget(), retainLp = true, aggregateCuts = true).use { authority ->
            authority.advanceWithPointCuts(source, ExecutionControl.Never)
        }

        assertEquals(SourceValue.Rational(BigFraction.ofLong(-2)),
            assertIs<SourceClaim.Bound>(assertNotNull(result.bound).claim).value)
        assertTrue(CutFamily.AGGREGATION_MIR in result.cutBoundFamilies)
        assertEquals(SourceEvidenceCheck.Checked,
            SourceEvidenceVerifier(source.withLinearAuthority()).verify(assertNotNull(result.bound)))
    }

    @Test
    fun `integer side substitution should separate either sign of an odd coefficient`() {
        for (sign in listOf(-1L, 1L)) {
            val source = SourceProofContext(scope, setOf(x, y, real), linkedMapOf(
                "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(3L * sign),
                    real to BigFraction.ONE), BigFraction.ofLong(2))),
                "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(2),
                    real to BigFraction.MINUS_ONE), BigFraction.ofLong(if (sign > 0) 2 else 1))),
                "lower" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE),
                    BigFraction.ofLong(if (sign > 0) 0 else 1))),
                "upper" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE),
                    BigFraction.ofLong(if (sign > 0) 1 else 0))),
            ))

            val cut = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never,
                point = mapOf(x to 0.75 * sign, y to (if (sign > 0) 0.875 else 0.375), real to -0.25)).single()

            assertEquals(SourceInequality(mapOf(x to BigFraction.ofLong(if (sign > 0) 2 else -1),
                y to BigFraction.ONE), BigFraction.ofLong(if (sign > 0) 2 else 1)), cut.inequality)
            assertEquals(setOf(0, 1, 3), cut.owners)
            assertEquals(CutFamily.AGGREGATION_MIR, cut.family)
            assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
                SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
        }
    }

    @Test
    fun `integer side scoring should remove strictness belonging only to the replaced lower bound`() {
        val source = SourceProofContext(scope, setOf(x, y, real), linkedMapOf(
            "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(3),
                real to BigFraction.ONE), BigFraction.ofLong(2))),
            "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(2),
                real to BigFraction.MINUS_ONE), BigFraction.ofLong(2))),
            "lower" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE),
                BigFraction.ZERO, strict = true)),
            "upper" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ofLong(4))),
        ))
        val observations = mutableMapOf<String, Long>()

        val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never,
            observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count },
            point = mapOf(x to 3.5, y to -3.25, real to -8.5))

        assertTrue(cuts.isEmpty())
        assertEquals(1L, observations["AggregateHalfSideScans"])
        assertNull(observations["AggregateHalfSideAttempts"])
    }

    @Test
    fun `integer side substitution should retain upper premise guards and pins`() {
        val bool = SourceCoordinate(SourceCoordinate.Kind.Boolean, 0)
        val pin = SourceCoordinate(SourceCoordinate.Kind.Integer, 2)
        val source = SourceProofContext(scope, setOf(x, y, real, bool, pin), linkedMapOf(
            "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(3),
                real to BigFraction.ONE), BigFraction.ofLong(2))),
            "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(2),
                real to BigFraction.MINUS_ONE), BigFraction.ofLong(2))),
            "lower" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "upper" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ONE),
                mapOf(0 to true), mapOf(pin to BigFraction.ONE)),
        ))
        val established = SourceCondition(bools = mapOf(0 to true),
            lower = mapOf(pin to BigFraction.ONE), upper = mapOf(pin to BigFraction.ONE))
        val cut = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never,
            point = mapOf(x to 0.75, y to 0.875, real to -0.25)).single()

        for (condition in listOf(established, SourceCondition(lower = established.lower, upper = established.upper),
            SourceCondition(bools = established.bools, lower = established.lower), established)) {
            val context = source.atScope(scope.copy(condition = condition))
            val check = SourceEvidenceVerifier(context).verify(
                SourceEvidence(context.scope, SourceClaim.Consequence(cut.inequality), cut.derivation))

            if (condition == established) assertEquals(SourceEvidenceCheck.Checked, check)
            else assertIs<SourceEvidenceCheck.Rejected>(check)
        }
    }

    @Test
    fun `integer side substitution should release the replaced lower premise`() {
        val bool = SourceCoordinate(SourceCoordinate.Kind.Boolean, 0)
        val source = SourceProofContext(scope.copy(condition = SourceCondition(bools = mapOf(0 to false))),
            setOf(x, y, real, bool), linkedMapOf(
                "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(3),
                    real to BigFraction.ONE), BigFraction.ofLong(2))),
                "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(2),
                    real to BigFraction.MINUS_ONE), BigFraction.ofLong(2))),
                "lower" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE),
                    BigFraction.ZERO, strict = true), mapOf(0 to true)),
                "upper" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ofLong(3))),
            ))

        val cut = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never,
            point = mapOf(x to 2.75, y to -2.125, real to -6.25)).single()

        assertEquals(setOf(0, 1, 3), cut.owners)
        assertEquals(SourceInequality(mapOf(x to BigFraction.ofLong(2), y to BigFraction.ONE),
            BigFraction.ofLong(3)), cut.inequality)
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
            SourceEvidence(source.scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
    }

    @Test
    fun `integer side substitution should rebuild a changed upper premise`() {
        for (upper in listOf(1L, 2L, 1L)) {
            val source = SourceProofContext(scope, setOf(x, y, real), linkedMapOf(
                "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(3),
                    real to BigFraction.ONE), BigFraction.ofLong(2))),
                "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(2),
                    real to BigFraction.MINUS_ONE), BigFraction.ofLong(2))),
                "lower" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.ZERO)),
                "upper" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ofLong(upper))),
            ))

            val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never,
                point = mapOf(x to 0.75, y to 0.875, real to -0.25))

            if (upper == 1L) {
                val cut = cuts.single()
                assertEquals(BigFraction.ofLong(2), cut.inequality.rhs)
                assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
                    SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
            } else assertTrue(cuts.isEmpty())
        }
    }

    @Test
    fun `integer side interruption should withhold its partial proof`() {
        val source = SourceProofContext(scope, setOf(x, y, real), linkedMapOf(
            "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(3),
                real to BigFraction.ONE), BigFraction.ofLong(2))),
            "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(2),
                real to BigFraction.MINUS_ONE), BigFraction.ofLong(2))),
            "lower" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "upper" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ONE)),
        ))
        var stopped = false

        val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl { stopped },
            observe = { name, _ -> if (name == "AggregateHalfSideAttempts") stopped = true },
            point = mapOf(x to 0.75, y to 0.875, real to -0.25))

        assertTrue(stopped)
        assertTrue(cuts.isEmpty())
    }

    @Test
    fun `integer side substitution should share spent generation work and allocation`() {
        val source = SourceProofContext(scope, setOf(x, y, real), linkedMapOf(
            "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(3),
                real to BigFraction.ONE), BigFraction.ofLong(2))),
            "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(2),
                real to BigFraction.MINUS_ONE), BigFraction.ofLong(2))),
            "lower" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "upper" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ONE)),
        ))
        val point = mapOf(x to 0.75, y to 0.875, real to -0.25)
        val limits = LinearAuthorityAggregateCuts.pointSourcePairLimits
        var work = 0L
        var allocation = 0L
        var beforeWork = 0L
        var beforeAllocation = 0L
        val complete = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never,
            observe = { name, count ->
                if (name == "AggregateWork") work += count
                if (name == "AggregateAllocation") allocation += count
                if (name == "AggregateHalfSideAttempts") {
                    beforeWork = work
                    beforeAllocation = allocation
                }
            }, point = point)
        assertEquals(1, complete.size)
        assertTrue(beforeWork > 0L && beforeAllocation > 0L)

        for (limited in listOf(limits.copy(work = beforeWork), limits.copy(allocation = beforeAllocation))) {
            val observations = mutableMapOf<String, Long>()
            val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never,
                observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count },
                limits = limited, point = point)

            assertTrue(cuts.isEmpty())
            assertTrue((observations["AggregateWork"] ?: 0L) <= limited.work)
            assertTrue((observations["AggregateAllocation"] ?: 0L) <= limited.allocation)
        }
    }

    @Test
    fun `integer side scoring should build one separating substitution among several coordinates`() {
        val z = SourceCoordinate(SourceCoordinate.Kind.Integer, 2)
        val source = SourceProofContext(scope, setOf(x, y, z, real), linkedMapOf(
            "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(3),
                z to BigFraction.ofLong(2), real to BigFraction.ONE), BigFraction.ofLong(2))),
            "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(3),
                real to BigFraction.MINUS_ONE), BigFraction.ofLong(2))),
            "x lower" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "x upper" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ONE)),
            "y lower" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "y upper" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ONE), BigFraction.ONE)),
        ))
        val observations = mutableMapOf<String, Long>()

        val cut = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never,
            observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count },
            point = mapOf(x to 0.8, y to 0.6, z to -0.1, real to -0.2)).single()

        assertEquals(SourceInequality(mapOf(x to BigFraction.ofLong(2), y to BigFraction.ONE,
            z to BigFraction.ONE), BigFraction.ofLong(2)), cut.inequality)
        assertEquals(setOf(0, 1, 3, 4), cut.owners)
        assertEquals(1L, observations["AggregateHalfSideAttempts"])
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
            SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
    }

    @Test
    fun `integer point sides should combine upper complements into a checked separating cut`() {
        val z = SourceCoordinate(SourceCoordinate.Kind.Integer, 2)
        val source = SourceProofContext(scope,
            setOf(x, y, z, real), linkedMapOf(
                "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(3),
                    z to BigFraction.ofLong(2), real to BigFraction.ONE), BigFraction.ofLong(2))),
                "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(3),
                    real to BigFraction.MINUS_ONE), BigFraction.ofLong(3))),
                "x lower" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.ZERO)),
                "x upper" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ONE)),
                "y lower" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE), BigFraction.ZERO)),
                "y upper" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ONE), BigFraction.ONE)),
            ))
        val point = mapOf(x to 0.75, y to 0.75, z to 0.25, real to -0.75)
        val observations = mutableMapOf<String, Long>()
        val limits = LinearAuthorityAggregateCuts.pointSourcePairLimits

        val cut = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never,
            observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count },
            maximumCandidates = 1, limits = limits, point = point).single()

        assertEquals(SourceInequality(mapOf(x to BigFraction.ofLong(2), y to BigFraction.ofLong(2),
            z to BigFraction.ONE), BigFraction.ofLong(3)), cut.inequality)
        assertEquals(setOf(0, 1, 3, 5), cut.owners)
        assertEquals(CutFamily.AGGREGATION_MIR, cut.family)
        assertEquals(1L, observations["AggregateHalfPointEligible"])
        assertTrue((observations["AggregateWork"] ?: 0L) <= limits.work)
        assertTrue((observations["AggregateAllocation"] ?: 0L) <= limits.allocation)
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
            SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
    }

    @Test
    fun `integer point sides should withhold an interrupted multi-coordinate choice`() {
        val z = SourceCoordinate(SourceCoordinate.Kind.Integer, 2)
        val source = SourceProofContext(scope,
            setOf(x, y, z, real), linkedMapOf(
                "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(3),
                    z to BigFraction.ofLong(2), real to BigFraction.ONE), BigFraction.ofLong(2))),
                "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(3),
                    real to BigFraction.MINUS_ONE), BigFraction.ofLong(3))),
                "x lower" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.ZERO)),
                "x upper" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ONE)),
                "y lower" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE), BigFraction.ZERO)),
                "y upper" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ONE), BigFraction.ONE)),
            ))
        val point = mapOf(x to 0.75, y to 0.75, z to 0.25, real to -0.75)
        val observations = mutableMapOf<String, Long>()
        var stopped = false

        val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl { stopped },
            observe = { name, count ->
                observations[name] = (observations[name] ?: 0L) + count
                if (name == "AggregateHalfPointScans") stopped = true
            }, point = point)

        assertTrue(stopped)
        assertTrue(cuts.isEmpty())
        assertNull(observations["AggregateHalfPointComplete"])
        assertNull(observations["AggregateHalfPointAttempts"])
    }

    @Test
    fun `integer point sides should share the generation work and allocation`() {
        val z = SourceCoordinate(SourceCoordinate.Kind.Integer, 2)
        val source = SourceProofContext(scope,
            setOf(x, y, z, real), linkedMapOf(
                "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(3),
                    z to BigFraction.ofLong(2), real to BigFraction.ONE), BigFraction.ofLong(2))),
                "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(3),
                    real to BigFraction.MINUS_ONE), BigFraction.ofLong(3))),
                "x lower" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.ZERO)),
                "x upper" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ONE)),
                "y lower" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE), BigFraction.ZERO)),
                "y upper" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ONE), BigFraction.ONE)),
            ))
        val point = mapOf(x to 0.75, y to 0.75, z to 0.25, real to -0.75)
        val limits = LinearAuthorityAggregateCuts.pointSourcePairLimits
        var work = 0L
        var allocation = 0L
        var beforeWork = 0L
        var beforeAllocation = 0L
        val complete = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never,
            observe = { name, count ->
                if (name == "AggregateWork") work += count
                if (name == "AggregateAllocation") allocation += count
                if (name == "AggregateHalfPointAttempts") {
                    beforeWork = work
                    beforeAllocation = allocation
                }
            }, point = point)
        assertEquals(1, complete.size)
        assertTrue(beforeWork > 0L && beforeAllocation > 0L)

        for (limited in listOf(limits.copy(work = beforeWork), limits.copy(allocation = beforeAllocation))) {
            val observations = mutableMapOf<String, Long>()
            val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never,
                observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count },
                limits = limited, point = point)

            assertTrue(cuts.isEmpty())
            assertTrue((observations["AggregateWork"] ?: 0L) <= limited.work)
            assertTrue((observations["AggregateAllocation"] ?: 0L) <= limited.allocation)
        }
    }

    @Test
    fun `integer point sides should retain scoped upper endpoint authority`() {
        val z = SourceCoordinate(SourceCoordinate.Kind.Integer, 2)
        val source = SourceProofContext(scope.copy(condition = SourceCondition(upper = mapOf(y to BigFraction.ONE))),
            setOf(x, y, z, real), linkedMapOf(
                "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(3),
                    z to BigFraction.ofLong(2), real to BigFraction.ONE), BigFraction.ofLong(2))),
                "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(3),
                    real to BigFraction.MINUS_ONE), BigFraction.ofLong(3))),
                "x lower" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.ZERO)),
                "x upper" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ONE)),
                "y lower" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            ))
        val point = mapOf(x to 0.75, y to 0.75, z to 0.25, real to -0.75)

        val cut = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never, point =
            point).single()

        assertEquals(SourceInequality(mapOf(x to BigFraction.ofLong(2), y to BigFraction.ofLong(2),
            z to BigFraction.ONE), BigFraction.ofLong(3)), cut.inequality)
        assertEquals(setOf(0, 1, 3), cut.owners)
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
            SourceEvidence(source.scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
        val unscoped = source.atScope(scope)
        assertIs<SourceEvidenceCheck.Rejected>(SourceEvidenceVerifier(unscoped).verify(
            SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
        assertTrue(LinearAuthorityAggregateCuts.prepareSourcePairs(unscoped, ExecutionControl.Never,
            point = point).isEmpty())
    }

    @Test
    fun `half aggregation should strengthen the checked numerical bound`() {
        val source = SourceProofContext(scope.copy(objective = SourceObjective("sum", ObjectiveSense.MINIMIZE)),
            setOf(x, y, real), linkedMapOf(
                "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                    real to BigFraction.ONE), BigFraction.ONE)),
                "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(3),
                    real to BigFraction.MINUS_ONE), BigFraction.ofLong(2))),
                "bound" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            ), mapOf(x to BigFraction.MINUS_ONE, y to BigFraction.MINUS_ONE))

        val result = LinearAuthority(SourceLpBudget(), retainLp = true, aggregateCuts = true).use { authority ->
            authority.advanceWithPointCuts(source, ExecutionControl.Never)
        }

        assertEquals(SourceValue.Rational(BigFraction.MINUS_ONE),
            assertIs<SourceClaim.Bound>(assertNotNull(result.bound).claim).value)
        assertTrue(CutFamily.AGGREGATION_MIR in result.cutBoundFamilies)
        assertEquals(SourceEvidenceCheck.Checked,
            SourceEvidenceVerifier(source.withLinearAuthority()).verify(assertNotNull(result.bound)))
    }

    @Test
    fun `half aggregation should cite the stated lower or upper integer side`() {
        for (sign in listOf(-1L, 1L)) {
            val source = SourceProofContext(scope, setOf(x, y, real), linkedMapOf(
                "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                    real to BigFraction.ONE), BigFraction.ONE)),
                "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(3L * sign),
                    real to BigFraction.MINUS_ONE), BigFraction.ofLong(2))),
                "bound" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(-2L * sign)),
                    BigFraction.ZERO)),
            ))

            val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never,
                limits = LinearAuthorityAggregateCuts.pointSourcePairLimits.copy(rows = 2),
                point = mapOf(x to 0.75, y to 0.5 * sign, real to -0.5))

            val cut = cuts.single()
            assertEquals(SourceInequality(mapOf(x to BigFraction.ONE, y to BigFraction.ofLong(sign)),
                BigFraction.ONE), cut.inequality)
            assertEquals(setOf(0, 1, 2), cut.owners)
            assertEquals(CutFamily.AGGREGATION_MIR, cut.family)
            assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
                SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
            assertTrue(LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never).isEmpty())
        }
    }

    @Test
    fun `half aggregation should decline an odd coefficient without a source or scoped integer bound`() {
        val source = SourceProofContext(scope, setOf(x, y, real), linkedMapOf(
            "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                real to BigFraction.ONE), BigFraction.ONE)),
            "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(3),
                real to BigFraction.MINUS_ONE), BigFraction.ofLong(2))),
        ))
        val observations = mutableMapOf<String, Long>()

        val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never,
            observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count },
            point = mapOf(x to 0.75, y to 0.5, real to -0.5))

        assertTrue(cuts.isEmpty())
        assertEquals(1L, observations["AggregateHalfMissingIntegerBounds"])
    }

    @Test
    fun `interrupting half aggregation should withhold its partial proposal`() {
        val source = SourceProofContext(scope, setOf(x, y, real), linkedMapOf(
            "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                real to BigFraction.ONE), BigFraction.ONE)),
            "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(3),
                real to BigFraction.MINUS_ONE), BigFraction.ofLong(2))),
            "bound" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE), BigFraction.ZERO)),
        ))
        var stopped = false

        val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl { stopped },
            observe = { name, _ -> if (name == "AggregateHalfAttempts") stopped = true },
            point = mapOf(x to 0.75, y to 0.5, real to -0.5))

        assertTrue(stopped)
        assertTrue(cuts.isEmpty())
    }

    @Test
    fun `half aggregation should share spent generation work and allocation`() {
        val source = SourceProofContext(scope, setOf(x, y, real), linkedMapOf(
            "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                real to BigFraction.ONE), BigFraction.ONE)),
            "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(3),
                real to BigFraction.MINUS_ONE), BigFraction.ofLong(2))),
            "bound" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE), BigFraction.ZERO)),
        ))
        val point = mapOf(x to 0.75, y to 0.5, real to -0.5)
        val limits = LinearAuthorityAggregateCuts.pointSourcePairLimits
        var work = 0L
        var allocation = 0L
        var beforeWork = 0L
        var beforeAllocation = 0L
        val complete = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never,
            observe = { name, count ->
                if (name == "AggregateWork") work += count
                if (name == "AggregateAllocation") allocation += count
                if (name == "AggregateHalfAttempts") {
                    beforeWork = work
                    beforeAllocation = allocation
                }
            }, limits = limits, point = point)
        assertEquals(1, complete.size)
        assertTrue(beforeWork > 0L && beforeAllocation > 0L)

        for (limited in listOf(limits.copy(work = beforeWork), limits.copy(allocation = beforeAllocation))) {
            val observations = mutableMapOf<String, Long>()
            val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never,
                observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count },
                limits = limited, point = point)

            assertTrue(cuts.isEmpty())
            assertTrue((observations["AggregateWork"] ?: 0L) <= limited.work)
            assertTrue((observations["AggregateAllocation"] ?: 0L) <= limited.allocation)
        }
    }

    @Test
    fun `half aggregation should rebuild integer bound premises for each generation`() {
        for (rhs in listOf(0L, 2L, 0L)) {
            val source = SourceProofContext(scope, setOf(x, y, real), linkedMapOf(
                "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                    real to BigFraction.ONE), BigFraction.ONE)),
                "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(3),
                    real to BigFraction.MINUS_ONE), BigFraction.ofLong(2))),
                "bound" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE),
                    BigFraction.ofLong(rhs))),
            ))

            val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never,
                point = mapOf(x to 0.75, y to 0.5, real to -0.5))

            if (rhs == 0L) {
                val cut = cuts.single()
                assertEquals(BigFraction.ONE, cut.inequality.rhs)
                assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
                    SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
            } else assertTrue(cuts.isEmpty())
        }
    }

    @Test
    fun `half aggregation should retain supporting integer bound guards and pins`() {
        val bool = SourceCoordinate(SourceCoordinate.Kind.Boolean, 0)
        val pin = SourceCoordinate(SourceCoordinate.Kind.Integer, 2)
        val source = SourceProofContext(scope, setOf(x, y, real, bool, pin), linkedMapOf(
            "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                real to BigFraction.ONE), BigFraction.ONE)),
            "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(3),
                real to BigFraction.MINUS_ONE), BigFraction.ofLong(2))),
            "bound" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE), BigFraction.ZERO),
                mapOf(0 to true), mapOf(pin to BigFraction.ONE)),
        ))
        val established = SourceCondition(bools = mapOf(0 to true),
            lower = mapOf(pin to BigFraction.ONE), upper = mapOf(pin to BigFraction.ONE))
        val cut = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never,
            point = mapOf(x to 0.75, y to 0.5, real to -0.5)).single()

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
    fun `half aggregation should round strict integral endpoints below their right side`() {
        val source = SourceProofContext(scope, setOf(x, y, real), linkedMapOf(
            "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                real to BigFraction.ONE), BigFraction.ONE)),
            "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(3),
                real to BigFraction.MINUS_ONE), BigFraction.ofLong(2))),
            "bound" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE),
                BigFraction.MINUS_ONE, strict = true)),
        ))

        val cut = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never,
            point = mapOf(x to -0.75, y to 1.5, real to 2.5)).single()

        assertEquals(SourceInequality(mapOf(x to BigFraction.ONE, y to BigFraction.ONE), BigFraction.ZERO),
            cut.inequality)
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
            SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
    }
}
