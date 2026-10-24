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
    private val scope = SourceScope(
        SourceRevision("affine-fixture", "exact-affine"),
        objective = SourceObjective("x", ObjectiveSense.MINIMIZE),
    )

    @Test
    fun `retained branch bounds should match cold queries after sibling retraction`() {
        val other = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val source = SourceProofContext(scope, setOf(real, other), mapOf(
            "sum" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE,
                other to BigFraction.MINUS_ONE), BigFraction.ofLong(-3))),
            "x" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "y" to SourcePremise(SourceInequality(mapOf(other to BigFraction.MINUS_ONE), BigFraction.ZERO)),
        ), mapOf(real to BigFraction.ONE, other to BigFraction.ofLong(2)))
        val conditions = listOf(SourceCondition(),
            SourceCondition(upper = mapOf(real to BigFraction.ONE)),
            SourceCondition(lower = mapOf(real to BigFraction.ofLong(2))), SourceCondition())

        LinearAuthority(SourceLpBudget(), retainLp = true).use { retained ->
            for (condition in conditions) {
                val branch = source.atScope(scope.copy(condition = condition))
                val cold = LinearAuthority(SourceLpBudget()).use { it.check(branch, Cancellation.Never) }
                val result = retained.check(branch, Cancellation.Never)

                assertEquals(assertIs<SourceClaim.Optimal>(assertNotNull(cold.evidence).claim).value,
                    assertIs<SourceClaim.Optimal>(assertNotNull(result.evidence).claim).value)
                assertEquals(SourceEvidenceCheck.Checked,
                    SourceEvidenceVerifier(branch.withLinearAuthority()).verify(result.evidence))
            }
            assertEquals(1L, retained.preparations)
            assertEquals(3L, retained.reuses)
        }
    }

    @Test
    fun `retained objective replacement should not reuse a preceding bound`() {
        val source = SourceProofContext(scope, setOf(real), mapOf(
            "lower" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "upper" to SourcePremise(SourceInequality(mapOf(real to BigFraction.ONE), BigFraction.ofLong(3))),
        ), mapOf(real to BigFraction.ONE))
        val changed = SourceProofContext(scope.copy(objective = SourceObjective("negative-x", ObjectiveSense.MINIMIZE)),
            source.coordinates, source.premises, mapOf(real to BigFraction.MINUS_ONE))

        LinearAuthority(SourceLpBudget(), retainLp = true).use { retained ->
            retained.check(source, Cancellation.Never)
            val result = retained.check(changed, Cancellation.Never)

            assertEquals(SourceValue.Rational(BigFraction.ofLong(-3)),
                assertIs<SourceClaim.Optimal>(assertNotNull(result.evidence).claim).value)
            assertEquals(SourceEvidenceCheck.Checked,
                SourceEvidenceVerifier(changed.withLinearAuthority()).verify(result.evidence))
        }
    }

    @Test
    fun `fractional proposals should retain every eligible coordinate and floor negative values`() {
        val other = SourceCoordinate(SourceCoordinate.Kind.Integer, 1)
        val half = BigFraction.of(bigIntOf(1), bigIntOf(2))
        val source = SourceProofContext(scope, setOf(integer, other), mapOf(
            "first" to SourcePremise(SourceInequality(mapOf(integer to BigFraction.MINUS_ONE), half)),
            "second" to SourcePremise(SourceInequality(mapOf(other to BigFraction.MINUS_ONE), half.negated())),
        ), mapOf(integer to BigFraction.ONE, other to BigFraction.ONE))

        val result = LinearAuthority(SourceLpBudget()).check(source, Cancellation.Never)

        assertEquals(listOf(integer to half.negated(), other to half), result.fractional)
        assertEquals(integer to BigFraction.MINUS_ONE, result.split)
        assertNull(result.evidence)
    }

    @Test
    fun `free real objective should have a checked improving ray`() {
        val source = SourceProofContext(scope, setOf(real), emptyMap(), mapOf(real to BigFraction.ONE))

        val result = LinearAuthority(SourceLpBudget()).check(source, Cancellation.Never)

        assertIs<SourceClaim.Ray>(assertNotNull(result.evidence).claim)
        assertEquals(
            SourceEvidenceCheck.Checked,
            SourceEvidenceVerifier(source.withLinearAuthority()).verify(result.evidence),
        )
    }

    @Test
    fun `strict positive real objective should have a checked unattained infimum`() {
        val source = SourceProofContext(
            scope,
            setOf(real),
            mapOf(
                "positive" to SourcePremise(
                    SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.ZERO, strict = true),
                ),
            ),
            mapOf(real to BigFraction.ONE),
        )

        val result = LinearAuthority(SourceLpBudget()).check(source, Cancellation.Never)

        val claim = assertIs<SourceClaim.Limit>(assertNotNull(result.evidence).claim)
        assertEquals(SourceValue.Rational(BigFraction.ZERO), claim.value)
        assertEquals(
            SourceEvidenceCheck.Checked,
            SourceEvidenceVerifier(source.withLinearAuthority()).verify(result.evidence),
        )
    }

    @Test
    fun `an attained source optimum should fit one numerical operation`() {
        val source = SourceProofContext(
            scope,
            setOf(real),
            mapOf(
                "lower" to SourcePremise(
                    SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.MINUS_ONE),
                ),
            ),
            mapOf(real to BigFraction.ONE),
        )

        val result = LinearAuthority(SourceLpBudget(maxOperations = 1)).check(source, Cancellation.Never)

        val claim = assertIs<SourceClaim.Optimal>(assertNotNull(result.evidence).claim)
        assertEquals(SourceValue.Rational(BigFraction.ONE), claim.value)
        assertEquals(SourceEvidenceCheck.Checked,
            SourceEvidenceVerifier(source.withLinearAuthority()).verify(result.evidence))
    }

    @Test
    fun `redundant coordinate bounds should admit a checked optimum within one operation`() {
        val coordinates = List(32) { SourceCoordinate(SourceCoordinate.Kind.Real, it) }
        val premises = coordinates.flatMap { coordinate ->
            List(2) { copy -> listOf(
                "lower-${coordinate.id}-$copy" to SourcePremise(
                    SourceInequality(mapOf(coordinate to BigFraction.MINUS_ONE), BigFraction.ZERO)),
                "upper-${coordinate.id}-$copy" to SourcePremise(
                    SourceInequality(mapOf(coordinate to BigFraction.ONE), BigFraction.ONE)),
            ) }.flatten()
        }.toMap()
        val source = SourceProofContext(scope, coordinates.toSet(), premises,
            coordinates.associateWith { BigFraction.ONE })

        val result = LinearAuthority(SourceLpBudget(maxOperations = 1)).check(source, Cancellation.Never)

        val claim = assertIs<SourceClaim.Optimal>(assertNotNull(result.evidence).claim)
        assertEquals(SourceValue.Rational(BigFraction.ZERO), claim.value)
        assertEquals(SourceEvidenceCheck.Checked,
            SourceEvidenceVerifier(source.withLinearAuthority()).verify(result.evidence))
    }

    @Test
    fun `scaled upper column side should preserve a maximized source objective`() {
        val source = SourceProofContext(scope.copy(objective = SourceObjective("x", ObjectiveSense.MAXIMIZE)),
            setOf(real), mapOf("upper" to SourcePremise(
                SourceInequality(mapOf(real to BigFraction.ofLong(3)), BigFraction.ofLong(2)))),
            mapOf(real to BigFraction.ofLong(6)), objectiveConstant = BigFraction.ONE)

        val result = LinearAuthority(SourceLpBudget(maxOperations = 1)).check(source, Cancellation.Never)

        val claim = assertIs<SourceClaim.Optimal>(assertNotNull(result.evidence).claim)
        assertEquals(SourceValue.Rational(BigFraction.ofLong(5)), claim.value)
        assertEquals(SourceEvidenceCheck.Checked,
            SourceEvidenceVerifier(source.withLinearAuthority()).verify(result.evidence))
    }

    @Test
    fun `infeasible row over column sides should lift a checked source refutation`() {
        val other = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val source = SourceProofContext(scope, setOf(real, other), mapOf(
            "left" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "right" to SourcePremise(SourceInequality(mapOf(other to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "sum" to SourcePremise(SourceInequality(mapOf(real to BigFraction.ONE, other to BigFraction.ONE),
                BigFraction.MINUS_ONE)),
        ), mapOf(real to BigFraction.ONE))

        val result = LinearAuthority(SourceLpBudget(maxOperations = 1)).check(source, Cancellation.Never)

        assertEquals(SourceClaim.Refutation, assertNotNull(result.evidence).claim)
        assertEquals(SourceEvidenceCheck.Checked,
            SourceEvidenceVerifier(source.withLinearAuthority()).verify(result.evidence))
    }

    @Test
    fun `inclusive bound should supply an attained optimum`() {
        val source = SourceProofContext(
            scope,
            setOf(real),
            mapOf(
                "lower" to SourcePremise(
                    SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.MINUS_ONE),
                ),
            ),
            mapOf(real to BigFraction.ONE),
        )

        val result = LinearAuthority(SourceLpBudget()).check(source, Cancellation.Never)

        assertIs<SourceClaim.Optimal>(assertNotNull(result.evidence).claim)
        assertEquals(
            SourceEvidenceCheck.Checked,
            SourceEvidenceVerifier(source.withLinearAuthority()).verify(result.evidence),
        )
    }

    @Test
    fun `integer strict positivity should ask traversal to split instead of claiming a real infimum`() {
        val source = SourceProofContext(
            scope,
            setOf(integer),
            mapOf(
                "positive" to SourcePremise(
                    SourceInequality(mapOf(integer to BigFraction.MINUS_ONE), BigFraction.ZERO, strict = true),
                ),
            ),
            mapOf(integer to BigFraction.ONE),
        )

        val result = LinearAuthority(SourceLpBudget()).check(source, Cancellation.Never)

        assertNull(result.evidence)
        assertNotNull(result.split)
        assertNotNull(result.bound)
    }

    @Test
    fun `contradictory source bounds should supply a checked refutation`() {
        val source = SourceProofContext(
            scope.copy(
                condition = SourceCondition(
                    lower = mapOf(real to BigFraction.ONE),
                    upper = mapOf(real to BigFraction.ZERO),
                ),
            ),
            setOf(real),
            emptyMap(),
            mapOf(real to BigFraction.ONE),
        )

        val result = LinearAuthority(SourceLpBudget()).check(source, Cancellation.Never)

        assertEquals(SourceClaim.Refutation, assertNotNull(result.evidence).claim)
        assertEquals(
            SourceEvidenceCheck.Checked,
            SourceEvidenceVerifier(source.withLinearAuthority()).verify(result.evidence),
        )
    }

    @Test
    fun `exhausted numerical allowance should withhold global claims`() {
        val source = SourceProofContext(scope, setOf(real), emptyMap(), mapOf(real to BigFraction.ONE))

        val result = LinearAuthority(SourceLpBudget(maxOperations = 0)).check(source, Cancellation.Never)

        assertNull(result.evidence)
        assertNull(result.bound)
    }

    @Test
    fun `checked source bound should survive an incomplete strict feasibility check`() {
        val source = SourceProofContext(
            scope,
            setOf(real),
            mapOf(
                "positive" to SourcePremise(
                    SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.ZERO, strict = true),
                ),
            ),
            mapOf(real to BigFraction.ONE),
        )

        val result = LinearAuthority(SourceLpBudget(maxOperations = 2)).check(source, Cancellation.Never)

        assertNull(result.evidence)
        assertNotNull(result.bound)
        assertEquals(
            SourceEvidenceCheck.Checked,
            SourceEvidenceVerifier(source.withLinearAuthority()).verify(result.bound),
        )
    }

    @Test
    fun `wide rational coordinates should preserve attainment beyond floating precision`() {
        val value = BigFraction.of(bigIntOf(27021597764222980L), BIG_ONE) * BigFraction.ofLong(3).reciprocal()
        val source = SourceProofContext(
            scope,
            setOf(real),
            mapOf(
                "lower" to SourcePremise(
                    SourceInequality(mapOf(real to BigFraction.MINUS_ONE), value.negated()),
                ),
            ),
            mapOf(real to BigFraction.ONE),
        )

        val result = LinearAuthority(SourceLpBudget()).check(source, Cancellation.Never)

        val optimum = assertIs<SourceClaim.Optimal>(assertNotNull(result.evidence).claim)
        assertEquals(SourceValue.Rational(value), optimum.value)
        assertEquals(value, assertNotNull(optimum.sample.exactReals).single())
    }

    @Test
    fun `Boolean objective substitution should retain the complete source objective`() {
        val bool = SourceCoordinate(SourceCoordinate.Kind.Boolean, 0)
        val branch = scope.copy(condition = SourceCondition(bools = mapOf(0 to true)))
        val source = SourceProofContext(
            branch,
            setOf(bool, real),
            mapOf(
                "lower" to SourcePremise(
                    SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.MINUS_ONE),
                    mapOf(0 to true),
                ),
            ),
            mapOf(bool to BigFraction.ofLong(2), real to BigFraction.ONE),
        )

        val result = LinearAuthority(SourceLpBudget()).check(source, Cancellation.Never)

        val optimum = assertIs<SourceClaim.Optimal>(assertNotNull(result.evidence).claim)
        assertEquals(SourceValue.Rational(BigFraction.ofLong(3)), optimum.value)
        assertEquals(
            SourceEvidenceCheck.Checked,
            SourceEvidenceVerifier(source.withLinearAuthority()).verify(result.evidence),
        )
    }

    @Test
    fun `source objective identity change should invalidate retained linear evidence`() {
        val source = SourceProofContext(
            scope,
            setOf(real),
            mapOf(
                "lower" to SourcePremise(
                    SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.MINUS_ONE),
                ),
            ),
            mapOf(real to BigFraction.ONE),
        )
        val result = LinearAuthority(SourceLpBudget()).check(source, Cancellation.Never)
        val replacement = scope.copy(objective = SourceObjective("replacement", ObjectiveSense.MINIMIZE))
        val changed = SourceProofContext(
            replacement,
            source.coordinates,
            source.premises,
            source.objective,
        ).withLinearAuthority()

        val checked = SourceEvidenceVerifier(changed).verify(assertNotNull(result.evidence))

        assertIs<SourceEvidenceCheck.Rejected>(checked)
    }

    @Test
    fun `undeclared invocation coordinates should withhold linear authority`() {
        val absent = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val condition = SourceCondition(lower = mapOf(absent to BigFraction.ONE))
        val source = SourceProofContext(
            scope.copy(condition = condition),
            setOf(real),
            emptyMap(),
            mapOf(real to BigFraction.ONE),
        )

        val result = LinearAuthority(SourceLpBudget()).check(source, Cancellation.Never)

        assertNull(result.evidence)
        assertNull(result.bound)
    }
}
