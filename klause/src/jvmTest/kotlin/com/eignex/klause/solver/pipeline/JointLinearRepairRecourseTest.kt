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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JointLinearRepairRecourseTest : RuntimeInitializedTest() {
    @Test
    fun `private exchanges should follow continuous recourse between binary decisions`() {
        val links = (0..3).map { id ->
            ExactLinear.of(intArrayOf(Term.ofIntVar(id), Term.ofRealVar(id)),
                listOf(BigFraction.ONE, BigFraction.MINUS_ONE), LinearOp.EQ, BigFraction.ZERO)
        }
        val balances = listOf(0 to 1, 2 to 3, 0 to 2, 1 to 3).map { (a, b) ->
            ExactLinear.of(intArrayOf(Term.ofRealVar(a), Term.ofRealVar(b)),
                listOf(BigFraction.ONE, BigFraction.ONE), LinearOp.EQ, BigFraction.ONE)
        }
        val model = Problem(0, intBounds = IntBounds.fromModelBounds(LongArray(4), LongArray(4) { 1 }, null, null),
            factors = (links + balances).toTypedArray(), numRealVars = 4,
            realLower = DoubleArray(4), realUpper = DoubleArray(4) { 1.0 })
        val objective = LinearObjective(intCoefficients = longArrayOf(2, 1, 1, 2))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            ExecutionControl.Never))
        val exact = listOf(BigFraction.ONE, BigFraction.ZERO, BigFraction.ZERO, BigFraction.ONE)
        val seed = Sample(booleanArrayOf(), longArrayOf(1, 0, 0, 1), doubleArrayOf(1.0, 0.0, 0.0, 1.0),
            exactReals = exact)
        val verifier = SourceEvidenceVerifier(source.withLinearAuthority())

        JointLinearRepair(model, source, objective, TheoryParams(maxFlips = 3, sourceLpOperations = 4),
            external = { seed }, incumbentProximity = true, incumbentCompletionCheck = { sample, _, token ->
                verifier.verify(SourceEvidence(source.scope, SourceClaim.Witness(sample),
                    SourceDerivation.SourceWitness), token) == SourceEvidenceCheck.Checked
            }).use { repair ->
            repair.search(adaptive = false).use { search ->
                var done = false
                repeat(128) { if (!done) done = search.advance(ExecutionControl.Never, 5_000) }

                val result = assertIs<MinimizeResult.WithSample>(
                    search.minimizeResult(TerminationReason.BudgetExhausted))
                assertEquals(BigFraction.ofLong(2), objective.evaluateExact(result.sample))
                assertTrue(repair.isProximityDiscovery(result.sample))
                assertEquals(SourceEvidenceCheck.Checked, verifier.verify(SourceEvidence(source.scope,
                    SourceClaim.Witness(result.sample), SourceDerivation.SourceWitness)))
            }
        }
    }
}
