package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceCondition
import com.eignex.klause.solver.evidence.SourceCoordinate
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

class LinearTerminalAcceptanceTest {
    private val real = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
    private val scope = SourceScope(SourceRevision("terminal-source", "exact-affine"),
        objective = SourceObjective("cost", ObjectiveSense.MINIMIZE))
    private val source = SourceProofContext(scope, setOf(real), mapOf("positive" to SourcePremise(
        SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.ZERO, strict = true))),
        mapOf(real to BigFraction.ONE))

    @Test
    fun `a checked strict limit is accepted in its original invocation`() {
        val result = OpenTheoryPipeline.optimizeLinear(source)

        val checked = result.verifyTerminal(source, Cancellation.Never)

        assertIs<Verification.Accepted<Unit, Unit>>(checked)
    }

    @Test
    fun `a restricted invocation cannot terminate its weaker parent`() {
        val restricted = SourceProofContext(scope.copy(condition = SourceCondition(
            lower = mapOf(real to BigFraction.ONE))), source.coordinates, source.premises, source.objective)
        val result = OpenTheoryPipeline.optimizeLinear(restricted)

        val checked = result.verifyTerminal(source, Cancellation.Never)

        assertIs<Verification.Rejected>(checked)
    }

    @Test
    fun `a changed objective identity cannot reuse a terminal`() {
        val changed = SourceProofContext(scope.copy(objective = SourceObjective(
            "another-cost", ObjectiveSense.MINIMIZE)),
            source.coordinates, source.premises, source.objective)
        val result = OpenTheoryPipeline.optimizeLinear(source)

        val checked = result.verifyTerminal(changed, Cancellation.Never)

        assertIs<Verification.Rejected>(checked)
    }

    @Test
    fun `cancelled terminal verification withholds acceptance`() {
        val result = OpenTheoryPipeline.optimizeLinear(source)

        val checked = result.verifyTerminal(source, Cancellation { true })

        assertIs<Verification.Indeterminate>(checked)
    }

    @Test
    fun `a conditional refutation cannot terminate a feasible parent`() {
        val restricted = SourceProofContext(scope.copy(condition = SourceCondition(
            upper = mapOf(real to BigFraction.ZERO))), source.coordinates, source.premises, source.objective)
        val result = OpenTheoryPipeline.optimizeLinear(restricted)

        val checked = result.verifyTerminal(source, Cancellation.Never)

        assertIs<Verification.Rejected>(checked)
    }
}
