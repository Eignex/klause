package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.count.ApproxCountConfig
import com.eignex.klause.count.ExactCountConfig
import com.eignex.klause.count.SampleQuality
import com.eignex.klause.count.SamplingConfig
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.codec.factorCodecSamples
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.ScoringProjectionProvider
import com.eignex.klause.lp.RelaxationProjectionProvider
import com.eignex.klause.propagation.NoPropagator
import com.eignex.klause.propagation.PropagationProjection
import com.eignex.klause.propagation.PropagationProjectionProvider
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.AssignmentCheckCapability
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.PropagationCapability
import com.eignex.klause.solver.Sample
import com.eignex.klause.theory.Encoder
import com.eignex.klause.theory.EncoderProvider
import com.eignex.klause.theory.EncoderSupport
import org.junit.BeforeClass
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class PreparedProjectedOperationsTest(
    private val name: String,
    private val factor: Factor,
    private val value: Int,
    private val mechanisms: String,
) {
    companion object {
        @JvmStatic
        @BeforeClass
        fun initializeRuntime() = FiniteTranscendentalWorkerTest.initializeRuntime()

        @JvmStatic
        @Parameterized.Parameters(name = "{0} value={2} {3}")
        fun cases(): List<Array<Any>> = factorCodecSamples().filter { it.second.variables.reals.isEmpty() }
            .flatMapIndexed { index, (key, factor) ->
                listOf(0, 1).flatMap { value ->
                    listOf("native", "no-scoring", "no-arithmetic", "inert-propagation")
                        .map { arrayOf("$key-$index", factor, value, it) }
                }
            }
    }

    private val source get() = Problem(6, 6, Array(6) {
        if (it == 0) IntDomain(0, 1) else IntDomain(value.toLong(), value.toLong())
    },
        (listOf(factor) + (1 until 6).map { Clause(intArrayOf(Lit.make(it, value != 0))) }).toTypedArray())

    private val providers get() = when (mechanisms) {
        "native" -> FactorProjectionProviders.Default
        "no-scoring" -> FactorProjectionProviders(scoring = ScoringProjectionProvider { null })
        "no-arithmetic" -> FactorProjectionProviders(relaxation = RelaxationProjectionProvider { null },
            encoder = EncoderProvider { _, _ -> Encoder(EncoderSupport.UNSUPPORTED) })
        "inert-propagation" -> FactorProjectionProviders(propagation = PropagationProjectionProvider {
            PropagationProjection(PropagationCapability.INERT, AssignmentCheckCapability.SOURCE_CERTIFICATION,
                "The original relation checks each complete finite point.") { NoPropagator }
        })
        else -> error(mechanisms)
    }

    private fun expected(source: Problem): Set<Pair<List<Boolean>, List<Long>>> =
        BacktrackSolver(source.bake()).enumerate().map(::key).toSet()

    private fun key(sample: Sample): Pair<List<Boolean>, List<Long>> =
        (0 until 6).map { sample.boolValue(it) } to (0 until 6).map { sample.intValue(it) }

    @Test
    fun `enumeration should preserve the complete native solution set with selected mechanisms`() {
        val source = source
        val plan = source.prepareExecutionPlan(providers, preferFinite = true)
        assertTrue(plan.capabilities.admission(FiniteEngine.BACKTRACK, ExecutionOperation.ENUMERATE).runnable)

        val actual = BacktrackSolver(source.bake(providers)).enumerate().map(::key).toList()

        assertEquals(expected(source), actual.toSet(), "$name $mechanisms")
        assertEquals(actual.size, actual.toSet().size)
    }

    @Test
    fun `exact counting should preserve the native projection independently of scoring and relaxation`() {
        val source = source
        val plan = source.prepareExecutionPlan(providers, preferFinite = true)
        for (engine in FiniteEngine.entries) {
            assertTrue(plan.capabilities.admission(engine, ExecutionOperation.EXACT_COUNT).runnable)
        }

        val count = BacktrackSolver(source.bake(providers)).exactCount(
            ExactCountConfig(samplingSet = intArrayOf(0), intSamplingSet = intArrayOf(0))).last()

        assertTrue(count.exact, "$name $mechanisms")
        assertEquals(expected(source).size.toLong(), count.lower)
        assertEquals(count.lower, count.upper)
    }

    @Test
    fun `approximate counting should retain exact small cells under selected mechanisms`() {
        val source = source
        val plan = source.prepareExecutionPlan(providers, preferFinite = true)
        for (engine in FiniteEngine.entries) {
            assertTrue(plan.capabilities.admission(engine, ExecutionOperation.APPROXIMATE_COUNT).runnable)
        }

        val count = BacktrackSolver(source.bake(providers)).approximateCount(
            ApproxCountConfig(samplingSet = intArrayOf(0), intSamplingSet = intArrayOf(0), seed = 0))

        assertTrue(count.exact, "$name $mechanisms")
        assertEquals(expected(source).size.toLong(), count.lower)
        assertEquals(count.lower, count.upper)
    }

    @Test
    fun `uniform sampling should publish only native source witnesses under selected mechanisms`() {
        val source = source
        val plan = source.prepareExecutionPlan(providers, preferFinite = true)
        for (engine in FiniteEngine.entries) {
            assertTrue(plan.capabilities.admission(engine, ExecutionOperation.UNIFORM_SAMPLE).runnable)
        }

        val samples = BacktrackSolver(source.bake(providers)).samples(SamplingConfig(
            quality = SampleQuality.ACCURATE, samplingSet = intArrayOf(0), intSamplingSet = intArrayOf(0), seed = 0),
            BacktrackParams()).take(3).map(::key).toList()

        val expected = expected(source)
        assertEquals(expected.isEmpty(), samples.isEmpty(), "$name $mechanisms")
        assertTrue(samples.all { it in expected }, "$name $mechanisms")
    }

    @Test
    fun `native sampling should retain source checks under selected mechanisms`() {
        val source = source
        assertTrue(source.prepareExecutionPlan(providers, preferFinite = true)
            .capabilities.admission(FiniteEngine.BACKTRACK, ExecutionOperation.SAMPLE).runnable)

        val samples = BacktrackSolver(source.bake(providers)).samples(BacktrackParams(randomSeed = 0))
            .take(3).map(::key).toList()

        val expected = expected(source)
        assertEquals(expected.isEmpty(), samples.isEmpty(), "$name $mechanisms")
        assertTrue(samples.all { it in expected }, "$name $mechanisms")
    }
}
