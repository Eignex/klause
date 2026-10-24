package com.eignex.klause.lp.optimization

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.simplex.exact.BigFraction
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
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinearAuthorityCutsTest {
    private val x = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
    private val y = SourceCoordinate(SourceCoordinate.Kind.Integer, 1)
    private val scope = SourceScope(SourceRevision("rounding", "exact-affine"),
        objective = SourceObjective("sum", ObjectiveSense.MINIMIZE))

    @Test
    fun `single row rounding should strengthen a checked sparse objective bound`() {
        val source = SourceProofContext(scope, setOf(x, y), mapOf(
            "sum" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(-2),
                y to BigFraction.ofLong(-2)), BigFraction.ofLong(-3))),
            "x" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "y" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE), BigFraction.ZERO)),
        ), mapOf(x to BigFraction.ONE, y to BigFraction.ONE))

        LinearAuthority(SourceLpBudget(), retainLp = true).use { authority ->
            val baseline = authority.advance(source, Cancellation.Never)
            val result = authority.advance(source, Cancellation.Never, cuts = true)

            assertEquals(SourceValue.Rational(BigFraction.ofLong(3) * BigFraction.ofLong(2).reciprocal()),
                assertIs<SourceClaim.Bound>(assertNotNull(baseline.bound).claim).value)
            assertEquals(SourceValue.Rational(BigFraction.ofLong(2)),
                assertIs<SourceClaim.Bound>(assertNotNull(result.bound).claim).value)
            assertEquals(SourceEvidenceCheck.Checked,
                SourceEvidenceVerifier(source.withLinearAuthority()).verify(assertNotNull(result.bound)))
            assertNull(result.split)
        }
    }

    @Test
    fun `cut rows should retract and reactivate with their integer guarded source owner`() {
        val source = SourceProofContext(scope, setOf(x, y), mapOf(
            "guarded" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2)),
                BigFraction.ofLong(3)), emptyMap(), mapOf(y to BigFraction.ONE)),
        ), mapOf(x to BigFraction.MINUS_ONE))
        val template = assertNotNull(LinearAuthorityTemplate.prepare(source, Cancellation.Never, cuts = true))
        val enabled = source.atScope(scope.copy(condition = SourceCondition(
            lower = mapOf(y to BigFraction.ONE), upper = mapOf(y to BigFraction.ONE))))

        val first = assertNotNull(LinearAuthorityQuery.prepare(enabled, template, Cancellation.Never))
        val sibling = assertNotNull(LinearAuthorityQuery.prepare(source, template, Cancellation.Never))
        val restored = assertNotNull(LinearAuthorityQuery.prepare(enabled, template, Cancellation.Never))

        assertEquals(1, first.activeCuts)
        assertEquals(0, sibling.activeCuts)
        assertTrue(1 in sibling.inactive)
        assertEquals(first.cited[1], restored.cited[1])
        assertEquals(first.cited[1], sibling.cited[1])
        assertEquals(first.rows[1].rhs, restored.rows[1].rhs)
        val cut = first.cited[1]
        assertIs<SourceEvidenceCheck.Rejected>(SourceEvidenceVerifier(source).verify(
            SourceEvidence(scope, SourceClaim.Consequence(cut.first), cut.second)))
    }

    @Test
    fun `separation should decline rows with an unfixed continuous activity`() {
        val real = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
        val source = SourceProofContext(scope, setOf(x, real), mapOf(
            "mixed" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                real to BigFraction.ONE), BigFraction.ofLong(3))),
        ), mapOf(x to BigFraction.ONE))

        val cuts = assertNotNull(LinearAuthorityCuts.prepare(source, Cancellation.Never))

        assertTrue(cuts.rows.isEmpty())
    }

    @Test
    fun `cancelled separation should publish no retained rows`() {
        val source = SourceProofContext(scope, setOf(x), emptyMap(), emptyMap())

        val cuts = LinearAuthorityCuts.prepare(source, Cancellation { true })

        assertNull(cuts)
    }
}
