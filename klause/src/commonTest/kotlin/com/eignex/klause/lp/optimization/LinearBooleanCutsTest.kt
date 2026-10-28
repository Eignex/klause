package com.eignex.klause.lp.optimization

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
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
import com.eignex.klause.solver.evidence.sourceProofContext
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.prepareExecutionPlan
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinearBooleanCutsTest {
    private val x = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
    private val y = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
    private val quarter = BigFraction.ofLong(4).reciprocal()
    private val scope = SourceScope(SourceRevision("coupled-real", "klause-ir-exact-v1"),
        objective = SourceObjective("difference", ObjectiveSense.MINIMIZE))
    private val objective = LinearObjective(realCoefficients = doubleArrayOf(-1.0, 1.0))

    private fun model(refutation: Boolean = false): Problem = Problem(1, 0, emptyArray(), buildList {
        for (id in 0..1) add(ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(id),
            doubleArrayOf(1.0), LinearOp.LE, 0.0))
        for (sign in listOf(-1.0, 1.0)) add(Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0, 1),
            doubleArrayOf(sign, sign), LinearOp.LE, 0.25))
        if (refutation) add(Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0, 1),
            doubleArrayOf(-1.0, 1.0), LinearOp.LE, -0.5))
    }, numRealVars = 2, realLower = doubleArrayOf(-1.0, -1.0), realUpper = doubleArrayOf(1.0, 1.0))

    @Test
    fun `prepared real reifications should supply checked common rows in both directions`() {
        val prepared = model().prepareExecutionPlan(objective)
        val source = assertNotNull(assertNotNull(prepared.model).sourceProofContext(scope, objective))

        val result = LinearBooleanCutPreparation.prepare(source, Cancellation.Never)

        assertTrue(result.complete, result.reason)
        for (sign in listOf(BigFraction.ONE, BigFraction.MINUS_ONE)) {
            val row = SourceInequality(mapOf(x to sign, y to sign.negated()), quarter)
            val evidence = result.consequences.single { it.claim == SourceClaim.Consequence(row) }
            assertIs<SourceDerivation.BooleanSplit>(evidence.derivation)
            assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(evidence))
        }
    }

    @Test
    fun `common rows should refute a coupled row while its Boolean remains unpinned`() {
        val source = assertNotNull(model(true).sourceProofContext(scope, objective))
        val baseline = LinearActiveRows(source, SourceLpBudget()).use {
            it.check(SourceCondition(), Cancellation.Never)
        }

        val result = LinearActiveRows(source, SourceLpBudget(), composeBranches = true).use {
            it.check(SourceCondition(), Cancellation.Never)
        }

        assertNull(baseline.evidence)
        val evidence = assertNotNull(result.evidence)
        assertEquals(SourceClaim.Refutation, evidence.claim)
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source.withLinearAuthority()).verify(evidence))
    }

    @Test
    fun `ordinary exhaustive Boolean coverage should retain the same refutation authority`() {
        val source = assertNotNull(model(true).sourceProofContext(scope, objective))
        val proofs = listOf(false, true).map { value ->
            LinearActiveRows(source, SourceLpBudget()).use {
                assertNotNull(it.check(SourceCondition(bools = mapOf(0 to value)), Cancellation.Never).evidence).derivation
            }
        }

        val evidence = SourceEvidence(scope, SourceClaim.Refutation, SourceDerivation.BooleanSplit(0, proofs[0], proofs[1]))

        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source.withLinearAuthority()).verify(evidence))
    }

    @Test
    fun `composition should preserve feasible original points in both alternatives`() {
        val source = assertNotNull(model().sourceProofContext(scope, objective))
        val values = listOf(true to listOf(BigFraction.ZERO, quarter.negated()),
            false to listOf(quarter / BigFraction.ofLong(2), quarter / BigFraction.ofLong(2)))
        val verifier = SourceEvidenceVerifier(source)

        for ((bool, reals) in values) {
            val witness = Sample(booleanArrayOf(bool), longArrayOf(), reals.map { it.toDouble() }.toDoubleArray(),
                exactReals = reals)
            assertEquals(SourceEvidenceCheck.Checked, verifier.verify(SourceEvidence(scope,
                SourceClaim.Witness(witness), SourceDerivation.SourceWitness)))
        }
        val result = LinearActiveRows(source, SourceLpBudget(), composeBranches = true).use {
            it.check(SourceCondition(), Cancellation.Never)
        }

        assertNull(result.evidence)
    }

    @Test
    fun `a common consequence should preserve strictness only when both alternatives establish it`() {
        val bool = SourceCoordinate(SourceCoordinate.Kind.Boolean, 0)
        for ((leftStrict, rightStrict) in listOf(false to false, true to false, false to true, true to true)) {
            val source = SourceProofContext(scope, setOf(x, bool), mapOf(
                "false" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE), quarter, leftStrict),
                    mapOf(0 to false)),
                "true" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE), quarter, rightStrict),
                    mapOf(0 to true)),
            ), mapOf(x to BigFraction.ONE))

            val evidence = LinearBooleanCutPreparation.prepare(source, Cancellation.Never).consequences.single()

            assertEquals(leftStrict && rightStrict, (evidence.claim as SourceClaim.Consequence).inequality.strict)
            assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(evidence))
        }
    }

    @Test
    fun `composition should decline incomplete alternative and integer guarded support`() {
        val source = assertNotNull(model().sourceProofContext(scope, objective))
        val premises = source.premises.filterValues { it.guards[0] != false }
        val incomplete = SourceProofContext(scope, source.coordinates, premises, source.objective)

        val result = LinearBooleanCutPreparation.prepare(incomplete, Cancellation.Never)

        assertTrue(result.complete)
        assertTrue(result.consequences.isEmpty())
    }

    @Test
    fun `imported common rows should reject foreign revisions and changed source premises`() {
        val source = assertNotNull(model().sourceProofContext(scope, objective))
        val common = LinearBooleanCutPreparation.prepare(source, Cancellation.Never).consequences.first()
        val imported = SourceEvidence(scope.copy(revision = SourceRevision("other", scope.revision.semantics)),
            common.claim, SourceDerivation.Import(common))
        val missing = SourceProofContext(scope, source.coordinates,
            source.premises.filterValues { it.guards[0] != false }, source.objective)

        assertIs<SourceEvidenceCheck.Rejected>(SourceEvidenceVerifier(source.atScope(imported.scope)).verify(imported))
        assertIs<SourceEvidenceCheck.Rejected>(SourceEvidenceVerifier(missing).verify(common))
    }

    @Test
    fun `a retracted bound should remove its composed partial refutation`() {
        val source = assertNotNull(model().sourceProofContext(scope, objective))
        val restricted = SourceCondition(lower = mapOf(x to BigFraction.ONE), upper = mapOf(y to BigFraction.ZERO))

        LinearActiveRows(source, SourceLpBudget(), composeBranches = true).use { checker ->
            assertNotNull(checker.check(restricted, Cancellation.Never).evidence)
            checker.retract()
            assertNull(checker.check(SourceCondition(), Cancellation.Never).evidence)
            checker.retract()
            assertNotNull(checker.check(restricted, Cancellation.Never).evidence)
        }
    }

    @Test
    fun `bounded failed preparation should retain its charged work and no authority`() {
        val source = assertNotNull(model().sourceProofContext(scope, objective))
        for (limits in listOf(LinearBooleanCutLimits(work = 1L), LinearBooleanCutLimits(allocation = 1L),
            LinearBooleanCutLimits(scalarBits = 1), LinearBooleanCutLimits(rows = 1),
            LinearBooleanCutLimits(branchRows = 1))) {
            var work = 0L
            var allocation = 0L

            val result = LinearBooleanCutPreparation.prepare(source, Cancellation.Never,
                { steps, bytes -> work += steps; allocation += bytes }, limits)

            assertFalse(result.complete)
            assertTrue(result.consequences.isEmpty())
            assertTrue(work <= limits.work)
            assertTrue(allocation <= limits.allocation)
        }
    }

    @Test
    fun `containing cancellation and exhausted funding should withhold a composed conflict`() {
        val source = assertNotNull(model(true).sourceProofContext(scope, objective))
        val funding = SourceLpFunding(1L, 1024L).apply { grant(1L) }
        val result = LinearActiveRows(source, SourceLpBudget(funding = funding), composeBranches = true).use {
            it.check(SourceCondition(), Cancellation.Never)
        }
        val cancelled = LinearBooleanCutPreparation.prepare(source, Cancellation { true })

        assertNull(result.evidence)
        assertEquals(1L, funding.spent)
        assertFalse(cancelled.complete)
        assertTrue(cancelled.consequences.isEmpty())
    }
}
