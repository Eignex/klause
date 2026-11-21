package com.eignex.klause.lp.optimization

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.engine.CutFamily
import com.eignex.klause.lp.optimization.AggregateCutTestSource.left
import com.eignex.klause.lp.optimization.AggregateCutTestSource.real
import com.eignex.klause.lp.optimization.AggregateCutTestSource.right
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
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.shl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinearAuthorityIntegerRecipesTest {
    @Test
    fun `integer recipes should strengthen pure numerical bounds only when enabled`() {
        for (paired in listOf(false, true)) {
            val premises = if (paired) linkedMapOf(
                "first" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                    y to BigFraction.ONE), BigFraction.ofLong(2))),
                "second" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE,
                    y to BigFraction.ofLong(2)), BigFraction.ofLong(2))),
            ) else linkedMapOf(
                "row" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                    y to BigFraction.ofLong(3)), BigFraction.ofLong(3))),
                "lower" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            )
            val source = SourceProofContext(scope.copy(objective = SourceObjective("sum", ObjectiveSense.MINIMIZE)),
                setOf(x, y), premises, mapOf(x to BigFraction.MINUS_ONE, y to BigFraction.MINUS_ONE))
            for (enabled in listOf(false, true)) {
                val result = LinearAuthority(SourceLpBudget(), retainLp = true, aggregateCuts = enabled).use {
                    it.advanceWithPointCuts(source, ExecutionControl.Never)
                }

                val expected = if (enabled) BigFraction.MINUS_ONE else if (paired)
                    BigFraction.ofLong(-4) * BigFraction.ofLong(3).reciprocal()
                else BigFraction.ofLong(-3) * BigFraction.ofLong(2).reciprocal()
                assertEquals(SourceValue.Rational(expected),
                    assertIs<SourceClaim.Bound>(assertNotNull(result.bound).claim).value)
                assertEquals(SourceEvidenceCheck.Checked,
                    SourceEvidenceVerifier(source.withLinearAuthority()).verify(assertNotNull(result.bound)))
                if (enabled) assertTrue((if (paired) CutFamily.SOURCE_AGGREGATION else CutFamily.AGGREGATION_MIR)
                    in result.cutBoundFamilies)
            }
        }
    }

    @Test
    fun `integer row recipes should cite either stated integer endpoint`() {
        for (sign in listOf(-1L, 1L)) {
            val source = SourceProofContext(scope, setOf(x, y), linkedMapOf(
                "row" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                    y to BigFraction.ofLong(3L * sign)), BigFraction.ofLong(3))),
                "endpoint" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(-2L * sign)),
                    BigFraction.ZERO)),
            ))

            val cut = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never,
                limits = LinearAuthorityAggregateCuts.pointSourcePairLimits,
                point = mapOf(x to 0.75, y to 0.5 * sign)).single()

            assertEquals(SourceInequality(mapOf(x to BigFraction.ONE, y to BigFraction.ofLong(sign)),
                BigFraction.ONE), cut.inequality)
            assertEquals(setOf(0, 1), cut.owners)
            assertEquals(CutFamily.AGGREGATION_MIR, cut.family)
            assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
                SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
            assertTrue(LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never).isEmpty())
        }
    }

    @Test
    fun `scoped integer endpoints should strengthen half rounding with checked assumptions`() {
        for (upper in listOf(false, true)) {
            val sign = if (upper) BigFraction.MINUS_ONE else BigFraction.ONE
            val condition = if (upper) SourceCondition(upper = mapOf(x to sign))
            else SourceCondition(lower = mapOf(x to sign))
            val source = SourceProofContext(scope.copy(condition = condition), setOf(x, y), linkedMapOf(
                "row" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(3) * sign,
                    y to BigFraction.ofLong(2)), BigFraction.ofLong(4))),
                "endpoint" to SourcePremise(SourceInequality(mapOf(x to sign.negated()), BigFraction.ZERO)),
            ))

            val cut = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never,
                limits = LinearAuthorityAggregateCuts.pointSourcePairLimits,
                point = mapOf(x to sign.toDouble(), y to 0.5)).single()

            assertEquals(SourceInequality(mapOf(x to sign, y to BigFraction.ONE), BigFraction.ONE), cut.inequality)
            assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
                SourceEvidence(source.scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
        }
    }

    @Test
    fun `weaker scoped integer endpoints should preserve the stated bound authority`() {
        val source = SourceProofContext(scope.copy(condition = SourceCondition(lower = mapOf(x to BigFraction.ZERO))),
            setOf(x, y), linkedMapOf(
                "row" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(3),
                    y to BigFraction.ofLong(2)), BigFraction.ofLong(4))),
                "endpoint" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE),
                    BigFraction.MINUS_ONE)),
            ))

        val cut = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never,
            limits = LinearAuthorityAggregateCuts.pointSourcePairLimits,
            point = mapOf(x to 1.0, y to 0.5)).single()

        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source.atScope(scope)).verify(
            SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
    }

    @Test
    fun `scoped integer cuts should retract and reactivate across numerical queries`() {
        val source = SourceProofContext(scope.copy(objective = SourceObjective("sum", ObjectiveSense.MINIMIZE)),
            setOf(x, y), linkedMapOf(
                "row" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(3),
                    y to BigFraction.ofLong(2)), BigFraction.ofLong(4))),
                "x-lower" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.ZERO)),
                "y-lower" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            ), mapOf(x to BigFraction.MINUS_ONE, y to BigFraction.MINUS_ONE))
        val scoped = source.atScope(source.scope.copy(
            condition = SourceCondition(lower = mapOf(x to BigFraction.ONE)),
        ))
        val cut = LinearAuthorityAggregateCuts.prepareSourcePairs(scoped, ExecutionControl.Never,
            limits = LinearAuthorityAggregateCuts.pointSourcePairLimits,
            point = mapOf(x to 1.0, y to 0.5)).single()
        val weaker = source.atScope(source.scope.copy(
            condition = SourceCondition(lower = mapOf(x to BigFraction.ZERO)),
        ))

        val bounds = LinearAuthority(SourceLpBudget(), retainLp = true, additionalCuts = listOf(cut)).use { authority ->
            listOf(scoped, weaker, source, scoped).map { current ->
                val result = authority.advance(current, ExecutionControl.Never, cuts = true)
                val bound = assertNotNull(result.bound)
                assertEquals(SourceEvidenceCheck.Checked,
                    SourceEvidenceVerifier(current.withLinearAuthority()).verify(bound))
                assertIs<SourceClaim.Bound>(bound.claim).value
            }
        }

        assertEquals(listOf(-1L, -2L, -2L, -1L).map { SourceValue.Rational(BigFraction.ofLong(it)) }, bounds)
    }

    @Test
    fun `cancelled scoped endpoint publication should withhold its half rounding proposal`() {
        val source = SourceProofContext(scope.copy(condition = SourceCondition(lower = mapOf(x to BigFraction.ONE))),
            setOf(x, y), mapOf("row" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(3),
                y to BigFraction.ofLong(2)), BigFraction.ofLong(4)))))
        var stopped = false

        val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl { stopped },
            observe = { name, _ -> if (name == "AggregateIntegerBoundScopedRetained") stopped = true },
            limits = LinearAuthorityAggregateCuts.pointSourcePairLimits,
            point = mapOf(x to 1.0, y to 0.5))

        assertTrue(stopped)
        assertTrue(cuts.isEmpty())
    }

    @Test
    fun `scoped integer rounding should strengthen the checked numerical bound`() {
        val source = SourceProofContext(scope.copy(condition = SourceCondition(lower = mapOf(x to BigFraction.ONE)),
            objective = SourceObjective("sum", ObjectiveSense.MINIMIZE)), setOf(x, y), linkedMapOf(
            "row" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(3),
                y to BigFraction.ofLong(2)), BigFraction.ofLong(4))),
            "x-lower" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "y-lower" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE), BigFraction.ZERO)),
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
    fun `integer endpoint lookup should ignore unrelated bounds within the production allowance`() {
        for (unrelatedCount in listOf(700, 1800)) {
            val premises = linkedMapOf(
                "row" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                    y to BigFraction.ofLong(3)), BigFraction.ofLong(3))),
                "lower" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            )
            val coordinates = mutableSetOf(x, y)
            repeat(unrelatedCount) { index ->
                val id = SourceCoordinate(SourceCoordinate.Kind.Integer, index + 2)
                coordinates += id
                premises["unrelated $index"] = SourcePremise(SourceInequality(
                    mapOf(id to BigFraction.MINUS_ONE), BigFraction.ZERO))
            }
            val source = SourceProofContext(scope, coordinates, premises)
            val observations = mutableMapOf<String, Long>()
            val limits = LinearAuthorityAggregateCuts.pointSourcePairLimits

            val cut = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never,
                observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count },
                limits = limits, point = mapOf(x to 0.75, y to 0.5)).single()

            assertEquals(SourceInequality(mapOf(x to BigFraction.ONE, y to BigFraction.ONE), BigFraction.ONE),
                cut.inequality)
            assertEquals(setOf(0, 1), cut.owners)
            assertEquals(1L, observations["AggregateIntegerRowNormalized"])
            assertEquals(1L, observations["AggregateIntegerBoundScanComplete"])
            assertEquals(2L, observations["AggregateIntegerBoundCoordinates"])
            assertTrue((observations["AggregateWork"] ?: 0L) <= limits.work)
            assertTrue((observations["AggregateAllocation"] ?: 0L) <= limits.allocation)
            assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
                SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
        }
    }

    @Test
    fun `integer endpoint lookup should reuse checked coordinates across row recipes`() {
        val source = SourceProofContext(scope, setOf(x, y), linkedMapOf(
            "first" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                y to BigFraction.ofLong(3)), BigFraction.ofLong(3))),
            "second" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(3),
                y to BigFraction.ofLong(2)), BigFraction.ofLong(3))),
            "lower x" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "lower y" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE), BigFraction.ZERO)),
        ))
        val observations = mutableMapOf<String, Long>()

        val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never,
            observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count },
            limits = LinearAuthorityAggregateCuts.pointSourcePairLimits, point = mapOf(x to 0.6, y to 0.6))

        assertEquals(2L, observations["AggregateIntegerRowEligible"])
        assertEquals(1L, observations["AggregateIntegerBoundScanComplete"])
        assertEquals(2L, observations["AggregateIntegerBoundCoordinates"])
        assertTrue(cuts.all { cut ->
            SourceEvidenceVerifier(source).verify(
                SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)) ==
                SourceEvidenceCheck.Checked
        })
    }

    @Test
    fun `integer endpoint lookup should extend checked coordinates without changing source owners`() {
        val a = SourceCoordinate(SourceCoordinate.Kind.Integer, 2)
        val b = SourceCoordinate(SourceCoordinate.Kind.Integer, 3)
        val source = SourceProofContext(scope, setOf(x, y, a, b), linkedMapOf(
            "first" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                y to BigFraction.ofLong(3)), BigFraction.ofLong(3))),
            "lower y" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "second" to SourcePremise(SourceInequality(mapOf(a to BigFraction.ofLong(2),
                b to BigFraction.ofLong(3)), BigFraction.ofLong(3))),
            "lower b" to SourcePremise(SourceInequality(mapOf(b to BigFraction.MINUS_ONE), BigFraction.ZERO)),
        ))
        val observations = mutableMapOf<String, Long>()

        val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never,
            observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count },
            limits = LinearAuthorityAggregateCuts.pointSourcePairLimits,
            point = mapOf(x to 0.75, y to 0.5, a to 0.75, b to 0.5))

        assertEquals(setOf(0, 1), cuts.first().owners)
        assertEquals(setOf(2, 3), cuts[1].owners)
        assertEquals(2L, observations["AggregateIntegerBoundScanComplete"])
        assertEquals(4L, observations["AggregateIntegerBoundCoordinates"])
        assertTrue(cuts.all { cut ->
            SourceEvidenceVerifier(source).verify(
                SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)) ==
                SourceEvidenceCheck.Checked
        })
    }

    @Test
    fun `interrupting an integer endpoint scan should withhold its partially retained bounds`() {
        val source = SourceProofContext(scope, setOf(x, y), linkedMapOf(
            "row" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                y to BigFraction.ofLong(3)), BigFraction.ofLong(3))),
            "lower" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE), BigFraction.ZERO)),
        ))
        var stopped = false
        val observations = mutableMapOf<String, Long>()

        val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl { stopped },
            observe = { name, count ->
                observations[name] = (observations[name] ?: 0L) + count
                if (name == "AggregateIntegerBoundRetained") stopped = true
            }, limits = LinearAuthorityAggregateCuts.pointSourcePairLimits, point = mapOf(x to 0.75, y to 0.5))

        assertTrue(stopped)
        assertTrue(cuts.isEmpty())
        assertNull(observations["AggregateIntegerBoundScanComplete"])
        assertNull(observations["AggregateIntegerBoundCoordinates"])
    }

    @Test
    fun `an empty half recipe should leave production allowance for a later useful row`() {
        val unit = (2 until 27).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
        val premises = linkedMapOf("empty" to SourcePremise(SourceInequality(
            unit.associateWith { BigFraction.ONE }, BigFraction.ofLong(25))))
        unit.forEachIndexed { index, id ->
            premises["l$index"] = SourcePremise(SourceInequality(mapOf(id to BigFraction.MINUS_ONE),
                BigFraction.ZERO))
        }
        premises["useful"] = SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
            y to BigFraction.ofLong(3)), BigFraction.ofLong(3)))
        premises["lower y"] = SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE), BigFraction.ZERO))
        val coordinates = (unit + listOf(x, y)).toMutableSet()
        repeat(900) { index ->
            val id = SourceCoordinate(SourceCoordinate.Kind.Integer, index + 27)
            coordinates += id
            premises["u$index"] = SourcePremise(SourceInequality(mapOf(id to BigFraction.MINUS_ONE),
                BigFraction.ZERO))
        }
        val source = SourceProofContext(scope, coordinates, premises)
        val point = unit.associateWith { 0.0 } + mapOf(x to 0.75, y to 0.5)
        val observations = mutableMapOf<String, Long>()
        val limits = LinearAuthorityAggregateCuts.pointSourcePairLimits

        val cut = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never,
            observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count },
            limits = limits, point = point).single()

        assertEquals(SourceInequality(mapOf(x to BigFraction.ONE, y to BigFraction.ONE), BigFraction.ONE),
            cut.inequality)
        assertEquals(setOf(26, 27), cut.owners)
        assertTrue((observations["AggregateHalfPreviewNoIntegerActivity"] ?: 0L) > 0L)
        assertTrue((observations["AggregateWork"] ?: 0L) <= limits.work)
        assertTrue((observations["AggregateAllocation"] ?: 0L) <= limits.allocation)
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
            SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
    }

    @Test
    fun `an empty half preview should respect either stated endpoint direction`() {
        for (sign in listOf(-1L, 1L)) {
            val coefficient = BigFraction.ofLong(sign)
            val source = SourceProofContext(scope, setOf(x, y), linkedMapOf(
                "row" to SourcePremise(SourceInequality(mapOf(x to coefficient, y to coefficient),
                    if (sign > 0L) BigFraction.ofLong(2) else BigFraction.ZERO)),
                "x side" to SourcePremise(SourceInequality(mapOf(x to coefficient.negated()), BigFraction.ZERO)),
                "y side" to SourcePremise(SourceInequality(mapOf(y to coefficient.negated()), BigFraction.ZERO)),
            ))
            val observations = mutableMapOf<String, Long>()

            val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never,
                observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count },
                limits = LinearAuthorityAggregateCuts.pointSourcePairLimits, point = mapOf(x to 0.0, y to 0.0))

            assertTrue(cuts.isEmpty())
            assertEquals(1L, observations["AggregateHalfPreviewNoIntegerActivity"])
            assertEquals(1L, observations["AggregateHalfNoIntegerActivity"])
        }
    }

    @Test
    fun `an empty half preview should preserve missing endpoint refusal`() {
        for (sign in listOf(-1L, 1L)) {
            val source = SourceProofContext(scope, setOf(x, y), mapOf(
                "row" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(sign),
                    y to BigFraction.ofLong(sign)), BigFraction.ofLong(2))),
            ))
            val observations = mutableMapOf<String, Long>()

            val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never,
                observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count },
                limits = LinearAuthorityAggregateCuts.pointSourcePairLimits, point = mapOf(x to 0.0, y to 0.0))

            assertTrue(cuts.isEmpty())
            assertNull(observations["AggregateHalfPreviewNoIntegerActivity"])
            assertEquals(1L, observations["AggregateHalfMissingIntegerBounds"])
        }
    }

    @Test
    fun `interrupting a half preview should withhold the unfinished recipe`() {
        val source = SourceProofContext(scope, setOf(x, y), linkedMapOf(
            "row" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE, y to BigFraction.ONE),
                BigFraction.ofLong(2))),
            "lower x" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "lower y" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE), BigFraction.ZERO)),
        ))
        var stopped = false

        val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl { stopped },
            observe = { name, _ -> if (name == "AggregateHalfPreviewCoordinates") stopped = true },
            limits = LinearAuthorityAggregateCuts.pointSourcePairLimits, point = mapOf(x to 0.0, y to 0.0))

        assertTrue(stopped)
        assertTrue(cuts.isEmpty())
    }

    @Test
    fun `integer row normalization should retain nonunit rational scaling`() {
        val half = BigFraction.ofLong(2).reciprocal()
        val source = SourceProofContext(scope, setOf(x, y), linkedMapOf(
            "row" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE,
                y to BigFraction.ofLong(3) * half), BigFraction.ofLong(3) * half)),
            "lower" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE), BigFraction.ZERO)),
        ))
        val observations = mutableMapOf<String, Long>()

        val cut = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never,
            observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count },
            limits = LinearAuthorityAggregateCuts.pointSourcePairLimits, point = mapOf(x to 0.75, y to 0.5)).single()

        assertEquals(SourceInequality(mapOf(x to BigFraction.ONE, y to BigFraction.ONE), BigFraction.ONE),
            cut.inequality)
        assertEquals(1L, observations["AggregateIntegerRowScaled"])
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
            SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
    }

    @Test
    fun `completed integer endpoint batches should preserve allowance for a cross batch row`() {
        val groups = (0 until 2).map { group ->
            (0 until 25).map { index -> SourceCoordinate(SourceCoordinate.Kind.Integer, group * 25 + index) }
        }
        val premises = linkedMapOf<String, SourcePremise>()
        groups.forEachIndexed { group, ids ->
            premises["row $group"] = SourcePremise(SourceInequality(ids.associateWith { BigFraction.ONE },
                BigFraction.ofLong(25)))
            ids.forEachIndexed { index, id ->
                premises["l$group $index"] = SourcePremise(SourceInequality(mapOf(id to BigFraction.MINUS_ONE),
                    BigFraction.ZERO))
                premises["u$group $index"] = SourcePremise(SourceInequality(mapOf(id to BigFraction.ONE),
                    BigFraction.ONE))
            }
        }
        val a = groups[0].first()
        val b = groups[1].first()
        premises["useful"] = SourcePremise(SourceInequality(mapOf(a to BigFraction.ofLong(2),
            b to BigFraction.ofLong(3)), BigFraction.ofLong(3)))
        val coordinates = groups.flatten().toMutableSet()
        repeat(700) { index ->
            val id = SourceCoordinate(SourceCoordinate.Kind.Integer, index + 50)
            coordinates += id
            premises["extra $index"] = SourcePremise(SourceInequality(mapOf(id to BigFraction.MINUS_ONE),
                BigFraction.ZERO))
        }
        val source = SourceProofContext(scope, coordinates, premises)
        val point = groups.flatten().associateWith { 0.0 } + mapOf(a to 0.75, b to 0.5)
        val observations = mutableMapOf<String, Long>()
        val limits = LinearAuthorityAggregateCuts.pointSourcePairLimits

        val cut = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never,
            observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count },
            limits = limits, point = point).single()

        assertEquals(SourceInequality(mapOf(a to BigFraction.ONE, b to BigFraction.ONE), BigFraction.ONE),
            cut.inequality)
        assertEquals(setOf(102, 52), cut.owners)
        assertEquals(2L, observations["AggregateIntegerBoundSourceScanComplete"])
        assertEquals(2L, observations["AggregateIntegerBoundScanComplete"])
        assertEquals(50L, observations["AggregateIntegerBoundCoordinates"])
        assertTrue((observations["AggregateWork"] ?: 0L) <= limits.work)
        assertTrue((observations["AggregateAllocation"] ?: 0L) <= limits.allocation)
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
            SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
    }

    @Test
    fun `stopping before integer endpoint batch publication should withhold its recipe`() {
        val source = SourceProofContext(scope, setOf(x, y), linkedMapOf(
            "row" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                y to BigFraction.ofLong(3)), BigFraction.ofLong(3))),
            "lower" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE), BigFraction.ZERO)),
        ))
        val observations = mutableMapOf<String, Long>()
        var stopped = false

        val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl { stopped },
            observe = { name, count ->
                observations[name] = (observations[name] ?: 0L) + count
                if (name == "AggregateIntegerBoundSourceScanComplete") stopped = true
            }, limits = LinearAuthorityAggregateCuts.pointSourcePairLimits, point = mapOf(x to 0.75, y to 0.5))

        assertTrue(stopped)
        assertTrue(cuts.isEmpty())
        assertEquals(1L, observations["AggregateIntegerBoundSourceScanComplete"])
        assertNull(observations["AggregateIntegerBoundScanComplete"])
    }

    @Test
    fun `singleton inspection reuse should leave production allowance for a later disjoint row`() {
        val groups = (0 until 3).map { group ->
            (0 until 25).map { index -> SourceCoordinate(SourceCoordinate.Kind.Integer, group * 25 + index + 2) }
        }
        val premises = linkedMapOf<String, SourcePremise>()
        groups.forEachIndexed { group, ids ->
            premises["row $group"] = SourcePremise(SourceInequality(ids.associateWith { BigFraction.ONE },
                BigFraction.ofLong(25)))
            ids.forEachIndexed { index, id ->
                premises["l$group $index"] = SourcePremise(SourceInequality(mapOf(id to BigFraction.MINUS_ONE),
                    BigFraction.ZERO))
                premises["u$group $index"] = SourcePremise(SourceInequality(mapOf(id to BigFraction.ONE),
                    BigFraction.ONE))
            }
        }
        premises["useful"] = SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
            y to BigFraction.ofLong(3)), BigFraction.ofLong(3)))
        premises["lower y"] = SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE), BigFraction.ZERO))
        val coordinates = (groups.flatten() + listOf(x, y)).toMutableSet()
        repeat(700) { index ->
            val id = SourceCoordinate(SourceCoordinate.Kind.Integer, index + 77)
            coordinates += id
            premises["extra $index"] = SourcePremise(SourceInequality(mapOf(id to BigFraction.MINUS_ONE),
                BigFraction.ZERO))
        }
        val source = SourceProofContext(scope, coordinates, premises)
        val point = groups.flatten().associateWith { 0.0 } + mapOf(x to 0.75, y to 0.5)
        val observations = mutableMapOf<String, Long>()
        val limits = LinearAuthorityAggregateCuts.pointSourcePairLimits

        val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never,
            observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count },
            limits = limits, point = point)

        val cut = assertNotNull(cuts.find { it.owners == setOf(153, 154) })
        assertEquals(SourceInequality(mapOf(x to BigFraction.ONE, y to BigFraction.ONE), BigFraction.ONE),
            cut.inequality)
        assertEquals(1L, observations["AggregateIntegerBoundSnapshotComplete"])
        assertTrue((observations["AggregateIntegerBoundSnapshotReuseAttempts"] ?: 0L) >= 3L)
        assertTrue((observations["AggregateWork"] ?: 0L) <= limits.work)
        assertTrue((observations["AggregateAllocation"] ?: 0L) <= limits.allocation)
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
            SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
    }

    @Test
    fun `cancelling a reused singleton scan should withhold the unfinished recipe`() {
        val a = SourceCoordinate(SourceCoordinate.Kind.Integer, 2)
        val b = SourceCoordinate(SourceCoordinate.Kind.Integer, 3)
        val source = SourceProofContext(scope, setOf(x, y, a, b), linkedMapOf(
            "empty" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE, y to BigFraction.ONE),
                BigFraction.ofLong(2))),
            "lower x" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "lower y" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "useful" to SourcePremise(SourceInequality(mapOf(a to BigFraction.ofLong(2),
                b to BigFraction.ofLong(3)), BigFraction.ofLong(3))),
            "lower b" to SourcePremise(SourceInequality(mapOf(b to BigFraction.MINUS_ONE), BigFraction.ZERO)),
        ))
        val observations = mutableMapOf<String, Long>()
        var stopped = false

        val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl { stopped },
            observe = { name, count ->
                observations[name] = (observations[name] ?: 0L) + count
                if (name == "AggregateIntegerBoundSnapshotReuseAttempts" &&
                    (observations["AggregateIntegerBoundScanComplete"] ?: 0L) > 0L) stopped = true
            }, limits = LinearAuthorityAggregateCuts.pointSourcePairLimits,
            point = mapOf(x to 0.0, y to 0.0, a to 0.75, b to 0.5))

        assertTrue(stopped)
        assertTrue(cuts.isEmpty())
        assertEquals(1L, observations["AggregateIntegerBoundScanComplete"])
        assertNull(observations["AggregateIntegerRowEligible"])
    }

    @Test
    fun `an integral unit coefficient should preserve the checked rounded row for either sign`() {
        for (sign in listOf(-1L, 1L)) {
            val source = SourceProofContext(scope, setOf(x, y), linkedMapOf(
                "row" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(sign),
                    y to BigFraction.ofLong(2)), BigFraction.ofLong(3))),
                "side" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(-sign)), BigFraction.ZERO)),
            ))
            val observations = mutableMapOf<String, Long>()

            val cut = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never,
                observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count },
                limits = LinearAuthorityAggregateCuts.pointSourcePairLimits, point = mapOf(x to 0.0, y to 1.5)).single()

            assertEquals(SourceInequality(mapOf(y to BigFraction.ONE), BigFraction.ONE), cut.inequality)
            assertEquals(setOf(0, 1), cut.owners)
            assertEquals(1L, observations["AggregateUnitScaleNormalization"])
            assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
                SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
        }
    }

    @Test
    fun `integral normalization without a unit coefficient should retain its common factor`() {
        val source = SourceProofContext(scope, setOf(x, y), linkedMapOf(
            "row" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                y to BigFraction.ofLong(4)), BigFraction.ofLong(5))),
            "lower" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.ZERO)),
        ))
        val observations = mutableMapOf<String, Long>()

        val cut = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never,
            observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count },
            limits = LinearAuthorityAggregateCuts.pointSourcePairLimits, point = mapOf(x to 0.0, y to 1.25)).single()

        assertEquals(SourceInequality(mapOf(y to BigFraction.ONE), BigFraction.ONE), cut.inequality)
        assertNull(observations["AggregateUnitScaleNormalization"])
        assertEquals(1L, observations["AggregateIntegerRowScaled"])
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
            SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
    }

    @Test
    fun `cancelling unit scale normalization should withhold the unfinished row`() {
        val source = SourceProofContext(scope, setOf(x, y), linkedMapOf(
            "row" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE,
                y to BigFraction.ofLong(2)), BigFraction.ofLong(3))),
            "lower" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.ZERO)),
        ))
        var stopped = false

        val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl { stopped },
            observe = { name, _ -> if (name == "AggregateUnitScaleNormalization") stopped = true },
            limits = LinearAuthorityAggregateCuts.pointSourcePairLimits, point = mapOf(x to 0.0, y to 1.5))

        assertTrue(stopped)
        assertTrue(cuts.isEmpty())
    }

    @Test
    fun `integer pair recipes should combine pure rows without endpoint premises`() {
        val source = SourceProofContext(scope, setOf(x, y), linkedMapOf(
            "first" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                y to BigFraction.ONE), BigFraction.ofLong(2))),
            "second" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE,
                y to BigFraction.ofLong(2)), BigFraction.ofLong(2))),
        ))

        val cut = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never,
            limits = LinearAuthorityAggregateCuts.pointSourcePairLimits,
            point = mapOf(x to 2.0 / 3.0, y to 2.0 / 3.0)).single()

        assertEquals(SourceInequality(mapOf(x to BigFraction.ONE, y to BigFraction.ONE), BigFraction.ONE),
            cut.inequality)
        assertEquals(setOf(0, 1), cut.owners)
        assertEquals(CutFamily.SOURCE_AGGREGATION, cut.family)
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
            SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
    }

    @Test
    fun `integer row recipes should retain scoped endpoint authority`() {
        val source = SourceProofContext(scope.copy(condition = SourceCondition(lower = mapOf(y to BigFraction.ZERO))),
            setOf(x, y), mapOf("row" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                y to BigFraction.ofLong(3)), BigFraction.ofLong(3)))))

        val cut = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never,
            limits = LinearAuthorityAggregateCuts.pointSourcePairLimits,
            point = mapOf(x to 0.75, y to 0.5)).single()

        assertEquals(SourceInequality(mapOf(x to BigFraction.ONE, y to BigFraction.ONE), BigFraction.ONE),
            cut.inequality)
        assertEquals(setOf(0), cut.owners)
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
            SourceEvidence(source.scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
        val unscoped = source.atScope(scope)
        assertIs<SourceEvidenceCheck.Rejected>(SourceEvidenceVerifier(unscoped).verify(
            SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
        assertTrue(LinearAuthorityAggregateCuts.prepareSourcePairs(unscoped, ExecutionControl.Never,
            limits = LinearAuthorityAggregateCuts.pointSourcePairLimits,
            point = mapOf(x to 0.75, y to 0.5)).isEmpty())
    }

    @Test
    fun `integer row recipes should retain guarded and pinned source owners`() {
        val bool = SourceCoordinate(SourceCoordinate.Kind.Boolean, 0)
        val pin = SourceCoordinate(SourceCoordinate.Kind.Integer, 2)
        val source = SourceProofContext(scope, setOf(x, y, bool, pin), linkedMapOf(
            "row" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                y to BigFraction.ofLong(3)), BigFraction.ofLong(3)), mapOf(0 to true)),
            "lower" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE), BigFraction.ZERO),
                guards = emptyMap(), integerPins = mapOf(pin to BigFraction.ONE)),
        ))
        val established = SourceCondition(bools = mapOf(0 to true),
            lower = mapOf(pin to BigFraction.ONE), upper = mapOf(pin to BigFraction.ONE))
        val cut = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never,
            limits = LinearAuthorityAggregateCuts.pointSourcePairLimits,
            point = mapOf(x to 0.75, y to 0.5)).single()

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
    fun `integer row recipes should rebuild changed source generations`() {
        LinearAuthority(SourceLpBudget(), retainLp = true, aggregateCuts = true).use { authority ->
            for (lower in listOf(0L, 2L, 0L)) {
                val currentScope = scope.copy(revision = SourceRevision("integer-row-$lower", "exact-affine"),
                    objective = SourceObjective("sum", ObjectiveSense.MINIMIZE))
                val source = SourceProofContext(currentScope, setOf(x, y), linkedMapOf(
                    "row" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                        y to BigFraction.ofLong(3)), BigFraction.ofLong(3))),
                    "lower" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE),
                        BigFraction.ofLong(lower))),
                ), mapOf(x to BigFraction.MINUS_ONE, y to BigFraction.MINUS_ONE))

                val result = authority.advanceWithPointCuts(source, ExecutionControl.Never)

                assertEquals(SourceValue.Rational(BigFraction.ofLong(if (lower == 0L) -1 else -2)),
                    assertIs<SourceClaim.Bound>(assertNotNull(result.bound).claim).value)
                assertEquals(SourceEvidenceCheck.Checked,
                    SourceEvidenceVerifier(source.withLinearAuthority()).verify(assertNotNull(result.bound)))
            }
        }
    }

    @Test
    fun `integer recipe interruption should withhold its partial proposal`() {
        val source = SourceProofContext(scope, setOf(x, y), linkedMapOf(
            "row" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                y to BigFraction.ofLong(3)), BigFraction.ofLong(3))),
            "lower" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE), BigFraction.ZERO)),
        ))
        var stopped = false

        val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl { stopped },
            observe = { name, _ -> if (name == "AggregateIntegerRowAttempts") stopped = true },
            limits = LinearAuthorityAggregateCuts.pointSourcePairLimits,
            point = mapOf(x to 0.75, y to 0.5))

        assertTrue(stopped)
        assertTrue(cuts.isEmpty())
    }

    @Test
    fun `integer recipes should share work and allocation with continuous aggregation`() {
        val a = SourceCoordinate(SourceCoordinate.Kind.Integer, 2)
        val b = SourceCoordinate(SourceCoordinate.Kind.Integer, 3)
        val source = SourceProofContext(scope, setOf(x, y, a, b, real), linkedMapOf(
            "row" to SourcePremise(SourceInequality(mapOf(a to BigFraction.ofLong(2),
                b to BigFraction.ofLong(3)), BigFraction.ofLong(3))),
            "lower" to SourcePremise(SourceInequality(mapOf(b to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "left" to SourcePremise(left), "right" to SourcePremise(right),
        ))
        val point = mapOf(x to 0.75, y to 0.75, real to 1.5, a to 0.75, b to 0.5)
        val limits = LinearAuthorityAggregateCuts.pointSourcePairLimits
        var work = 0L
        var allocation = 0L
        var beforeWork = 0L
        var beforeAllocation = 0L
        val complete = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never,
            observe = { name, count ->
                if (name == "AggregateWork") work += count
                if (name == "AggregateAllocation") allocation += count
                if (name == "AggregateIntegerRowAttempts") {
                    beforeWork = work
                    beforeAllocation = allocation
                }
            }, limits = limits, point = point)
        assertEquals(2, complete.size)
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
    fun `integer recipes should retain at most the existing producer cut cap`() {
        val premises = linkedMapOf<String, SourcePremise>()
        val point = linkedMapOf<SourceCoordinate, Double>()
        repeat(17) { index ->
            val a = SourceCoordinate(SourceCoordinate.Kind.Integer, index * 2)
            val b = SourceCoordinate(SourceCoordinate.Kind.Integer, index * 2 + 1)
            premises["row $index"] = SourcePremise(SourceInequality(mapOf(a to BigFraction.ofLong(2),
                b to BigFraction.ofLong(3)), BigFraction.ofLong(3)))
            premises["lower $index"] = SourcePremise(SourceInequality(mapOf(b to BigFraction.MINUS_ONE),
                BigFraction.ZERO))
            point[a] = 0.75
            point[b] = 0.5
        }
        val source = SourceProofContext(scope, point.keys, premises)

        val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never,
            limits = LinearAuthorityAggregateCuts.pointSourcePairLimits, point = point)

        assertEquals(16, cuts.size)
        assertTrue(cuts.all { cut ->
            SourceEvidenceVerifier(source).verify(
                SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)) ==
                SourceEvidenceCheck.Checked
        })
    }

    @Test
    fun `integer recipes should withhold coverage beyond the existing input row cap`() {
        val premises = linkedMapOf<String, SourcePremise>()
        val point = linkedMapOf<SourceCoordinate, Double>()
        repeat(49) { index ->
            val a = SourceCoordinate(SourceCoordinate.Kind.Integer, index * 2)
            val b = SourceCoordinate(SourceCoordinate.Kind.Integer, index * 2 + 1)
            premises["row $index"] = SourcePremise(SourceInequality(mapOf(a to BigFraction.ofLong(2),
                b to BigFraction.ofLong(3)), BigFraction.ofLong(3)))
            premises["lower $index"] = SourcePremise(SourceInequality(mapOf(b to BigFraction.MINUS_ONE),
                BigFraction.ZERO))
            point[a] = if (index == 48) 0.75 else 0.0
            point[b] = if (index == 48) 0.5 else 0.0
        }
        val source = SourceProofContext(scope, point.keys, premises)
        val observations = mutableMapOf<String, Long>()

        val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never,
            limits = LinearAuthorityAggregateCuts.pointSourcePairLimits,
            observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count }, point = point)

        assertTrue(cuts.isEmpty())
        assertEquals(1L, observations["AggregateIntegerRowLimit"])
        assertEquals(1L, observations["AggregatePairIncomplete"])
    }

    @Test
    fun `integer row recipes should exclude Boolean activity from integer rounding`() {
        val bool = SourceCoordinate(SourceCoordinate.Kind.Boolean, 0)
        val source = SourceProofContext(scope, setOf(x, bool), mapOf(
            "row" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                bool to BigFraction.ofLong(3)), BigFraction.ofLong(3))),
        ))

        val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never,
            limits = LinearAuthorityAggregateCuts.pointSourcePairLimits,
            point = mapOf(x to 0.75, bool to 0.5))

        assertTrue(cuts.isEmpty())
    }

    @Test
    fun `integer row recipes should retain later supported rows after a scalar refusal`() {
        val a = SourceCoordinate(SourceCoordinate.Kind.Integer, 2)
        val b = SourceCoordinate(SourceCoordinate.Kind.Integer, 3)
        val large = BigFraction.of(BIG_ONE shl 512, BIG_ONE)
        val source = SourceProofContext(scope, setOf(x, y, a, b), linkedMapOf(
            "wide" to SourcePremise(SourceInequality(mapOf(x to large, y to BigFraction.ONE),
                BigFraction.ofLong(3))),
            "row" to SourcePremise(SourceInequality(mapOf(a to BigFraction.ofLong(2),
                b to BigFraction.ofLong(3)), BigFraction.ofLong(3))),
            "lower" to SourcePremise(SourceInequality(mapOf(b to BigFraction.MINUS_ONE), BigFraction.ZERO)),
        ))
        val observations = mutableMapOf<String, Long>()

        val cut = LinearAuthorityAggregateCuts.prepareSourcePairs(source, ExecutionControl.Never,
            observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count },
            limits = LinearAuthorityAggregateCuts.pointSourcePairLimits,
            point = mapOf(x to 0.0, y to 0.0, a to 0.75, b to 0.5)).single()

        assertEquals(SourceInequality(mapOf(a to BigFraction.ONE, b to BigFraction.ONE), BigFraction.ONE),
            cut.inequality)
        assertEquals(setOf(1, 2), cut.owners)
        assertEquals(1L, observations["AggregateIntegerRowScalarLimit"])
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
            SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
    }

    @Test
    fun `integer row recipes should preserve the explicit aggregate row minimum`() {
        val row = SourceInequality(mapOf(x to BigFraction.ofLong(2), y to BigFraction.ofLong(3)),
            BigFraction.ofLong(3))

        val cut = LinearAuthorityAggregateCuts.derive(listOf(LinearAuthorityAggregateTerm(0, "row", row)),
            ExecutionControl.Never)

        assertNull(cut)
    }
}
