package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.count.ApproxCountConfig
import com.eignex.klause.count.ExactCountConfig
import com.eignex.klause.count.SampleQuality
import com.eignex.klause.count.SamplingConfig
import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.Power
import com.eignex.klause.factor.arithmetic.RealFiniteDomain
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.factor.table.RealElement
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.ir.Term
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.util.numbers.BigFraction
import org.junit.BeforeClass
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class PreparedFiniteRealOperationsTest(private val relation: String, private val operation: ExecutionOperation) {
    companion object {
        @JvmStatic
        @BeforeClass
        fun initializeRuntime() = FiniteTranscendentalWorkerTest.initializeRuntime()

        @JvmStatic
        @Parameterized.Parameters(name = "{0} {1}")
        fun cases(): List<Array<Any>> = listOf("membership", "empty-membership", "element", "product",
            "affine-power", "exact-row").flatMap { relation ->
            listOf(ExecutionOperation.ENUMERATE, ExecutionOperation.EXACT_COUNT, ExecutionOperation.APPROXIMATE_COUNT,
                ExecutionOperation.SAMPLE, ExecutionOperation.UNIFORM_SAMPLE).map { arrayOf(relation, it) }
        }
    }

    @Test
    fun `projected finite operations should retain required real completion`() {
        val factor: Factor = when (relation) {
            "membership" -> RealFiniteDomain(0, doubleArrayOf(0.0, 1.0))
            "empty-membership" -> RealFiniteDomain(0, doubleArrayOf())
            "element" -> RealElement(0, 0, doubleArrayOf(0.0, 1.0), indexOffset = 0)
            "product" -> RealProduct(0, 0, 0, 1.0, 1.0)
            "affine-power" -> Power(Power.Coordinate.Real(SourceRealId(0)), 1,
                Power.Coordinate.Real(SourceRealId(1)))
            "exact-row" -> ExactLinear.of(intArrayOf(Term.ofRealVar(0), Term.ofIntVar(0)),
                listOf(BigFraction.ONE, BigFraction.MINUS_ONE), LinearOp.EQ, BigFraction.ZERO)
            else -> error(relation)
        }
        val reals = if (relation == "affine-power") 2 else 1
        val source = Problem(1, 1, arrayOf(IntDomain(0, 1)), arrayOf(factor), numRealVars = reals,
            realLower = DoubleArray(reals) { if (relation == "product") 1.0 else 0.0 },
            realUpper = DoubleArray(reals) { 1.0 })
        val plan = source.prepareExecutionPlan(preferFinite = true)
        for (engine in FiniteEngine.entries) assertTrue(plan.capabilities.admission(engine, operation).runnable)
        val solver = BacktrackSolver(source.bake())
        val expected = when (relation) {
            "empty-membership" -> 0L
            "product" -> 2L
            else -> 4L
        }

        when (operation) {
            ExecutionOperation.EXACT_COUNT -> {
                val count = solver.exactCount(ExactCountConfig()).last()
                assertTrue(count.exact)
                assertEquals(expected, count.lower)
                assertEquals(expected, count.upper)
            }
            ExecutionOperation.APPROXIMATE_COUNT -> {
                val count = solver.approximateCount(ApproxCountConfig(seed = 0))
                assertTrue(count.exact)
                assertEquals(expected, count.lower)
                assertEquals(expected, count.upper)
            }
            else -> {
                val samples = when (operation) {
                    ExecutionOperation.ENUMERATE -> solver.enumerate().toList()
                    ExecutionOperation.SAMPLE -> solver.samples(BacktrackParams(randomSeed = 0)).take(3).toList()
                    ExecutionOperation.UNIFORM_SAMPLE -> solver.samples(SamplingConfig(
                        quality = SampleQuality.ACCURATE, seed = 0), BacktrackParams()).take(3).toList()
                    else -> error(operation)
                }
                if (operation == ExecutionOperation.ENUMERATE) {
                    assertEquals(expected, samples.map { it.boolValue(0) to it.intValue(0) }.toSet().size.toLong())
                    assertEquals(expected, samples.size.toLong())
                }
                assertEquals(expected == 0L, samples.isEmpty())
                for (sample in samples) checkCompletion(sample, reals)
            }
        }
    }

    private fun checkCompletion(sample: Sample, reals: Int) {
        assertEquals(reals, sample.numRealVars)
        val value = sample.approximateRealValue(0)
        assertTrue(value in 0.0..1.0)
        when (relation) {
            "membership" -> assertTrue(value == 0.0 || value == 1.0)
            "element", "exact-row" -> assertEquals(sample.intValue(0).toDouble(), value)
            "product" -> {
                assertEquals(1L, sample.intValue(0))
                assertEquals(1.0, value)
            }
            "affine-power" -> assertEquals(value, sample.approximateRealValue(1))
        }
    }
}
