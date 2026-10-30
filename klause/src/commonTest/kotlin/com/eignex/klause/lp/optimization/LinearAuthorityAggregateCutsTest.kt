package com.eignex.klause.lp.optimization

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCondition
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceEvidenceVerifier
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourcePremise
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.shl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun `automatic source pairs should strengthen a checked bound across an unbounded continuous coordinate`() {
        val residual = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val mixed = SourceInequality(left.coefficients + (residual to BigFraction.MINUS_ONE), left.rhs)
        val bound = SourceInequality(mapOf(residual to BigFraction.MINUS_ONE), BigFraction.ZERO)
        val source = SourceProofContext(scope.copy(objective = SourceObjective("sum", ObjectiveSense.MINIMIZE)),
            setOf(x, y, real, residual), mapOf("left" to SourcePremise(mixed),
                "right" to SourcePremise(right), "bound" to SourcePremise(bound)),
            mapOf(x to BigFraction.MINUS_ONE, y to BigFraction.MINUS_ONE, residual to BigFraction.ONE))

        for ((aggregate, selection, expected) in listOf(
            Triple(false, false, BigFraction.ofLong(-3) * BigFraction.ofLong(2).reciprocal()),
            Triple(true, false, BigFraction.MINUS_ONE), Triple(true, true, BigFraction.MINUS_ONE),
        )) {
            LinearAuthority(SourceLpBudget(), retainLp = true, aggregateCuts = aggregate).use { authority ->
                val result = if (selection) authority.advanceWithPointCuts(source, Cancellation.Never)
                    else authority.advance(source, Cancellation.Never, cuts = true)

                assertEquals(SourceValue.Rational(expected),
                    assertIs<SourceClaim.Bound>(assertNotNull(result.bound).claim).value)
                assertEquals(SourceEvidenceCheck.Checked,
                    SourceEvidenceVerifier(source.withLinearAuthority()).verify(assertNotNull(result.bound)))
                if (selection) {
                    val selected = authority.cutObservations()["numericalCutSelectionSelected"] ?: 0L
                    assertTrue(selected > 0L)
                }
            }
        }
    }

    @Test
    fun `repeated selected queries should reuse their completed pair generation`() {
        val residual = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val mixed = SourceInequality(left.coefficients + (residual to BigFraction.MINUS_ONE), left.rhs)
        val bound = SourceInequality(mapOf(residual to BigFraction.MINUS_ONE), BigFraction.ZERO)
        val source = SourceProofContext(scope.copy(objective = SourceObjective("sum", ObjectiveSense.MINIMIZE)),
            setOf(x, y, real, residual), mapOf("left" to SourcePremise(mixed),
                "right" to SourcePremise(right), "bound" to SourcePremise(bound)),
            mapOf(x to BigFraction.MINUS_ONE, y to BigFraction.MINUS_ONE, residual to BigFraction.ONE))
        LinearAuthority(SourceLpBudget(), retainLp = true, aggregateCuts = true).use { authority ->
            authority.advanceWithPointCuts(source, Cancellation.Never)
            val attempts = assertNotNull(authority.cutObservations()["numericalCutAggregatePairAttempts"])

            val result = authority.advanceWithPointCuts(source, Cancellation.Never)

            assertEquals(attempts, authority.cutObservations()["numericalCutAggregatePairAttempts"])
            assertEquals(SourceValue.Rational(BigFraction.MINUS_ONE),
                assertIs<SourceClaim.Bound>(assertNotNull(result.bound).claim).value)
            assertEquals(SourceEvidenceCheck.Checked,
                SourceEvidenceVerifier(source.withLinearAuthority()).verify(assertNotNull(result.bound)))
        }
    }

    @Test
    fun `source pair preparation should retain its containing work and storage limits`() {
        val source = SourceProofContext(scope, setOf(x, y, real), mapOf(
            "left" to SourcePremise(left), "right" to SourcePremise(right)))

        for (limits in listOf(LinearAuthorityAggregateLimits(work = 1),
            LinearAuthorityAggregateLimits(allocation = 1))) {
            val observations = mutableMapOf<String, Long>()
            val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation.Never,
                observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count }, limits = limits)

            assertTrue(cuts.isEmpty())
            assertTrue((observations["AggregateWork"] ?: 0L) <= limits.work)
            assertTrue((observations["AggregateAllocation"] ?: 0L) <= limits.allocation)
        }
    }

    @Test
    fun `opposite bound projection should strengthen a checked sparse bound before mixed rounding`() {
        val residual = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val source = SourceProofContext(scope.copy(objective = SourceObjective("projection", ObjectiveSense.MINIMIZE)),
            setOf(x, real, residual), mapOf(
                "row" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                    real to BigFraction.MINUS_ONE, residual to BigFraction.ONE), BigFraction.ofLong(3))),
                "real-lower" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.ZERO)),
                "real-upper" to SourcePremise(SourceInequality(mapOf(real to BigFraction.ONE), BigFraction.ofLong(2))),
                "residual-lower" to SourcePremise(SourceInequality(mapOf(residual to BigFraction.MINUS_ONE),
                    BigFraction.ZERO)),
                "residual-upper" to SourcePremise(SourceInequality(mapOf(residual to BigFraction.ONE),
                    BigFraction.ofLong(100))),
            ), mapOf(x to BigFraction.MINUS_ONE, real to BigFraction.ONE))
        for ((aggregate, expected) in listOf(false to (BigFraction.ofLong(-3) *
            BigFraction.ofLong(2).reciprocal()), true to BigFraction.MINUS_ONE)) {
            LinearAuthority(SourceLpBudget(), retainLp = true, aggregateCuts = aggregate).use { authority ->
                val result = authority.advance(source, Cancellation.Never, cuts = true)

                assertEquals(SourceValue.Rational(expected),
                    assertIs<SourceClaim.Bound>(assertNotNull(result.bound).claim).value)
                assertEquals(SourceEvidenceCheck.Checked,
                    SourceEvidenceVerifier(source.withLinearAuthority()).verify(assertNotNull(result.bound)))
            }
        }
    }

    @Test
    fun `appending an aggregate should stop at the shared allocation allowance`() {
        val source = SourceProofContext(scope.copy(objective = SourceObjective("sum", ObjectiveSense.MINIMIZE)),
            setOf(x, y, real), mapOf("left" to SourcePremise(left), "right" to SourcePremise(right)),
            mapOf(x to BigFraction.MINUS_ONE, y to BigFraction.MINUS_ONE))
        val cut = assertNotNull(LinearAuthorityAggregateCuts.derive(listOf(
            LinearAuthorityAggregateTerm(0, "left", left), LinearAuthorityAggregateTerm(1, "right", right),
        ), Cancellation.Never))
        val funding = SourceLpFunding(1_000_000L, 1L).apply { grant(1_000_000L) }
        LinearAuthority(SourceLpBudget(funding = funding), retainLp = true,
            additionalCuts = listOf(cut)).use { authority ->
            val result = authority.advance(source, Cancellation.Never, cuts = true)

            assertTrue(funding.exhausted)
            assertTrue(funding.allocated > 0L)
            assertTrue(result.paused)
            assertNull(result.bound)
        }
    }

    @Test
    fun `appending a false proposal should leave its row without authority`() {
        val source = SourceProofContext(scope, setOf(x, y, real), mapOf(
            "left" to SourcePremise(left), "right" to SourcePremise(right)))
        val falseCut = LinearAuthorityCut(0, SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ZERO),
            SourceDerivation.Premise("left"), owners = setOf(0, 1))
        val template = assertNotNull(assertNotNull(LinearAuthorityTemplate.prepare(source, Cancellation.Never,
            cuts = true)).withAdditional(listOf(falseCut), Cancellation.Never))

        val query = assertNotNull(LinearAuthorityQuery.prepare(source, template, Cancellation.Never))

        assertEquals(0, query.activeCuts)
        assertTrue(query.checkedCuts.isEmpty())
    }

    @Test
    fun `an appended aggregate should strengthen a freshly checked sparse bound`() {
        val residual = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val mixed = SourceInequality(left.coefficients + (residual to BigFraction.MINUS_ONE), left.rhs)
        val bound = SourceInequality(mapOf(residual to BigFraction.MINUS_ONE), BigFraction.ZERO)
        val objectiveScope = scope.copy(objective = SourceObjective("aggregate", ObjectiveSense.MINIMIZE))
        val source = SourceProofContext(objectiveScope, setOf(x, y, real, residual), mapOf(
            "left" to SourcePremise(mixed), "right" to SourcePremise(right), "bound" to SourcePremise(bound)),
            mapOf(x to BigFraction.MINUS_ONE, y to BigFraction.MINUS_ONE, residual to BigFraction.ONE))
        val cut = assertNotNull(LinearAuthorityAggregateCuts.derive(listOf(
            LinearAuthorityAggregateTerm(0, "left", mixed), LinearAuthorityAggregateTerm(1, "right", right),
        ), Cancellation.Never, allowMixed = true, continuousBounds = mapOf(
            (residual to false) to LinearAuthorityAggregateTerm(2, "bound", bound))))
        assertTrue(assertNotNull(LinearAuthorityCuts.prepare(source, Cancellation.Never)).rows.isEmpty())
        for ((additional, expected) in listOf(emptyList<LinearAuthorityCut>() to
            (BigFraction.ofLong(-3) * BigFraction.ofLong(2).reciprocal()), listOf(cut) to BigFraction.MINUS_ONE)) {
            LinearAuthority(SourceLpBudget(), retainLp = true, additionalCuts = additional).use { authority ->
                val result = authority.advance(source, Cancellation.Never, cuts = true)

                assertEquals(SourceValue.Rational(expected),
                    assertIs<SourceClaim.Bound>(assertNotNull(result.bound).claim).value)
                assertEquals(SourceEvidenceCheck.Checked,
                    SourceEvidenceVerifier(source.withLinearAuthority()).verify(assertNotNull(result.bound)))
            }
        }
    }

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

    @Test
    fun `pair inspection should expose a checked missing inference at a row feasible point`() {
        val quarter = BigFraction.ofLong(4).reciprocal()
        val point = mapOf(x to quarter * BigFraction.ofLong(3), y to quarter * BigFraction.ofLong(3),
            real to quarter * BigFraction.ofLong(6))
        val source = SourceProofContext(scope, setOf(x, y, real), mapOf(
            "left" to SourcePremise(left), "right" to SourcePremise(right)))

        val result = LinearAuthorityAggregateCuts.inspectPairs(listOf(
            LinearAuthorityAggregateTerm(0, "left", left), LinearAuthorityAggregateTerm(1, "right", right),
        ), emptySet(), point, Cancellation.Never)

        assertTrue(result.complete)
        assertEquals(true, result.activeSourceRowsSatisfied)
        assertEquals(BigFraction.ZERO, result.maximumSourceResidual)
        assertEquals(listOf(BigFraction.ofLong(2).reciprocal()), result.violations)
        val cut = result.candidates.single()
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
            SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
    }

    @Test
    fun `pair inspection should include a continuous only cancellation row`() {
        val residual = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val mixed = SourceInequality(mapOf(x to BigFraction.ofLong(2), real to BigFraction.ONE,
            residual to BigFraction.ONE), BigFraction.ofLong(3))
        val continuous = SourceInequality(mapOf(real to BigFraction.MINUS_ONE, residual to BigFraction.MINUS_ONE),
            BigFraction.ZERO)
        val point = mapOf(x to BigFraction.ofLong(3) * BigFraction.ofLong(2).reciprocal(),
            real to BigFraction.ZERO, residual to BigFraction.ZERO)

        val result = LinearAuthorityAggregateCuts.inspectPairs(listOf(
            LinearAuthorityAggregateTerm(0, "mixed", mixed),
            LinearAuthorityAggregateTerm(1, "continuous", continuous),
        ), emptySet(), point, Cancellation.Never)

        assertTrue(result.complete)
        assertEquals(true, result.activeSourceRowsSatisfied)
        assertEquals(SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ONE),
            result.candidates.single().inequality)
    }

    @Test
    fun `pair inspection should retain bounded inferences after a wide source row`() {
        val wide = SourceInequality((2..6).associate {
            SourceCoordinate(SourceCoordinate.Kind.Real, it) to BigFraction.ONE
        }, BigFraction.ZERO)
        val quarter = BigFraction.ofLong(4).reciprocal()
        val point = mapOf(x to quarter * BigFraction.ofLong(3), y to quarter * BigFraction.ofLong(3),
            real to quarter * BigFraction.ofLong(6))
        val source = SourceProofContext(scope, point.keys + wide.coefficients.keys, mapOf(
            "wide" to SourcePremise(wide), "left" to SourcePremise(left), "right" to SourcePremise(right)))

        val result = LinearAuthorityAggregateCuts.inspectPairs(listOf(
            LinearAuthorityAggregateTerm(0, "wide", wide), LinearAuthorityAggregateTerm(1, "left", left),
            LinearAuthorityAggregateTerm(2, "right", right),
        ), emptySet(), point, Cancellation.Never, limits = LinearAuthorityAggregateLimits(support = 4))

        val cut = result.candidates.single()
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
            SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
        assertFalse(result.complete)
        assertNull(result.activeSourceRowsSatisfied)
    }

    @Test
    fun `pair inspection should distinguish source residuals from aggregate violations`() {
        val result = LinearAuthorityAggregateCuts.inspectPairs(listOf(
            LinearAuthorityAggregateTerm(0, "left", left), LinearAuthorityAggregateTerm(1, "right", right),
        ), emptySet(), mapOf(x to BigFraction.ONE, y to BigFraction.ONE, real to BigFraction.ZERO), Cancellation.Never)

        assertTrue(result.complete)
        assertEquals(false, result.activeSourceRowsSatisfied)
        assertEquals(BigFraction.ofLong(2), result.maximumSourceResidual)
        assertEquals(listOf(BigFraction.ONE), result.violations)
    }

    @Test
    fun `pair inspection should not combine inactive source rows`() {
        val result = LinearAuthorityAggregateCuts.inspectPairs(listOf(
            LinearAuthorityAggregateTerm(0, "left", left), LinearAuthorityAggregateTerm(1, "right", right),
        ), setOf(1), mapOf(x to BigFraction.ONE, y to BigFraction.ONE, real to BigFraction.ZERO), Cancellation.Never)

        assertTrue(result.complete)
        assertTrue(result.candidates.isEmpty())
        assertEquals(true, result.activeSourceRowsSatisfied)
    }

    @Test
    fun `limited pair inspection should withhold complete scan claims`() {
        val quarter = BigFraction.ofLong(4).reciprocal()
        val point = mapOf(x to quarter * BigFraction.ofLong(3), y to quarter * BigFraction.ofLong(3),
            real to quarter * BigFraction.ofLong(6))
        val rows = listOf(LinearAuthorityAggregateTerm(0, "left", left),
            LinearAuthorityAggregateTerm(1, "right", right))
        for (limits in listOf(LinearAuthorityAggregateLimits(work = 1),
            LinearAuthorityAggregateLimits(allocation = 1))) {
            val result = LinearAuthorityAggregateCuts.inspectPairs(rows, emptySet(), point, Cancellation.Never,
                limits = limits)

            assertFalse(result.complete)
            assertTrue(result.candidates.isEmpty())
        }
    }

    @Test
    fun `a pair retention limit should not establish the absence of violated cuts`() {
        val quarter = BigFraction.ofLong(4).reciprocal()
        val point = mapOf(x to quarter * BigFraction.ofLong(3), y to quarter * BigFraction.ofLong(3),
            real to quarter * BigFraction.ofLong(6))

        val result = LinearAuthorityAggregateCuts.inspectPairs(listOf(
            LinearAuthorityAggregateTerm(0, "left", left), LinearAuthorityAggregateTerm(1, "right", right),
        ), emptySet(), point, Cancellation.Never, maximumCandidates = 0)

        assertFalse(result.complete)
        assertTrue(result.candidates.isEmpty())
        assertEquals(true, result.activeSourceRowsSatisfied)
    }

    @Test
    fun `pair inspection should complete when the aggregate is satisfied at the point`() {
        val result = LinearAuthorityAggregateCuts.inspectPairs(listOf(
            LinearAuthorityAggregateTerm(0, "left", left), LinearAuthorityAggregateTerm(1, "right", right),
        ), emptySet(), mapOf(x to BigFraction.ONE, y to BigFraction.ZERO, real to BigFraction.ONE), Cancellation.Never)

        assertTrue(result.complete)
        assertTrue(result.candidates.isEmpty())
        assertEquals(true, result.activeSourceRowsSatisfied)
    }

    @Test
    fun `pair inspection should retain unknown efficacy when a point coordinate is missing`() {
        val result = LinearAuthorityAggregateCuts.inspectPairs(listOf(
            LinearAuthorityAggregateTerm(0, "left", left), LinearAuthorityAggregateTerm(1, "right", right),
        ), emptySet(), mapOf(x to BigFraction.ONE, real to BigFraction.ZERO), Cancellation.Never)

        assertFalse(result.complete)
        assertTrue(result.candidates.isEmpty())
        assertNull(result.activeSourceRowsSatisfied)
    }

    @Test
    fun `pair inspection should charge nested aggregation once to the containing meter`() {
        var charged = 0L
        var observed = 0L
        val meter = WorkMeter { charged += it }
        val token = object : Cancellation {
            override fun isCancelled(): Boolean = false
            override fun workMeter(): WorkMeter = meter
        }
        val quarter = BigFraction.ofLong(4).reciprocal()
        val point = mapOf(x to quarter * BigFraction.ofLong(3), y to quarter * BigFraction.ofLong(3),
            real to quarter * BigFraction.ofLong(6))

        val result = LinearAuthorityAggregateCuts.inspectPairs(listOf(
            LinearAuthorityAggregateTerm(0, "left", left), LinearAuthorityAggregateTerm(1, "right", right),
        ), emptySet(), point, token, { stage, count -> if (stage == "AggregateWork") observed += count })

        assertTrue(result.complete)
        assertTrue(charged > 0L)
        assertEquals(observed, charged)
    }

    @Test
    fun `mixed aggregates should retain checked continuous bound complements and strict source rows`() {
        val residual = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        for (sign in listOf(BigFraction.MINUS_ONE, BigFraction.ONE)) {
            for (strict in listOf(false, true)) {
                val mixed = SourceInequality(left.coefficients + (residual to sign), left.rhs, strict)
                val bound = SourceInequality(mapOf(residual to sign), BigFraction.ZERO)
                val source = SourceProofContext(scope, setOf(x, y, real, residual), mapOf(
                    "left" to SourcePremise(mixed), "right" to SourcePremise(right),
                    "bound" to SourcePremise(bound)))

                val cut = assertNotNull(LinearAuthorityAggregateCuts.derive(listOf(
                    LinearAuthorityAggregateTerm(0, "left", mixed),
                    LinearAuthorityAggregateTerm(1, "right", right),
                ), Cancellation.Never, allowMixed = true, continuousBounds = mapOf(
                    (residual to (sign > BigFraction.ZERO)) to LinearAuthorityAggregateTerm(2, "bound", bound))))

                assertEquals(SourceInequality(mapOf(x to BigFraction.ONE, y to BigFraction.ONE, residual to sign),
                    BigFraction.ONE), cut.inequality)
                assertTrue(cut.mixed)
                assertEquals(setOf(0, 1, 2), cut.owners)
                assertTrue(assertNotNull(LinearAuthorityCuts.prepare(source, Cancellation.Never)).rows.isEmpty())
                assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
                    SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
            }
        }
    }

    @Test
    fun `mixed aggregates should decline a missing residual bound`() {
        val residual = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val mixed = SourceInequality(left.coefficients + (residual to BigFraction.MINUS_ONE), left.rhs)
        val observations = linkedMapOf<String, Long>()

        val cut = LinearAuthorityAggregateCuts.derive(listOf(
            LinearAuthorityAggregateTerm(0, "left", mixed), LinearAuthorityAggregateTerm(1, "right", right),
        ), Cancellation.Never, { key, value -> observations[key] = (observations[key] ?: 0L) + value },
            allowMixed = true)

        assertNull(cut)
        assertEquals(1L, observations["AggregateMissingBounds"])
    }

    @Test
    fun `mixed aggregates should retain the activation premise of a residual bound`() {
        val residual = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val bool = SourceCoordinate(SourceCoordinate.Kind.Boolean, 0)
        val mixed = SourceInequality(left.coefficients + (residual to BigFraction.MINUS_ONE), left.rhs)
        val bound = SourceInequality(mapOf(residual to BigFraction.MINUS_ONE), BigFraction.ZERO)
        val source = SourceProofContext(scope, setOf(x, y, real, residual, bool), mapOf(
            "left" to SourcePremise(mixed), "right" to SourcePremise(right),
            "bound" to SourcePremise(bound, mapOf(0 to true))))
        val cut = assertNotNull(LinearAuthorityAggregateCuts.derive(listOf(
            LinearAuthorityAggregateTerm(0, "left", mixed), LinearAuthorityAggregateTerm(1, "right", right),
        ), Cancellation.Never, allowMixed = true, continuousBounds = mapOf(
            (residual to false) to LinearAuthorityAggregateTerm(2, "bound", bound))))
        val template = assertNotNull(assertNotNull(LinearAuthorityTemplate.prepare(source, Cancellation.Never,
            cuts = true)).withAdditional(listOf(cut), Cancellation.Never))
        for (enabled in listOf(true, false, true)) {
            val context = source.atScope(scope.copy(condition = SourceCondition(bools = mapOf(0 to enabled))))

            val check = SourceEvidenceVerifier(context).verify(
                SourceEvidence(context.scope, SourceClaim.Consequence(cut.inequality), cut.derivation))
            val query = assertNotNull(LinearAuthorityQuery.prepare(context, template, Cancellation.Never))

            assertEquals(if (enabled) 1 else 0, query.activeCuts)
            if (enabled) assertEquals(SourceEvidenceCheck.Checked, check)
            else assertIs<SourceEvidenceCheck.Rejected>(check)
        }
    }

    @Test
    fun `mixed aggregate arithmetic should decline a wide bound endpoint before retaining a cut`() {
        val residual = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val mixed = SourceInequality(left.coefficients + (residual to BigFraction.MINUS_ONE), left.rhs)
        val large = BigFraction.of(BIG_ONE shl 127, BIG_ONE)
        val bound = SourceInequality(mapOf(residual to large.reciprocal().negated()), large)
        val observations = linkedMapOf<String, Long>()

        val cut = LinearAuthorityAggregateCuts.derive(listOf(
            LinearAuthorityAggregateTerm(0, "left", mixed), LinearAuthorityAggregateTerm(1, "right", right),
        ), Cancellation.Never, { key, value -> observations[key] = (observations[key] ?: 0L) + value },
            LinearAuthorityAggregateLimits(scalarBits = 128), allowMixed = true, continuousBounds = mapOf(
                (residual to false) to LinearAuthorityAggregateTerm(2, "bound", bound)))

        assertNull(cut)
        assertEquals(1L, observations["AggregateScalarLimit"])
        assertTrue(assertNotNull(observations["AggregateWork"]) > 0L)
    }

    @Test
    fun `mixed pair inspection should expose a checked missing inference at a row feasible point`() {
        val residual = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val mixed = SourceInequality(left.coefficients + (residual to BigFraction.MINUS_ONE), left.rhs)
        val bound = SourceInequality(mapOf(residual to BigFraction.MINUS_ONE), BigFraction.ZERO)
        val source = SourceProofContext(scope, setOf(x, y, real, residual), mapOf(
            "left" to SourcePremise(mixed), "right" to SourcePremise(right), "bound" to SourcePremise(bound)))
        val quarter = BigFraction.ofLong(4).reciprocal()
        val point = mapOf(x to quarter * BigFraction.ofLong(3), y to quarter * BigFraction.ofLong(3),
            real to quarter * BigFraction.ofLong(6), residual to BigFraction.ZERO)

        val result = LinearAuthorityAggregateCuts.inspectPairs(listOf(
            LinearAuthorityAggregateTerm(0, "left", mixed), LinearAuthorityAggregateTerm(1, "right", right),
            LinearAuthorityAggregateTerm(2, "bound", bound),
        ), emptySet(), point, Cancellation.Never, allowMixed = true)

        assertTrue(result.complete)
        assertEquals(true, result.activeSourceRowsSatisfied)
        assertEquals(listOf(BigFraction.ofLong(2).reciprocal()), result.violations)
        val cut = result.candidates.single()
        assertTrue(cut.mixed)
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
            SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
    }

    @Test
    fun `mixed pair inspection should require an active continuous bound owner`() {
        val residual = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val mixed = SourceInequality(left.coefficients + (residual to BigFraction.MINUS_ONE), left.rhs)
        val bound = SourceInequality(mapOf(residual to BigFraction.MINUS_ONE), BigFraction.ZERO)
        val quarter = BigFraction.ofLong(4).reciprocal()
        val point = mapOf(x to quarter * BigFraction.ofLong(3), y to quarter * BigFraction.ofLong(3),
            real to quarter * BigFraction.ofLong(6), residual to BigFraction.ZERO)
        for (inactive in listOf(emptySet(), setOf(2), emptySet())) {
            val result = LinearAuthorityAggregateCuts.inspectPairs(listOf(
                LinearAuthorityAggregateTerm(0, "left", mixed), LinearAuthorityAggregateTerm(1, "right", right),
                LinearAuthorityAggregateTerm(2, "bound", bound),
            ), inactive, point, Cancellation.Never, allowMixed = true)

            assertTrue(result.complete)
            assertEquals(if (inactive.isEmpty()) 1 else 0, result.candidates.size)
        }
    }

    @Test
    fun `a scalar limited candidate should withhold complete pair inspection`() {
        val large = BigFraction.of(BIG_ONE shl 127, BIG_ONE)
        val largeLeft = SourceInequality(mapOf(x to BigFraction.ofLong(2), real to large), BigFraction.ofLong(3))
        val largeRight = SourceInequality(mapOf(y to BigFraction.ofLong(2), real to large.negated()), BigFraction.ZERO)
        val point = mapOf(x to BigFraction.ZERO, y to BigFraction.ZERO, real to BigFraction.ZERO)

        val result = LinearAuthorityAggregateCuts.inspectPairs(listOf(
            LinearAuthorityAggregateTerm(0, "left", largeLeft), LinearAuthorityAggregateTerm(1, "right", largeRight),
        ), emptySet(), point, Cancellation.Never,
            limits = LinearAuthorityAggregateLimits(scalarBits = 128))

        assertFalse(result.complete)
        assertTrue(result.candidates.isEmpty())
        assertEquals(true, result.activeSourceRowsSatisfied)
    }
}
