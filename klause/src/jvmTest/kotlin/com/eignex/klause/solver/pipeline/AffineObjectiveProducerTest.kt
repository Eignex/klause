package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.evidence.QuadraticEngineSource
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.quadraticEngineInvocation
import com.eignex.klause.solver.evidence.sourceCondition
import com.eignex.klause.solver.evidence.sourceResultCapture
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.toQuadraticObjectiveSpec
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class AffineObjectiveProducerTest(private val engine: FiniteEngine, private val sense: ObjectiveSense) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} {1}")
        fun variants(): List<Array<Any>> = listOf(FiniteEngine.BACKTRACK, FiniteEngine.FIXED, FiniteEngine.MIXED)
            .flatMap { engine -> ObjectiveSense.entries.map { arrayOf<Any>(engine, it) } }
    }

    @Test
    fun `completed affine objective retains its actual original invocation`() {
        val source = Problem(1, 1, arrayOf(IntDomain(0, 1)), arrayOf(ContinuousProduct(0, 0, 1)),
            numRealVars = 2, realLower = doubleArrayOf(-1.0, 0.0), realUpper = doubleArrayOf(1.0, 1.0))
        val objective = LinearObjective(constant = 7, realCoefficients = doubleArrayOf(0.0, 1.0))
        val pins = Assumptions.None.withBool(0, true).withInt(0, 1)
        val request = source.pipelineRequest()
        val params = PolynomialTheoryParams(maximumWork = 200000, strategy = PolynomialStrategyParams(
            assumptions = pins, localSearch = LocalSearchParams(maxFlips = 1, randomSeed = 17), alnsIterations = 1,
        ))
        val revision = SourceRevision("affine-producer", "klause-ir-exact-v1")
        val capture = assertNotNull(request.affineOperation(objective, sense).preparedPlan
            .sourceResultCapture(revision, pins, "cost"))
        val result = assertIs<QuadraticOptimizationResult.Optimal>(
            OpenTheoryPipeline.optimize(request, objective, params, engine, sense).result)
        val invocation = assertNotNull(result.certificate.invocation)

        assertSame(source, assertIs<QuadraticEngineSource.ExactIr>(invocation.source).problem)
        assertSame(objective, invocation.affineObjective)
        assertEquals(objective.toQuadraticObjectiveSpec(), invocation.objective)
        assertEquals(sense, invocation.sense)
        assertEquals(pins.sourceCondition(), invocation.condition)
        assertTrue(capture.accepts(invocation))
    }

    @Test
    fun `source recapture preserves the actual producer derivation`() {
        val source = Problem(1, 1, arrayOf(IntDomain(0, 1)), arrayOf(ContinuousProduct(0, 0, 1)),
            numRealVars = 2, realLower = doubleArrayOf(-1.0, 0.0), realUpper = doubleArrayOf(1.0, 1.0))
        val objective = LinearObjective(constant = 7, realCoefficients = doubleArrayOf(0.0, 1.0))
        val pins = Assumptions.None.withBool(0, true).withInt(0, 1)
        val request = source.pipelineRequest()
        val params = PolynomialTheoryParams(maximumWork = 200000, strategy = PolynomialStrategyParams(
            assumptions = pins, localSearch = LocalSearchParams(maxFlips = 1, randomSeed = 17), alnsIterations = 1,
        ))
        val revision = SourceRevision("affine-producer", "klause-ir-exact-v1")
        val capture = assertNotNull(request.affineOperation(objective, sense).preparedPlan
            .sourceResultCapture(revision, pins, "cost"))
        val result = assertIs<QuadraticOptimizationResult.Optimal>(
            OpenTheoryPipeline.optimize(request, objective, params, engine, sense).result)
        val invocation = assertNotNull(result.certificate.invocation)

        val captured = assertIs<QuadraticOptimizationResult.Optimal>(capture.quadratic(result, "source-operation"))

        assertNotNull(result.certificate.derivation)
        assertSame(result.certificate.derivation, captured.certificate.derivation)
        assertSame(invocation, captured.certificate.invocation)
        assertEquals(capture.context.scope, assertNotNull(captured.evidence).scope)
    }

    @Test
    fun `equal shaped replacement affine objective cannot recapture an old producer`() {
        val source = Problem(1, 1, arrayOf(IntDomain(0, 1)), arrayOf(ContinuousProduct(0, 0, 1)),
            numRealVars = 2, realLower = doubleArrayOf(-1.0, 0.0), realUpper = doubleArrayOf(1.0, 1.0))
        val objective = LinearObjective(constant = 7, realCoefficients = doubleArrayOf(0.0, 1.0))
        val pins = Assumptions.None.withBool(0, true).withInt(0, 1)
        val request = source.pipelineRequest()
        val params = PolynomialTheoryParams(maximumWork = 200000, strategy = PolynomialStrategyParams(
            assumptions = pins, localSearch = LocalSearchParams(maxFlips = 1, randomSeed = 17), alnsIterations = 1,
        ))
        val revision = SourceRevision("affine-producer", "klause-ir-exact-v1")
        val capture = assertNotNull(request.affineOperation(objective, sense).preparedPlan
            .sourceResultCapture(revision, pins, "cost"))
        val result = assertIs<QuadraticOptimizationResult.Optimal>(
            OpenTheoryPipeline.optimize(request, objective, params, engine, sense).result)
        val invocation = assertNotNull(result.certificate.invocation)

        val replacement = assertNotNull(request.affineOperation(objective.copy(), sense).preparedPlan
            .sourceResultCapture(revision, pins, "cost"))

        assertFalse(replacement.accepts(invocation))
        assertSame(result, replacement.quadratic(result, "replacement-operation"))
    }

    @Test
    fun `weaker caller assumptions cannot recapture an old producer`() {
        val source = Problem(1, 1, arrayOf(IntDomain(0, 1)), arrayOf(ContinuousProduct(0, 0, 1)),
            numRealVars = 2, realLower = doubleArrayOf(-1.0, 0.0), realUpper = doubleArrayOf(1.0, 1.0))
        val objective = LinearObjective(constant = 7, realCoefficients = doubleArrayOf(0.0, 1.0))
        val pins = Assumptions.None.withBool(0, true).withInt(0, 1)
        val request = source.pipelineRequest()
        val params = PolynomialTheoryParams(maximumWork = 200000, strategy = PolynomialStrategyParams(
            assumptions = pins, localSearch = LocalSearchParams(maxFlips = 1, randomSeed = 17), alnsIterations = 1,
        ))
        val revision = SourceRevision("affine-producer", "klause-ir-exact-v1")
        val capture = assertNotNull(request.affineOperation(objective, sense).preparedPlan
            .sourceResultCapture(revision, pins, "cost"))
        val result = assertIs<QuadraticOptimizationResult.Optimal>(
            OpenTheoryPipeline.optimize(request, objective, params, engine, sense).result)
        val invocation = assertNotNull(result.certificate.invocation)

        val weaker = assertNotNull(request.affineOperation(objective, sense).preparedPlan
            .sourceResultCapture(revision, objectiveIdentity = "cost"))

        assertFalse(weaker.accepts(invocation))
        assertSame(result, weaker.quadratic(result, "weaker-operation"))
    }

    @Test
    fun `changed affine constant cannot recapture an old producer`() {
        val source = Problem(1, 1, arrayOf(IntDomain(0, 1)), arrayOf(ContinuousProduct(0, 0, 1)),
            numRealVars = 2, realLower = doubleArrayOf(-1.0, 0.0), realUpper = doubleArrayOf(1.0, 1.0))
        val objective = LinearObjective(constant = 7, realCoefficients = doubleArrayOf(0.0, 1.0))
        val pins = Assumptions.None.withBool(0, true).withInt(0, 1)
        val request = source.pipelineRequest()
        val params = PolynomialTheoryParams(maximumWork = 200000, strategy = PolynomialStrategyParams(
            assumptions = pins, localSearch = LocalSearchParams(maxFlips = 1, randomSeed = 17), alnsIterations = 1,
        ))
        val revision = SourceRevision("affine-producer", "klause-ir-exact-v1")
        val capture = assertNotNull(request.affineOperation(objective, sense).preparedPlan
            .sourceResultCapture(revision, pins, "cost"))
        val result = assertIs<QuadraticOptimizationResult.Optimal>(
            OpenTheoryPipeline.optimize(request, objective, params, engine, sense).result)
        val invocation = assertNotNull(result.certificate.invocation)

        val replacement = assertNotNull(request.affineOperation(objective.copy(constant = 13), sense).preparedPlan
            .sourceResultCapture(revision, pins, "cost"))

        assertFalse(replacement.accepts(invocation))
        assertSame(result, replacement.quadratic(result, "changed-operation"))
    }

    @Test
    fun `mismatched normalized objective cannot bind an affine operation`() {
        val source = Problem(1, 1, arrayOf(IntDomain(0, 1)), arrayOf(ContinuousProduct(0, 0, 1)),
            numRealVars = 2, realLower = doubleArrayOf(-1.0, 0.0), realUpper = doubleArrayOf(1.0, 1.0))
        val objective = LinearObjective(constant = 7, realCoefficients = doubleArrayOf(0.0, 1.0))
        val pins = Assumptions.None.withBool(0, true).withInt(0, 1)
        val request = source.pipelineRequest()
        val revision = SourceRevision("affine-producer", "klause-ir-exact-v1")
        val capture = assertNotNull(request.affineOperation(objective, sense).preparedPlan
            .sourceResultCapture(revision, pins, "cost"))

        val differentView = request.affineOperation(objective, sense).preparedPlan.quadraticEngineInvocation(
            objective.copy(constant = 13).toQuadraticObjectiveSpec(), sense, pins,
        )

        assertTrue(differentView == null || !capture.accepts(differentView))
    }

    @Test
    fun `opposite objective direction cannot bind an affine operation`() {
        val source = Problem(1, 1, arrayOf(IntDomain(0, 1)), arrayOf(ContinuousProduct(0, 0, 1)),
            numRealVars = 2, realLower = doubleArrayOf(-1.0, 0.0), realUpper = doubleArrayOf(1.0, 1.0))
        val objective = LinearObjective(constant = 7, realCoefficients = doubleArrayOf(0.0, 1.0))
        val pins = Assumptions.None.withBool(0, true).withInt(0, 1)
        val request = source.pipelineRequest()
        val revision = SourceRevision("affine-producer", "klause-ir-exact-v1")
        val capture = assertNotNull(request.affineOperation(objective, sense).preparedPlan
            .sourceResultCapture(revision, pins, "cost"))

        val opposite = if (sense == ObjectiveSense.MINIMIZE) ObjectiveSense.MAXIMIZE else ObjectiveSense.MINIMIZE
        val differentView = request.affineOperation(objective, sense).preparedPlan.quadraticEngineInvocation(
            objective.toQuadraticObjectiveSpec(), opposite, pins,
        )

        assertTrue(differentView == null || !capture.accepts(differentView))
    }

    @Test
    fun `unrepresentable affine metadata supplies no original affine binding`() {
        val source = Problem(1, 1, arrayOf(IntDomain(0, 1)), arrayOf(ContinuousProduct(0, 0, 1)),
            numRealVars = 2, realLower = doubleArrayOf(-1.0, 0.0), realUpper = doubleArrayOf(1.0, 1.0))
        val objective = LinearObjective(constant = 7, realCoefficients = doubleArrayOf(0.0, 1.0))
        val pins = Assumptions.None.withBool(0, true).withInt(0, 1)
        val request = source.pipelineRequest()
        val invalid = objective.copy(realCoefficients = doubleArrayOf(0.0, Double.NaN))

        val invocation = assertNotNull(request.affineOperation(invalid, sense).preparedPlan.quadraticEngineInvocation(
            objective.toQuadraticObjectiveSpec(), sense, pins,
        ))

        assertEquals(null, invocation.affineObjective)
    }

}
