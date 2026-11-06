package com.eignex.klause.lp.optimization

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.lp.engine.CutFamily
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
import kotlin.test.assertIs
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

    @Test
    fun `an initial point recipe should yield its checked base before another LP operation`() {
        val budget = SourceLpBudget()
        LinearAuthority(budget, retainLp = true, aggregateCuts = true).use { authority ->
            val result = authority.advanceWithPointGeneratedCuts(mixedMaximum, Cancellation.Never, null)

            assertEquals(1, budget.operations)
            assertEquals(SourceValue.Rational(BigFraction.ofLong(5) * BigFraction.ofLong(2).reciprocal()),
                assertIs<SourceClaim.Bound>(assertNotNull(result.bound).claim).value)
            assertTrue(result.fractional.isNotEmpty())
            assertTrue(result.cutBoundFamilies.isEmpty())
            assertEquals(1L, authority.cutObservations()["numericalCutSelectionInitialBaseYielded"])
            assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(mixedMaximum.withLinearAuthority())
                .verify(assertNotNull(result.bound)))
        }
    }

    @Test
    fun `a yielded initial point recipe should resume its selected query`() {
        val budget = SourceLpBudget()
        LinearAuthority(budget, retainLp = true, aggregateCuts = true).use { authority ->
            authority.advanceWithPointGeneratedCuts(mixedMaximum, Cancellation.Never, null)

            val result = authority.advanceWithPointGeneratedCuts(mixedMaximum, Cancellation.Never, null)

            assertEquals(2, budget.operations)
            assertEquals(SourceValue.Rational(BigFraction.ofLong(2)),
                assertIs<SourceClaim.Bound>(assertNotNull(result.bound).claim).value)
            assertTrue(result.cutBoundFamilies.isNotEmpty())
            assertEquals(1L, authority.cutObservations()["numericalCutSelectionInitialBaseYielded"])
            assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(mixedMaximum.withLinearAuthority())
                .verify(assertNotNull(result.bound)))
        }
    }

    @Test
    fun `a changed scope should not resume the yielded root result`() {
        val half = BigFraction.ofLong(2).reciprocal()
        val conditions = listOf(
            SourceCondition(upper = mapOf(mixedReal to half)),
            SourceCondition().integerBound(SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ONE)))
        for (condition in conditions) {
            LinearAuthority(SourceLpBudget(), retainLp = true, aggregateCuts = true).use { authority ->
                val initial = authority.advanceWithPointGeneratedCuts(mixedMaximum, Cancellation.Never, null)
                val child = mixedMaximum.atScope(mixedMaximum.scope.copy(condition = condition))

                val result = authority.advanceWithPointGeneratedCuts(child, Cancellation.Never, null)

                val bound = assertNotNull(result.bound)
                assertEquals(child.scope, bound.scope)
                assertFalse(assertNotNull(initial.bound).claim == bound.claim)
                assertEquals(SourceEvidenceCheck.Checked,
                    SourceEvidenceVerifier(child.withLinearAuthority()).verify(bound))
            }
        }
    }

    @Test
    fun `a paused yielded query should report its base work only once`() {
        val funding = SourceLpFunding(1_000_000L, 4_000_000L).apply { grant(1_000_000L) }
        val budget = SourceLpBudget(funding = funding)
        LinearAuthority(budget, retainLp = true, aggregateCuts = true).use { authority ->
            val initial = authority.advanceWithPointGeneratedCuts(mixedMaximum, Cancellation.Never, null)
            assertEquals(1L, initial.primalStages["PrimalFractional"])
            funding.grant(0L)

            val paused = authority.advanceWithPointGeneratedCuts(mixedMaximum, Cancellation.Never, null)

            assertTrue(paused.paused)
            assertEquals(assertNotNull(initial.bound).claim, assertNotNull(paused.bound).claim)
            assertTrue(paused.primalStages.isEmpty())
            assertEquals(1, budget.operations)
            funding.grant(1_000_000L)
            val resumed = authority.advanceWithPointGeneratedCuts(mixedMaximum, Cancellation.Never, null)
            assertFalse(resumed.paused)
            assertNull(resumed.primalStages["PrimalFractional"])
            assertEquals(2, budget.operations)
            assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(mixedMaximum.withLinearAuthority())
                .verify(assertNotNull(resumed.bound)))
        }
    }

    @Test
    fun `cancelling a yielded query should retain its already reported checked base`() {
        val budget = SourceLpBudget()
        LinearAuthority(budget, retainLp = true, aggregateCuts = true).use { authority ->
            val initial = authority.advanceWithPointGeneratedCuts(mixedMaximum, Cancellation.Never, null)

            val result = authority.advanceWithPointGeneratedCuts(mixedMaximum, Cancellation { true }, null)

            assertEquals(assertNotNull(initial.bound).claim, assertNotNull(result.bound).claim)
            assertEquals(1, budget.operations)
            assertTrue(result.primalStages.isEmpty())
            assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(mixedMaximum.withLinearAuthority())
                .verify(assertNotNull(result.bound)))
        }
    }

    @Test
    fun `empty discovery should cool down while preserving checked base bounds`() {
        val triangle = (1..3).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
        val premises = nonseparating.premises.toMutableMap()
        premises["coupled"] = SourcePremise(SourceInequality(
            mapOf(x to BigFraction.ONE, mixedReal to BigFraction.ONE), BigFraction.ofLong(3)))
        for ((index, coordinate) in triangle.withIndex()) {
            premises["nonnegative $index"] = SourcePremise(SourceInequality(
                mapOf(coordinate to BigFraction.MINUS_ONE), BigFraction.ZERO))
            premises["edge $index"] = SourcePremise(SourceInequality(
                mapOf(coordinate to BigFraction.ONE, triangle[(index + 1) % 3] to BigFraction.ONE), BigFraction.ONE))
        }
        val half = BigFraction.ofLong(2).reciprocal()
        val quarter = BigFraction.ofLong(4).reciprocal()
        val root = SourceProofContext(nonseparating.scope, nonseparating.coordinates + triangle, premises,
            (triangle + x).associateWith { BigFraction.ONE } +
                (triangle.first() to half) + (mixedReal to quarter.negated()))
        LinearAuthority(SourceLpBudget(), retainLp = true, aggregateCuts = true).use { authority ->
            authority.advanceWithPointGeneratedCuts(root, Cancellation.Never, BigFraction.ZERO)
            val child = root.atScope(root.scope.copy(condition = SourceCondition(
                upper = mapOf(mixedReal to BigFraction.ofLong(5)))))
            authority.advanceWithPointGeneratedCuts(child, Cancellation.Never, BigFraction.ZERO)
            assertEquals(2L, authority.cutObservations()["numericalCutSelectionPointPrepared"])

            for (upper in 6L..12L) {
                val context = root.atScope(root.scope.copy(condition = SourceCondition(
                    upper = mapOf(mixedReal to BigFraction.ofLong(upper)))))

                val result = authority.advanceWithPointGeneratedCuts(context, Cancellation.Never, BigFraction.ZERO)

                val current = authority.advance(context, Cancellation.Never, cuts = false)
                assertEquals(assertNotNull(current.bound).claim, assertNotNull(result.bound).claim)
                assertEquals(SourceEvidenceCheck.Checked,
                    SourceEvidenceVerifier(context.withLinearAuthority()).verify(assertNotNull(result.bound)))
            }

            assertEquals(2L, authority.cutObservations()["numericalCutSelectionPointPrepared"])
            assertEquals(7L, authority.cutObservations()["numericalCutSelectionEmptyDiscoveryCooldownQueries"])
            val resumed = root.atScope(root.scope.copy(condition = SourceCondition(
                upper = mapOf(mixedReal to BigFraction.ofLong(13)))))
            val result = authority.advanceWithPointGeneratedCuts(resumed, Cancellation.Never, BigFraction.ZERO)
            assertEquals(3L, authority.cutObservations()["numericalCutSelectionPointPrepared"])
            assertEquals(SourceEvidenceCheck.Checked,
                SourceEvidenceVerifier(resumed.withLinearAuthority()).verify(assertNotNull(result.bound)))
        }
    }

    @Test
    fun `a new source template should reset empty discovery backoff`() {
        val triangle = (1..3).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
        val premises = nonseparating.premises.toMutableMap()
        premises["coupled"] = SourcePremise(SourceInequality(
            mapOf(x to BigFraction.ONE, mixedReal to BigFraction.ONE), BigFraction.ofLong(3)))
        for ((index, coordinate) in triangle.withIndex()) {
            premises["nonnegative $index"] = SourcePremise(SourceInequality(
                mapOf(coordinate to BigFraction.MINUS_ONE), BigFraction.ZERO))
            premises["edge $index"] = SourcePremise(SourceInequality(
                mapOf(coordinate to BigFraction.ONE, triangle[(index + 1) % 3] to BigFraction.ONE), BigFraction.ONE))
        }
        val half = BigFraction.ofLong(2).reciprocal()
        val quarter = BigFraction.ofLong(4).reciprocal()
        val root = SourceProofContext(nonseparating.scope, nonseparating.coordinates + triangle, premises,
            (triangle + x).associateWith { BigFraction.ONE } +
                (triangle.first() to half) + (mixedReal to quarter.negated()))
        LinearAuthority(SourceLpBudget(), retainLp = true, aggregateCuts = true).use { authority ->
            authority.advanceWithPointGeneratedCuts(root, Cancellation.Never, BigFraction.ZERO)
            val child = root.atScope(root.scope.copy(condition = SourceCondition(
                upper = mapOf(mixedReal to BigFraction.ofLong(5)))))
            authority.advanceWithPointGeneratedCuts(child, Cancellation.Never, BigFraction.ZERO)
            val replacement = SourceProofContext(
                root.scope.copy(revision = SourceRevision("empty-discovery-next", "exact-affine")),
                root.coordinates, root.premises, root.objective)

            val result = authority.advanceWithPointGeneratedCuts(replacement, Cancellation.Never, BigFraction.ZERO)

            assertEquals(3L, authority.cutObservations()["numericalCutSelectionPointPrepared"])
            assertNull(authority.cutObservations()["numericalCutSelectionEmptyDiscoveryCooldownQueries"])
            assertEquals(SourceEvidenceCheck.Checked,
                SourceEvidenceVerifier(replacement.withLinearAuthority()).verify(assertNotNull(result.bound)))
        }
    }

    @Test
    fun `an interrupted discovery admission should preserve its backoff`() {
        for (pause in listOf(false, true)) {
            val funding = SourceLpFunding(10_000L, 100_000L).apply { grant(10_000L) }
            val selection = LinearAuthorityPointCuts(SourceLpBudget(funding = funding))
            repeat(2) {
                assertTrue(selection.admitDiscovery(Cancellation.Never))
                selection.select(emptyList(), emptyList(), emptyList(), emptySet(), emptyList(),
                    emptySet(), Cancellation.Never)
                selection.observeDiscovery(false, Cancellation.Never)
            }
            var cancelled = !pause
            if (pause) funding.grant(0L)

            assertFalse(selection.admitDiscovery(Cancellation { cancelled }))

            cancelled = false
            funding.grant(10_000L)
            repeat(7) { assertFalse(selection.admitDiscovery(Cancellation.Never)) }
            assertTrue(selection.admitDiscovery(Cancellation.Never))
        }
    }

    @Test
    fun `an unavailable scoring point should not advance empty discovery backoff`() {
        val rows = listOf(LinearAuthorityCut(0,
            SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ZERO), SourceDerivation.Premise("upper")))
        val selection = LinearAuthorityPointCuts(SourceLpBudget())
        repeat(2) {
            assertTrue(selection.admitDiscovery(Cancellation.Never))
            selection.select(rows, listOf(x), listOf(BigFraction.ZERO), setOf(0), emptyList(),
                emptySet(), Cancellation.Never)

            selection.observeDiscovery(false, Cancellation.Never)
        }

        assertTrue(selection.admitDiscovery(Cancellation.Never))
    }

    @Test
    fun `numeric scope changes should retry a nonseparating point recipe`() {
        val triangle = (1..3).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
        val premises = nonseparating.premises.toMutableMap()
        premises["coupled"] = SourcePremise(SourceInequality(
            mapOf(x to BigFraction.ONE, mixedReal to BigFraction.ONE), BigFraction.ofLong(3)))
        for ((index, coordinate) in triangle.withIndex()) {
            premises["nonnegative $index"] = SourcePremise(SourceInequality(
                mapOf(coordinate to BigFraction.MINUS_ONE), BigFraction.ZERO))
            premises["edge $index"] = SourcePremise(SourceInequality(
                mapOf(coordinate to BigFraction.ONE, triangle[(index + 1) % 3] to BigFraction.ONE), BigFraction.ONE))
        }
        val half = BigFraction.ofLong(2).reciprocal()
        val quarter = BigFraction.ofLong(4).reciprocal()
        val root = SourceProofContext(nonseparating.scope, nonseparating.coordinates + triangle, premises,
            (triangle + x).associateWith { BigFraction.ONE } +
                (triangle.first() to half) + (mixedReal to quarter.negated()))
        val conditions = listOf(
            SourceCondition(upper = mapOf(mixedReal to half)) to BigFraction.ofLong(23) * quarter * half,
            SourceCondition().integerBound(SourceInequality(
                mapOf(x to BigFraction.ONE, triangle.first() to BigFraction.MINUS_ONE), BigFraction.ONE)) to
                BigFraction.ofLong(11) * quarter)
        for ((condition, baseline) in conditions) {
            val child = root.atScope(root.scope.copy(condition = condition))
            for (prime in listOf(false, true)) {
                LinearAuthority(SourceLpBudget(), retainLp = true, aggregateCuts = true).use { authority ->
                    val initial = authority.advanceWithPointGeneratedCuts(root, Cancellation.Never, BigFraction.ZERO)
                    assertEquals(SourceValue.Rational(BigFraction.ofLong(3)),
                        assertIs<SourceClaim.Bound>(assertNotNull(initial.bound).claim).value)
                    assertEquals(1L, authority.cutObservations()["numericalCutSelectionCurrentFallback"])
                    val preparations = authority.preparations
                    val scoring = authority.cutObservations()["numericalCutSelectionWork"]
                    authority.advanceWithPointGeneratedCuts(root, Cancellation.Never, BigFraction.ZERO)
                    assertEquals(preparations, authority.preparations)
                    assertEquals(scoring, authority.cutObservations()["numericalCutSelectionWork"])
                    if (prime) {
                        val current = authority.advance(child, Cancellation.Never, cuts = false)
                        assertEquals(SourceValue.Rational(baseline),
                            assertIs<SourceClaim.Bound>(assertNotNull(current.bound).claim).value)
                    }

                    val result = authority.advanceWithPointGeneratedCuts(child, Cancellation.Never, BigFraction.ZERO)

                    assertEquals(SourceValue.Rational(BigFraction.ofLong(21) * quarter * half),
                        assertIs<SourceClaim.Bound>(assertNotNull(result.bound).claim).value)
                    assertTrue(result.cutBoundFamilies.isNotEmpty())
                    assertEquals(SourceEvidenceCheck.Checked,
                        SourceEvidenceVerifier(child.withLinearAuthority()).verify(assertNotNull(result.bound)))
                }
            }
        }
    }

    @Test
    fun `retrying a changed fallback scope should preserve the operation limited bound`() {
        val triangle = (1..3).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
        val premises = nonseparating.premises.toMutableMap()
        premises["coupled"] = SourcePremise(SourceInequality(
            mapOf(x to BigFraction.ONE, mixedReal to BigFraction.ONE), BigFraction.ofLong(3)))
        for ((index, coordinate) in triangle.withIndex()) {
            premises["nonnegative $index"] = SourcePremise(SourceInequality(
                mapOf(coordinate to BigFraction.MINUS_ONE), BigFraction.ZERO))
            premises["edge $index"] = SourcePremise(SourceInequality(
                mapOf(coordinate to BigFraction.ONE, triangle[(index + 1) % 3] to BigFraction.ONE), BigFraction.ONE))
        }
        val half = BigFraction.ofLong(2).reciprocal()
        val quarter = BigFraction.ofLong(4).reciprocal()
        val root = SourceProofContext(nonseparating.scope, nonseparating.coordinates + triangle, premises,
            (triangle + x).associateWith { BigFraction.ONE } +
                (triangle.first() to half) + (mixedReal to quarter.negated()))
        val conditions = listOf(
            SourceCondition(upper = mapOf(mixedReal to half)) to BigFraction.ofLong(23) * quarter * half,
            SourceCondition().integerBound(SourceInequality(
                mapOf(x to BigFraction.ONE, triangle.first() to BigFraction.MINUS_ONE), BigFraction.ONE)) to
                BigFraction.ofLong(11) * quarter)
        for ((condition, baseline) in conditions) {
            val child = root.atScope(root.scope.copy(condition = condition))
            val budget = SourceLpBudget(maxOperations = 2)
            LinearAuthority(budget, retainLp = true, aggregateCuts = true).use { authority ->
                authority.advanceWithPointGeneratedCuts(root, Cancellation.Never, BigFraction.ZERO)

                val result = authority.advanceWithPointGeneratedCuts(child, Cancellation.Never, BigFraction.ZERO)

                assertEquals(2, budget.operations)
                assertEquals(SourceValue.Rational(baseline),
                    assertIs<SourceClaim.Bound>(assertNotNull(result.bound).claim).value)
                assertEquals(SourceEvidenceCheck.Checked,
                    SourceEvidenceVerifier(child.withLinearAuthority()).verify(assertNotNull(result.bound)))
            }
        }
    }

    @Test
    fun `a fully retired point recipe should release its inactive query rows`() {
        val triangle = (1..3).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
        val premises = mixedMaximum.premises.toMutableMap()
        for ((index, coordinate) in triangle.withIndex()) {
            premises["nonnegative $index"] = SourcePremise(SourceInequality(
                mapOf(coordinate to BigFraction.MINUS_ONE), BigFraction.ZERO))
            premises["edge $index"] = SourcePremise(SourceInequality(
                mapOf(coordinate to BigFraction.ONE, triangle[(index + 1) % 3] to BigFraction.ONE), BigFraction.ONE))
        }
        val context = SourceProofContext(mixedMaximum.scope, setOf(x, mixedReal) + triangle, premises,
            (triangle + x).associateWith { BigFraction.ONE })
        val capture = LinearAuthorityCapture(40, 30_000L, 1_000_000L, Cancellation.Never)
        LinearAuthority(SourceLpBudget(), retainLp = true, aggregateCuts = true).use { authority ->
            authority.capture = capture
            var result = authority.advanceWithPointGeneratedCuts(context, Cancellation.Never, BigFraction.ZERO)
            val bound = assertNotNull(result.bound)
            assertEquals(2, authority.preparations)
            assertTrue(capture.records.last().cited.size > context.premises.size)
            repeat(32) {
                if (authority.cutObservations()["numericalCutSelectionUnproductiveRecipeRetired"] == null) {
                    result = authority.advanceWithPointGeneratedCuts(context, Cancellation.Never, BigFraction.ZERO)
                }
            }

            assertEquals(1L, authority.cutObservations()["numericalCutSelectionUnproductiveRecipeRetired"])
            assertEquals(3, authority.preparations)
            assertEquals(context.premises.size, capture.records.last().cited.size)
            assertTrue(capture.records.last().inactive.isEmpty())
            assertTrue(capture.records.last().cuts.none { it.installed })
            assertEquals(bound.claim, assertNotNull(result.bound).claim)
            assertEquals(SourceEvidenceCheck.Checked,
                SourceEvidenceVerifier(context.withLinearAuthority()).verify(assertNotNull(result.bound)))
            val scoring = authority.cutObservations()["numericalCutSelectionWork"]

            repeat(2) { authority.advanceWithPointGeneratedCuts(context, Cancellation.Never, BigFraction.ZERO) }

            assertEquals(3, authority.preparations)
            assertEquals(scoring, authority.cutObservations()["numericalCutSelectionWork"])
            assertEquals(context.premises.size, capture.records.last().cited.size)
        }
    }

    @Test
    fun `retired point compaction should preserve its checked bound across funding pauses`() {
        val triangle = (1..3).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
        val premises = mixedMaximum.premises.toMutableMap()
        for ((index, coordinate) in triangle.withIndex()) {
            premises["nonnegative $index"] = SourcePremise(SourceInequality(
                mapOf(coordinate to BigFraction.MINUS_ONE), BigFraction.ZERO))
            premises["edge $index"] = SourcePremise(SourceInequality(
                mapOf(coordinate to BigFraction.ONE, triangle[(index + 1) % 3] to BigFraction.ONE), BigFraction.ONE))
        }
        val context = SourceProofContext(mixedMaximum.scope, setOf(x, mixedReal) + triangle, premises,
            (triangle + x).associateWith { BigFraction.ONE })
        val funding = SourceLpFunding(4_000_000L, 8_000_000L).apply { grant(4_000_000L) }
        var active: LinearAuthority? = null
        var blocked = false
        val budget = SourceLpBudget(funding = funding, onWork = {
            if (!blocked && active?.cutObservations()?.get("numericalCutSelectionUnproductiveRecipeRetired") == 1L) {
                blocked = true
                funding.grant(0L)
            }
        })
        LinearAuthority(budget, retainLp = true, aggregateCuts = true).use { authority ->
            active = authority
            var result = authority.advanceWithPointGeneratedCuts(context, Cancellation.Never, BigFraction.ZERO)
            val bound = assertNotNull(result.bound)
            repeat(32) {
                if (!blocked) result = authority.advanceWithPointGeneratedCuts(context, Cancellation.Never,
                    BigFraction.ZERO)
            }
            assertTrue(blocked)
            assertTrue(result.paused)
            val operations = budget.operations

            repeat(2) {
                val paused = authority.advanceWithPointGeneratedCuts(context, Cancellation.Never, BigFraction.ZERO)

                assertTrue(paused.paused)
                assertEquals(bound.claim, assertNotNull(paused.bound).claim)
                assertEquals(SourceEvidenceCheck.Checked,
                    SourceEvidenceVerifier(context.withLinearAuthority()).verify(assertNotNull(paused.bound)))
            }
            assertEquals(operations, budget.operations)

            funding.grant(4_000_000L)
            val completed = authority.advanceWithPointGeneratedCuts(context, Cancellation.Never, BigFraction.ZERO)

            assertFalse(completed.paused)
            assertEquals(operations + 1, budget.operations)
            assertEquals(bound.claim, assertNotNull(completed.bound).claim)
            assertEquals(SourceEvidenceCheck.Checked,
                SourceEvidenceVerifier(context.withLinearAuthority()).verify(assertNotNull(completed.bound)))
        }
    }

    @Test
    fun `a checked no cut optimum should bypass point generated discovery`() {
        val current = source.atScope(scope.copy(condition = SourceCondition(lower = mapOf(x to BigFraction.ONE))))
        val budget = SourceLpBudget()
        LinearAuthority(budget, retainLp = true, aggregateCuts = true).use { authority ->
            val result = authority.advanceWithPointGeneratedCuts(current, Cancellation.Never, BigFraction.ofLong(2))

            assertEquals(1, budget.operations)
            assertNull(authority.cutObservations()["numericalCutAggregatePairAttempts"])
            assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(current.withLinearAuthority())
                .verify(assertNotNull(result.evidence)))
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

            val result = authority.advanceWithPointCuts(nonseparating, Cancellation.Never)

            assertEquals(listOf(true, true), capture.records.map { it.cuts.single().installed })
            assertEquals(preparations, authority.preparations)
            assertEquals(scoring, authority.cutObservations()["numericalCutSelectionWork"])
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
    fun `initial trimming should retain tight checked rows and remove slack rows`() {
        val rows = listOf(
            LinearAuthorityCut(0, SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ONE),
                SourceDerivation.Premise("x"), family = CutFamily.SOURCE_AGGREGATION),
            LinearAuthorityCut(1, SourceInequality(mapOf(y to BigFraction.ONE), BigFraction.ONE),
                SourceDerivation.Premise("y"), family = CutFamily.SOURCE_AGGREGATION))
        val selection = LinearAuthorityPointCuts(SourceLpBudget())
        val installed = selection.prepareGenerated(rows, setOf(0, 1), Cancellation.Never)

        val selected = selection.select(rows, listOf(x, y), List(2) { BigFraction.ZERO }, setOf(0, 1),
            listOf(BigFraction.ONE, BigFraction.ZERO), installed, Cancellation.Never, retireInitialSlack = true)

        assertEquals(setOf(0), selected)
        assertTrue(selection.selectionComplete)
        assertTrue(selection.retainsCandidateLayout(rows))
        assertEquals(1L, selection.observations["numericalCutSelectionInitialSlackRetired"])
    }

    @Test
    fun `incomplete initial scoring should preserve every installed row`() {
        val rows = listOf(
            LinearAuthorityCut(0, SourceInequality(mapOf(x to BigFraction.ONE, y to BigFraction.ONE),
                BigFraction.ONE), SourceDerivation.Premise("sum"), family = CutFamily.SOURCE_AGGREGATION),
            LinearAuthorityCut(1, SourceInequality(mapOf(x to BigFraction.ONE, y to BigFraction.MINUS_ONE),
                BigFraction.ONE), SourceDerivation.Premise("difference"), family = CutFamily.SOURCE_AGGREGATION))
        for ((support, checked, interrupted) in listOf(Triple(2, setOf(0, 1), false),
            Triple(4, setOf(0), false), Triple(4, setOf(0, 1), true))) {
            val selection = LinearAuthorityPointCuts(SourceLpBudget(), maximumSupport = support)
            val installed = selection.prepareGenerated(rows, setOf(0, 1), Cancellation.Never)

            val selected = selection.select(rows, listOf(x, y), List(2) { BigFraction.ZERO }, checked,
                List(2) { BigFraction.ZERO }, installed, Cancellation { interrupted }, retireInitialSlack = true)

            assertEquals(setOf(0, 1), selected)
            assertFalse(selection.selectionComplete)
            assertNull(selection.observations["numericalCutSelectionInitialSlackRetired"])
        }
    }

    @Test
    fun `generated initialization should use one checked LP operation`() {
        val budget = SourceLpBudget(maxOperations = 1)
        val capture = LinearAuthorityCapture(2, 20_000L, 1_000_000L, Cancellation.Never)
        val result = LinearAuthority(budget, retainLp = true, aggregateCuts = true).use { authority ->
            authority.capture = capture
            authority.advanceWithGeneratedCutAging(nonseparating, Cancellation.Never)
        }

        assertEquals(1, budget.operations)
        assertEquals(SourceValue.Rational(BigFraction.ofLong(2)),
            (assertNotNull(result.bound).claim as SourceClaim.Bound).value)
        assertTrue(CutFamily.SOURCE_AGGREGATION in result.cutBoundFamilies)
        assertEquals(1, capture.records.size)
        assertTrue(capture.records.single().cuts.all { it.checked && it.installed })
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(nonseparating.withLinearAuthority())
            .verify(assertNotNull(result.bound)))
    }

    @Test
    fun `a nonterminal mixed query should install checked generated rows`() {
        val capture = LinearAuthorityCapture(4, 20_000L, 1_000_000L, Cancellation.Never)
        val result = LinearAuthority(SourceLpBudget(), retainLp = true, aggregateCuts = true).use { authority ->
            authority.capture = capture
            authority.advanceWithPointCuts(nonseparating, Cancellation.Never)
        }

        assertEquals(SourceValue.Rational(BigFraction.ofLong(2)),
            (assertNotNull(result.bound).claim as SourceClaim.Bound).value)
        assertTrue(CutFamily.SOURCE_AGGREGATION in result.cutBoundFamilies)
        assertTrue(capture.records.first().cuts.isNotEmpty())
        assertTrue(capture.records.drop(1).any { record ->
            record.cuts.size > capture.records.first().cuts.size && record.cuts.all { it.checked && it.installed }
        })
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(nonseparating.withLinearAuthority())
            .verify(assertNotNull(result.bound)))
    }

    @Test
    fun `a checked mixed generated recipe should enter installed row aging`() {
        val rows = listOf(
            LinearAuthorityCut(0, SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ONE),
                SourceDerivation.Premise("mixed"), mixed = true),
            LinearAuthorityCut(1, SourceInequality(mapOf(mixedReal to BigFraction.ONE), BigFraction.ONE),
                SourceDerivation.Premise("aggregate"), family = CutFamily.SOURCE_AGGREGATION))
        val selection = LinearAuthorityPointCuts(SourceLpBudget())
        var installed = selection.prepareGenerated(rows, setOf(0, 1), Cancellation.Never)
        assertEquals(setOf(0, 1), installed)
        repeat(3) {
            installed = selection.select(rows, listOf(x, mixedReal), List(2) { BigFraction.ZERO },
                setOf(0, 1), List(2) { BigFraction.ZERO }, installed, Cancellation.Never)
        }
        assertEquals(setOf(0, 1), installed)

        val selected = selection.select(rows, listOf(x, mixedReal), List(2) { BigFraction.ZERO },
            setOf(0, 1), List(2) { BigFraction.ZERO }, installed, Cancellation.Never)

        assertTrue(selected.isEmpty())
        assertEquals(2L, selection.observations["numericalCutSelectionRetiredInstalled"])
    }

    @Test
    fun `an unproductive partial mask should retire without installing its replacement`() {
        val rows = listOf(
            LinearAuthorityCut(0, SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ONE),
                SourceDerivation.Premise("x"), family = CutFamily.SOURCE_AGGREGATION),
            LinearAuthorityCut(1, SourceInequality(mapOf(y to BigFraction.ONE), BigFraction.ONE),
                SourceDerivation.Premise("y"), family = CutFamily.SOURCE_AGGREGATION))
        val selection = LinearAuthorityPointCuts(SourceLpBudget())
        var installed = selection.select(rows, listOf(x, y), List(2) { BigFraction.ONE }, setOf(0, 1),
            listOf(BigFraction.ofLong(2), BigFraction.ZERO), emptySet(), Cancellation.Never,
            usefulnessOpportunity = true, trackSelectedUsefulness = true)
        assertEquals(setOf(0), installed)
        assertTrue(selection.retainsCandidateLayout(rows))
        assertNull(selection.observations["numericalCutSelectionUsefulnessOpportunities"])
        repeat(3) {
            selection.observeRound(rows, improved = true)
            installed = selection.select(rows, listOf(x, y), List(2) { BigFraction.ONE }, setOf(0, 1),
                listOf(BigFraction.ONE, BigFraction.ZERO), installed, Cancellation.Never,
                usefulnessOpportunity = true, trackSelectedUsefulness = true)
        }
        assertEquals(setOf(0), installed)
        selection.observeRound(rows, improved = true)

        val selected = selection.select(rows, listOf(x, y), List(2) { BigFraction.ONE }, setOf(0, 1),
            listOf(BigFraction.ONE, BigFraction.ofLong(2)), installed, Cancellation.Never,
            usefulnessOpportunity = true, trackSelectedUsefulness = true)

        assertTrue(selected.isEmpty())
        assertFalse(selection.retainsCandidateLayout(rows))
        assertEquals(1L, selection.observations["numericalCutSelectionUnproductiveRecipeRetired"])
    }

    @Test
    fun `actual use should preserve a nonclosing partial mask`() {
        val rows = listOf(
            LinearAuthorityCut(0, SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ONE),
                SourceDerivation.Premise("x"), family = CutFamily.SOURCE_AGGREGATION),
            LinearAuthorityCut(1, SourceInequality(mapOf(y to BigFraction.ONE), BigFraction.ONE),
                SourceDerivation.Premise("y"), family = CutFamily.SOURCE_AGGREGATION))
        val selection = LinearAuthorityPointCuts(SourceLpBudget())
        var installed = selection.select(rows, listOf(x, y), List(2) { BigFraction.ONE }, setOf(0, 1),
            listOf(BigFraction.ofLong(2), BigFraction.ZERO), emptySet(), Cancellation.Never,
            trackSelectedUsefulness = true)
        repeat(3) {
            selection.observeRound(rows, improved = true)
            installed = selection.select(rows, listOf(x, y), List(2) { BigFraction.ONE }, setOf(0, 1),
                listOf(BigFraction.ONE, BigFraction.ZERO), installed, Cancellation.Never,
                usefulnessOpportunity = true, trackSelectedUsefulness = true)
        }

        assertTrue(selection.observeUse(rows, CutFamily.SOURCE_AGGREGATION))
        selection.observeRound(rows, improved = true)
        val selected = selection.select(rows, listOf(x, y), List(2) { BigFraction.ONE }, setOf(0, 1),
            listOf(BigFraction.ONE, BigFraction.ZERO), installed, Cancellation.Never,
            usefulnessOpportunity = true, trackSelectedUsefulness = true)

        assertEquals(setOf(0), selected)
        assertTrue(selection.retainsCandidateLayout(rows))
        assertNull(selection.observations["numericalCutSelectionUnproductiveRecipeRetired"])
    }

    @Test
    fun `four unproductive points should retire a tight generated recipe`() {
        val rows = listOf(LinearAuthorityCut(0,
            SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ONE),
            SourceDerivation.Premise("aggregate"), family = CutFamily.SOURCE_AGGREGATION))
        val selection = LinearAuthorityPointCuts(SourceLpBudget())
        var installed = selection.prepareGenerated(rows, setOf(0), Cancellation.Never, trackUsefulness = true)
        repeat(3) {
            installed = selection.select(rows, listOf(x), listOf(BigFraction.ONE), setOf(0),
                listOf(BigFraction.ONE), installed, Cancellation.Never, usefulnessOpportunity = true)
        }
        assertEquals(setOf(0), installed)

        val selected = selection.select(rows, listOf(x), listOf(BigFraction.ONE), setOf(0),
            listOf(BigFraction.ONE), installed, Cancellation.Never, usefulnessOpportunity = true)

        assertTrue(selected.isEmpty())
        assertTrue(selection.retainsCandidateLayout(rows))
        assertEquals(1L, selection.observations["numericalCutSelectionUnproductiveRecipeRetired"])
    }

    @Test
    fun `missing usefulness opportunities should preserve a tight generated recipe`() {
        val rows = listOf(LinearAuthorityCut(0,
            SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ONE),
            SourceDerivation.Premise("aggregate"), family = CutFamily.SOURCE_AGGREGATION))
        val selection = LinearAuthorityPointCuts(SourceLpBudget())
        var installed = selection.prepareGenerated(rows, setOf(0), Cancellation.Never, trackUsefulness = true)

        repeat(8) {
            installed = selection.select(rows, listOf(x), listOf(BigFraction.ONE), setOf(0),
                listOf(BigFraction.ONE), installed, Cancellation.Never)
        }

        assertEquals(setOf(0), installed)
        assertNull(selection.observations["numericalCutSelectionUnproductiveRecipeRetired"])
    }

    @Test
    fun `only a matching recipe and family use should reset usefulness aging`() {
        for ((used, sameGeneration) in listOf(CutFamily.SOURCE_AGGREGATION to true,
            CutFamily.GOMORY to true, CutFamily.SOURCE_AGGREGATION to false)) {
            val rows = listOf(LinearAuthorityCut(0,
                SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ONE),
                SourceDerivation.Premise("aggregate"), family = CutFamily.SOURCE_AGGREGATION))
            val selection = LinearAuthorityPointCuts(SourceLpBudget())
            var installed = selection.prepareGenerated(rows, setOf(0), Cancellation.Never, trackUsefulness = true)
            repeat(3) {
                installed = selection.select(rows, listOf(x), listOf(BigFraction.ONE), setOf(0),
                    listOf(BigFraction.ONE), installed, Cancellation.Never, usefulnessOpportunity = true)
            }

            selection.observeUse(if (sameGeneration) rows else rows.toMutableList(), used)
            val selected = selection.select(rows, listOf(x), listOf(BigFraction.ONE), setOf(0),
                listOf(BigFraction.ONE), installed, Cancellation.Never, usefulnessOpportunity = true)

            assertEquals(if (used == CutFamily.SOURCE_AGGREGATION && sameGeneration) setOf(0) else emptySet(), selected)
        }
    }

    @Test
    fun `an interrupted usefulness observation should preserve the retirement streak`() {
        val rows = listOf(LinearAuthorityCut(0,
            SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ONE),
            SourceDerivation.Premise("aggregate"), family = CutFamily.SOURCE_AGGREGATION))
        val selection = LinearAuthorityPointCuts(SourceLpBudget())
        var installed = selection.prepareGenerated(rows, setOf(0), Cancellation.Never, trackUsefulness = true)
        repeat(3) {
            installed = selection.select(rows, listOf(x), listOf(BigFraction.ONE), setOf(0),
                listOf(BigFraction.ONE), installed, Cancellation.Never, usefulnessOpportunity = true)
        }

        val selected = selection.select(rows, listOf(x), listOf(BigFraction.ONE), setOf(0),
            listOf(BigFraction.ONE), installed, Cancellation { true }, usefulnessOpportunity = true)

        assertEquals(setOf(0), selected)
        assertEquals(3L, selection.observations["numericalCutSelectionUsefulnessOpportunities"])
    }

    @Test
    fun `incumbent aware initialization should preserve the one operation checked bound`() {
        val budget = SourceLpBudget(maxOperations = 1)
        val result = LinearAuthority(budget, retainLp = true, aggregateCuts = true).use { authority ->
            authority.advanceWithGeneratedCutUse(nonseparating, Cancellation.Never, BigFraction.ZERO)
        }

        assertEquals(1, budget.operations)
        assertEquals(SourceValue.Rational(BigFraction.ofLong(2)),
            (assertNotNull(result.bound).claim as SourceClaim.Bound).value)
        assertTrue(CutFamily.SOURCE_AGGREGATION in result.cutBoundFamilies)
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(nonseparating.withLinearAuthority())
            .verify(assertNotNull(result.bound)))
    }

    @Test
    fun `generated recipe selection should require complete checked coverage`() {
        val rows = List(2) { index -> LinearAuthorityCut(index,
            SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ONE),
            SourceDerivation.Premise("aggregate $index"), family = CutFamily.SOURCE_AGGREGATION) }
        val selection = LinearAuthorityPointCuts(SourceLpBudget())

        val selected = selection.prepareGenerated(rows, setOf(0), Cancellation.Never)

        assertTrue(selected.isEmpty())
        assertFalse(selection.retainsCandidateLayout(rows))
    }

    @Test
    fun `generated recipe selection should retain the selected row limit`() {
        val rows = List(2) { index -> LinearAuthorityCut(index,
            SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ONE),
            SourceDerivation.Premise("aggregate $index"), family = CutFamily.SOURCE_AGGREGATION) }
        val selection = LinearAuthorityPointCuts(SourceLpBudget(), maximumCuts = 1)

        val selected = selection.prepareGenerated(rows, setOf(0, 1), Cancellation.Never)

        assertTrue(selected.isEmpty())
        assertFalse(selection.retainsCandidateLayout(rows))
    }

    @Test
    fun `an interrupted generated recipe selection should publish no mask`() {
        val rows = listOf(LinearAuthorityCut(0,
            SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ONE),
            SourceDerivation.Premise("aggregate"), family = CutFamily.SOURCE_AGGREGATION))
        val selection = LinearAuthorityPointCuts(SourceLpBudget())

        val selected = selection.prepareGenerated(rows, setOf(0), Cancellation { true })

        assertTrue(selected.isEmpty())
        assertFalse(selection.retainsCandidateLayout(rows))
    }

    @Test
    fun `an unfunded generated recipe selection should publish no mask`() {
        val rows = listOf(LinearAuthorityCut(0,
            SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ONE),
            SourceDerivation.Premise("aggregate"), family = CutFamily.SOURCE_AGGREGATION))
        for ((work, allocation) in listOf(1L to 1_000_000L, 1_000_000L to 1L)) {
            val funding = SourceLpFunding(work, allocation).apply { grant(work) }
            val selection = LinearAuthorityPointCuts(SourceLpBudget(funding = funding))

            val selected = selection.prepareGenerated(rows, setOf(0), Cancellation.Never)

            assertTrue(selected.isEmpty())
            assertFalse(selection.retainsCandidateLayout(rows))
            assertTrue(funding.spent <= work)
            assertTrue(funding.allocated <= allocation)
        }
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
