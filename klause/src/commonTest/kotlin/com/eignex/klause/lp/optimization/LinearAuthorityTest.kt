package com.eignex.klause.lp.optimization

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SparseSourceLpPolicy
import com.eignex.klause.lp.engine.ReconstructionLimits
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
import kotlin.test.assertTrue

class LinearAuthorityTest {
    private val real = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
    private val integer = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
    private val scope = SourceScope(
        SourceRevision("affine-fixture", "exact-affine"),
        objective = SourceObjective("x", ObjectiveSense.MINIMIZE),
    )

    @Test
    fun `suspended sparse refutations should lift column sides and charge certificate work once`() {
        val coordinates = (0 until 129).map { SourceCoordinate(SourceCoordinate.Kind.Real, it) }
        val other = coordinates[1]
        val source = SourceProofContext(scope, coordinates.toSet(), mapOf(
            "left" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "right" to SourcePremise(SourceInequality(mapOf(other to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "sum" to SourcePremise(SourceInequality(mapOf(real to BigFraction.ONE, other to BigFraction.ONE),
                BigFraction.MINUS_ONE)),
        ), mapOf(real to BigFraction.ONE))
        val budget = SourceLpBudget(maxOperations = 1)

        LinearAuthority(budget, retainLp = true, guidancePolicy = SparseSourceLpPolicy(quantum = 1L)).use { authority ->
            var result = authority.advance(source, Cancellation.Never)
            assertTrue(result.paused)
            assertEquals(null, result.evidence)
            repeat(64) {
                if (result.paused) result = authority.advance(source, Cancellation.Never)
            }

            val evidence = assertNotNull(result.evidence)
            assertEquals(SourceClaim.Refutation, evidence.claim)
            assertEquals(SourceEvidenceCheck.Checked,
                SourceEvidenceVerifier(source.withLinearAuthority()).verify(evidence))
            assertTrue(result.relaxedPoint.isEmpty())
            val certificateWork = budget.measuredConflictCertificationWork
            val floatWork = budget.measuredFloatWork
            assertTrue(certificateWork > 0L)
            assertEquals(SourceClaim.Refutation, authority.advance(source, Cancellation.Never).evidence?.claim)
            assertEquals(certificateWork, budget.measuredConflictCertificationWork)
            assertEquals(floatWork, budget.measuredFloatWork)
            assertEquals(1, budget.operations)
        }
    }

    @Test
    fun `sparse refutations should lose authority after a scoped bound retracts`() {
        val coordinates = (0 until 129).map { SourceCoordinate(SourceCoordinate.Kind.Real, it) }
        val other = coordinates[1]
        val source = SourceProofContext(scope, coordinates.toSet(), mapOf(
            "left" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "right" to SourcePremise(SourceInequality(mapOf(other to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "sum" to SourcePremise(SourceInequality(mapOf(real to BigFraction.ONE, other to BigFraction.ONE),
                BigFraction.ONE)),
        ), mapOf(real to BigFraction.ONE))
        val restricted = source.atScope(scope.copy(condition = SourceCondition(
            lower = mapOf(real to BigFraction.ofLong(2)))))
        val budget = SourceLpBudget(maxOperations = 2)

        LinearAuthority(budget, retainLp = true).use { authority ->
            val evidence = assertNotNull(authority.advance(restricted, Cancellation.Never).evidence)
            assertEquals(SourceClaim.Refutation, evidence.claim)
            assertEquals(SourceEvidenceCheck.Checked,
                SourceEvidenceVerifier(restricted.withLinearAuthority()).verify(evidence))
            val globalCheck = SourceEvidenceVerifier(source.withLinearAuthority()).verify(evidence)
            assertTrue(globalCheck != SourceEvidenceCheck.Checked)

            val retracted = authority.advance(source, Cancellation.Never)
            assertIs<SourceClaim.Optimal>(assertNotNull(retracted.evidence).claim)
            assertEquals(2, budget.operations)
        }
    }

    @Test
    fun `exhausted sparse conflict certification should leave the source undecided`() {
        val coordinates = (0 until 129).map { SourceCoordinate(SourceCoordinate.Kind.Real, it) }
        val source = SourceProofContext(scope, coordinates.toSet(), mapOf(
            "left" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "right" to SourcePremise(SourceInequality(mapOf(coordinates[1] to BigFraction.MINUS_ONE),
                BigFraction.ZERO)),
            "sum" to SourcePremise(SourceInequality(mapOf(real to BigFraction.ONE,
                coordinates[1] to BigFraction.ONE), BigFraction.MINUS_ONE)),
        ), mapOf(real to BigFraction.ONE))

        LinearAuthority(SourceLpBudget(maxOperations = 1), retainLp = true,
            guidancePolicy = SparseSourceLpPolicy(certificate = ReconstructionLimits(maxWork = 0L))).use { authority ->
            val result = authority.advance(source, Cancellation.Never)

            assertNull(result.evidence)
            assertNull(result.bound)
            assertTrue(result.relaxedPoint.isEmpty())
        }
    }

    @Test
    fun `sparse conflicts should retire when a contributing guarded row is disabled`() {
        val bool = SourceCoordinate(SourceCoordinate.Kind.Boolean, 0)
        val coordinates = (0 until 129).map { SourceCoordinate(SourceCoordinate.Kind.Real, it) }
        val source = SourceProofContext(scope, (coordinates + bool).toSet(), mapOf(
            "left" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "right" to SourcePremise(SourceInequality(mapOf(coordinates[1] to BigFraction.MINUS_ONE),
                BigFraction.ZERO)),
            "sum" to SourcePremise(SourceInequality(mapOf(real to BigFraction.ONE,
                coordinates[1] to BigFraction.ONE), BigFraction.MINUS_ONE), guards = mapOf(0 to true)),
        ), mapOf(real to BigFraction.ONE))
        val enabled = source.atScope(scope.copy(condition = SourceCondition(bools = mapOf(0 to true))))
        val disabled = source.atScope(scope.copy(condition = SourceCondition(bools = mapOf(0 to false))))

        LinearAuthority(SourceLpBudget(maxOperations = 2), retainLp = true).use { authority ->
            val conflict = assertNotNull(authority.advance(enabled, Cancellation.Never).evidence)
            assertEquals(SourceClaim.Refutation, conflict.claim)
            val result = authority.advance(disabled, Cancellation.Never)

            val evidence = assertNotNull(result.evidence)
            assertIs<SourceClaim.Optimal>(evidence.claim)
            assertEquals(SourceEvidenceCheck.Checked,
                SourceEvidenceVerifier(disabled.withLinearAuthority()).verify(evidence))
        }
    }

    @Test
    fun `paused source guidance should retain one admitted operation across slices`() {
        val other = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val source = SourceProofContext(scope, setOf(real, other), mapOf(
            "sum" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE,
                other to BigFraction.MINUS_ONE), BigFraction.ofLong(-3))),
            "x" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "y" to SourcePremise(SourceInequality(mapOf(other to BigFraction.MINUS_ONE), BigFraction.ZERO)),
        ), mapOf(real to BigFraction.ONE, other to BigFraction.ofLong(2)))
        val budget = SourceLpBudget(maxOperations = 1)

        LinearAuthority(budget, retainLp = true, guidancePolicy = SparseSourceLpPolicy(quantum = 1L)).use { authority ->
            var result = authority.advance(source, Cancellation.Never)
            assertTrue(result.paused)
            assertNull(result.bound)
            assertTrue(result.relaxedPoint.isEmpty())
            repeat(32) {
                if (result.paused) result = authority.advance(source, Cancellation.Never)
            }

            val terminal = assertNotNull(result.evidence)
            assertEquals(SourceValue.Rational(BigFraction.ofLong(3)),
                assertIs<SourceClaim.Optimal>(terminal.claim).value)
            assertEquals(SourceEvidenceCheck.Checked,
                SourceEvidenceVerifier(source.withLinearAuthority()).verify(terminal))
            val floatWork = budget.measuredFloatWork
            val certificateWork = budget.measuredBoundCertificationWork
            authority.advance(source, Cancellation.Never)
            assertEquals(1, budget.operations)
            assertEquals(1L, authority.preparations)
            assertEquals(floatWork, budget.measuredFloatWork)
            assertEquals(certificateWork, budget.measuredBoundCertificationWork)
        }
    }

    @Test
    fun `sparse source guidance should check a source beyond exact primal admission`() {
        val coordinates = (0 until 129).map { SourceCoordinate(SourceCoordinate.Kind.Real, it) }
        val premises = coordinates.withIndex().associate { (id, coordinate) ->
            "lower$id" to SourcePremise(SourceInequality(mapOf(coordinate to BigFraction.MINUS_ONE), BigFraction.ZERO))
        } + ("sum" to SourcePremise(SourceInequality(coordinates.associateWith { BigFraction.MINUS_ONE },
            BigFraction.MINUS_ONE)))
        val source = SourceProofContext(scope, coordinates.toSet(), premises,
            coordinates.associateWith { BigFraction.ONE })

        LinearAuthority(SourceLpBudget(maxOperations = 1), retainLp = true).use { authority ->
            val result = authority.advance(source, Cancellation.Never)

            val terminal = assertNotNull(result.evidence)
            assertEquals(SourceValue.Rational(BigFraction.ONE), assertIs<SourceClaim.Optimal>(terminal.claim).value)
            assertEquals(SourceEvidenceCheck.Checked,
                SourceEvidenceVerifier(source.withLinearAuthority()).verify(terminal))
        }
    }

    @Test
    fun `sparse guidance should reconstruct fixed source values beyond floating precision`() {
        val coordinates = (0 until 129).map { SourceCoordinate(SourceCoordinate.Kind.Real, it) }
        val value = BigFraction.ofLong(9007199254740993L)
        val source = SourceProofContext(scope, coordinates.toSet(), mapOf(
            "lower" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE), value.negated())),
            "upper" to SourcePremise(SourceInequality(mapOf(real to BigFraction.ONE), value)),
        ), mapOf(real to BigFraction.ONE))

        LinearAuthority(SourceLpBudget(maxOperations = 1), retainLp = true).use { authority ->
            val result = authority.advance(source, Cancellation.Never)

            val terminal = assertNotNull(result.evidence)
            assertEquals(SourceValue.Rational(value), assertIs<SourceClaim.Optimal>(terminal.claim).value)
            assertEquals(value, assertNotNull(result.incumbent?.exactReals).first())
            assertEquals(SourceEvidenceCheck.Checked,
                SourceEvidenceVerifier(source.withLinearAuthority()).verify(terminal))
        }
    }

    @Test
    fun `changed source conditions should discard a suspended guidance cursor`() {
        val other = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val source = SourceProofContext(scope, setOf(real, other), mapOf(
            "sum" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE,
                other to BigFraction.MINUS_ONE), BigFraction.ofLong(-3))),
            "x" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "y" to SourcePremise(SourceInequality(mapOf(other to BigFraction.MINUS_ONE), BigFraction.ZERO)),
        ), mapOf(real to BigFraction.ONE, other to BigFraction.ofLong(2)))
        val changed = source.atScope(scope.copy(condition = SourceCondition(
            lower = mapOf(real to BigFraction.ofLong(4)))))
        val budget = SourceLpBudget(maxOperations = 2)

        LinearAuthority(budget, retainLp = true, guidancePolicy = SparseSourceLpPolicy(quantum = 1L)).use { authority ->
            assertTrue(authority.advance(source, Cancellation.Never).paused)
            var result = authority.advance(changed, Cancellation.Never)
            repeat(32) {
                if (result.paused) result = authority.advance(changed, Cancellation.Never)
            }

            val terminal = assertNotNull(result.evidence)
            assertEquals(SourceValue.Rational(BigFraction.ofLong(4)),
                assertIs<SourceClaim.Optimal>(terminal.claim).value)
            assertEquals(SourceEvidenceCheck.Checked,
                SourceEvidenceVerifier(changed.withLinearAuthority()).verify(terminal))
            assertEquals(2, budget.operations)
        }
    }

    @Test
    fun `retained queries should reject absent affine authority after a zero objective`() {
        val source = SourceProofContext(scope, setOf(real), emptyMap(), emptyMap())
        val unavailable = SourceProofContext(scope, setOf(real), emptyMap())

        LinearAuthority(SourceLpBudget(), retainLp = true).use { authority ->
            assertNotNull(authority.check(source, Cancellation.Never).evidence)
            val result = authority.check(unavailable, Cancellation.Never)

            assertNull(result.evidence)
            assertNull(result.bound)
        }
    }

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
    fun `conditional source rows should retract across retained sibling queries`() {
        val other = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val source = SourceProofContext(scope, setOf(integer, real, other), mapOf(
            "conditional" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE,
                other to BigFraction.MINUS_ONE), BigFraction.ofLong(-3)), emptyMap(),
                mapOf(integer to BigFraction.ZERO)),
            "x" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "y" to SourcePremise(SourceInequality(mapOf(other to BigFraction.MINUS_ONE), BigFraction.ZERO)),
        ), mapOf(real to BigFraction.ONE, other to BigFraction.ofLong(2)))

        LinearAuthority(SourceLpBudget(), retainLp = true).use { retained ->
            for (pin in listOf(BigFraction.ZERO, BigFraction.ONE, BigFraction.ZERO)) {
                val condition = SourceCondition(lower = mapOf(integer to pin), upper = mapOf(integer to pin))
                val branch = source.atScope(scope.copy(condition = condition))
                val result = retained.check(branch, Cancellation.Never)

                assertEquals(SourceValue.Rational(if (pin.isZero) BigFraction.ofLong(3) else BigFraction.ZERO),
                    assertIs<SourceClaim.Optimal>(assertNotNull(result.evidence).claim).value)
                assertEquals(SourceEvidenceCheck.Checked,
                    SourceEvidenceVerifier(branch.withLinearAuthority()).verify(result.evidence))
            }
            assertEquals(1L, retained.preparations)
            assertEquals(2L, retained.reuses)
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
