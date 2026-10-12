package com.eignex.klause.lp.optimization

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCondition
import com.eignex.klause.solver.evidence.SourceCoordinate
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
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LinearAuthorityTest {
    private val real = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
    private val integer = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
    private val scope = SourceScope(SourceRevision("affine-fixture", "exact-affine"),
        objective = SourceObjective("x", ObjectiveSense.MINIMIZE))

    @Test
    fun `a free real objective has a checked improving ray`() {
        val source = SourceProofContext(scope, setOf(real), emptyMap(), mapOf(real to BigFraction.ONE))

        val result = LinearAuthority(SourceLpBudget()).check(source, Cancellation.Never)

        assertIs<SourceClaim.Ray>(assertNotNull(result.evidence).claim)
        assertEquals(SourceEvidenceCheck.Checked,
            SourceEvidenceVerifier(source.withLinearAuthority()).verify(result.evidence))
    }

    @Test
    fun `a strict positive real objective has a checked unattained infimum`() {
        val source = SourceProofContext(scope, setOf(real), mapOf("positive" to SourcePremise(
            SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.ZERO, strict = true))),
            mapOf(real to BigFraction.ONE))

        val result = LinearAuthority(SourceLpBudget()).check(source, Cancellation.Never)

        val claim = assertIs<SourceClaim.Limit>(assertNotNull(result.evidence).claim)
        assertEquals(SourceValue.Rational(BigFraction.ZERO), claim.value)
        assertEquals(SourceEvidenceCheck.Checked,
            SourceEvidenceVerifier(source.withLinearAuthority()).verify(result.evidence))
    }

    @Test
    fun `a strict upper side has a checked unattained supremum`() {
        val source = SourceProofContext(scope.copy(objective = SourceObjective("x", ObjectiveSense.MAXIMIZE)),
            setOf(real), mapOf("upper" to SourcePremise(SourceInequality(mapOf(real to BigFraction.ONE),
                BigFraction.ONE, strict = true))), mapOf(real to BigFraction.ONE))

        val result = LinearAuthority(SourceLpBudget()).check(source, Cancellation.Never)

        val claim = assertIs<SourceClaim.Limit>(assertNotNull(result.evidence).claim)
        assertEquals(SourceValue.Rational(BigFraction.ONE), claim.value)
        assertEquals(SourceEvidenceCheck.Checked,
            SourceEvidenceVerifier(source.withLinearAuthority()).verify(result.evidence))
    }

    @Test
    fun `an inclusive bound supplies an attained optimum`() {
        val source = SourceProofContext(scope, setOf(real), mapOf("lower" to SourcePremise(
            SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.MINUS_ONE))),
            mapOf(real to BigFraction.ONE))

        val result = LinearAuthority(SourceLpBudget()).check(source, Cancellation.Never)

        assertIs<SourceClaim.Optimal>(assertNotNull(result.evidence).claim)
        assertEquals(SourceEvidenceCheck.Checked,
            SourceEvidenceVerifier(source.withLinearAuthority()).verify(result.evidence))
    }

    @Test
    fun `integer strict positivity asks traversal to split instead of claiming a real infimum`() {
        val source = SourceProofContext(scope, setOf(integer), mapOf("positive" to SourcePremise(
            SourceInequality(mapOf(integer to BigFraction.MINUS_ONE), BigFraction.ZERO, strict = true))),
            mapOf(integer to BigFraction.ONE))

        val result = LinearAuthority(SourceLpBudget()).check(source, Cancellation.Never)

        assertNull(result.evidence)
        assertNotNull(result.split)
        assertNotNull(result.bound)
    }

    @Test
    fun `contradictory source bounds supply a checked refutation`() {
        val source = SourceProofContext(scope.copy(condition = SourceCondition(lower = mapOf(real to BigFraction.ONE),
            upper = mapOf(real to BigFraction.ZERO))), setOf(real), emptyMap(), mapOf(real to BigFraction.ONE))

        val result = LinearAuthority(SourceLpBudget()).check(source, Cancellation.Never)

        assertEquals(SourceClaim.Refutation, assertNotNull(result.evidence).claim)
        assertEquals(SourceEvidenceCheck.Checked,
            SourceEvidenceVerifier(source.withLinearAuthority()).verify(result.evidence))
    }

    @Test
    fun `an exhausted numerical allowance withholds global claims`() {
        val source = SourceProofContext(scope, setOf(real), emptyMap(), mapOf(real to BigFraction.ONE))

        val result = LinearAuthority(SourceLpBudget(maxOperations = 0)).check(source, Cancellation.Never)

        assertNull(result.evidence)
        assertNull(result.bound)
    }

    @Test
    fun `a checked source bound survives an incomplete strict feasibility check`() {
        val source = SourceProofContext(scope, setOf(real), mapOf("positive" to SourcePremise(
            SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.ZERO, strict = true))),
            mapOf(real to BigFraction.ONE))

        val result = LinearAuthority(SourceLpBudget(maxOperations = 2)).check(source, Cancellation.Never)

        assertNull(result.evidence)
        assertNotNull(result.bound)
        assertEquals(SourceEvidenceCheck.Checked,
            SourceEvidenceVerifier(source.withLinearAuthority()).verify(result.bound))
    }

    @Test
    fun `wide rational coordinates preserve source attainment beyond floating precision`() {
        val value = BigFraction.of(bigIntOf(27021597764222980L), BIG_ONE) * BigFraction.ofLong(3).reciprocal()
        val source = SourceProofContext(scope, setOf(real), mapOf("lower" to SourcePremise(
            SourceInequality(mapOf(real to BigFraction.MINUS_ONE), value.negated()))), mapOf(real to BigFraction.ONE))

        val result = LinearAuthority(SourceLpBudget()).check(source, Cancellation.Never)

        val optimum = assertIs<SourceClaim.Optimal>(assertNotNull(result.evidence).claim)
        assertEquals(SourceValue.Rational(value), optimum.value)
        assertEquals(value, assertNotNull(optimum.sample.exactReals).single())
    }

    @Test
    fun `Boolean objective substitution retains the complete assumed source objective`() {
        val bool = SourceCoordinate(SourceCoordinate.Kind.Boolean, 0)
        val branch = scope.copy(condition = SourceCondition(bools = mapOf(0 to true)))
        val source = SourceProofContext(branch, setOf(bool, real), mapOf("lower" to SourcePremise(
            SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.MINUS_ONE), mapOf(0 to true))),
            mapOf(bool to BigFraction.ofLong(2), real to BigFraction.ONE))

        val result = LinearAuthority(SourceLpBudget()).check(source, Cancellation.Never)

        val optimum = assertIs<SourceClaim.Optimal>(assertNotNull(result.evidence).claim)
        assertEquals(SourceValue.Rational(BigFraction.ofLong(3)), optimum.value)
        assertEquals(SourceEvidenceCheck.Checked,
            SourceEvidenceVerifier(source.withLinearAuthority()).verify(result.evidence))
    }

    @Test
    fun `a source objective identity change invalidates retained linear evidence`() {
        val source = SourceProofContext(scope, setOf(real), mapOf("lower" to SourcePremise(
            SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.MINUS_ONE))),
            mapOf(real to BigFraction.ONE))
        val result = LinearAuthority(SourceLpBudget()).check(source, Cancellation.Never)
        val replacement = scope.copy(objective = SourceObjective("replacement", ObjectiveSense.MINIMIZE))
        val changed = SourceProofContext(replacement,
            source.coordinates, source.premises, source.objective).withLinearAuthority()

        val checked = SourceEvidenceVerifier(changed).verify(assertNotNull(result.evidence))

        assertIs<SourceEvidenceCheck.Rejected>(checked)
    }

    @Test
    fun `undeclared invocation coordinates withhold linear authority`() {
        val absent = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val condition = SourceCondition(lower = mapOf(absent to BigFraction.ONE))
        val source = SourceProofContext(scope.copy(condition = condition),
            setOf(real), emptyMap(), mapOf(real to BigFraction.ONE))

        val result = LinearAuthority(SourceLpBudget()).check(source, Cancellation.Never)

        assertNull(result.evidence)
        assertNull(result.bound)
    }
}
