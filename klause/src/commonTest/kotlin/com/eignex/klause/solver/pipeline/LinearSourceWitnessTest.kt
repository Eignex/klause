package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourcePremise
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertIs

class LinearSourceWitnessTest {
    private val real = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
    private val scope = SourceScope(SourceRevision("source-witness", "exact-affine"),
        objective = SourceObjective("cost", ObjectiveSense.MINIMIZE))

    @Test
    fun `an IR witness attestation cannot admit a strict source closure endpoint`() {
        val weak = Problem(0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))
        val objective = LinearObjective(realCoefficients = doubleArrayOf(1.0))
        val result = RationalTheoryDescent(weak, objective, TheoryParams()).use {
            it.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { _ -> }
        }
        val sample = assertIs<MinimizeResult.Optimal>(result).sample
        val source = SourceProofContext(scope, setOf(real), mapOf("positive" to SourcePremise(
            SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.ZERO, strict = true))),
            mapOf(real to BigFraction.ONE))

        val checked = source.linearWitnessVerifier(Cancellation.Never).verify(Candidate(sample, 0.0))

        assertIs<Verification.Rejected>(checked)
    }

    @Test
    fun `source Boolean values select the guarded witness requirements`() {
        val bool = SourceCoordinate(SourceCoordinate.Kind.Boolean, 0)
        val source = SourceProofContext(scope, setOf(bool, real), mapOf("positive" to SourcePremise(
            SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.ZERO, strict = true),
            mapOf(0 to false))), mapOf(real to BigFraction.ONE))
        for (value in listOf(false, true)) {
            val sample = Sample(booleanArrayOf(value), longArrayOf(), doubleArrayOf(0.0),
                exactReals = listOf(BigFraction.ZERO))

            val checked = source.linearWitnessVerifier(Cancellation.Never).verify(Candidate(sample, 0.0))

            if (value) assertIs<Verification.Accepted<Sample, Double?>>(checked)
            else assertIs<Verification.Rejected>(checked)
        }
    }

    @Test
    fun `cancelled original witness checking stays indeterminate`() {
        val source = SourceProofContext(scope, emptySet(), emptyMap(), emptyMap())
        val sample = Sample(booleanArrayOf(), longArrayOf())

        val checked = source.linearWitnessVerifier(Cancellation { true }).verify(Candidate(sample, 0.0))

        assertIs<Verification.Indeterminate>(checked)
    }
}
