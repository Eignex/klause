package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.Power
import com.eignex.klause.factor.arithmetic.RealArrayMinMax
import com.eignex.klause.factor.arithmetic.RealFiniteDomain
import com.eignex.klause.factor.codec.factorCodecSamples
import com.eignex.klause.factor.codec.sampleWide
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.ir.Term
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BigFraction
import org.junit.BeforeClass
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class PreparedFiniteRealSupportTest(
    private val name: String,
    private val factor: Factor,
    private val engine: FiniteEngine,
    private val operations: Set<ExecutionOperation>,
) {
    companion object {
        @JvmStatic
        @BeforeClass
        fun initializeRuntime() = FiniteTranscendentalWorkerTest.initializeRuntime()

        @JvmStatic
        @Parameterized.Parameters(name = "{0} {2}")
        fun cases(): List<Array<Any>> {
            val finite = ExecutionOperation.entries.toSet()
            val polynomial = setOf(ExecutionOperation.SATISFY, ExecutionOperation.OPTIMIZE)
            val samples = factorCodecSamples().filter { it.second.variables.reals.isNotEmpty() }
                .mapIndexed { index, (key, factor) ->
                    val operations = when (key) {
                        "exactLinear", "realProduct", "realAbs", "realFiniteDomain", "realElement" -> finite
                        "continuousProduct" -> if ((factor as ContinuousProduct).finiteChoices != null) finite
                        else polynomial
                        "power" -> polynomial
                        else -> emptySet()
                    }
                    Triple("$key-$index", factor, operations)
                }
            val variants = listOf(
                Triple("non-strict-row", Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0),
                    doubleArrayOf(1.0), LinearOp.LE, 1.0), finite),
                Triple("affine-real-power", Power(Power.Coordinate.Real(SourceRealId(0)), 1,
                    Power.Coordinate.Real(SourceRealId(1))), finite),
                Triple("leaf-extremum", RealArrayMinMax(0, intArrayOf(1, 2), false).leafEnforcedCopy(), finite),
                Triple("empty-real-membership", RealFiniteDomain(0, doubleArrayOf()), finite),
                Triple("wide-strict-row", ExactLinear.of(intArrayOf(Term.ofRealVar(0)),
                    listOf(BigFraction.of(sampleWide, BIG_ONE)), LinearOp.LE, BigFraction.ONE,
                    strict = true), emptySet()),
                Triple("reified-exact-equality", ExactLinear.of(intArrayOf(Term.ofRealVar(0)),
                    listOf(BigFraction.ONE), LinearOp.EQ, BigFraction.ZERO, activator = 0), emptySet()),
            )
            return (samples + variants).flatMap { (name, factor, operations) ->
                FiniteEngine.entries.map { arrayOf(name, factor, it, operations) }
            }
        }
    }

    @Test
    fun `finite preference should preserve explicit real completion prerequisites`() {
        val source = Problem(6, 6, Array(6) { IntDomain(-10, 10) }, arrayOf(factor), numRealVars = 4,
            realLower = DoubleArray(4) { -5.0 }, realUpper = DoubleArray(4) { 5.0 })
        val plan = source.prepareExecutionPlan(preferFinite = true)
            .withObjective(LinearObjective(intCoefficients = longArrayOf(1)))

        for (operation in ExecutionOperation.entries) {
            val admission = plan.capabilities.admission(engine, operation)
            assertEquals(operation in operations, admission.runnable, "$name $engine $operation")
            if (!admission.runnable) assertTrue(admission.declines.isNotEmpty())
        }
    }
}
