package com.eignex.klause.solver.pipeline

import com.eignex.klause.RuntimeInitializedTest
import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
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
class JointLinearRepairObjectiveTest(private val alias: Boolean) : RuntimeInitializedTest() {
    @Test
    fun `private assignment exchanges should retain objective definition checks`() {
        val costs = LongArray(33) { if (it == 0 || it == 3) 2 else 1 }
        val pairs = listOf(0 to 1, 2 to 3, 0 to 2, 1 to 3)
        val factors: List<Factor> = pairs.map { (a, b) -> Linear(intArrayOf(1, 1), intArrayOf(a, b), LinearOp.EQ, 1) }
        val definition = ExactLinear.of(IntArray(34) { if (it == 33) Term.ofRealVar(0) else Term.ofIntVar(it) },
            costs.map(BigFraction::ofLong) + BigFraction.MINUS_ONE, LinearOp.EQ, BigFraction.ZERO)
        val model = Problem(0,
            intBounds = IntBounds.fromModelBounds(LongArray(33), LongArray(33) { 1 }, null, null),
            factors = if (alias) factors.toTypedArray() + definition else factors.toTypedArray(),
            numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(if (alias) 35.0 else 0.0))
        val objective = if (alias) LinearObjective(realCoefficients = doubleArrayOf(1.0))
        else LinearObjective(intCoefficients = costs)
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            ExecutionControl.Never))
        val real = if (alias) BigFraction.ofLong(4) else BigFraction.ZERO
        val seed = Sample(booleanArrayOf(), LongArray(33) { if (it == 0 || it == 3) 1 else 0 },
            doubleArrayOf(real.toDouble()), exactReals = listOf(real))
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

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "alias={0}")
        fun cases() = listOf(false, true).map { arrayOf(it) }
    }
}
