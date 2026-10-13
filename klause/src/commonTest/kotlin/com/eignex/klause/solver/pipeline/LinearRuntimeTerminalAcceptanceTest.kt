package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.evidence.sourceProofContext
import com.eignex.klause.solver.incumbent.EngineInvocation
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class LinearRuntimeTerminalAcceptanceTest {
    private val model = Problem(0, 0, emptyArray(), arrayOf(Linear(intArrayOf(), doubleArrayOf(),
        intArrayOf(0), doubleArrayOf(1.0), LinearOp.GE, 0.0, strict = true)), numRealVars = 1,
        realLower = doubleArrayOf(Double.NEGATIVE_INFINITY), realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
    private val objective = LinearObjective(realCoefficients = doubleArrayOf(1.0))
    private val plan = model.prepareExecutionPlan(objective)
    private val source = assertNotNull(model.sourceProofContext(SourceScope(
        SourceRevision("runtime-source", "klause-ir-exact-v1"),
        objective = SourceObjective("cost", ObjectiveSense.MINIMIZE)), objective))
    private val expected = EngineInvocation(plan, ModelIdentity.of(model, objective), scope = source.scope)

    @Test
    fun `matching runtime and original source accept a checked strict limit`() {
        val result = OpenTheoryPipeline.optimizeLinear(source)

        val checked = result.verifyRuntimeTerminal(expected, expected, source, Cancellation.Never)

        assertIs<Verification.Accepted<Unit, Unit>>(checked)
    }

    @Test
    fun `a different prepared invocation cannot reuse a checked source terminal`() {
        val produced = EngineInvocation(model.prepareExecutionPlan(objective), ModelIdentity.of(model, objective),
            scope = source.scope)
        val result = OpenTheoryPipeline.optimizeLinear(source)

        val checked = result.verifyRuntimeTerminal(expected, produced, source, Cancellation.Never)

        assertIs<Verification.Rejected>(checked)
    }

    @Test
    fun `a distinct objective reference cannot reuse a checked source terminal`() {
        val other = LinearObjective(realCoefficients = doubleArrayOf(1.0))
        val produced = EngineInvocation(plan, ModelIdentity.of(model, other), other, scope = source.scope)
        val result = OpenTheoryPipeline.optimizeLinear(source)

        val checked = result.verifyRuntimeTerminal(expected, produced, source, Cancellation.Never)

        assertIs<Verification.Rejected>(checked)
    }

    @Test
    fun `a foreign model cannot reuse matching public scope labels`() {
        val other = Problem(0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val produced = EngineInvocation(plan, ModelIdentity.of(other, objective), scope = source.scope)
        val result = OpenTheoryPipeline.optimizeLinear(source)

        val checked = result.verifyRuntimeTerminal(expected, produced, source, Cancellation.Never)

        assertIs<Verification.Rejected>(checked)
    }

    @Test
    fun `missing runtime source capture withholds independent terminal acceptance`() {
        val uncaptured = EngineInvocation(plan, ModelIdentity.of(model, objective))
        val result = OpenTheoryPipeline.optimizeLinear(source)

        val checked = result.verifyRuntimeTerminal(uncaptured, uncaptured, source, Cancellation.Never)

        assertIs<Verification.Indeterminate>(checked)
    }

    @Test
    fun `matching runtime labels cannot erase execution pins from a terminal witness`() {
        val integerModel = Problem(numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0),
                Bits(1), Bits(1).also { it.set(0) }), factors = emptyArray())
        val cost = LinearObjective(intCoefficients = longArrayOf(1))
        val request = OpenTheoryRequest(integerModel, cost)
        val original = assertNotNull(request.linearAuthorityContext(Cancellation.Never))
        val runtime = EngineInvocation(request.preparedPlan, ModelIdentity.of(integerModel, cost),
            executionAssumptions = Assumptions(ints = mapOf(0 to 1L)), scope = original.scope)
        val result = OpenTheoryPipeline.optimizeLinear(original)

        val checked = result.verifyRuntimeTerminal(runtime, runtime, original, Cancellation.Never)

        assertIs<Verification.Rejected>(checked)
    }
}
