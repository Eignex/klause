package com.eignex.klause.lp.optimization

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SourceLpFunding
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

    @Test
    fun `point policy preparation should include scoring in its containing recipe`() {
        val budget = SourceLpBudget()
        LinearAuthority(budget, retainLp = true).use { authority ->
            authority.advanceWithPointCuts(source, Cancellation.Never)

            val observations = authority.cutObservations()
            assertTrue((observations["numericalCutSelectionWork"] ?: 0L) > 0L)
            assertEquals(budget.measuredPreparationWork,
                observations["numericalCutLifecycleRECIPE_POINT_SELECTION_PreparationWork"])
        }
    }

    @Test
    fun `a checked violated cut should strengthen the retained bound`() {
        val budget = SourceLpBudget()
        val capture = LinearAuthorityCapture(2, 10_000L, 1_000_000L, Cancellation.Never)
        val result = LinearAuthority(budget, retainLp = true).use {
            it.capture = capture
            it.advanceWithPointCuts(source, Cancellation.Never)
        }

        assertEquals(SourceValue.Rational(BigFraction.ONE),
            (assertNotNull(result.bound).claim as SourceClaim.Bound).value)
        assertEquals(2, budget.operations)
        assertEquals(listOf(false, true), capture.records.map { it.cuts.single().installed })
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
        val real = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
        val maximum = SourceProofContext(scope.copy(objective = SourceObjective("x", ObjectiveSense.MAXIMIZE)),
            setOf(x, real), mapOf(
                "mixed" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                    real to BigFraction.MINUS_ONE), BigFraction.ofLong(3))),
                "lower" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.ZERO)),
                "upper" to SourcePremise(SourceInequality(mapOf(real to BigFraction.ONE), BigFraction.ofLong(2))),
            ), mapOf(x to BigFraction.ONE))

        LinearAuthority(SourceLpBudget(), retainLp = true).use { authority ->
            val current = authority.advance(maximum, Cancellation.Never, cuts = true)
            val candidate = authority.advanceWithPointCuts(maximum, Cancellation.Never)
            val restored = authority.advance(maximum, Cancellation.Never, cuts = true)

            assertEquals(SourceValue.Rational(BigFraction.ofLong(5) * BigFraction.ofLong(2).reciprocal()),
                (assertNotNull(current.bound).claim as SourceClaim.Bound).value)
            assertEquals(SourceValue.Rational(BigFraction.ofLong(2)),
                (assertNotNull(candidate.bound).claim as SourceClaim.Bound).value)
            assertEquals((assertNotNull(current.bound).claim as SourceClaim.Bound).value,
                (assertNotNull(restored.bound).claim as SourceClaim.Bound).value)
            assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(maximum.withLinearAuthority())
                .verify(assertNotNull(candidate.bound)))
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
    fun `candidate retirement should preserve installed rows`() {
        val rows = listOf(LinearAuthorityCut(0,
            SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ONE), SourceDerivation.Premise("upper")))
        val selection = LinearAuthorityPointCuts(SourceLpBudget())
        repeat(25) {
            selection.select(rows, listOf(x), listOf(BigFraction.ZERO), setOf(0),
                listOf(BigFraction.ZERO), setOf(0), Cancellation.Never)
        }

        val selected = selection.select(rows, listOf(x), listOf(BigFraction.ZERO), setOf(0),
            listOf(BigFraction.ZERO), setOf(0), Cancellation.Never)

        assertEquals(setOf(0), selected)
        assertEquals(1L, selection.observations["numericalCutSelectionRetiredFamilies"])
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
        val guarded = SourceProofContext(scope, setOf(x, y), mapOf(
            "lower" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "upper" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ofLong(3))),
            "guarded" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2)),
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

        assertEquals(listOf(-1L, -3L, -1L).map { SourceValue.Rational(BigFraction.ofLong(it)) }, values)
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
            val paused = authority.advanceWithPointCuts(source, Cancellation.Never)
            assertTrue(paused.paused)
            assertEquals(SourceValue.Rational(BigFraction.ofLong(2).reciprocal()),
                (assertNotNull(paused.bound).claim as SourceClaim.Bound).value)

            funding.grant(1_000_000L)
            val completed = authority.advanceWithPointCuts(source, Cancellation.Never)

            assertFalse(completed.paused)
            assertEquals(SourceValue.Rational(BigFraction.ONE),
                (assertNotNull(completed.bound).claim as SourceClaim.Bound).value)
            assertEquals(2, budget.operations)
        }
    }
}
