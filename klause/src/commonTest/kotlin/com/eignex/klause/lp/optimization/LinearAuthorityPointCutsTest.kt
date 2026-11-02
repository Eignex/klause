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
    fun `an addition should let the next point admit a complementary cut`() {
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

        assertEquals(setOf(0, 1), selected)
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
            assertEquals(SourceValue.Rational(BigFraction.ofLong(5) * BigFraction.ofLong(2).reciprocal()),
                (assertNotNull(paused.bound).claim as SourceClaim.Bound).value)

            funding.grant(1_000_000L)
            val completed = authority.advanceWithPointCuts(mixedMaximum, Cancellation.Never)

            assertFalse(completed.paused)
            assertEquals(SourceValue.Rational(BigFraction.ofLong(2)),
                (assertNotNull(completed.bound).claim as SourceClaim.Bound).value)
            assertEquals(2, budget.operations)
        }
    }
}
