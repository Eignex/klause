package com.eignex.klause.lp.optimization

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.lp.SparseSourceLpPolicy
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
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.shl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinearActiveRowsTest {
    private val real = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
    private val integer = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
    private val bool = SourceCoordinate(SourceCoordinate.Kind.Boolean, 0)
    private val open = SourceCoordinate(SourceCoordinate.Kind.Boolean, 1)
    private val scope = SourceScope(SourceRevision("active-rows", "exact-affine"),
        objective = SourceObjective("cost", ObjectiveSense.MINIMIZE))

    @Test
    fun `a captured delayed state should permit a source checked partial refutation`() {
        val source = SourceProofContext(scope, setOf(real, bool), mapOf(
            "lower" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "upper" to SourcePremise(SourceInequality(mapOf(real to BigFraction.ONE), BigFraction.MINUS_ONE)),
        ), mapOf(bool to BigFraction.ONE))
        val capture = LinearAuthorityCapture(1, 100_000L, 1_000_000L, Cancellation.Never)
        capture.record(source, null, null, null, LinearAuthorityResult())

        val replay = LinearAuthorityReplay.activeRows(capture.records.single(), 20_000_000L, 67_108_864L,
            Cancellation.Never)

        val checked = assertNotNull(replay.query)
        assertEquals(scope, checked.context.scope)
        assertEquals("checked-refutation", checked.state)
        assertEquals(emptyMap(), checked.numeric.objective)
        assertNull(checked.point)
        assertNull(checked.bound)
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source.withLinearAuthority())
            .verify(assertNotNull(checked.refutation)))
    }

    @Test
    fun `observing an active query should preserve its numerical accounting`() {
        val source = SourceProofContext(scope, setOf(real, bool), mapOf(
            "lower" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.ZERO)),
        ), mapOf(bool to BigFraction.ONE))
        val captures = listOf(null, LinearAuthorityCapture(1, 100_000L, 1_000_000L, Cancellation.Never))

        val work = captures.map { capture ->
            val funding = SourceLpFunding(20_000_000L, 67_108_864L).apply { grant(20_000_000L) }
            val budget = SourceLpBudget(funding = funding)
            LinearActiveRows(source, budget).use {
                it.check(SourceCondition(), Cancellation.Never, capture = capture)
            }
            listOf(budget.operations.toLong(), funding.spent, funding.allocated,
                budget.measuredPreparationWork, budget.measuredFloatWork, budget.measuredContinuationWork,
                budget.measuredConflictCertificationWork)
        }

        assertEquals(work[0], work[1])
    }

    @Test
    fun `feasibility consultation should refute active strict rows before Boolean completion`() {
        val source = SourceProofContext(scope, setOf(real, bool), mapOf(
            "lower" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "strict" to SourcePremise(SourceInequality(mapOf(real to BigFraction.ONE), BigFraction.ZERO, true)),
        ), mapOf(bool to BigFraction.ONE))

        val result = LinearActiveRows(source, SourceLpBudget()).use {
            it.check(SourceCondition(), Cancellation.Never)
        }

        val evidence = assertNotNull(result.evidence)
        assertEquals(SourceClaim.Refutation, evidence.claim)
        assertEquals(SourceEvidenceCheck.Checked,
            SourceEvidenceVerifier(source.withLinearAuthority()).verify(evidence))
    }

    @Test
    fun `unchanged active rows should not spend another numerical check`() {
        val source = SourceProofContext(scope, setOf(real, bool, open), mapOf(
            "lower" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.ZERO)),
        ), mapOf(open to BigFraction.ONE))
        val budget = SourceLpBudget()

        LinearActiveRows(source, budget).use {
            val first = it.check(SourceCondition(), Cancellation.Never)
            val operations = budget.operations
            val second = it.check(SourceCondition(bools = mapOf(0 to true)), Cancellation.Never)

            assertNull(first.evidence)
            assertTrue(second.unchanged)
            assertEquals(operations, budget.operations)
            it.release()
            it.retract()

            val restored = it.check(SourceCondition(), Cancellation.Never)

            assertTrue(restored.unchanged)
            assertEquals(operations, budget.operations)
        }
    }

    @Test
    fun `changed guards should discard an interrupted numerical query`() {
        val other = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val source = SourceProofContext(scope, setOf(real, other, bool, open), mapOf(
            "lower" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "other" to SourcePremise(SourceInequality(mapOf(other to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "sum" to SourcePremise(SourceInequality(mapOf(real to BigFraction.ONE, other to BigFraction.ONE),
                BigFraction.MINUS_ONE), mapOf(0 to true)),
        ), emptyMap())

        LinearActiveRows(source, SourceLpBudget(), SparseSourceLpPolicy(quantum = 1L)).use { checker ->
            val first = checker.check(SourceCondition(bools = mapOf(0 to true)), Cancellation.Never)
            assertTrue(first.paused)
            var retracted = checker.check(SourceCondition(bools = mapOf(0 to false)), Cancellation.Never)
            repeat(64) {
                if (retracted.paused) {
                    retracted = checker.check(SourceCondition(bools = mapOf(0 to false)), Cancellation.Never)
                }
            }

            assertEquals(false, retracted.paused)
            assertNull(retracted.evidence)
        }
    }

    @Test
    fun `exhausted funding should withhold an early conflict`() {
        val source = SourceProofContext(scope, setOf(bool), mapOf(
            "conflict" to SourcePremise(SourceInequality(emptyMap(), BigFraction.MINUS_ONE)),
        ), emptyMap())
        val budget = SourceLpBudget(funding = SourceLpFunding(0L, 1024L))

        val result = LinearActiveRows(source, budget).use { it.check(SourceCondition(), Cancellation.Never) }

        assertNull(result.evidence)
        assertEquals(0, budget.operations)
    }

    @Test
    fun `partial preparation should pause within the remaining work grant`() {
        val source = SourceProofContext(scope, setOf(real, bool), mapOf(
            "conflict" to SourcePremise(SourceInequality(emptyMap(), BigFraction.MINUS_ONE)),
        ), emptyMap())
        val funding = SourceLpFunding(1L, 1024L).apply { grant(1L) }
        val budget = SourceLpBudget(funding = funding)

        val result = LinearActiveRows(source, budget).use { it.check(SourceCondition(), Cancellation.Never) }

        assertTrue(result.paused)
        assertNull(result.evidence)
        assertEquals(1L, funding.spent)
        assertEquals(0, budget.operations)
    }

    @Test
    fun `established Boolean terms should permit a refutation with unrelated open costs`() {
        val source = SourceProofContext(scope.copy(condition = SourceCondition(bools = mapOf(0 to true))),
            setOf(real, bool, open), mapOf(
                "lower" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.ZERO)),
                "upper" to SourcePremise(SourceInequality(mapOf(real to BigFraction.ONE, bool to BigFraction.ONE),
                    BigFraction.ZERO)),
                "unresolved" to SourcePremise(SourceInequality(mapOf(open to BigFraction.ONE), BigFraction.ZERO)),
            ), mapOf(open to BigFraction.ofLong(7)))
        val evidence = SourceEvidence(source.scope, SourceClaim.Refutation,
            LinearActiveRefutation(combination("lower", "upper")))

        val checked = SourceEvidenceVerifier(source.withLinearAuthority()).verify(evidence)

        assertEquals(SourceEvidenceCheck.Checked, checked)
        assertIs<SourceEvidenceCheck.Rejected>(SourceEvidenceVerifier(source.withLinearAuthority()).verify(
            SourceEvidence(source.scope, SourceClaim.Refutation,
                LinearPinnedRefutation(combination("lower", "upper")))))
    }

    @Test
    fun `unresolved and opposite guards should withhold their rows`() {
        for (value in listOf(null, false)) {
            val condition = SourceCondition(bools = value?.let { mapOf(0 to it) }.orEmpty())
            val source = SourceProofContext(scope.copy(condition = condition), setOf(bool, open), mapOf(
                "guarded" to SourcePremise(SourceInequality(emptyMap(), BigFraction.MINUS_ONE), mapOf(0 to true)),
            ), emptyMap())

            val checked = SourceEvidenceVerifier(source.withLinearAuthority()).verify(SourceEvidence(source.scope,
                SourceClaim.Refutation, LinearActiveRefutation(SourceDerivation.Premise("guarded"))))

            assertIs<SourceEvidenceCheck.Rejected>(checked)
        }
    }

    @Test
    fun `retracting an activation should revoke its refutation derivation`() {
        val source = SourceProofContext(scope, setOf(bool, open), mapOf(
            "guarded" to SourcePremise(SourceInequality(emptyMap(), BigFraction.MINUS_ONE), mapOf(0 to true)),
        ), emptyMap())
        val active = source.atScope(scope.copy(condition = SourceCondition(bools = mapOf(0 to true))))
        val proof = LinearActiveRefutation(SourceDerivation.Premise("guarded"))
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(active.withLinearAuthority()).verify(
            SourceEvidence(active.scope, SourceClaim.Refutation, proof)))

        val checked = SourceEvidenceVerifier(source.withLinearAuthority()).verify(
            SourceEvidence(source.scope, SourceClaim.Refutation, proof))

        assertIs<SourceEvidenceCheck.Rejected>(checked)
    }

    @Test
    fun `unresolved Boolean coefficients should not receive a false substitution`() {
        val source = SourceProofContext(scope, setOf(bool, open), mapOf(
            "unresolved" to SourcePremise(SourceInequality(mapOf(bool to BigFraction.MINUS_ONE),
                BigFraction.MINUS_ONE)),
        ), emptyMap())

        val checked = SourceEvidenceVerifier(source.withLinearAuthority()).verify(SourceEvidence(source.scope,
            SourceClaim.Refutation, LinearActiveRefutation(SourceDerivation.Premise("unresolved"))))

        assertIs<SourceEvidenceCheck.Rejected>(checked)
    }

    @Test
    fun `active refutations should preserve strict endpoints`() {
        for ((strict, expected) in listOf(false to false, true to true)) {
            val source = SourceProofContext(scope, setOf(real, bool), mapOf(
                "lower" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.ZERO)),
                "upper" to SourcePremise(SourceInequality(mapOf(real to BigFraction.ONE), BigFraction.ZERO, strict)),
            ), emptyMap())

            val checked = SourceEvidenceVerifier(source.withLinearAuthority()).verify(SourceEvidence(source.scope,
                SourceClaim.Refutation, LinearActiveRefutation(combination("lower", "upper"))))

            assertEquals(expected, checked == SourceEvidenceCheck.Checked)
        }
    }

    @Test
    fun `integer indicators should require independent established bounds`() {
        for ((established, expected) in listOf(false to false, true to true)) {
            val condition = if (established) SourceCondition(lower = mapOf(integer to BigFraction.ONE),
                upper = mapOf(integer to BigFraction.ONE)) else SourceCondition()
            val source = SourceProofContext(scope.copy(condition = condition), setOf(integer, bool), mapOf(
                "conditional" to SourcePremise(SourceInequality(emptyMap(), BigFraction.MINUS_ONE),
                    emptyMap(), mapOf(integer to BigFraction.ONE)),
                "circularLower" to SourcePremise(SourceInequality(mapOf(integer to BigFraction.MINUS_ONE),
                    BigFraction.MINUS_ONE), emptyMap(), mapOf(integer to BigFraction.ONE)),
                "circularUpper" to SourcePremise(SourceInequality(mapOf(integer to BigFraction.ONE),
                    BigFraction.ONE), emptyMap(), mapOf(integer to BigFraction.ONE)),
            ), emptyMap())

            val checked = SourceEvidenceVerifier(source.withLinearAuthority()).verify(SourceEvidence(source.scope,
                SourceClaim.Refutation, LinearActiveRefutation(SourceDerivation.Premise("conditional"))))

            assertEquals(expected, checked == SourceEvidenceCheck.Checked)
        }
    }

    @Test
    fun `a feasible active row should not establish a bound on unresolved Boolean costs`() {
        val source = SourceProofContext(scope, setOf(real, bool), mapOf(
            "lower" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.ZERO)),
        ), mapOf(bool to BigFraction.ONE))
        val evidence = SourceEvidence(scope, SourceClaim.Bound(BigFraction.ZERO),
            LinearActiveRefutation(SourceDerivation.Premise("lower")))

        val checked = SourceEvidenceVerifier(source.withLinearAuthority()).verify(evidence)

        assertIs<SourceEvidenceCheck.Rejected>(checked)
    }

    @Test
    fun `a refutation should not invent an objective for a bound claim`() {
        val source = SourceProofContext(scope.copy(objective = null), setOf(bool), mapOf(
            "conflict" to SourcePremise(SourceInequality(emptyMap(), BigFraction.MINUS_ONE)),
        ), emptyMap())
        val evidence = SourceEvidence(source.scope, SourceClaim.Bound(BigFraction.ZERO),
            LinearActiveRefutation(SourceDerivation.Premise("conflict")))

        val checked = SourceEvidenceVerifier(source.withLinearAuthority()).verify(evidence)

        assertIs<SourceEvidenceCheck.Rejected>(checked)
    }

    @Test
    fun `active projection should discard unresolved objective coefficients`() {
        val source = SourceProofContext(scope, setOf(real, bool), emptyMap(), mapOf(bool to BigFraction.ONE))

        val numeric = assertNotNull(source.activeNumericContext(Cancellation.Never))

        assertEquals(emptyMap(), numeric.objective)
        assertEquals(BigFraction.ZERO, numeric.objectiveConstant)
    }

    @Test
    fun `oversized active scalars should decline before numerical preparation`() {
        val large = BigFraction.of(BIG_ONE shl 4096, BIG_ONE)
        val source = SourceProofContext(scope, setOf(real, bool), mapOf(
            "wide" to SourcePremise(SourceInequality(mapOf(real to large), BigFraction.ZERO)),
        ), emptyMap())

        val numeric = source.activeNumericContext(Cancellation.Never)

        assertNull(numeric)
    }

    @Test
    fun `an ordinary partial refutation should not inspect oversized dormant rows`() {
        val large = BigFraction.of(BIG_ONE shl 4096, BIG_ONE)
        val source = SourceProofContext(scope, setOf(real, bool), mapOf(
            "conflict" to SourcePremise(SourceInequality(emptyMap(), BigFraction.MINUS_ONE)),
            "dormant" to SourcePremise(SourceInequality(mapOf(real to large), BigFraction.ZERO), mapOf(0 to true)),
        ), emptyMap())
        val evidence = SourceEvidence(scope, SourceClaim.Refutation,
            LinearActiveRefutation(SourceDerivation.Premise("conflict")))

        val verdict = SourceEvidenceVerifier(source.withLinearAuthority()).verify(evidence)

        assertEquals(SourceEvidenceCheck.Checked, verdict)
    }

    @Test
    fun `interrupted row capture should withhold a partial context`() {
        val source = SourceProofContext(scope, setOf(real, bool), mapOf(
            "row" to SourcePremise(SourceInequality(mapOf(real to BigFraction.ONE), BigFraction.ZERO)),
        ), emptyMap())
        var polls = 0

        val numeric = source.activeNumericContext(Cancellation { ++polls >= 3 })

        assertNull(numeric)
    }

    private fun combination(vararg ids: String): SourceDerivation = SourceDerivation.Combination(ids.map {
        SourceDerivation.Combination.Term(BigFraction.ONE, SourceDerivation.Premise(it))
    })
}
