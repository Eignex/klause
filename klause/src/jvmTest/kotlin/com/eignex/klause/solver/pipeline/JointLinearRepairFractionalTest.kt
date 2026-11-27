package com.eignex.klause.solver.pipeline

import com.eignex.klause.RuntimeInitializedTest
import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.lp.optimization.withLinearAuthority
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceEvidenceVerifier
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class JointLinearRepairFractionalTest(
    private val advisory: Boolean,
    private val boundOrdered: Boolean,
) : RuntimeInitializedTest() {
    @Test
    fun `private repair should complete coherent fractional binary guidance`() {
        val half = BigFraction.ofLong(2).reciprocal()
        val links = (0..1).map { id ->
            ExactLinear.of(intArrayOf(Term.ofIntVar(id), Term.ofRealVar(id)),
                listOf(BigFraction.ONE, BigFraction.MINUS_ONE), LinearOp.EQ, BigFraction.ZERO)
        }
        val coherence = listOf(0 to 1, 1 to 0).map { (a, b) ->
            ExactLinear.of(intArrayOf(Term.ofRealVar(a), Term.ofRealVar(b)),
                listOf(BigFraction.ONE, BigFraction.MINUS_ONE), LinearOp.LE, half)
        }
        val distances = (0..1).flatMap { id ->
            listOf(
                ExactLinear.of(intArrayOf(Term.ofRealVar(id), Term.ofRealVar(2)),
                    listOf(BigFraction.ONE, BigFraction.MINUS_ONE), LinearOp.LE, half),
                ExactLinear.of(intArrayOf(Term.ofRealVar(id), Term.ofRealVar(2)),
                    listOf(BigFraction.MINUS_ONE, BigFraction.MINUS_ONE), LinearOp.LE, half.negated()),
            )
        }
        val model = Problem(0, intBounds = IntBounds.fromModelBounds(LongArray(2), LongArray(2) { 1 }, null, null),
            factors = (links + coherence + distances).toTypedArray(), numRealVars = 3,
            realLower = DoubleArray(3), realUpper = doubleArrayOf(1.0, 1.0, 0.5))
        val objective = LinearObjective(intCoefficients = LongArray(2) { -1 },
            realCoefficients = doubleArrayOf(0.0, 0.0, 8.0))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            ExecutionControl.Never))
        val seed = Sample(booleanArrayOf(), LongArray(2), doubleArrayOf(0.0, 0.0, 0.5),
            exactReals = List(2) { BigFraction.ZERO } + half)
        val offered = if (advisory) seed.copy(exactReals = null) else seed
        val improved = Sample(booleanArrayOf(), LongArray(2) { 1 }, doubleArrayOf(1.0, 1.0, 0.5),
            exactReals = List(2) { BigFraction.ONE } + half)
        val verifier = SourceEvidenceVerifier(source.withLinearAuthority())
        assertEquals(SourceEvidenceCheck.Checked, verifier.verify(SourceEvidence(source.scope,
            SourceClaim.Witness(seed), SourceDerivation.SourceWitness)))
        assertEquals(BigFraction.ofLong(4), objective.evaluateExact(seed))
        assertEquals(SourceEvidenceCheck.Checked, verifier.verify(SourceEvidence(source.scope,
            SourceClaim.Witness(improved), SourceDerivation.SourceWitness)))
        assertEquals(BigFraction.ofLong(2), objective.evaluateExact(improved))
        val records = mutableListOf<JointRepairObservation>()

        JointLinearRepair(model, source, objective, TheoryParams(maxFlips = 3, sourceLpOperations = 4),
            boundOrdered = boundOrdered, external = { offered }, incumbentProximity = true,
            incumbentCompletionCheck = { sample, _, token ->
                verifier.verify(SourceEvidence(source.scope, SourceClaim.Witness(sample),
                    SourceDerivation.SourceWitness), token) == SourceEvidenceCheck.Checked
            }, onObservation = records::add).use { repair ->
            repair.search(adaptive = false).use { search ->
                var done = false
                repeat(128) { if (!done) done = search.advance(ExecutionControl.Never, 5_000) }

                val diagnostic = "lp=${repair.stats.smt.sourceLp} " +
                    repair.stats.smt.completeSearchStages?.filterKeys {
                        "CutLifecycle" !in it && ("Proximity" in it || "Primal" in it)
                    }
                val result = assertIs<MinimizeResult.WithSample>(
                    search.minimizeResult(TerminationReason.BudgetExhausted), diagnostic)
                assertEquals(BigFraction.ofLong(2), objective.evaluateExact(result.sample), diagnostic)
                assertTrue(repair.isProximityDiscovery(result.sample))
                assertTrue(repair.stats.smt.sourceLp.operations <= 4L)
                assertEquals(SourceEvidenceCheck.Checked, verifier.verify(SourceEvidence(source.scope,
                    SourceClaim.Witness(result.sample), SourceDerivation.SourceWitness)))
                val stages = assertNotNull(repair.stats.smt.completeSearchStages)
                val receipt = "jointRepairPrivateQuery1_"
                val query = assertNotNull(stages[receipt + "Query"])
                val publication = records.single { it.phase == "publication" && it.query == query }
                assertEquals("checked", publication.disposition)
                assertEquals(publication.proposal, stages[receipt + "Proposal"])
                assertEquals(1L, stages[receipt + "PublicationChecked"])
                assertTrue((stages[receipt + "OfferAccepted"] ?: 0L) > 0L)
                assertEquals(2L, stages[receipt + "PinWidth"])
                assertEquals(0L, stages[receipt + "FixedIntegerMask"])
                assertTrue((stages[receipt + "FirstSearchWork"] ?: 0L) > 0L)
            }
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "advisory={0}, boundOrdered={1}")
        fun cases() = listOf(arrayOf(false, false), arrayOf(true, false), arrayOf(true, true))
    }
}
