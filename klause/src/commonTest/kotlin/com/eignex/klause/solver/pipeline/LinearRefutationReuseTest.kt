package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.lp.optimization.LinearPinnedRefutation
import com.eignex.klause.lp.optimization.atScope
import com.eignex.klause.lp.optimization.withLinearAuthority
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
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinearRefutationReuseTest {
    private val x = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
    private val other = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
    private val scope = SourceScope(SourceRevision("reuse-source", "exact-affine"),
        objective = SourceObjective("reuse-objective", ObjectiveSense.MINIMIZE))
    private val source = SourceProofContext(scope, setOf(x, other), mapOf(
        "lower" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.MINUS_ONE)),
    ))
    private val proof = LinearPinnedRefutation(SourceDerivation.Combination(listOf(
        SourceDerivation.Combination.Term(BigFraction.ONE, SourceDerivation.Premise("lower")),
        SourceDerivation.Combination.Term(BigFraction.ONE, SourceDerivation.Assumption(x, upper = true)),
    )))
    private val restricted = scope.copy(condition = SourceCondition(
        lower = mapOf(other to BigFraction.ONE), upper = mapOf(x to BigFraction.ZERO)))
    private val evidence = SourceEvidence(restricted, SourceClaim.Refutation, proof)

    @Test
    fun `support reduction should reuse a checked refutation after an unrelated bound changes`() {
        val reuse = LinearRefutationReuse(source, SourceLpBudget(), Cancellation.Never)
        reuse.retain(evidence)
        val condition = SourceCondition(upper = mapOf(x to BigFraction.ZERO, other to BigFraction.MINUS_ONE))

        val reused = assertNotNull(reuse.find(condition))

        assertEquals(SourceEvidenceCheck.Checked,
            SourceEvidenceVerifier(source.withLinearAuthority().atScope(reused.scope)).verify(reused))
        assertEquals(1L, reuse.observations()["numericalRefutationReuseReducedScopes"])
    }

    @Test
    fun `support lookup should accept equal and stronger bounds`() {
        for (upper in listOf(true, false)) {
            val coefficient = if (upper) BigFraction.MINUS_ONE else BigFraction.ONE
            val context = SourceProofContext(scope, setOf(x), mapOf("bound" to SourcePremise(
                SourceInequality(mapOf(x to coefficient), BigFraction.MINUS_ONE))))
            val conflict = LinearPinnedRefutation(SourceDerivation.Combination(listOf(
                SourceDerivation.Combination.Term(BigFraction.ONE, SourceDerivation.Premise("bound")),
                SourceDerivation.Combination.Term(BigFraction.ONE, SourceDerivation.Assumption(x, upper)),
            )))
            val condition = SourceCondition().bound(x, BigFraction.ZERO, upper)
            val reuse = LinearRefutationReuse(context, SourceLpBudget(), Cancellation.Never)
            reuse.retain(SourceEvidence(scope.copy(condition = condition), SourceClaim.Refutation, conflict))

            for (value in listOf(BigFraction.ZERO, coefficient)) {
                assertNotNull(reuse.find(SourceCondition().bound(x, value, upper)))
            }
        }
    }

    @Test
    fun `support lookup should reject weaker and unrelated bounds`() {
        for (upper in listOf(true, false)) {
            val coefficient = if (upper) BigFraction.MINUS_ONE else BigFraction.ONE
            val context = SourceProofContext(scope, setOf(x), mapOf("bound" to SourcePremise(
                SourceInequality(mapOf(x to coefficient), BigFraction.MINUS_ONE))))
            val conflict = LinearPinnedRefutation(SourceDerivation.Combination(listOf(
                SourceDerivation.Combination.Term(BigFraction.ONE, SourceDerivation.Premise("bound")),
                SourceDerivation.Combination.Term(BigFraction.ONE, SourceDerivation.Assumption(x, upper)),
            )))
            val condition = SourceCondition().bound(x, BigFraction.ZERO, upper)
            val reuse = LinearRefutationReuse(context, SourceLpBudget(), Cancellation.Never)
            reuse.retain(SourceEvidence(scope.copy(condition = condition), SourceClaim.Refutation, conflict))
            val conditions = listOf(SourceCondition(),
                SourceCondition().bound(x, coefficient.negated(), upper),
                SourceCondition().bound(x, coefficient.negated(), !upper))

            for (candidate in conditions) assertNull(reuse.find(candidate))
        }
    }

    @Test
    fun `reuse should retain the invocation window when a proof has smaller support`() {
        val window = SourceCondition(lower = mapOf(other to BigFraction.ZERO),
            upper = mapOf(other to BigFraction.ONE))
        val local = source.atScope(scope.copy(condition = window))
        val reuse = LinearRefutationReuse(local, SourceLpBudget(), Cancellation.Never)
        val conditioned = SourceCondition(window.bools, window.lower, window.upper + (x to BigFraction.ZERO))
        reuse.retain(SourceEvidence(local.scope.copy(condition = conditioned), SourceClaim.Refutation, proof))

        assertNull(reuse.find(SourceCondition(upper = mapOf(x to BigFraction.ZERO))))
        assertNotNull(reuse.find(conditioned))
    }

    @Test
    fun `reuse should retain complete Boolean substitutions after numeric support reduction`() {
        val bool = SourceCoordinate(SourceCoordinate.Kind.Boolean, 0)
        val guarded = SourceProofContext(scope, source.coordinates + bool, source.premises)
        val reuse = LinearRefutationReuse(guarded, SourceLpBudget(), Cancellation.Never)
        val condition = SourceCondition(bools = mapOf(0 to true), upper = mapOf(x to BigFraction.ZERO))
        reuse.retain(SourceEvidence(scope.copy(condition = condition), SourceClaim.Refutation, proof))

        assertNull(reuse.find(SourceCondition(upper = mapOf(x to BigFraction.ZERO))))
        assertNull(reuse.find(SourceCondition(bools = mapOf(0 to false), upper = mapOf(x to BigFraction.ZERO))))
        assertNotNull(reuse.find(condition))
    }

    @Test
    fun `a proof with open Boolean coordinates should not enter reuse`() {
        val bool = SourceCoordinate(SourceCoordinate.Kind.Boolean, 0)
        val guarded = SourceProofContext(scope, source.coordinates + bool, source.premises)
        val reuse = LinearRefutationReuse(guarded, SourceLpBudget(), Cancellation.Never)

        reuse.retain(evidence)

        assertNull(reuse.find(restricted.condition))
        assertEquals(0L, reuse.observations()["numericalRefutationReuseRetained"])
    }

    @Test
    fun `implicit integer activation bounds should survive a rejected support reduction`() {
        val integer = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
        val rows = mapOf("guarded" to SourcePremise(SourceInequality(emptyMap(), BigFraction.MINUS_ONE),
            emptyMap(), mapOf(integer to BigFraction.ZERO)))
        val guarded = SourceProofContext(scope, setOf(integer), rows)
        val reuse = LinearRefutationReuse(guarded, SourceLpBudget(), Cancellation.Never)
        val condition = SourceCondition(lower = mapOf(integer to BigFraction.ZERO),
            upper = mapOf(integer to BigFraction.ZERO))
        val derived = LinearPinnedRefutation(SourceDerivation.Premise("guarded"))

        reuse.retain(SourceEvidence(scope.copy(condition = condition), SourceClaim.Refutation, derived))

        assertNull(reuse.find(SourceCondition()))
        assertNotNull(reuse.find(condition))
        assertEquals(1L, reuse.observations()["numericalRefutationReuseReductionRejected"])
    }

    @Test
    fun `changed source semantics and objective identities should reject retained evidence`() {
        val foreign = listOf(
            restricted.copy(revision = scope.revision.copy(identity = "edited-source")),
            restricted.copy(revision = scope.revision.copy(semantics = "tolerance")),
            restricted.copy(objective = checkNotNull(scope.objective).copy(identity = "edited-objective")),
            restricted.copy(objective = checkNotNull(scope.objective).copy(sense = ObjectiveSense.MAXIMIZE)),
        )

        for (otherScope in foreign) {
            val reuse = LinearRefutationReuse(source, SourceLpBudget(), Cancellation.Never)
            reuse.retain(SourceEvidence(otherScope, SourceClaim.Refutation, proof))

            assertNull(reuse.find(restricted.condition))
        }
    }

    @Test
    fun `proofs should be freshly checked against rows with matching identifiers`() {
        val changed = SourceProofContext(scope, setOf(x, other), mapOf(
            "lower" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.ZERO)),
        ))
        val reuse = LinearRefutationReuse(changed, SourceLpBudget(), Cancellation.Never)

        reuse.retain(evidence)

        assertNull(reuse.find(restricted.condition))
    }

    @Test
    fun `objective bounds should not enter refutation reuse`() {
        val reuse = LinearRefutationReuse(source, SourceLpBudget(), Cancellation.Never)

        reuse.retain(SourceEvidence(restricted, SourceClaim.Bound(BigFraction.ONE), proof))

        assertNull(reuse.find(restricted.condition))
    }

    @Test
    fun `cancelled import verification should preserve the retained alternative for a later check`() {
        var polls = 0
        var stopAfter = Int.MAX_VALUE
        val reuse = LinearRefutationReuse(source, SourceLpBudget(), Cancellation { ++polls >= stopAfter })
        reuse.retain(evidence)
        polls = 0
        stopAfter = 20

        assertNull(reuse.find(restricted.condition))
        assertEquals(1L, reuse.observations()["numericalRefutationReuseRetained"])
        polls = 0
        stopAfter = Int.MAX_VALUE
        assertNotNull(reuse.find(restricted.condition))
    }

    @Test
    fun `an exhausted cumulative grant should withhold reuse without discarding a proof`() {
        val funding = SourceLpFunding(1_000_000L, 1_000_000L).apply { grant(1_000_000L) }
        val budget = SourceLpBudget(funding = funding)
        val reuse = LinearRefutationReuse(source, budget, Cancellation.Never)
        reuse.retain(evidence)
        funding.grant(0L)

        assertNull(reuse.find(restricted.condition))
        assertEquals(1L, reuse.observations()["numericalRefutationReuseRetained"])
        assertTrue(budget.measuredConflictCertificationWork > 0L)
        assertTrue(funding.allocated > 0L)
    }

    @Test
    fun `entry eviction should keep the newer proof and charge cleanup work`() {
        val budget = SourceLpBudget()
        val reuse = LinearRefutationReuse(source, budget, Cancellation.Never, maximumEntries = 1)
        reuse.retain(evidence)
        val stronger = scope.copy(condition = SourceCondition(upper = mapOf(x to BigFraction.MINUS_ONE)))
        reuse.retain(SourceEvidence(stronger, SourceClaim.Refutation, proof))

        assertNull(reuse.find(SourceCondition(upper = mapOf(x to BigFraction.ZERO))))
        assertNotNull(reuse.find(stronger.condition))
        assertEquals(1L, reuse.observations()["numericalRefutationReuseEvictions"])
        val before = budget.measuredConflictCertificationWork
        reuse.clear()
        assertTrue(budget.measuredConflictCertificationWork > before)
        assertEquals(0L, reuse.observations()["numericalRefutationReuseBytes"])
    }

    @Test
    fun `proofs exceeding the retention allocation should leave all queries open`() {
        val reuse = LinearRefutationReuse(source, SourceLpBudget(), Cancellation.Never, maximumBytes = 64L)

        reuse.retain(evidence)

        assertNull(reuse.find(restricted.condition))
        assertEquals(0L, reuse.observations()["numericalRefutationReuseRetained"])
    }
}
