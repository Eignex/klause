package com.eignex.klause.solver.pipeline

import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.lp.optimization.LinearAuthorityCapturedCut
import com.eignex.klause.lp.optimization.LinearAuthorityCapturedQuery
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourcePremise
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinearSupportBranchingTest {
    private val x = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
    private val y = SourceCoordinate(SourceCoordinate.Kind.Integer, 1)
    private val point = linkedMapOf(x to BigFraction.ofLong(4).reciprocal(), y to BigFraction.ofLong(2).reciprocal())
    private val row = SourceInequality(mapOf(x to BigFraction.ofLong(2), y to BigFraction.ONE), BigFraction.ONE)
    private val source = SourceProofContext(SourceScope(SourceRevision("branch", "exact-affine")),
        setOf(x, y), mapOf("row" to SourcePremise(row)), emptyMap())
    private val cut = LinearAuthorityCapturedCut(0, 0, row, SourceDerivation.Premise("row"),
        installed = true, ownerActive = true, checked = true, violation = BigFraction.ZERO)
    private val query = LinearAuthorityCapturedQuery(0, source, source, listOf(x, y), emptyList(), emptySet(),
        point, listOf(cut), emptyMap(), null, null, "bound-unavailable")

    @Test
    fun `tight cut support should rank the more fractional integer coordinate`() {
        val funding = SourceLpFunding(1_000L, 10_000L).apply { grant(1_000L) }
        val branching = LinearSupportBranching(funding, SourceNumericalFeedback(funding, Cancellation.Never))

        val selected = branching.select(SourceNumericalFeedbackResult.Available(query, null),
            point.toList(), Cancellation.Never)

        assertEquals(y to point.getValue(y), selected)
        assertEquals(1L, branching.selections)
        assertTrue(funding.spent > 0L)
    }

    @Test
    fun `equal support scores should retain source order rather than coordinate identifiers`() {
        val funding = SourceLpFunding(1_000L, 10_000L).apply { grant(1_000L) }
        val branching = LinearSupportBranching(funding, SourceNumericalFeedback(funding, Cancellation.Never))
        val tiedPoint = linkedMapOf(y to BigFraction.ofLong(2).reciprocal(), x to BigFraction.ofLong(2).reciprocal())
        val tiedRow = SourceInequality(mapOf(x to BigFraction.ONE, y to BigFraction.ONE), BigFraction.ONE)
        val context = SourceProofContext(source.scope, source.coordinates,
            mapOf("row" to SourcePremise(tiedRow)), emptyMap())
        val tied = query.copy(context = context, numeric = context, point = tiedPoint,
            cuts = listOf(cut.copy(inequality = tiedRow)))

        val selected = branching.select(SourceNumericalFeedbackResult.Available(tied, null),
            tiedPoint.toList(), Cancellation.Never)

        assertEquals(y to tiedPoint.getValue(y), selected)
    }

    @Test
    fun `a requested projection no longer delivered should fall back to source order`() {
        val funding = SourceLpFunding(1_000L, 10_000L).apply { grant(1_000L) }
        val branching = LinearSupportBranching(funding, SourceNumericalFeedback(funding, Cancellation.Never))
        val released = query.copy(cuts = listOf(cut.copy(violation = null)),
            projection = LinearSupportBranching.REQUEST)

        val selected = branching.select(SourceNumericalFeedbackResult.Available(released, null),
            point.toList(), Cancellation.Never)

        assertEquals(x to point.getValue(x), selected)
        assertEquals(1L, branching.fallbacks)
    }

    @Test
    fun `uninstalled inactive unchecked and slack cuts should preserve source order`() {
        val funding = SourceLpFunding(1_000L, 10_000L).apply { grant(1_000L) }
        val branching = LinearSupportBranching(funding, SourceNumericalFeedback(funding, Cancellation.Never))

        for (ignored in listOf(cut.copy(installed = false), cut.copy(ownerActive = false),
            cut.copy(checked = false), cut.copy(violation = BigFraction.MINUS_ONE))) {
            val selected = branching.select(SourceNumericalFeedbackResult.Available(query.copy(cuts = listOf(ignored)),
                null), point.toList(), Cancellation.Never)

            assertEquals(x to point.getValue(x), selected)
        }
    }
}
