package com.eignex.klause.solver.evidence

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SourceResultCaptureTest {
    private val model = Problem(0, 1, arrayOf(IntDomain(1, 3)), emptyArray())
    private val scope = SourceScope(SourceRevision("original-ir", "klause-ir-exact-v1"))

    @Test
    fun `ordinary completed results omit capture by default`() {
        val result = SolveResult.Sat(Sample(booleanArrayOf(), longArrayOf(1)))

        assertNull(result.evidence)
    }

    @Test
    fun `optional witness capture is carried by the completed result`() {
        val context = assertNotNull(model.sourceProofContext(scope))
        val sample = Sample(booleanArrayOf(), longArrayOf(1))
        val result = SourceResultCapture(context).solve(SolveResult.Sat(sample), "fixture-engine")

        assertEquals(SourceEvidenceCheck.Checked,
            assertNotNull(result.evidence).verify(SourceClaim.Witness(sample), context))
    }

    @Test
    fun `an uncaptured refutation retains only an engine attestation`() {
        val context = assertNotNull(model.sourceProofContext(scope))
        val result = SourceResultCapture(context).solve(SolveResult.Unsat(), "fixture-engine")
        val evidence = assertNotNull(result.evidence)

        assertIs<SourceEvidenceCheck.Unavailable>(evidence.verify(SourceClaim.Refutation, context))
        assertEquals(SourceClaim.Refutation, evidence.attestations.single().claim)
    }

    @Test
    fun `a captured original bound and witness establish an exact optimum`() {
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val scoped = scope.copy(objective = SourceObjective("x", ObjectiveSense.MINIMIZE))
        val context = assertNotNull(model.sourceProofContext(scoped, objective))
        val sample = Sample(booleanArrayOf(), longArrayOf(1))
        val proof = SourceDerivation.Optimum(SourceDerivation.SourceWitness, SourceDerivation.Premise("int/0/lower"))
        val result = SourceResultCapture(context).minimize(MinimizeResult.Optimal(sample, 1.0), objective,
            "fixture-engine", proof)
        val claim = SourceClaim.Optimal(sample, SourceValue.Rational(BigFraction.ONE))

        assertEquals(SourceEvidenceCheck.Checked, assertNotNull(result.evidence).verify(claim, context))
    }

    @Test
    fun `missing objective proof capture cannot gain coverage from an optimal result label`() {
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val scoped = scope.copy(objective = SourceObjective("x", ObjectiveSense.MINIMIZE))
        val context = assertNotNull(model.sourceProofContext(scoped, objective))
        val sample = Sample(booleanArrayOf(), longArrayOf(1))
        val result = SourceResultCapture(context).minimize(MinimizeResult.Optimal(sample, 1.0), objective,
            "fixture-engine")

        assertIs<SourceEvidenceCheck.Unavailable>(assertNotNull(result.evidence).verify(
            SourceClaim.Optimal(sample, SourceValue.Rational(BigFraction.ONE)), context,
        ))
    }

    @Test
    fun `cancelled optional objective capture preserves the completed result`() {
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val scoped = scope.copy(objective = SourceObjective("x", ObjectiveSense.MINIMIZE))
        val context = assertNotNull(model.sourceProofContext(scoped, objective))
        val result = MinimizeResult.Optimal(Sample(booleanArrayOf(), longArrayOf(1)), 1.0)

        val captured = SourceResultCapture(context).minimize(result, objective, "fixture-engine",
            cancellation = Cancellation { true })

        assertEquals(result, captured)
        assertNull(captured.evidence)
    }

}
