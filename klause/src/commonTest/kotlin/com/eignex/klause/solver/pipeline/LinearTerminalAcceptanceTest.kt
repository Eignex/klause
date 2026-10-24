package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.optimization.withLinearAuthority
import com.eignex.klause.simplex.exact.BigFraction
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
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class LinearTerminalAcceptanceTest {
    private val real = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
    private val scope = SourceScope(
        SourceRevision("terminal-source", "exact-affine"),
        objective = SourceObjective("cost", ObjectiveSense.MINIMIZE),
    )
    private val source = SourceProofContext(
        scope,
        setOf(real),
        mapOf(
            "positive" to SourcePremise(
                SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.ZERO, strict = true),
            ),
        ),
        mapOf(real to BigFraction.ONE),
    )

    @Test
    fun `typed terminal replay should honor one configured verifier allowance`() {
        val result = OpenTheoryPipeline.optimizeLinear(source)
        val terminal = assertNotNull(result.terminal)
        val captured = source.withLinearAuthority()
        val sufficient = listOf(1, 2, 4, 8, 16, 32, 64, 128, 256).first { steps ->
            SourceEvidenceVerifier(captured, maximumSteps = steps).verify(terminal) == SourceEvidenceCheck.Checked
        }

        for (steps in listOf(1, sufficient)) {
            val checked = result.verifyTerminal(source, Cancellation.Never,
                SourceEvidenceVerifier(captured, maximumSteps = steps))

            if (steps == sufficient) assertIs<Verification.Accepted<Unit, Unit>>(checked)
            else assertIs<Verification.Indeterminate>(checked)
        }
    }

    @Test
    fun `a checked strict limit should be accepted in its original invocation`() {
        val result = OpenTheoryPipeline.optimizeLinear(source)

        val checked = result.verifyTerminal(source, Cancellation.Never)

        assertIs<Verification.Accepted<Unit, Unit>>(checked)
    }

    @Test
    fun `a restricted invocation should not terminate its weaker parent`() {
        val restricted = SourceProofContext(
            scope.copy(
                condition = SourceCondition(
                    lower = mapOf(real to BigFraction.ONE),
                ),
            ),
            source.coordinates,
            source.premises,
            source.objective,
        )
        val result = OpenTheoryPipeline.optimizeLinear(restricted)

        val checked = result.verifyTerminal(source, Cancellation.Never)

        assertIs<Verification.Rejected>(checked)
    }

    @Test
    fun `a changed objective identity should not reuse a terminal`() {
        val changed = SourceProofContext(
            scope.copy(
                objective = SourceObjective(
                    "another-cost",
                    ObjectiveSense.MINIMIZE,
                ),
            ),
            source.coordinates,
            source.premises,
            source.objective,
        )
        val result = OpenTheoryPipeline.optimizeLinear(source)

        val checked = result.verifyTerminal(changed, Cancellation.Never)

        assertIs<Verification.Rejected>(checked)
    }

    @Test
    fun `cancelled terminal verification should withhold acceptance`() {
        val result = OpenTheoryPipeline.optimizeLinear(source)

        val checked = result.verifyTerminal(source, Cancellation { true })

        assertIs<Verification.Indeterminate>(checked)
    }

    @Test
    fun `a conditional refutation should not terminate a feasible parent`() {
        val restricted = SourceProofContext(
            scope.copy(
                condition = SourceCondition(
                    upper = mapOf(real to BigFraction.ZERO),
                ),
            ),
            source.coordinates,
            source.premises,
            source.objective,
        )
        val result = OpenTheoryPipeline.optimizeLinear(restricted)

        val checked = result.verifyTerminal(source, Cancellation.Never)

        assertIs<Verification.Rejected>(checked)
    }
}
