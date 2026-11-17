package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.codec.factorCodecSamples
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.objective.LinearObjective
import org.junit.BeforeClass
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class PreparedFiniteFactorOperationsTest(
    private val name: String,
    private val factor: Factor,
    private val engine: FiniteEngine,
) {
    companion object {
        @JvmStatic
        @BeforeClass
        fun initializeRuntime() = FiniteTranscendentalWorkerTest.initializeRuntime()

        @JvmStatic
        @Parameterized.Parameters(name = "{0} {2}")
        fun cases(): List<Array<Any>> = factorCodecSamples().filter { it.second.variables.reals.isEmpty() }
            .flatMapIndexed { index, (key, factor) ->
                FiniteEngine.entries.map { arrayOf("$key-$index", factor, it) }
            }
    }

    @Test
    fun `native finite factors should retain their projected operation surface`() {
        val source = Problem(6, 6, Array(6) { IntDomain(-10, 10) }, arrayOf(factor))
        val plan = source.prepareExecutionPlan(preferFinite = true)
            .withObjective(LinearObjective(intCoefficients = longArrayOf(1)))

        for (operation in ExecutionOperation.entries) {
            val admission = plan.capabilities.admission(engine, operation)
            assertTrue(admission.runnable, "$name $engine $operation")
        }
    }
}
