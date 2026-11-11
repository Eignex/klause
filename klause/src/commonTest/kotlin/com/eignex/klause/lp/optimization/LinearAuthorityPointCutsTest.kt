package com.eignex.klause.lp.optimization

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.lp.engine.CutFamily
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCondition
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinearAuthorityPointCutsTest {
    private val x = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
    private val y = SourceCoordinate(SourceCoordinate.Kind.Integer, 1)
    private val scope = SourceScope(SourceRevision("point-cuts", "exact-affine"),
        objective = SourceObjective("x", ObjectiveSense.MINIMIZE))
    private val source = SourceProofContext(scope, setOf(x), mapOf(
        "lower" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(-2)), BigFraction.MINUS_ONE)),
    ), mapOf(x to BigFraction.ONE))
    private val mixedReal = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
    private val mixedMaximum = SourceProofContext(
        scope.copy(objective = SourceObjective("x", ObjectiveSense.MAXIMIZE)), setOf(x, mixedReal), mapOf(
            "mixed" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                mixedReal to BigFraction.MINUS_ONE), BigFraction.ofLong(3))),
            "lower" to SourcePremise(SourceInequality(mapOf(mixedReal to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "upper" to SourcePremise(SourceInequality(mapOf(mixedReal to BigFraction.ONE), BigFraction.ofLong(2))),
        ), mapOf(x to BigFraction.ONE),
    )
    private val nonseparating = SourceProofContext(mixedMaximum.scope, setOf(x, mixedReal), mapOf(
        "mixed" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
            mixedReal to BigFraction.MINUS_ONE), BigFraction.ofLong(3))),
        "coupled" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE,
            mixedReal to BigFraction.ONE), BigFraction.ofLong(5))),
        "real lower" to SourcePremise(SourceInequality(mapOf(mixedReal to BigFraction.MINUS_ONE), BigFraction.ZERO)),
        "integer lower" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.ZERO)),
    ), mapOf(x to BigFraction.ONE))

    private val independentMixed = run {
        val z = SourceCoordinate(SourceCoordinate.Kind.Integer, 2)
        val w = SourceCoordinate(SourceCoordinate.Kind.Integer, 3)
        val other = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        SourceProofContext(mixedMaximum.scope.copy(objective = SourceObjective("sum", ObjectiveSense.MAXIMIZE)),
            mixedMaximum.coordinates + setOf(y, z, w, other), mixedMaximum.premises + mapOf(
                "mixed" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                    y to BigFraction.ofLong(2), mixedReal to BigFraction.MINUS_ONE), BigFraction.ofLong(3))),
                "other mixed" to SourcePremise(SourceInequality(mapOf(z to BigFraction.ofLong(2),
                    w to BigFraction.ofLong(2), other to BigFraction.MINUS_ONE), BigFraction.ofLong(3))),
                "other lower" to SourcePremise(SourceInequality(mapOf(other to BigFraction.MINUS_ONE),
                    BigFraction.ZERO)),
                "other upper" to SourcePremise(SourceInequality(mapOf(other to BigFraction.ONE),
                    BigFraction.ofLong(2))),
            ), listOf(x, y, z, w).associateWith { BigFraction.ONE })
    }


    @Test
    fun `a numerical cut improvement should count as progress while the LP point stays fractional`() {
        val triangle = (1..3).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
        val premises = linkedMapOf<String, SourcePremise>()
        for ((index, coordinate) in triangle.withIndex()) {
            premises["nonnegative $index"] = SourcePremise(SourceInequality(
                mapOf(coordinate to BigFraction.MINUS_ONE), BigFraction.ZERO))
            premises["edge $index"] = SourcePremise(SourceInequality(
                mapOf(coordinate to BigFraction.ONE, triangle[(index + 1) % 3] to BigFraction.ONE),
                BigFraction.ONE))
        }
        val source = SourceProofContext(mixedMaximum.scope.copy(
            objective = SourceObjective("sum", ObjectiveSense.MAXIMIZE)),
            mixedMaximum.coordinates + triangle, mixedMaximum.premises + premises,
            (triangle + x).associateWith { BigFraction.ONE })
        val budget = SourceLpBudget(solveContext = { LpSolveContext.Numerical })

        LinearAuthority(budget, retainLp = true).use { authority ->
            val before = authority.advance(source, Cancellation.Never, cuts = true)

            val selected = authority.advanceWithPointCuts(source, Cancellation.Never)

            assertEquals(4.0, assertNotNull(before.numerical?.bound), 0.00000001)
            assertEquals(3.5, assertNotNull(selected.numerical?.bound), 0.00000001)
            assertNull(selected.incumbent)
            assertNotNull(selected.split)
            assertNull(selected.bound)
            assertEquals(1L, authority.cutObservations()["numericalCutSelectionImprovedRounds"])
        }
    }

    @Test
    fun `later paid points should stop fractional discovery after two attempts`() {
        val source = SourceProofContext(scope.copy(objective = SourceObjective("sum", ObjectiveSense.MAXIMIZE)),
            setOf(x, y, mixedReal), linkedMapOf(
                "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                    mixedReal to BigFraction.ONE), BigFraction.ofLong(3))),
                "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(2),
                    mixedReal to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            ), mapOf(x to BigFraction.ONE, y to BigFraction.ONE))
        val cuts = source.premises.entries.mapIndexed { owner, (name, premise) ->
            LinearAuthorityCut(owner, premise.inequality, SourceDerivation.Premise(name),
                mixed = true, family = CutFamily.SOURCE_AGGREGATION)
        }
        val funding = SourceLpFunding(8_000_000L, 16_000_000L).apply { grant(8_000_000L) }
        val budget = SourceLpBudget(maxOperations = 16, funding = funding)
        LinearAuthority(budget, retainLp = true, additionalCuts = cuts, aggregateCuts = true,
            fractionalAggregateOrder = true).use { authority ->
            authority.advanceWithPointCuts(source, Cancellation.Never)
            for (step in 1..5) {
                val child = source.atScope(source.scope.copy(condition = SourceCondition(lower = mapOf(
                    mixedReal to BigFraction.ofLong(step * 2L - 1L) * BigFraction.ofLong(2).reciprocal()))))
                val result = authority.advanceWithPointCuts(child, Cancellation.Never)
                result.bound?.let {
                    assertEquals(SourceEvidenceCheck.Checked,
                        SourceEvidenceVerifier(child.withLinearAuthority()).verify(it))
                }
            }

            assertEquals(2L, authority.cutObservations()["numericalCutSelectionFractionalPassAttempts"])
            assertTrue(budget.operations <= 16)
        }
    }

    @Test
    fun `an exhausted operation allowance should not unlock another fractional attempt`() {
        val source = SourceProofContext(scope.copy(objective = SourceObjective("sum", ObjectiveSense.MAXIMIZE)),
            setOf(x, y, mixedReal), linkedMapOf(
                "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                    mixedReal to BigFraction.ONE), BigFraction.ofLong(3))),
                "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(2),
                    mixedReal to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            ), mapOf(x to BigFraction.ONE, y to BigFraction.ONE))
        val cuts = source.premises.entries.mapIndexed { owner, (name, premise) ->
            LinearAuthorityCut(owner, premise.inequality, SourceDerivation.Premise(name),
                mixed = true, family = CutFamily.SOURCE_AGGREGATION)
        }
        val budget = SourceLpBudget(maxOperations = 2)
        LinearAuthority(budget, retainLp = true, additionalCuts = cuts, aggregateCuts = true,
            fractionalAggregateOrder = true).use { authority ->
            authority.advanceWithPointCuts(source, Cancellation.Never)
            val child = source.atScope(source.scope.copy(condition = SourceCondition(lower = mapOf(
                mixedReal to BigFraction.ofLong(2).reciprocal()))))

            authority.advanceWithPointCuts(child, Cancellation.Never)

            assertEquals(1L, authority.cutObservations()["numericalCutSelectionFractionalPassAttempts"])
            assertEquals(2, budget.operations)
        }
    }

    @Test
    fun `partial current pools should defer fractional generation without changing current selection`() {
        val controlBudget = SourceLpBudget()
        val control = LinearAuthority(controlBudget, retainLp = true, aggregateCuts = true).use {
            it.advanceWithPointCuts(independentMixed, Cancellation.Never)
        }
        val budget = SourceLpBudget()
        LinearAuthority(budget, retainLp = true, aggregateCuts = true, fractionalAggregateOrder = true)
            .use { authority ->
                val result = authority.advanceWithPointCuts(independentMixed, Cancellation.Never)

                assertEquals(controlBudget.operations, budget.operations)
                assertEquals(assertNotNull(control.bound).claim, assertNotNull(result.bound).claim)
                assertEquals(1L, authority.cutObservations()["numericalCutSelectionFractionalPoolDeferred"])
                assertEquals(0L, authority.cutObservations()["numericalCutSelectionFractionalPreparations"] ?: 0L)
                assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(independentMixed.withLinearAuthority())
                    .verify(assertNotNull(result.bound)))
            }
    }

    @Test
    fun `closing the numerical owner should release retained aggregate preparation`() {
        val source = SourceProofContext(scope.copy(objective = SourceObjective("sum", ObjectiveSense.MAXIMIZE)),
            setOf(x, y, mixedReal), linkedMapOf(
                "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                    mixedReal to BigFraction.ONE), BigFraction.ofLong(3))),
                "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(2),
                    mixedReal to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            ), mapOf(x to BigFraction.ONE, y to BigFraction.ONE))
        val cuts = source.premises.entries.mapIndexed { owner, (name, premise) ->
            LinearAuthorityCut(owner, premise.inequality, SourceDerivation.Premise(name),
                mixed = true, family = CutFamily.SOURCE_AGGREGATION)
        }
        val funding = SourceLpFunding(4_000_000L, 8_000_000L).apply { grant(4_000_000L) }
        val authority = LinearAuthority(SourceLpBudget(funding = funding), retainLp = true,
            additionalCuts = cuts, aggregateCuts = true, fractionalAggregateOrder = true,
            reuseAggregatePreparation = true)

        val result = authority.use { it.advanceWithPointCuts(source, Cancellation.Never) }

        assertTrue(assertNotNull(authority.cutObservations()["numericalCutAggregateSourceIndexPrepared"]) > 0L)
        assertTrue(assertNotNull(authority.cutObservations()["numericalCutAggregateSourceIndexRetired"]) > 0L)
        assertTrue(assertNotNull(authority.cutObservations()["numericalCutAggregateSourceIndexReleasedBytes"]) > 0L)
        assertEquals(SourceEvidenceCheck.Checked,
            SourceEvidenceVerifier(source.withLinearAuthority()).verify(assertNotNull(result.bound)))
    }

    @Test
    fun `attempt observation should retain exact completed data after the numerical owner closes`() {
        val source = SourceProofContext(scope.copy(objective = SourceObjective("sum", ObjectiveSense.MAXIMIZE)),
            setOf(x, y, mixedReal), linkedMapOf(
                "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                    mixedReal to BigFraction.ONE), BigFraction.ofLong(3))),
                "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(2),
                    mixedReal to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            ), mapOf(x to BigFraction.ONE, y to BigFraction.ONE))
        val cuts = source.premises.entries.mapIndexed { owner, (name, premise) ->
            LinearAuthorityCut(owner, premise.inequality, SourceDerivation.Premise(name),
                mixed = true, family = CutFamily.SOURCE_AGGREGATION)
        }
        val funding = SourceLpFunding(4_000_000L, 8_000_000L).apply { grant(4_000_000L) }
        val budget = SourceLpBudget(funding = funding)
        val capture = LinearAuthorityCapture(4, 100_000L, 4_000_000L, Cancellation.Never)
        capture.observeFractionalAttempt()

        val result = LinearAuthority(budget, retainLp = true, additionalCuts = cuts, aggregateCuts = true,
            fractionalAggregateOrder = true).use {
            it.capture = capture
            it.advanceWithPointCuts(source, Cancellation.Never)
        }

        val attempt = assertNotNull(capture.fractionalAttempt)
        val query = assertNotNull(attempt.query)
        val point = assertNotNull(query.point)
        assertEquals(source.scope, query.context.scope)
        assertEquals(source.scope, query.generationScope)
        assertEquals(BigFraction.ofLong(3) * BigFraction.ofLong(2).reciprocal(),
            point.getValue(x) + point.getValue(y))
        assertTrue(query.cuts.all { it.installed && it.checked })
        assertEquals(1L, attempt.before["operations"])
        assertEquals(1L, attempt.after["operations"])
        assertEquals("query-selected", attempt.outcome)
        assertTrue(attempt.observationWork > 0L && attempt.observationAllocation > 0L)
        assertTrue(funding.spent >= attempt.observationWork)
        assertTrue(funding.allocated >= attempt.observationAllocation)
        val cost = assertNotNull(capture.fractionalQueryCost)
        assertEquals(source.scope, cost.scope)
        assertTrue(cost.installed.containsAll(query.cuts.map { it.id }))
        val after = assertNotNull(cost.after)
        assertEquals(1L, cost.before["operations"])
        assertEquals(2L, after["operations"])
        assertEquals("completed", cost.outcome)
        assertEquals(1L, cost.calls)
        assertEquals(0L, cost.pauses)
        assertTrue(assertNotNull(after["fundedWork"]) - assertNotNull(cost.before["fundedWork"]) >=
            cost.observationWork)
        assertEquals(SourceEvidenceCheck.Checked,
            SourceEvidenceVerifier(source.withLinearAuthority()).verify(assertNotNull(result.bound)))
    }

    @Test
    fun `fractional observation should remain disabled for an ordinary capture`() {
        val source = SourceProofContext(scope.copy(objective = SourceObjective("sum", ObjectiveSense.MAXIMIZE)),
            setOf(x, y, mixedReal), linkedMapOf(
                "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                    mixedReal to BigFraction.ONE), BigFraction.ofLong(3))),
                "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(2),
                    mixedReal to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            ), mapOf(x to BigFraction.ONE, y to BigFraction.ONE))
        val cuts = source.premises.entries.mapIndexed { owner, (name, premise) ->
            LinearAuthorityCut(owner, premise.inequality, SourceDerivation.Premise(name),
                mixed = true, family = CutFamily.SOURCE_AGGREGATION)
        }
        val capture = LinearAuthorityCapture(4, 100_000L, 4_000_000L, Cancellation.Never)
        LinearAuthority(SourceLpBudget(), retainLp = true, additionalCuts = cuts, aggregateCuts = true,
            fractionalAggregateOrder = true).use {
            it.capture = capture
            it.advanceWithPointCuts(source, Cancellation.Never)
        }

        assertEquals(0L, capture.fractionalAttempts)
        assertNull(capture.fractionalAttempt)
        assertFalse(capture.fractionalQueryCostStarted)
        assertNull(capture.fractionalQueryCost)
    }

    @Test
    fun `query cost should span a paused fractional continuation`() {
        val source = SourceProofContext(scope.copy(objective = SourceObjective("sum", ObjectiveSense.MAXIMIZE)),
            setOf(x, y, mixedReal), linkedMapOf(
                "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                    mixedReal to BigFraction.ONE), BigFraction.ofLong(3))),
                "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(2),
                    mixedReal to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            ), mapOf(x to BigFraction.ONE, y to BigFraction.ONE))
        val cuts = source.premises.entries.mapIndexed { owner, (name, premise) ->
            LinearAuthorityCut(owner, premise.inequality, SourceDerivation.Premise(name),
                mixed = true, family = CutFamily.SOURCE_AGGREGATION)
        }
        val funding = SourceLpFunding(4_000_000L, 8_000_000L).apply { grant(4_000_000L) }
        var operations = 0L
        var blocked = false
        val budget = SourceLpBudget(funding = funding, onWork = {
            operations += it.operations
            if (!blocked && operations == 2L) {
                blocked = true
                funding.grant(0L)
            }
        })
        val capture = LinearAuthorityCapture(4, 100_000L, 4_000_000L, Cancellation.Never)
        capture.observeFractionalAttempt()
        LinearAuthority(budget, retainLp = true, additionalCuts = cuts, aggregateCuts = true,
            fractionalAggregateOrder = true).use { authority ->
            authority.capture = capture
            assertTrue(authority.advanceWithPointCuts(source, Cancellation.Never).paused)
            assertTrue(capture.fractionalQueryCostPending)
            assertNull(capture.fractionalQueryCost)

            funding.grant(4_000_000L)
            assertFalse(authority.advanceWithPointCuts(source, Cancellation.Never).paused)
        }

        val cost = assertNotNull(capture.fractionalQueryCost)
        assertEquals("completed", cost.outcome)
        assertEquals(2L, cost.calls)
        assertEquals(1L, cost.pauses)
        assertEquals(1L, cost.before["operations"])
        assertEquals(2L, assertNotNull(cost.after)["operations"])
        assertFalse(capture.fractionalQueryCostPending)
    }

    @Test
    fun `an operation limit should leave fractional query cost incomplete`() {
        val source = SourceProofContext(scope.copy(objective = SourceObjective("sum", ObjectiveSense.MAXIMIZE)),
            setOf(x, y, mixedReal), linkedMapOf(
                "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                    mixedReal to BigFraction.ONE), BigFraction.ofLong(3))),
                "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(2),
                    mixedReal to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            ), mapOf(x to BigFraction.ONE, y to BigFraction.ONE))
        val cuts = source.premises.entries.mapIndexed { owner, (name, premise) ->
            LinearAuthorityCut(owner, premise.inequality, SourceDerivation.Premise(name),
                mixed = true, family = CutFamily.SOURCE_AGGREGATION)
        }
        val funding = SourceLpFunding(4_000_000L, 8_000_000L).apply { grant(4_000_000L) }
        val capture = LinearAuthorityCapture(4, 100_000L, 4_000_000L, Cancellation.Never)
        capture.observeFractionalAttempt()

        val result = LinearAuthority(SourceLpBudget(maxOperations = 1, funding = funding), retainLp = true,
            additionalCuts = cuts, aggregateCuts = true, fractionalAggregateOrder = true).use {
            it.capture = capture
            it.advanceWithPointCuts(source, Cancellation.Never)
        }

        val cost = assertNotNull(capture.fractionalQueryCost)
        assertEquals("unavailable", cost.outcome)
        assertEquals(1L, cost.before["operations"])
        assertEquals(1L, assertNotNull(cost.after)["operations"])
        assertEquals(SourceValue.Rational(BigFraction.ofLong(3) * BigFraction.ofLong(2).reciprocal()),
            (assertNotNull(result.bound).claim as SourceClaim.Bound).value)
    }

    @Test
    fun `an unavailable attempt capture should preserve the checked selected result`() {
        val source = SourceProofContext(scope.copy(objective = SourceObjective("sum", ObjectiveSense.MAXIMIZE)),
            setOf(x, y, mixedReal), linkedMapOf(
                "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                    mixedReal to BigFraction.ONE), BigFraction.ofLong(3))),
                "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(2),
                    mixedReal to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            ), mapOf(x to BigFraction.ONE, y to BigFraction.ONE))
        val cuts = source.premises.entries.mapIndexed { owner, (name, premise) ->
            LinearAuthorityCut(owner, premise.inequality, SourceDerivation.Premise(name),
                mixed = true, family = CutFamily.SOURCE_AGGREGATION)
        }
        val funding = SourceLpFunding(4_000_000L, 8_000_000L).apply { grant(4_000_000L) }
        val capture = LinearAuthorityCapture(1, 0L, 0L, Cancellation.Never)
        capture.observeFractionalAttempt()

        val result = LinearAuthority(SourceLpBudget(funding = funding), retainLp = true,
            additionalCuts = cuts, aggregateCuts = true, fractionalAggregateOrder = true).use {
            it.capture = capture
            it.advanceWithPointCuts(source, Cancellation.Never)
        }

        assertEquals(1L, capture.fractionalAttempts)
        assertTrue(capture.fractionalAttemptLimited)
        assertNull(capture.fractionalAttempt)
        assertEquals(SourceValue.Rational(BigFraction.ONE),
            (assertNotNull(result.bound).claim as SourceClaim.Bound).value)
        assertEquals(SourceEvidenceCheck.Checked,
            SourceEvidenceVerifier(source.withLinearAuthority()).verify(assertNotNull(result.bound)))
    }

    @Test
    fun `fractional continuation should retain installed current definitions`() {
        val source = SourceProofContext(scope.copy(objective = SourceObjective("sum", ObjectiveSense.MAXIMIZE)),
            setOf(x, y, mixedReal), linkedMapOf(
                "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                    mixedReal to BigFraction.ONE), BigFraction.ofLong(3))),
                "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(2),
                    mixedReal to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            ), mapOf(x to BigFraction.ONE, y to BigFraction.ONE))
        val cuts = source.premises.entries.mapIndexed { owner, (name, premise) ->
            LinearAuthorityCut(owner, premise.inequality, SourceDerivation.Premise(name),
                mixed = true, family = CutFamily.SOURCE_AGGREGATION)
        }
        val budget = SourceLpBudget()
        val capture = LinearAuthorityCapture(2, 100_000L, 4_000_000L, Cancellation.Never)
        LinearAuthority(budget, retainLp = true, additionalCuts = cuts, aggregateCuts = true,
            fractionalAggregateOrder = true)
            .use { authority ->
                authority.capture = capture

                val result = authority.advanceWithPointCuts(source, Cancellation.Never)

                assertEquals(SourceValue.Rational(BigFraction.ONE),
                    (assertNotNull(result.bound).claim as SourceClaim.Bound).value)
                assertEquals(2, budget.operations)
                val guidance = assertNotNull(result.branchGuidance)
                assertTrue(guidance.scope === source.scope)
                assertEquals(x to BigFraction.ONE, guidance.split)
                assertEquals(listOf(x to BigFraction.ofLong(3) * BigFraction.ofLong(2).reciprocal()),
                    guidance.fractional)
                assertEquals(1L, authority.cutObservations()["numericalCutSelectionFractionalRounds"])
                for (name in listOf("Pool", "Checked", "Installed")) {
                    assertEquals(2L, authority.cutObservations()["numericalCutSelectionFractionalAttempt${name}Rows"])
                }
                val previous = capture.records[0].cuts
                val continued = capture.records[1].cuts
                for (cut in previous) {
                    val retained = continued.single { it.id == cut.id }
                    assertEquals(cut.inequality, retained.inequality)
                    if (cut.installed) assertTrue(retained.installed && retained.checked)
                }
                assertEquals(SourceEvidenceCheck.Checked,
                    SourceEvidenceVerifier(source.withLinearAuthority()).verify(assertNotNull(result.bound)))
            }
    }

    @Test
    fun `an integral fractional continuation should withhold baseline branch guidance`() {
        val source = SourceProofContext(scope.copy(objective = SourceObjective("sum", ObjectiveSense.MAXIMIZE)),
            setOf(x, y, mixedReal), linkedMapOf(
                "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                    mixedReal to BigFraction.ONE), BigFraction.ofLong(3))),
                "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(2),
                    mixedReal to BigFraction.MINUS_ONE), BigFraction.ZERO)),
                "x lower" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.ZERO)),
                "x upper" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ZERO)),
            ), mapOf(x to BigFraction.ONE, y to BigFraction.ONE))
        val cuts = source.premises.entries.mapIndexed { owner, (name, premise) ->
            LinearAuthorityCut(owner, premise.inequality, SourceDerivation.Premise(name),
                mixed = true, family = CutFamily.SOURCE_AGGREGATION)
        }
        LinearAuthority(SourceLpBudget(), retainLp = true, additionalCuts = cuts, aggregateCuts = true,
            fractionalAggregateOrder = true).use { authority ->
            val result = authority.advanceWithPointCuts(source, Cancellation.Never)

            assertEquals(1L, authority.cutObservations()["numericalCutSelectionFractionalRounds"])
            assertNotNull(result.incumbent)
            assertNull(result.branchGuidance)
            assertEquals(SourceValue.Rational(BigFraction.ONE),
                (assertNotNull(result.bound).claim as SourceClaim.Bound).value)
            assertEquals(SourceEvidenceCheck.Checked,
                SourceEvidenceVerifier(source.withLinearAuthority()).verify(assertNotNull(result.bound)))
        }
    }

    @Test
    fun `fractional query decline should retain the completed current bound within its operation limit`() {
        val source = SourceProofContext(scope.copy(objective = SourceObjective("sum", ObjectiveSense.MAXIMIZE)),
            setOf(x, y, mixedReal), linkedMapOf(
                "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                    mixedReal to BigFraction.ONE), BigFraction.ofLong(3))),
                "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(2),
                    mixedReal to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            ), mapOf(x to BigFraction.ONE, y to BigFraction.ONE))
        val cuts = source.premises.entries.mapIndexed { owner, (name, premise) ->
            LinearAuthorityCut(owner, premise.inequality, SourceDerivation.Premise(name),
                mixed = true, family = CutFamily.SOURCE_AGGREGATION)
        }
        val budget = SourceLpBudget(maxOperations = 1)

        val result = LinearAuthority(budget, retainLp = true, additionalCuts = cuts, aggregateCuts = true,
            fractionalAggregateOrder = true)
            .use { it.advanceWithPointCuts(source, Cancellation.Never, retainBasePoint = true) }

        assertEquals(1, budget.operations)
        assertFalse(result.paused)
        assertEquals(SourceValue.Rational(BigFraction.ofLong(3) * BigFraction.ofLong(2).reciprocal()),
            (assertNotNull(result.bound).claim as SourceClaim.Bound).value)
        assertEquals(1.5, result.relaxedPoint.getValue(x) + result.relaxedPoint.getValue(y))
        assertEquals(SourceEvidenceCheck.Checked,
            SourceEvidenceVerifier(source.withLinearAuthority()).verify(assertNotNull(result.bound)))
    }

    @Test
    fun `a paused fractional query should resume without another current query`() {
        val source = SourceProofContext(scope.copy(objective = SourceObjective("sum", ObjectiveSense.MAXIMIZE)),
            setOf(x, y, mixedReal), linkedMapOf(
                "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                    mixedReal to BigFraction.ONE), BigFraction.ofLong(3))),
                "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(2),
                    mixedReal to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            ), mapOf(x to BigFraction.ONE, y to BigFraction.ONE))
        val cuts = source.premises.entries.mapIndexed { owner, (name, premise) ->
            LinearAuthorityCut(owner, premise.inequality, SourceDerivation.Premise(name),
                mixed = true, family = CutFamily.SOURCE_AGGREGATION)
        }
        val funding = SourceLpFunding(4_000_000L, 8_000_000L).apply { grant(4_000_000L) }
        var operations = 0L
        var blocked = false
        val budget = SourceLpBudget(funding = funding, onWork = {
            operations += it.operations
            if (!blocked && operations == 2L) {
                blocked = true
                funding.grant(0L)
            }
        })
        LinearAuthority(budget, retainLp = true, additionalCuts = cuts, aggregateCuts = true,
            fractionalAggregateOrder = true)
            .use { authority ->
                val paused = authority.advanceWithPointCuts(source, Cancellation.Never)
                assertTrue(paused.paused)
                assertNull(paused.branchGuidance)
                assertEquals(SourceValue.Rational(BigFraction.ofLong(3) * BigFraction.ofLong(2).reciprocal()),
                    (assertNotNull(paused.bound).claim as SourceClaim.Bound).value)

                funding.grant(4_000_000L)
                val completed = authority.advanceWithPointCuts(source, Cancellation.Never)

                assertFalse(completed.paused)
                assertEquals(2, budget.operations)
                assertEquals(SourceValue.Rational(BigFraction.ONE),
                    (assertNotNull(completed.bound).claim as SourceClaim.Bound).value)
                assertEquals(1L, authority.cutObservations()["numericalCutSelectionBaseQueries"])
                assertEquals(1L, authority.cutObservations()["numericalCutSelectionFractionalPreparations"])
                assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source.withLinearAuthority())
                    .verify(assertNotNull(completed.bound)))
            }
    }

    @Test
    fun `stopping a fractional query should preserve checked current authority`() {
        val source = SourceProofContext(scope.copy(objective = SourceObjective("sum", ObjectiveSense.MAXIMIZE)),
            setOf(x, y, mixedReal), linkedMapOf(
                "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                    mixedReal to BigFraction.ONE), BigFraction.ofLong(3))),
                "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(2),
                    mixedReal to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            ), mapOf(x to BigFraction.ONE, y to BigFraction.ONE))
        val cuts = source.premises.entries.mapIndexed { owner, (name, premise) ->
            LinearAuthorityCut(owner, premise.inequality, SourceDerivation.Premise(name),
                mixed = true, family = CutFamily.SOURCE_AGGREGATION)
        }
        for (reason in listOf("cancellation", "work", "allocation")) {
            val funding = SourceLpFunding(4_000_000L, 8_000_000L).apply { grant(4_000_000L) }
            var operations = 0L
            var cancelled = false
            val budget = SourceLpBudget(funding = funding, onWork = {
                operations += it.operations
                if (operations == 2L) when (reason) {
                    "cancellation" -> cancelled = true
                    "work" -> funding.charge(funding.remainingWork)
                    "allocation" -> funding.charge(0L, funding.remainingAllocation)
                }
            })

            val result = LinearAuthority(budget, retainLp = true, additionalCuts = cuts, aggregateCuts = true,
                fractionalAggregateOrder = true)
                .use { it.advanceWithPointCuts(source, Cancellation { cancelled }, retainBasePoint = true) }

            assertEquals(2, budget.operations)
            assertEquals(SourceValue.Rational(BigFraction.ofLong(3) * BigFraction.ofLong(2).reciprocal()),
                (assertNotNull(result.bound).claim as SourceClaim.Bound).value)
            assertNull(result.evidence)
            assertNull(result.incumbent)
            assertNull(result.advisoryPoint)
            assertNull(result.branchGuidance)
            assertEquals(SourceEvidenceCheck.Checked,
                SourceEvidenceVerifier(source.withLinearAuthority()).verify(assertNotNull(result.bound)))
        }
    }

    @Test
    fun `a changed scope should discard paused work before a fresh fractional pass`() {
        val source = SourceProofContext(scope.copy(objective = SourceObjective("sum", ObjectiveSense.MAXIMIZE)),
            setOf(x, y, mixedReal), linkedMapOf(
                "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                    mixedReal to BigFraction.ONE), BigFraction.ofLong(3))),
                "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(2),
                    mixedReal to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            ), mapOf(x to BigFraction.ONE, y to BigFraction.ONE))
        val cuts = source.premises.entries.mapIndexed { owner, (name, premise) ->
            LinearAuthorityCut(owner, premise.inequality, SourceDerivation.Premise(name),
                mixed = true, family = CutFamily.SOURCE_AGGREGATION)
        }
        val funding = SourceLpFunding(4_000_000L, 8_000_000L).apply { grant(4_000_000L) }
        var operations = 0L
        var blocked = false
        val budget = SourceLpBudget(funding = funding, onWork = {
            operations += it.operations
            if (!blocked && operations == 2L) {
                blocked = true
                funding.grant(0L)
            }
        })
        val capture = LinearAuthorityCapture(4, 100_000L, 4_000_000L, Cancellation.Never)
        capture.observeFractionalAttempt()
        LinearAuthority(budget, retainLp = true, additionalCuts = cuts, aggregateCuts = true,
            fractionalAggregateOrder = true)
            .use { authority ->
                authority.capture = capture
                assertTrue(authority.advanceWithPointCuts(source, Cancellation.Never).paused)

                funding.grant(4_000_000L)
                val changed = source.atScope(source.scope.copy(condition = SourceCondition(
                    upper = mapOf(x to BigFraction.ONE))))
                val result = authority.advanceWithPointCuts(changed, Cancellation.Never)

                assertFalse(result.paused)
                assertEquals("scope-changed", assertNotNull(capture.fractionalQueryCost).outcome)
                assertEquals(2L, authority.cutObservations()["numericalCutSelectionFractionalPassAttempts"])
                assertTrue(budget.operations >= 4)
                assertEquals(SourceValue.Rational(BigFraction.ONE),
                    (assertNotNull(result.bound).claim as SourceClaim.Bound).value)
                assertEquals(changed.scope, assertNotNull(result.bound).scope)
                result.branchGuidance?.let { assertTrue(it.scope === changed.scope) }
                assertEquals(SourceEvidenceCheck.Checked,
                    SourceEvidenceVerifier(changed.withLinearAuthority()).verify(assertNotNull(result.bound)))
            }
    }

    @Test
    fun `cancelled fractional preparation should restore the current selector and retain its costs`() {
        val source = SourceProofContext(scope.copy(objective = SourceObjective("sum", ObjectiveSense.MAXIMIZE)),
            setOf(x, y, mixedReal), linkedMapOf(
                "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                    mixedReal to BigFraction.ONE), BigFraction.ofLong(3))),
                "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(2),
                    mixedReal to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            ), mapOf(x to BigFraction.ONE, y to BigFraction.ONE))
        val cuts = source.premises.entries.mapIndexed { owner, (name, premise) ->
            LinearAuthorityCut(owner, premise.inequality, SourceDerivation.Premise(name),
                mixed = true, family = CutFamily.SOURCE_AGGREGATION)
        }
        val controlBudget = SourceLpBudget()
        val control = LinearAuthority(controlBudget, retainLp = true, additionalCuts = cuts, aggregateCuts = true)
            .use { authority ->
                listOf(authority.advanceWithPointCuts(source, Cancellation.Never),
                    authority.advanceWithPointCuts(source, Cancellation.Never))
            }
        var cancelled = false
        var observed: LinearAuthority? = null
        val budget = SourceLpBudget(onWork = {
            if ((observed?.cutObservations()?.get("numericalCutSelectionFractionalPreparations") ?: 0L) > 0L) {
                cancelled = true
            }
        })
        LinearAuthority(budget, retainLp = true, additionalCuts = cuts, aggregateCuts = true,
            fractionalAggregateOrder = true)
            .use { authority ->
                observed = authority
                val stopped = authority.advanceWithPointCuts(source, Cancellation { cancelled },
                    retainBasePoint = true)
                val work = authority.cutObservations().getValue("numericalCutSelectionWork")
                assertTrue(cancelled)
                assertNull(stopped.advisoryPoint)
                assertEquals(assertNotNull(control[0].bound).claim, assertNotNull(stopped.bound).claim)

                val restored = authority.advanceWithPointCuts(source, Cancellation.Never)

                assertEquals(controlBudget.operations, budget.operations)
                assertEquals(assertNotNull(control[1].bound).claim, assertNotNull(restored.bound).claim)
                assertTrue(authority.cutObservations().getValue("numericalCutSelectionWork") >= work)
                assertEquals(1L, authority.cutObservations()["numericalCutSelectionFractionalPreparations"])
                assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source.withLinearAuthority())
                    .verify(assertNotNull(restored.bound)))
            }
    }

    @Test
    fun `point policy preparation should include scoring in its containing recipe`() {
        val budget = SourceLpBudget()
        LinearAuthority(budget, retainLp = true).use { authority ->
            authority.advanceWithPointCuts(mixedMaximum, Cancellation.Never)

            val observations = authority.cutObservations()
            assertTrue((observations["numericalCutSelectionWork"] ?: 0L) > 0L)
            assertEquals(budget.measuredPreparationWork,
                (observations["numericalCutLifecycleRECIPE_CURRENT_PreparationWork"] ?: 0L) +
                    (observations["numericalCutLifecycleRECIPE_POINT_SELECTION_PreparationWork"] ?: 0L))
        }
    }

    @Test
    fun `closing the numerical owner should preserve final selection observations`() {
        val authority = LinearAuthority(SourceLpBudget(), retainLp = true)
        authority.advanceWithPointCuts(source, Cancellation.Never)
        val before = authority.cutObservations().filterKeys { it.startsWith("numericalCutSelection") }
        assertTrue(before.isNotEmpty())

        authority.close()

        assertEquals(before,
            authority.cutObservations().filterKeys { it.startsWith("numericalCutSelection") })
    }

    @Test
    fun `a checked current cut should settle its base without another selection round`() {
        val budget = SourceLpBudget()
        val capture = LinearAuthorityCapture(2, 10_000L, 1_000_000L, Cancellation.Never)
        val result = LinearAuthority(budget, retainLp = true).use {
            it.capture = capture
            it.advanceWithPointCuts(source, Cancellation.Never)
        }

        assertEquals(SourceValue.Rational(BigFraction.ONE),
            (assertNotNull(result.bound).claim as SourceClaim.Bound).value)
        assertEquals(1, budget.operations)
        assertEquals(listOf(true), capture.records.map { it.cuts.single().installed })
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source.withLinearAuthority())
            .verify(assertNotNull(result.evidence)))
    }

    @Test
    fun `maximization should retain the tighter checked selected upper bound`() {
        val maximum = SourceProofContext(scope.copy(objective = SourceObjective("x", ObjectiveSense.MAXIMIZE)),
            setOf(x), mapOf("upper" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2)),
                BigFraction.ofLong(3)))), mapOf(x to BigFraction.ONE))

        val result = LinearAuthority(SourceLpBudget(), retainLp = true).use {
            it.advanceWithPointCuts(maximum, Cancellation.Never)
        }

        assertEquals(SourceValue.Rational(BigFraction.ONE),
            (assertNotNull(result.bound).claim as SourceClaim.Bound).value)
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(maximum.withLinearAuthority())
            .verify(assertNotNull(result.evidence)))
    }

    @Test
    fun `complementary mixed rounding should strengthen a bound left unchanged by the preferred side`() {
        LinearAuthority(SourceLpBudget(), retainLp = true).use { authority ->
            val current = authority.advance(mixedMaximum, Cancellation.Never, cuts = true)
            val candidate = authority.advanceWithPointCuts(mixedMaximum, Cancellation.Never)
            val restored = authority.advance(mixedMaximum, Cancellation.Never, cuts = true)

            assertEquals(SourceValue.Rational(BigFraction.ofLong(5) * BigFraction.ofLong(2).reciprocal()),
                (assertNotNull(current.bound).claim as SourceClaim.Bound).value)
            assertEquals(SourceValue.Rational(BigFraction.ofLong(2)),
                (assertNotNull(candidate.bound).claim as SourceClaim.Bound).value)
            assertEquals((assertNotNull(current.bound).claim as SourceClaim.Bound).value,
                (assertNotNull(restored.bound).claim as SourceClaim.Bound).value)
            assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(mixedMaximum.withLinearAuthority())
                .verify(assertNotNull(candidate.bound)))
            assertEquals(0L, authority.cutObservations()["numericalCutSelectionUnchangedPreparations"] ?: 0L)
        }
    }

    @Test
    fun `a nonseparating point should retain the ordinary checked recipe across queries`() {
        val capture = LinearAuthorityCapture(2, 10_000L, 1_000_000L, Cancellation.Never)

        LinearAuthority(SourceLpBudget(), retainLp = true).use { authority ->
            authority.capture = capture
            authority.advanceWithPointCuts(nonseparating, Cancellation.Never)
            val preparations = authority.preparations
            val scoring = authority.cutObservations()["numericalCutSelectionWork"]
            assertEquals(1L, authority.cutObservations()["numericalCutSelectionUnchangedPreparations"])
            assertNull(scoring)

            val result = authority.advanceWithPointCuts(nonseparating, Cancellation.Never)

            assertEquals(listOf(true, true), capture.records.map { it.cuts.single().installed })
            assertEquals(preparations, authority.preparations)
            assertEquals(scoring, authority.cutObservations()["numericalCutSelectionWork"])
            assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(nonseparating.withLinearAuthority())
                .verify(assertNotNull(result.bound)))
        }
    }

    @Test
    fun `an interrupted unchanged comparison should keep its checked base bound`() {
        var cancelled = false
        var observed: LinearAuthority? = null
        val budget = SourceLpBudget(onWork = {
            if ((observed?.cutObservations()?.get("numericalCutSelectionPreparationReuseChecks") ?: 0L) > 0L) {
                cancelled = true
            }
        })
        LinearAuthority(budget, retainLp = true).use { authority ->
            observed = authority

            val result = authority.advanceWithPointCuts(nonseparating, Cancellation { cancelled })

            assertTrue(cancelled)
            assertEquals(0L, authority.cutObservations()["numericalCutSelectionUnchangedPreparations"] ?: 0L)
            assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(nonseparating.withLinearAuthority())
                .verify(assertNotNull(result.bound)))
        }
    }

    @Test
    fun `a changed source should clear the ordinary fallback`() {
        LinearAuthority(SourceLpBudget(), retainLp = true).use { authority ->
            authority.advanceWithPointCuts(nonseparating, Cancellation.Never)

            val result = authority.advanceWithPointCuts(mixedMaximum, Cancellation.Never)

            assertEquals(SourceValue.Rational(BigFraction.ofLong(2)),
                (assertNotNull(result.bound).claim as SourceClaim.Bound).value)
            assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(mixedMaximum.withLinearAuthority())
                .verify(assertNotNull(result.bound)))
        }
    }

    @Test
    fun `parallel candidates should keep only the strongest scored face`() {
        val rows = listOf(
            LinearAuthorityCut(0, SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ZERO),
                SourceDerivation.Premise("a")),
            LinearAuthorityCut(1, SourceInequality(mapOf(x to BigFraction.ofLong(2)), BigFraction.ZERO),
                SourceDerivation.Premise("b")),
        )
        val selection = LinearAuthorityPointCuts(SourceLpBudget())

        val selected = selection.select(rows, listOf(x), listOf(BigFraction.ZERO), setOf(0, 1),
            listOf(BigFraction.ONE), emptySet(), Cancellation.Never)

        assertEquals(setOf(0), selected)
    }

    @Test
    fun `a point should admit only the strongest nonparallel candidate`() {
        val rows = listOf(
            LinearAuthorityCut(0, SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ZERO),
                SourceDerivation.Premise("x upper")),
            LinearAuthorityCut(1, SourceInequality(mapOf(y to BigFraction.ONE), BigFraction.ZERO),
                SourceDerivation.Premise("y upper")),
        )
        val selection = LinearAuthorityPointCuts(SourceLpBudget())

        val selected = selection.select(rows, listOf(x, y), List(2) { BigFraction.ZERO }, setOf(0, 1),
            listOf(BigFraction.ofLong(2), BigFraction.ONE), emptySet(), Cancellation.Never)

        assertEquals(setOf(0), selected)
    }

    @Test
    fun `fractional batching should retain the useful alternative to the highest violation`() {
        val context = SourceProofContext(scope, setOf(x, y), linkedMapOf(
            "x lower" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(-10)),
                BigFraction.MINUS_ONE)),
            "y lower" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(-5)),
                BigFraction.ofLong(-2))),
        ), mapOf(x to BigFraction.ONE, y to BigFraction.ofLong(100)))
        val rows = assertNotNull(LinearAuthorityCuts.prepare(context, Cancellation.Never)).rows
        val checked = rows.indices.toSet()
        val coordinates = listOf(x, y)
        val costs = listOf(BigFraction.ONE, BigFraction.ofLong(100))
        val point = listOf(BigFraction.ofLong(10).reciprocal(),
            BigFraction.ofLong(2) * BigFraction.ofLong(5).reciprocal())
        val bounds = ArrayList<SourceValue>()
        for (fractional in listOf(false, true)) {
            val selection = LinearAuthorityPointCuts(SourceLpBudget())

            val selected = selection.select(rows, coordinates, costs, checked, point, emptySet(),
                Cancellation.Never, fractional = fractional)
            val result = LinearAuthority(SourceLpBudget()).use {
                it.useReplayCuts(rows)
                it.advance(context, Cancellation.Never, cuts = true, cutMask = selected)
            }

            assertEquals(if (fractional) setOf(0, 1) else setOf(0), selected)
            val bound = assertNotNull(result.bound)
            bounds += (bound.claim as SourceClaim.Bound).value
            assertEquals(SourceEvidenceCheck.Checked,
                SourceEvidenceVerifier(context.withLinearAuthority()).verify(bound))
        }

        assertEquals<List<SourceValue>>(listOf(SourceValue.Rational(BigFraction.ofLong(41)),
            SourceValue.Rational(BigFraction.ofLong(101))), bounds)
    }

    @Test
    fun `fractional batches should respect the addition and installed pool caps`() {
        val coordinates = (0..9).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
        val rows = coordinates.mapIndexed { id, coordinate ->
            LinearAuthorityCut(id, SourceInequality(mapOf(coordinate to BigFraction.ONE), BigFraction.ZERO),
                SourceDerivation.Premise("upper $id"))
        }
        for (maximum in listOf(64, 5)) {
            val selection = LinearAuthorityPointCuts(SourceLpBudget(), maximumCuts = maximum)

            val selected = selection.select(rows, coordinates, List(10) { BigFraction.ZERO },
                (0..8).toSet(), List(10) { if (it == 0) BigFraction.ZERO else BigFraction.ONE }, setOf(0),
                Cancellation.Never, fractional = true)

            assertEquals((0..minOf(8, maximum - 1)).toSet(), selected)
            assertEquals(1L, selection.observations["numericalCutSelectionFractionalBatchAttempts"])
        }
    }

    @Test
    fun `fractional batches should exclude candidates parallel to an installed row`() {
        val rows = listOf(
            LinearAuthorityCut(0, SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ZERO),
                SourceDerivation.Premise("x upper")),
            LinearAuthorityCut(1, SourceInequality(mapOf(x to BigFraction.ofLong(2)), BigFraction.MINUS_ONE),
                SourceDerivation.Premise("parallel upper")),
            LinearAuthorityCut(2, SourceInequality(mapOf(y to BigFraction.ONE), BigFraction.ZERO),
                SourceDerivation.Premise("y upper")),
        )

        val selected = LinearAuthorityPointCuts(SourceLpBudget()).select(rows, listOf(x, y),
            List(2) { BigFraction.ZERO }, setOf(0, 1, 2), listOf(BigFraction.ZERO, BigFraction.ONE), setOf(0),
            Cancellation.Never, fractional = true)

        assertEquals(setOf(0, 2), selected)
    }

    @Test
    fun `cancelling batch comparisons should withhold the partially selected mask`() {
        val coordinates = (0..2).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
        val rows = coordinates.mapIndexed { id, coordinate ->
            LinearAuthorityCut(id, SourceInequality(mapOf(coordinate to BigFraction.ONE), BigFraction.ZERO),
                SourceDerivation.Premise("upper $id"))
        }
        val budget = SourceLpBudget()
        val selection = LinearAuthorityPointCuts(budget)
        var batching = false
        var polls = 0
        selection.observeTo { key, _ ->
            if (key == "numericalCutSelectionFractionalBatchAttempts") batching = true
        }

        val selected = selection.select(rows, coordinates, List(3) { BigFraction.ZERO }, setOf(0, 1, 2),
            List(3) { BigFraction.ONE }, emptySet(), Cancellation { batching && ++polls >= 2 }, fractional = true)

        assertTrue(polls >= 2)
        assertTrue(selected.isEmpty())
        assertNull(selection.observations["numericalCutSelectionSelected"])
        assertEquals(1L, selection.observations["numericalCutSelectionWorkLimited"])
        assertTrue(budget.measuredPreparationWork > 0L)
    }

    @Test
    fun `unfunded batches should preserve the prior installed mask`() {
        val coordinates = (0..2).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
        val rows = coordinates.mapIndexed { id, coordinate ->
            LinearAuthorityCut(id, SourceInequality(mapOf(coordinate to BigFraction.ONE), BigFraction.ZERO),
                SourceDerivation.Premise("upper $id"))
        }
        for (resource in listOf("work", "allocation")) {
            val funding = SourceLpFunding(100_000L, 1_048_576L).apply { grant(100_000L) }
            val budget = SourceLpBudget(funding = funding)
            val selection = LinearAuthorityPointCuts(budget)
            selection.observeTo { key, _ ->
                if (key == "numericalCutSelectionFractionalBatchAttempts") {
                    if (resource == "work") funding.charge(funding.remainingWork)
                    else funding.charge(0L, funding.remainingAllocation)
                }
            }

            val selected = selection.select(rows, coordinates, List(3) { BigFraction.ZERO }, setOf(0, 1, 2),
                listOf(BigFraction.ZERO, BigFraction.ONE, BigFraction.ONE), setOf(0), Cancellation.Never,
                fractional = true)

            assertEquals(setOf(0), selected)
            assertEquals(1L, selection.observations["numericalCutSelectionWorkLimited"])
            assertTrue(budget.measuredPreparationWork > 0L)
        }
    }

    @Test
    fun `cancelling fractional batch publication should retain the paid base bound`() {
        val context = SourceProofContext(scope.copy(objective = SourceObjective("sum", ObjectiveSense.MAXIMIZE)),
            setOf(x, y, mixedReal), linkedMapOf(
                "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                    mixedReal to BigFraction.ONE), BigFraction.ofLong(3))),
                "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(2),
                    mixedReal to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            ), mapOf(x to BigFraction.ONE, y to BigFraction.ONE))
        val rows = context.premises.entries.mapIndexed { owner, (name, premise) ->
            LinearAuthorityCut(owner, premise.inequality, SourceDerivation.Premise(name),
                mixed = true, family = CutFamily.SOURCE_AGGREGATION)
        }
        var observed: LinearAuthority? = null
        var stopped = false
        val funding = SourceLpFunding(4_000_000L, 8_000_000L).apply { grant(4_000_000L) }
        val budget = SourceLpBudget(funding = funding, onWork = {
            if ((observed?.cutObservations()?.get("numericalCutSelectionFractionalBatchAttempts") ?: 0L) > 0L) {
                stopped = true
            }
        })

        val result = LinearAuthority(budget, retainLp = true, additionalCuts = rows, aggregateCuts = true,
            fractionalAggregateOrder = true).use {
            observed = it
            it.advanceWithPointCuts(context, Cancellation { stopped })
        }

        assertTrue(stopped)
        assertEquals(1, budget.operations)
        val bound = assertNotNull(result.bound)
        assertEquals(SourceValue.Rational(BigFraction.ofLong(3) * BigFraction.ofLong(2).reciprocal()),
            (bound.claim as SourceClaim.Bound).value)
        assertEquals(SourceEvidenceCheck.Checked,
            SourceEvidenceVerifier(context.withLinearAuthority()).verify(bound))
    }

    @Test
    fun `explicit objective aliases should rank cuts like their structural objective`() {
        val rows = listOf(
            LinearAuthorityCut(0, SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ZERO),
                SourceDerivation.Premise("x upper")),
            LinearAuthorityCut(1, SourceInequality(mapOf(y to BigFraction.ONE), BigFraction.ZERO),
                SourceDerivation.Premise("y upper")),
        )
        for (sign in listOf(1L, -1L)) for (scale in listOf(1L, -3L)) {
            val coefficients = mapOf(mixedReal to BigFraction.ofLong(scale), y to BigFraction.ofLong(-2 * scale))
            val rhs = BigFraction.ofLong(3 * scale)
            val source = SourceProofContext(scope.copy(objective = SourceObjective("cost",
                if (sign > 0) ObjectiveSense.MINIMIZE else ObjectiveSense.MAXIMIZE)), setOf(x, y, mixedReal), mapOf(
                "definition upper" to SourcePremise(SourceInequality(coefficients, rhs)),
                "definition lower" to SourcePremise(SourceInequality(coefficients.mapValues { it.value.negated() },
                    rhs.negated())),
            ), mapOf(mixedReal to BigFraction.ofLong(sign)))
            val coordinates = listOf(x, y, mixedReal)
            val point = listOf(BigFraction.ONE, BigFraction.ONE, BigFraction.ofLong(5))
            val aliasCosts = listOf(BigFraction.ZERO, BigFraction.ZERO, BigFraction.ofLong(sign))
            val directCosts = listOf(BigFraction.ZERO, BigFraction.ofLong(2 * sign), BigFraction.ZERO)
            val selection = LinearAuthorityPointCuts(SourceLpBudget())

            val raw = LinearAuthorityPointCuts(SourceLpBudget()).select(rows, coordinates, aliasCosts,
                setOf(0, 1), point, emptySet(), Cancellation.Never)
            val direct = LinearAuthorityPointCuts(SourceLpBudget()).select(rows, coordinates, directCosts,
                setOf(0, 1), point, emptySet(), Cancellation.Never, objectiveSource = source)
            val aliased = selection.select(rows, coordinates, aliasCosts, setOf(0, 1), point,
                emptySet(), Cancellation.Never, objectiveSource = source)

            assertEquals(setOf(0), raw)
            assertEquals(setOf(1), direct)
            assertEquals(direct, aliased)
            assertEquals(1L, selection.observations["numericalCutSelectionObjectiveProjected"])
        }
    }

    @Test
    fun `unsupported objective definitions should retain raw cut ordering`() {
        val rows = listOf(
            LinearAuthorityCut(0, SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ZERO),
                SourceDerivation.Premise("x upper")),
            LinearAuthorityCut(1, SourceInequality(mapOf(y to BigFraction.ONE), BigFraction.ZERO),
                SourceDerivation.Premise("y upper")),
        )
        val boolean = SourceCoordinate(SourceCoordinate.Kind.Boolean, 0)
        val huge = assertNotNull(BigFraction.ofDouble(Double.MAX_VALUE)) * BigFraction.ofLong(2)
        val tiny = assertNotNull(BigFraction.ofDouble(Double.MIN_VALUE))
        val upper = SourceInequality(mapOf(mixedReal to BigFraction.ONE, y to BigFraction.ofLong(-2)),
            BigFraction.ofLong(3))
        val lower = SourceInequality(mapOf(mixedReal to BigFraction.MINUS_ONE, y to BigFraction.ofLong(2)),
            BigFraction.ofLong(-3))
        val nonfinite = SourceInequality(mapOf(mixedReal to BigFraction.ONE, y to huge), BigFraction.ZERO)
        val underflow = SourceInequality(mapOf(mixedReal to BigFraction.ONE, y to tiny), BigFraction.ZERO)
        val definitions = listOf(
            mapOf("upper" to SourcePremise(upper)),
            mapOf("upper" to SourcePremise(upper), "lower" to SourcePremise(
                SourceInequality(lower.coefficients, lower.rhs, strict = true))),
            mapOf("upper" to SourcePremise(upper, mapOf(0 to true)),
                "lower" to SourcePremise(lower, mapOf(0 to true))),
            mapOf("upper" to SourcePremise(upper, emptyMap(), mapOf(x to BigFraction.ONE)),
                "lower" to SourcePremise(lower, emptyMap(), mapOf(x to BigFraction.ONE))),
            mapOf("upper" to SourcePremise(upper), "lower" to SourcePremise(
                SourceInequality(lower.coefficients, BigFraction.ofLong(-4)))),
            mapOf("upper" to SourcePremise(upper), "lower" to SourcePremise(lower),
                "other definition" to SourcePremise(SourceInequality(mapOf(mixedReal to BigFraction.ONE,
                    x to BigFraction.MINUS_ONE), BigFraction.ZERO))),
            mapOf("upper" to SourcePremise(nonfinite), "lower" to SourcePremise(SourceInequality(
                nonfinite.coefficients.mapValues { it.value.negated() }, BigFraction.ZERO))),
            mapOf("upper" to SourcePremise(underflow), "lower" to SourcePremise(SourceInequality(
                underflow.coefficients.mapValues { it.value.negated() }, BigFraction.ZERO))),
        )
        for (premises in definitions) {
            val source = SourceProofContext(scope, setOf(x, y, mixedReal, boolean), premises,
                mapOf(mixedReal to BigFraction.ONE))
            val selection = LinearAuthorityPointCuts(SourceLpBudget())

            val selected = selection.select(rows, listOf(x, y, mixedReal),
                listOf(BigFraction.ZERO, BigFraction.ZERO, BigFraction.ONE), setOf(0, 1),
                listOf(BigFraction.ONE, BigFraction.ONE, BigFraction.ofLong(5)), emptySet(), Cancellation.Never,
                objectiveSource = source)

            assertEquals(setOf(0), selected)
            assertEquals(1L, selection.observations["numericalCutSelectionObjectiveProjectionDeclined"])
            assertNull(selection.observations["numericalCutSelectionObjectiveProjected"])
        }
    }

    @Test
    fun `objective projection should debit its lookup and storage`() {
        val rows = listOf(
            LinearAuthorityCut(0, SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ZERO),
                SourceDerivation.Premise("x upper")),
            LinearAuthorityCut(1, SourceInequality(mapOf(y to BigFraction.ONE), BigFraction.ZERO),
                SourceDerivation.Premise("y upper")),
        )
        val source = SourceProofContext(scope, setOf(x, y, mixedReal), mapOf(
            "upper" to SourcePremise(SourceInequality(mapOf(mixedReal to BigFraction.ONE,
                y to BigFraction.ofLong(-2)), BigFraction.ZERO)),
            "lower" to SourcePremise(SourceInequality(mapOf(mixedReal to BigFraction.MINUS_ONE,
                y to BigFraction.ofLong(2)), BigFraction.ZERO)),
        ), mapOf(mixedReal to BigFraction.ONE))
        val costs = listOf(BigFraction.ZERO, BigFraction.ZERO, BigFraction.ONE)
        val point = listOf(BigFraction.ONE, BigFraction.ONE, BigFraction.ofLong(2))
        val rawFunding = SourceLpFunding(100_000L, 1_048_576L).apply { grant(100_000L) }
        val projectedFunding = SourceLpFunding(100_000L, 1_048_576L).apply { grant(100_000L) }
        val budget = SourceLpBudget(funding = projectedFunding)

        LinearAuthorityPointCuts(SourceLpBudget(funding = rawFunding)).select(rows, listOf(x, y, mixedReal),
            costs, setOf(0, 1), point, emptySet(), Cancellation.Never)
        val selected = LinearAuthorityPointCuts(budget).select(rows, listOf(x, y, mixedReal), costs,
            setOf(0, 1), point, emptySet(), Cancellation.Never, objectiveSource = source)

        assertEquals(setOf(1), selected)
        assertTrue(projectedFunding.spent > rawFunding.spent)
        assertTrue(projectedFunding.allocated > rawFunding.allocated)
        assertEquals(projectedFunding.spent, budget.measuredPreparationWork)
        assertTrue(projectedFunding.spent <= 100_000L)
        assertTrue(projectedFunding.allocated <= 1_048_576L)
    }

    @Test
    fun `interrupted objective projection should preserve the installed mask`() {
        val rows = listOf(
            LinearAuthorityCut(0, SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ZERO),
                SourceDerivation.Premise("x upper")),
            LinearAuthorityCut(1, SourceInequality(mapOf(y to BigFraction.ONE), BigFraction.ZERO),
                SourceDerivation.Premise("y upper")),
        )
        val source = SourceProofContext(scope, setOf(x, y, mixedReal), mapOf(
            "upper" to SourcePremise(SourceInequality(mapOf(mixedReal to BigFraction.ONE,
                y to BigFraction.ofLong(-2)), BigFraction.ZERO)),
            "lower" to SourcePremise(SourceInequality(mapOf(mixedReal to BigFraction.MINUS_ONE,
                y to BigFraction.ofLong(2)), BigFraction.ZERO)),
        ), mapOf(mixedReal to BigFraction.ONE))
        for (interruption in listOf("cancellation", "work", "allocation")) {
            val funding = SourceLpFunding(100_000L, 1_048_576L).apply { grant(100_000L) }
            val budget = SourceLpBudget(funding = funding)
            val selection = LinearAuthorityPointCuts(budget)
            var cancelled = false
            selection.observeTo { key, _ ->
                if (key == "numericalCutSelectionObjectiveProjectionAttempts") when (interruption) {
                    "cancellation" -> cancelled = true
                    "work" -> funding.charge(funding.remainingWork)
                    "allocation" -> funding.charge(0L, funding.remainingAllocation)
                }
            }

            val selected = selection.select(rows, listOf(x, y, mixedReal),
                listOf(BigFraction.ZERO, BigFraction.ZERO, BigFraction.ONE), setOf(0, 1),
                listOf(BigFraction.ONE, BigFraction.ONE, BigFraction.ofLong(2)), setOf(0),
                Cancellation { cancelled }, objectiveSource = source)

            assertEquals(setOf(0), selected)
            assertEquals(1L, selection.observations["numericalCutSelectionWorkLimited"])
            assertNull(selection.observations["numericalCutSelectionObjectiveProjected"])
            assertTrue(budget.measuredPreparationWork > 0L)
        }
    }

    @Test
    fun `checked round progress should let the next point admit a complementary cut`() {
        val rows = listOf(
            LinearAuthorityCut(0, SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ZERO),
                SourceDerivation.Premise("x upper")),
            LinearAuthorityCut(1, SourceInequality(mapOf(y to BigFraction.ONE), BigFraction.ZERO),
                SourceDerivation.Premise("y upper")),
        )
        val selection = LinearAuthorityPointCuts(SourceLpBudget())
        val installed = selection.select(rows, listOf(x, y), List(2) { BigFraction.ZERO }, setOf(0, 1),
            listOf(BigFraction.ofLong(2), BigFraction.ONE), emptySet(), Cancellation.Never)
        selection.observeRound(rows, improved = true)

        val selected = selection.select(rows, listOf(x, y), List(2) { BigFraction.ZERO }, setOf(0, 1),
            listOf(BigFraction.ZERO, BigFraction.ONE), installed, Cancellation.Never)

        assertEquals(setOf(0, 1), selected)
    }

    @Test
    fun `a stalled round should wait seven queries before admitting another violated cut`() {
        val rows = listOf(
            LinearAuthorityCut(0, SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ZERO),
                SourceDerivation.Premise("x upper")),
            LinearAuthorityCut(1, SourceInequality(mapOf(y to BigFraction.ONE), BigFraction.ZERO),
                SourceDerivation.Premise("y upper")),
        )
        val selection = LinearAuthorityPointCuts(SourceLpBudget())
        val installed = selection.select(rows, listOf(x, y), List(2) { BigFraction.ZERO }, setOf(0, 1),
            listOf(BigFraction.ofLong(2), BigFraction.ONE), emptySet(), Cancellation.Never)
        selection.observeRound(rows, improved = false)
        repeat(7) {
            assertEquals(installed, selection.select(rows, listOf(x, y), List(2) { BigFraction.ZERO },
                setOf(0, 1), listOf(BigFraction.ZERO, BigFraction.ONE), installed, Cancellation.Never))
        }

        val selected = selection.select(rows, listOf(x, y), List(2) { BigFraction.ZERO }, setOf(0, 1),
            listOf(BigFraction.ZERO, BigFraction.ONE), installed, Cancellation.Never)

        assertEquals(setOf(0, 1), selected)
    }

    @Test
    fun `an unfinished round should leave discovery cooling down`() {
        val rows = listOf(
            LinearAuthorityCut(0, SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ZERO),
                SourceDerivation.Premise("x upper")),
            LinearAuthorityCut(1, SourceInequality(mapOf(y to BigFraction.ONE), BigFraction.ZERO),
                SourceDerivation.Premise("y upper")),
        )
        val selection = LinearAuthorityPointCuts(SourceLpBudget())
        val installed = selection.select(rows, listOf(x, y), List(2) { BigFraction.ZERO }, setOf(0, 1),
            listOf(BigFraction.ofLong(2), BigFraction.ONE), emptySet(), Cancellation.Never)

        val selected = selection.select(rows, listOf(x, y), List(2) { BigFraction.ZERO }, setOf(0, 1),
            listOf(BigFraction.ZERO, BigFraction.ONE), installed, Cancellation.Never)

        assertEquals(installed, selected)
    }

    @Test
    fun `progress for a replaced family should not enable discovery`() {
        val previous = listOf(
            LinearAuthorityCut(0, SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ZERO),
                SourceDerivation.Premise("x upper")),
            LinearAuthorityCut(1, SourceInequality(mapOf(y to BigFraction.ONE), BigFraction.ZERO),
                SourceDerivation.Premise("y upper")),
        )
        val selection = LinearAuthorityPointCuts(SourceLpBudget())
        selection.select(previous, listOf(x, y), List(2) { BigFraction.ZERO }, setOf(0, 1),
            listOf(BigFraction.ofLong(2), BigFraction.ONE), emptySet(), Cancellation.Never)
        val rows = previous.toList()
        val installed = selection.select(rows, listOf(x, y), List(2) { BigFraction.ZERO }, setOf(0, 1),
            listOf(BigFraction.ofLong(2), BigFraction.ONE), emptySet(), Cancellation.Never)
        selection.observeRound(previous, improved = true)

        val selected = selection.select(rows, listOf(x, y), List(2) { BigFraction.ZERO }, setOf(0, 1),
            listOf(BigFraction.ZERO, BigFraction.ONE), installed, Cancellation.Never)

        assertEquals(installed, selected)
    }

    @Test
    fun `an unsuccessful point should restore discovery cooldown after an addition`() {
        val rows = listOf(
            LinearAuthorityCut(0, SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ZERO),
                SourceDerivation.Premise("x upper")),
            LinearAuthorityCut(1, SourceInequality(mapOf(y to BigFraction.ONE), BigFraction.ZERO),
                SourceDerivation.Premise("y upper")),
        )
        val selection = LinearAuthorityPointCuts(SourceLpBudget())
        val installed = selection.select(rows, listOf(x, y), List(2) { BigFraction.ZERO }, setOf(0, 1),
            listOf(BigFraction.ofLong(2), BigFraction.ONE), emptySet(), Cancellation.Never)
        selection.observeRound(rows, improved = true)
        selection.select(rows, listOf(x, y), List(2) { BigFraction.ZERO }, setOf(0, 1),
            List(2) { BigFraction.ZERO }, installed, Cancellation.Never)
        val work = selection.observations["numericalCutSelectionWork"]

        val selected = selection.select(rows, listOf(x, y), List(2) { BigFraction.ZERO }, setOf(0, 1),
            listOf(BigFraction.ZERO, BigFraction.ONE), installed, Cancellation.Never)

        assertEquals(installed, selected)
        assertEquals(work, selection.observations["numericalCutSelectionWork"])
    }

    @Test
    fun `adding another checked cut should retain the numerical owner`() {
        val z = SourceCoordinate(SourceCoordinate.Kind.Integer, 2)
        val w = SourceCoordinate(SourceCoordinate.Kind.Integer, 3)
        val other = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val context = SourceProofContext(mixedMaximum.scope.copy(
            objective = SourceObjective("sum", ObjectiveSense.MAXIMIZE)),
            mixedMaximum.coordinates + setOf(y, z, w, other), mixedMaximum.premises + mapOf(
                "mixed" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                    y to BigFraction.ofLong(2), mixedReal to BigFraction.MINUS_ONE), BigFraction.ofLong(3))),
                "other mixed" to SourcePremise(SourceInequality(mapOf(z to BigFraction.ofLong(2),
                    w to BigFraction.ofLong(2),
                    other to BigFraction.MINUS_ONE), BigFraction.ofLong(3))),
                "other lower" to SourcePremise(SourceInequality(mapOf(other to BigFraction.MINUS_ONE),
                    BigFraction.ZERO)),
                "other upper" to SourcePremise(SourceInequality(mapOf(other to BigFraction.ONE),
                    BigFraction.ofLong(2))),
            ), listOf(x, y, z, w).associateWith { BigFraction.ONE })
        LinearAuthority(SourceLpBudget(), retainLp = true).use { authority ->
            val first = authority.advanceWithPointCuts(context, Cancellation.Never)
            assertEquals(SourceValue.Rational(BigFraction.ofLong(9) * BigFraction.ofLong(2).reciprocal()),
                (assertNotNull(first.bound).claim as SourceClaim.Bound).value)
            val preparations = authority.preparations

            val second = authority.advanceWithPointCuts(context, Cancellation.Never)

            assertEquals(SourceValue.Rational(BigFraction.ofLong(4)),
                (assertNotNull(second.bound).claim as SourceClaim.Bound).value)
            assertEquals(preparations, authority.preparations)
            assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(context.withLinearAuthority())
                .verify(assertNotNull(second.bound)))
        }
    }

    @Test
    fun `retained unselected definitions should not replace the ordinary current recipe`() {
        val z = SourceCoordinate(SourceCoordinate.Kind.Integer, 2)
        val w = SourceCoordinate(SourceCoordinate.Kind.Integer, 3)
        val other = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val context = SourceProofContext(mixedMaximum.scope.copy(
            objective = SourceObjective("sum", ObjectiveSense.MAXIMIZE)),
            mixedMaximum.coordinates + setOf(y, z, w, other), mixedMaximum.premises + mapOf(
                "mixed" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                    y to BigFraction.ofLong(2), mixedReal to BigFraction.MINUS_ONE), BigFraction.ofLong(3))),
                "other mixed" to SourcePremise(SourceInequality(mapOf(z to BigFraction.ofLong(2),
                    w to BigFraction.ofLong(2),
                    other to BigFraction.MINUS_ONE), BigFraction.ofLong(3))),
                "other lower" to SourcePremise(SourceInequality(mapOf(other to BigFraction.MINUS_ONE),
                    BigFraction.ZERO)),
                "other upper" to SourcePremise(SourceInequality(mapOf(other to BigFraction.ONE),
                    BigFraction.ofLong(2))),
            ), listOf(x, y, z, w).associateWith { BigFraction.ONE })
        LinearAuthority(SourceLpBudget(), retainLp = true).use { authority ->
            authority.advanceWithPointCuts(context, Cancellation.Never)

            val result = authority.advance(context, Cancellation.Never, cuts = true)

            assertEquals(SourceValue.Rational(BigFraction.ofLong(5)),
                (assertNotNull(result.bound).claim as SourceClaim.Bound).value)
            assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(context.withLinearAuthority())
                .verify(assertNotNull(result.bound)))
        }
    }

    @Test
    fun `an unchecked candidate should prevent retaining its family layout`() {
        val rows = listOf(
            LinearAuthorityCut(0, SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ZERO),
                SourceDerivation.Premise("x upper")),
            LinearAuthorityCut(1, SourceInequality(mapOf(y to BigFraction.ONE), BigFraction.ZERO),
                SourceDerivation.Premise("y upper")),
        )
        val selection = LinearAuthorityPointCuts(SourceLpBudget())

        val selected = selection.select(rows, listOf(x, y), List(2) { BigFraction.ZERO }, setOf(0),
            listOf(BigFraction.ofLong(2), BigFraction.ONE), emptySet(), Cancellation.Never)

        assertEquals(setOf(0), selected)
        assertFalse(selection.retainsCandidateLayout(rows))
    }

    @Test
    fun `candidate cooldown should avoid repeated scoring work`() {
        val rows = listOf(LinearAuthorityCut(0,
            SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ONE), SourceDerivation.Premise("upper")))
        val selection = LinearAuthorityPointCuts(SourceLpBudget())
        selection.select(rows, listOf(x), listOf(BigFraction.ZERO), setOf(0),
            listOf(BigFraction.ZERO), emptySet(), Cancellation.Never)
        val work = selection.observations["numericalCutSelectionWork"]

        selection.select(rows, listOf(x), listOf(BigFraction.ZERO), setOf(0),
            listOf(BigFraction.ZERO), emptySet(), Cancellation.Never)

        assertEquals(work, selection.observations["numericalCutSelectionWork"])
        assertEquals(1L, selection.observations["numericalCutSelectionCooldownQueries"])
    }

    @Test
    fun `four completed unsuccessful scans should retire the candidate generation`() {
        val rows = listOf(LinearAuthorityCut(0,
            SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ONE), SourceDerivation.Premise("upper")))
        val selection = LinearAuthorityPointCuts(SourceLpBudget())
        repeat(25) {
            selection.select(rows, listOf(x), listOf(BigFraction.ZERO), setOf(0),
                listOf(BigFraction.ZERO), emptySet(), Cancellation.Never)
        }

        val selected = selection.select(rows, listOf(x), listOf(BigFraction.ZERO), setOf(0),
            listOf(BigFraction.ofLong(2)), emptySet(), Cancellation.Never)

        assertTrue(selected.isEmpty())
        assertEquals(4L, selection.observations["numericalCutSelectionVisited"])
        assertEquals(1L, selection.observations["numericalCutSelectionRetiredFamilies"])
    }

    @Test
    fun `a replacement candidate generation should receive its own selection lifetime`() {
        val rows = listOf(LinearAuthorityCut(0,
            SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ONE), SourceDerivation.Premise("upper")))
        val selection = LinearAuthorityPointCuts(SourceLpBudget())
        repeat(25) {
            selection.select(rows, listOf(x), listOf(BigFraction.ZERO), setOf(0),
                listOf(BigFraction.ZERO), emptySet(), Cancellation.Never)
        }

        val selected = selection.select(ArrayList(rows), listOf(x), listOf(BigFraction.ZERO), setOf(0),
            listOf(BigFraction.ofLong(2)), emptySet(), Cancellation.Never)

        assertEquals(setOf(0), selected)
    }

    @Test
    fun `candidate retirement should preserve tight installed rows`() {
        val rows = listOf(LinearAuthorityCut(0,
            SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ONE), SourceDerivation.Premise("upper")))
        val selection = LinearAuthorityPointCuts(SourceLpBudget())
        repeat(25) {
            selection.select(rows, listOf(x), listOf(BigFraction.ZERO), setOf(0),
                listOf(BigFraction.ONE), setOf(0), Cancellation.Never)
        }

        val selected = selection.select(rows, listOf(x), listOf(BigFraction.ZERO), setOf(0),
            listOf(BigFraction.ONE), setOf(0), Cancellation.Never)

        assertEquals(setOf(0), selected)
        assertEquals(1L, selection.observations["numericalCutSelectionRetiredFamilies"])
    }

    @Test
    fun `slack installed rows should expire at retirement even in a full pool`() {
        val rows = listOf(LinearAuthorityCut(0,
            SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ONE), SourceDerivation.Premise("upper")))
        val selection = LinearAuthorityPointCuts(SourceLpBudget(), maximumCuts = 1)
        var installed = setOf(0)
        repeat(25) {
            installed = selection.select(rows, listOf(x), listOf(BigFraction.ZERO), setOf(0),
                listOf(BigFraction.ZERO), installed, Cancellation.Never)
        }
        val work = selection.observations["numericalCutSelectionWork"]

        val selected = selection.select(rows, listOf(x), listOf(BigFraction.ZERO), setOf(0),
            listOf(BigFraction.ofLong(2)), installed, Cancellation.Never)

        assertTrue(selected.isEmpty())
        assertEquals(1L, selection.observations["numericalCutSelectionRetiredInstalled"])
        assertEquals(work, selection.observations["numericalCutSelectionWork"])
    }

    @Test
    fun `adopted installed rows should age on consecutive usable points`() {
        val rows = listOf(LinearAuthorityCut(0,
            SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ONE), SourceDerivation.Premise("upper")))
        val selection = LinearAuthorityPointCuts(SourceLpBudget(), maximumCuts = 1)
        var installed = selection.select(rows, listOf(x), listOf(BigFraction.ZERO), setOf(0),
            listOf(BigFraction.ZERO), emptySet(), Cancellation.Never, adoptCurrent = true)
        repeat(3) {
            installed = selection.select(rows, listOf(x), listOf(BigFraction.ZERO), setOf(0),
                listOf(BigFraction.ZERO), installed, Cancellation.Never)
        }
        assertEquals(setOf(0), installed)

        val selected = selection.select(rows, listOf(x), listOf(BigFraction.ZERO), setOf(0),
            listOf(BigFraction.ZERO), installed, Cancellation.Never)

        assertTrue(selected.isEmpty())
    }

    @Test
    fun `installed rows should keep aging after candidate retirement`() {
        val rows = listOf(LinearAuthorityCut(0,
            SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ONE), SourceDerivation.Premise("upper")))
        val selection = LinearAuthorityPointCuts(SourceLpBudget(), maximumCuts = 1)
        var installed = selection.select(rows, listOf(x), listOf(BigFraction.ZERO), setOf(0),
            listOf(BigFraction.ONE), emptySet(), Cancellation.Never, adoptCurrent = true)
        repeat(4) {
            installed = selection.select(rows, listOf(x), listOf(BigFraction.ZERO), setOf(0),
                listOf(BigFraction.ONE), installed, Cancellation.Never)
        }
        assertTrue(selection.candidatesRetired(rows))
        repeat(3) {
            installed = selection.select(rows, listOf(x), listOf(BigFraction.ZERO), setOf(0),
                listOf(BigFraction.ZERO), installed, Cancellation.Never)
        }
        assertEquals(setOf(0), installed)

        val selected = selection.select(rows, listOf(x), listOf(BigFraction.ZERO), setOf(0),
            listOf(BigFraction.ZERO), installed, Cancellation.Never)

        assertTrue(selected.isEmpty())
        assertEquals(1L, selection.observations["numericalCutSelectionRetiredFamilies"])
    }

    @Test
    fun `continued aging should not reinstall retired candidates`() {
        val rows = listOf(
            LinearAuthorityCut(0, SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ONE),
                SourceDerivation.Premise("x-upper")),
            LinearAuthorityCut(1, SourceInequality(mapOf(y to BigFraction.ONE), BigFraction.ONE),
                SourceDerivation.Premise("y-upper")),
        )
        val selection = LinearAuthorityPointCuts(SourceLpBudget())
        var installed = selection.select(rows, listOf(x, y), List(2) { BigFraction.ZERO }, setOf(0, 1),
            List(2) { BigFraction.ONE }, emptySet(), Cancellation.Never, adoptCurrent = true)
        repeat(4) {
            installed = selection.select(rows, listOf(x, y), List(2) { BigFraction.ZERO }, setOf(0, 1),
                listOf(BigFraction.ZERO, BigFraction.ONE), installed, Cancellation.Never)
        }
        assertEquals(setOf(1), installed)

        val selected = selection.select(rows, listOf(x, y), List(2) { BigFraction.ZERO }, setOf(0, 1),
            listOf(BigFraction.ofLong(2), BigFraction.ONE), installed, Cancellation.Never)

        assertEquals(setOf(1), selected)
    }

    @Test
    fun `a fully selected family should age on consecutive usable points`() {
        val rows = listOf(LinearAuthorityCut(0,
            SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ONE), SourceDerivation.Premise("upper")))
        val selection = LinearAuthorityPointCuts(SourceLpBudget())
        var installed = selection.select(rows, listOf(x), listOf(BigFraction.ZERO), setOf(0),
            listOf(BigFraction.ofLong(2)), emptySet(), Cancellation.Never)
        repeat(3) {
            installed = selection.select(rows, listOf(x), listOf(BigFraction.ZERO), setOf(0),
                listOf(BigFraction.ZERO), installed, Cancellation.Never)
        }
        assertEquals(setOf(0), installed)

        val selected = selection.select(rows, listOf(x), listOf(BigFraction.ZERO), setOf(0),
            listOf(BigFraction.ZERO), installed, Cancellation.Never)

        assertTrue(selected.isEmpty())
    }

    @Test
    fun `a tight observation should interrupt installed row aging`() {
        val rows = listOf(LinearAuthorityCut(0,
            SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ONE), SourceDerivation.Premise("upper")))
        val selection = LinearAuthorityPointCuts(SourceLpBudget())
        var installed = setOf(0)
        repeat(3) {
            installed = selection.select(rows, listOf(x), listOf(BigFraction.ZERO), setOf(0),
                listOf(BigFraction.ZERO), installed, Cancellation.Never)
        }

        val selected = selection.select(rows, listOf(x), listOf(BigFraction.ZERO), setOf(0),
            listOf(BigFraction.ONE), installed, Cancellation.Never)

        assertEquals(setOf(0), selected)
        assertEquals(null, selection.observations["numericalCutSelectionRetiredInstalled"])
    }

    @Test
    fun `an incomplete scoring scan should not age installed rows`() {
        val rows = listOf(
            LinearAuthorityCut(0, SourceInequality(mapOf(x to BigFraction.ONE, y to BigFraction.ONE),
                BigFraction.ONE), SourceDerivation.Premise("sum")),
            LinearAuthorityCut(1, SourceInequality(mapOf(x to BigFraction.ONE, y to BigFraction.MINUS_ONE),
                BigFraction.ONE), SourceDerivation.Premise("difference")),
        )
        val selection = LinearAuthorityPointCuts(SourceLpBudget(), maximumSupport = 2)
        var installed = setOf(0, 1)

        repeat(25) {
            installed = selection.select(rows, listOf(x, y), List(2) { BigFraction.ZERO }, setOf(0, 1),
                List(2) { BigFraction.ZERO }, installed, Cancellation.Never)
        }

        assertEquals(setOf(0, 1), installed)
        assertEquals(null, selection.observations["numericalCutSelectionRetiredInstalled"])
        assertEquals(null, selection.observations["numericalCutSelectionRetiredFamilies"])
        assertFalse(selection.retainsCandidateLayout(rows))
    }

    @Test
    fun `installed half aggregation should enter aging with its checked recipe`() {
        for (family in listOf(CutFamily.SOURCE_AGGREGATION, CutFamily.AGGREGATION_MIR)) {
            val rows = listOf(LinearAuthorityCut(0,
                SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ONE),
                SourceDerivation.Premise("cut"), family = family))
            val selection = LinearAuthorityPointCuts(SourceLpBudget())

            val selected = selection.select(rows, listOf(x, mixedReal), List(2) { BigFraction.ZERO },
                setOf(0), List(2) { BigFraction.ZERO }, emptySet(), Cancellation.Never, adoptCurrent = true)

            assertEquals(setOf(0), selected)
            assertTrue(selection.managesCurrentRecipe(rows))
            assertTrue(selection.retainsCandidateLayout(rows))
            assertEquals(1L, selection.observations["numericalCutSelectionAdoptedInstalled"])
        }
    }

    @Test
    fun `adopting installed pure cuts should retain the current numerical query`() {
        val z = SourceCoordinate(SourceCoordinate.Kind.Integer, 2)
        val context = SourceProofContext(scope, setOf(x, y, z), source.premises + mapOf(
            "sum" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ONE, z to BigFraction.ONE),
                BigFraction.ONE)),
            "equal lower" to SourcePremise(SourceInequality(
                mapOf(y to BigFraction.MINUS_ONE, z to BigFraction.ONE), BigFraction.ZERO)),
            "equal upper" to SourcePremise(SourceInequality(
                mapOf(y to BigFraction.ONE, z to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "nonnegative y" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE),
                BigFraction.ZERO)),
            "nonnegative z" to SourcePremise(SourceInequality(mapOf(z to BigFraction.MINUS_ONE),
                BigFraction.ZERO)),
        ), mapOf(x to BigFraction.ONE, y to BigFraction.MINUS_ONE, z to BigFraction.MINUS_ONE))
        val capture = LinearAuthorityCapture(4, 10_000L, 1_000_000L, Cancellation.Never)
        LinearAuthority(SourceLpBudget(), retainLp = true).use { authority ->
            authority.capture = capture

            authority.advanceWithPointCuts(context, Cancellation.Never)

            assertEquals(1L, authority.cutObservations()["numericalCutSelectionAdoptedInstalled"])
            assertEquals(1, capture.records.size)
        }
    }

    @Test
    fun `installed pure cuts should age from the current checked recipe`() {
        val z = SourceCoordinate(SourceCoordinate.Kind.Integer, 2)
        val w = SourceCoordinate(SourceCoordinate.Kind.Integer, 3)
        val triangle = listOf(y, z, w)
        val premises = linkedMapOf("rounded" to SourcePremise(SourceInequality(
            mapOf(x to BigFraction.ofLong(2)), BigFraction.ofLong(3))))
        for ((index, coordinate) in triangle.withIndex()) {
            premises["nonnegative $index"] = SourcePremise(SourceInequality(
                mapOf(coordinate to BigFraction.MINUS_ONE), BigFraction.ZERO))
            premises["edge $index"] = SourcePremise(SourceInequality(
                mapOf(coordinate to BigFraction.ONE, triangle[(index + 1) % 3] to BigFraction.ONE),
                BigFraction.ONE))
        }
        val root = SourceProofContext(mixedMaximum.scope, (triangle + x).toSet(), premises,
            (triangle + x).associateWith { BigFraction.ONE })
        val child = root.atScope(root.scope.copy(condition = SourceCondition(upper = mapOf(x to BigFraction.ZERO))))
        val capture = LinearAuthorityCapture(128, 100_000L, 10_000_000L, Cancellation.Never)
        LinearAuthority(SourceLpBudget(), retainLp = true).use { authority ->
            authority.capture = capture
            authority.advanceWithPointCuts(root, Cancellation.Never)
            repeat(32) { authority.advanceWithPointCuts(child, Cancellation.Never) }

            val result = authority.advanceWithPointCuts(child, Cancellation.Never)

            assertEquals(1L, authority.cutObservations()["numericalCutSelectionRetiredInstalled"])
            assertTrue(capture.records.last().cuts.none { it.installed })
            assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(child.withLinearAuthority())
                .verify(assertNotNull(result.bound)))
        }
    }

    @Test
    fun `retiring an installed source row should suspend it without replacing the numerical owner`() {
        val triangle = (2..4).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
        val premises = linkedMapOf("rounded" to SourcePremise(SourceInequality(
            mapOf(x to BigFraction.ofLong(2), y to BigFraction.ofLong(2)), BigFraction.ofLong(3))),
            "nonnegative x" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "nonnegative y" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE), BigFraction.ZERO)))
        for ((index, coordinate) in triangle.withIndex()) {
            premises["nonnegative $index"] = SourcePremise(SourceInequality(
                mapOf(coordinate to BigFraction.MINUS_ONE), BigFraction.ZERO))
            premises["edge $index"] = SourcePremise(SourceInequality(
                mapOf(coordinate to BigFraction.ONE, triangle[(index + 1) % 3] to BigFraction.ONE),
                BigFraction.ONE))
        }
        val root = SourceProofContext(mixedMaximum.scope, (triangle + x + y).toSet(), premises,
            (triangle + x + y).associateWith { BigFraction.ONE })
        val child = root.atScope(root.scope.copy(condition = SourceCondition(upper = mapOf(
            x to BigFraction.ZERO, y to BigFraction.ZERO))))
        val capture = LinearAuthorityCapture(128, 100_000L, 10_000_000L, Cancellation.Never)
        LinearAuthority(SourceLpBudget(), retainLp = true).use { authority ->
            authority.capture = capture
            authority.advanceWithPointCuts(root, Cancellation.Never)
            repeat(32) { authority.advanceWithPointCuts(child, Cancellation.Never) }

            val result = authority.advanceWithPointCuts(child, Cancellation.Never)

            assertEquals(1L, authority.cutObservations()["numericalCutSelectionRetiredInstalled"])
            assertTrue(capture.records.last().cuts.none { it.installed })
            assertEquals(1L, authority.preparations)
            assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(child.withLinearAuthority())
                .verify(assertNotNull(result.bound)))
        }
    }

    @Test
    fun `an empty aged mask should retain its checked relaxation without reinstalling cuts`() {
        val z = SourceCoordinate(SourceCoordinate.Kind.Integer, 2)
        val w = SourceCoordinate(SourceCoordinate.Kind.Integer, 3)
        val triangle = listOf(y, z, w)
        val premises = mixedMaximum.premises.toMutableMap()
        for ((index, coordinate) in triangle.withIndex()) {
            premises["nonnegative $index"] = SourcePremise(SourceInequality(
                mapOf(coordinate to BigFraction.MINUS_ONE), BigFraction.ZERO))
            premises["edge $index"] = SourcePremise(SourceInequality(
                mapOf(coordinate to BigFraction.ONE, triangle[(index + 1) % 3] to BigFraction.ONE),
                BigFraction.ONE))
        }
        val root = SourceProofContext(mixedMaximum.scope, mixedMaximum.coordinates + triangle, premises,
            (triangle + x).associateWith { BigFraction.ONE })
        val child = root.atScope(root.scope.copy(condition = SourceCondition(upper = mapOf(x to BigFraction.ZERO))))
        val capture = LinearAuthorityCapture(128, 100_000L, 10_000_000L, Cancellation.Never)
        LinearAuthority(SourceLpBudget(), retainLp = true).use { authority ->
            authority.capture = capture
            authority.advanceWithPointCuts(root, Cancellation.Never)
            repeat(32) { authority.advanceWithPointCuts(child, Cancellation.Never) }
            val work = authority.cutObservations()["numericalCutSelectionWork"]

            val result = authority.advanceWithPointCuts(child, Cancellation.Never)

            assertEquals(1L, authority.cutObservations()["numericalCutSelectionRetiredInstalled"])
            assertTrue(capture.records.last().cuts.none { it.installed })
            assertEquals(work, authority.cutObservations()["numericalCutSelectionWork"])
            assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(child.withLinearAuthority())
                .verify(assertNotNull(result.bound)))
        }
    }

    @Test
    fun `point directed sides should strengthen a bound missed by uniform side choices`() {
        val real = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
        val other = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val maximum = SourceProofContext(scope.copy(objective = SourceObjective("difference", ObjectiveSense.MAXIMIZE)),
            setOf(x, real, other), mapOf(
                "mixed" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                    real to BigFraction.MINUS_ONE, other to BigFraction.MINUS_ONE), BigFraction.ofLong(3))),
                "lower" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.ZERO)),
                "upper" to SourcePremise(SourceInequality(mapOf(real to BigFraction.ONE), BigFraction.ofLong(2))),
                "other lower" to SourcePremise(SourceInequality(mapOf(other to BigFraction.MINUS_ONE),
                    BigFraction.ZERO)),
                "other upper" to SourcePremise(SourceInequality(mapOf(other to BigFraction.ONE),
                    BigFraction.ofLong(2))),
            ), mapOf(x to BigFraction.ONE, other to BigFraction.MINUS_ONE))
        val budget = SourceLpBudget()

        LinearAuthority(budget, retainLp = true).use { authority ->
            val current = authority.advance(maximum, Cancellation.Never, cuts = true)
            val candidate = authority.advanceWithPointCuts(maximum, Cancellation.Never)

            assertEquals(SourceValue.Rational(BigFraction.ofLong(5) * BigFraction.ofLong(2).reciprocal()),
                (assertNotNull(current.bound).claim as SourceClaim.Bound).value)
            assertEquals(SourceValue.Rational(BigFraction.ofLong(2)),
                (assertNotNull(candidate.bound).claim as SourceClaim.Bound).value)
            assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(maximum.withLinearAuthority())
                .verify(assertNotNull(candidate.bound)))
            assertEquals(1L, authority.cutObservations()["numericalCutSelectionPointPrepared"])
            assertEquals(budget.measuredPreparationWork,
                (authority.cutObservations()["numericalCutLifecycleRECIPE_CURRENT_PreparationWork"] ?: 0L) +
                    (authority.cutObservations()["numericalCutLifecycleRECIPE_POINT_SELECTION_PreparationWork"] ?: 0L))
        }
    }

    @Test
    fun `equally efficacious candidates should prefer sparse support`() {
        val z = SourceCoordinate(SourceCoordinate.Kind.Integer, 2)
        val rows = listOf(
            LinearAuthorityCut(0, SourceInequality(mapOf(y to BigFraction.ofLong(3), z to BigFraction.ofLong(4)),
                BigFraction.ZERO), SourceDerivation.Premise("dense")),
            LinearAuthorityCut(1, SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ZERO),
                SourceDerivation.Premise("sparse")),
        )

        val selected = LinearAuthorityPointCuts(SourceLpBudget(), maximumCuts = 1).select(rows,
            listOf(x, y, z), List(3) { BigFraction.ZERO }, setOf(0, 1),
            listOf(BigFraction.ONE, BigFraction.ONE, BigFraction.ofLong(2).reciprocal()), emptySet(),
            Cancellation.Never)

        assertEquals(setOf(1), selected)
    }

    @Test
    fun `unchecked candidates should stay outside the selected mask`() {
        val cut = LinearAuthorityCut(0, SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ZERO),
            SourceDerivation.Premise("unchecked"))

        val selected = LinearAuthorityPointCuts(SourceLpBudget()).select(listOf(cut), listOf(x),
            listOf(BigFraction.ZERO), emptySet(), listOf(BigFraction.ONE), emptySet(), Cancellation.Never)

        assertTrue(selected.isEmpty())
    }

    @Test
    fun `scoring should debit only the available cumulative work`() {
        val funding = SourceLpFunding(8L, 1024L).apply { grant(8L) }
        val budget = SourceLpBudget(funding = funding)
        val cut = LinearAuthorityCut(0, SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ZERO),
            SourceDerivation.Premise("bounded"))

        val selected = LinearAuthorityPointCuts(budget).select(listOf(cut), listOf(x),
            listOf(BigFraction.ZERO), setOf(0), listOf(BigFraction.ONE), emptySet(), Cancellation.Never)

        assertTrue(selected.isEmpty())
        assertTrue(funding.spent <= 8L)
        assertEquals(funding.spent, budget.measuredPreparationWork)
    }

    @Test
    fun `wide exact rows should remain outside the long carrier`() {
        val wide = BigFraction.ofLong(Long.MAX_VALUE) + BigFraction.ONE
        val row = SourceInequality(mapOf(x to wide), BigFraction.ZERO)
        val cut = LinearAuthorityCut(0, row, SourceDerivation.Premise("wide"))

        val selected = LinearAuthorityPointCuts(SourceLpBudget()).select(listOf(cut), listOf(x),
            listOf(BigFraction.ZERO), setOf(0), listOf(BigFraction.ONE), emptySet(), Cancellation.Never)

        assertEquals(setOf(0), selected)
        assertEquals(wide, cut.inequality.coefficients.getValue(x))
    }

    @Test
    fun `nonfinite scores should leave the candidate unselected`() {
        val huge = assertNotNull(BigFraction.ofDouble(Double.MAX_VALUE))
        val cut = LinearAuthorityCut(0, SourceInequality(mapOf(x to huge), BigFraction.ZERO),
            SourceDerivation.Premise("huge"))

        val selected = LinearAuthorityPointCuts(SourceLpBudget()).select(listOf(cut), listOf(x),
            listOf(BigFraction.ZERO), setOf(0), listOf(BigFraction.ONE), emptySet(), Cancellation.Never)

        assertTrue(selected.isEmpty())
    }

    @Test
    fun `satisfied candidates should not open another source operation`() {
        val integral = source.atScope(scope.copy(condition = SourceCondition(lower = mapOf(x to BigFraction.ONE))))
        val budget = SourceLpBudget()

        LinearAuthority(budget, retainLp = true).use { it.advanceWithPointCuts(integral, Cancellation.Never) }

        assertEquals(1, budget.operations)
    }

    @Test
    fun `satisfied installed rows should remain in the retained mask`() {
        val cut = LinearAuthorityCut(0, SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ONE),
            SourceDerivation.Premise("installed"))

        val selected = LinearAuthorityPointCuts(SourceLpBudget()).select(listOf(cut), listOf(x),
            listOf(BigFraction.ZERO), setOf(0), listOf(BigFraction.ofLong(2).reciprocal()), setOf(0),
            Cancellation.Never)

        assertEquals(setOf(0), selected)
    }

    @Test
    fun `an unavailable point should not trigger a separation query`() {
        val budget = SourceLpBudget(maxOperations = 0)
        val result = LinearAuthority(budget, retainLp = true).use {
            it.advanceWithPointCuts(source, Cancellation.Never)
        }

        assertEquals(0, budget.operations)
        assertNull(result.bound)
    }

    @Test
    fun `selected guarded cuts should retract and reactivate across siblings`() {
        val guarded = SourceProofContext(scope, setOf(x, y, mixedReal), mapOf(
            "lower" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "upper" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ofLong(3))),
            "real lower" to SourcePremise(SourceInequality(mapOf(mixedReal to BigFraction.MINUS_ONE),
                BigFraction.ZERO)),
            "real upper" to SourcePremise(SourceInequality(mapOf(mixedReal to BigFraction.ONE), BigFraction.ofLong(2))),
            "guarded" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                mixedReal to BigFraction.MINUS_ONE),
                BigFraction.ofLong(3)), emptyMap(), mapOf(y to BigFraction.ONE)),
        ), mapOf(x to BigFraction.MINUS_ONE))
        val active = guarded.atScope(scope.copy(condition = SourceCondition(
            lower = mapOf(y to BigFraction.ONE), upper = mapOf(y to BigFraction.ONE))))
        val inactive = guarded.atScope(scope.copy(condition = SourceCondition(
            lower = mapOf(y to BigFraction.ZERO), upper = mapOf(y to BigFraction.ZERO))))
        val values = LinearAuthority(SourceLpBudget(), retainLp = true).use { authority ->
            listOf(active, inactive, active).map {
                val result = authority.advanceWithPointCuts(it, Cancellation.Never)
                assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(it.withLinearAuthority())
                    .verify(assertNotNull(result.evidence)))
                (assertNotNull(result.bound).claim as SourceClaim.Bound).value
            }
        }

        assertEquals(listOf(-2L, -3L, -2L).map { SourceValue.Rational(BigFraction.ofLong(it)) }, values)
    }

    @Test
    fun `a paused separation round should retain its completed base bound`() {
        val funding = SourceLpFunding(1_000_000L, 4_000_000L).apply { grant(1_000_000L) }
        var blocked = false
        var operations = 0L
        val budget = SourceLpBudget(funding = funding, onWork = {
            operations += it.operations
            if (!blocked && operations == 2L) {
                blocked = true
                funding.grant(0L)
            }
        })
        LinearAuthority(budget, retainLp = true).use { authority ->
            val paused = authority.advanceWithPointCuts(mixedMaximum, Cancellation.Never)
            assertTrue(paused.paused)
            assertNull(authority.cutObservations()["numericalCutSelectionImprovedRounds"])
            assertEquals(SourceValue.Rational(BigFraction.ofLong(5) * BigFraction.ofLong(2).reciprocal()),
                (assertNotNull(paused.bound).claim as SourceClaim.Bound).value)

            funding.grant(1_000_000L)
            val completed = authority.advanceWithPointCuts(mixedMaximum, Cancellation.Never)

            assertFalse(completed.paused)
            assertEquals(SourceValue.Rational(BigFraction.ofLong(2)),
                (assertNotNull(completed.bound).claim as SourceClaim.Bound).value)
            assertEquals(2, budget.operations)
            assertEquals(1L, authority.cutObservations()["numericalCutSelectionImprovedRounds"])
        }
    }
}
