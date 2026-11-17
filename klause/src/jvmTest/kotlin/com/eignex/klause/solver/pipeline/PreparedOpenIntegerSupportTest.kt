package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.DivMod
import com.eignex.klause.factor.codec.factorCodecSamples
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import org.junit.BeforeClass
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class PreparedOpenIntegerSupportTest(private val name: String, private val factor: Factor) {
    companion object {
        @JvmStatic
        @BeforeClass
        fun initializeRuntime() = FiniteTranscendentalWorkerTest.initializeRuntime()

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = factorCodecSamples().filter { (key, factor) ->
            key == "product" || (key == "power" && factor.variables.reals.isEmpty()) ||
                (factor is DivMod && factor.constantDivisor == null)
        }.map { (key, factor) -> arrayOf(key, factor) }
    }

    @Test
    fun `open nonlinear integer factors should select their complete polynomial owner`() {
        val source = Problem(6, 6, Array(6) { IntDomain(-1, 1) }, arrayOf(factor),
            openIntLo = BooleanArray(6) { true }, openIntHi = BooleanArray(6) { true })

        val plan = source.prepareExecutionPlan()

        assertEquals(PreparedExecutionKind.NONLINEAR_INTEGER, plan.kind, name)
        assertEquals(ComponentResponsibility.COMPLETION, plan.factorOwner(0).responsibility)
        for (engine in FiniteEngine.entries) {
            assertTrue(plan.capabilities.admission(engine).runnable, "$name $engine")
        }
        for (id in 0 until source.numIntVars) {
            assertFalse(source.intBounds.hasLower(id))
            assertFalse(source.intBounds.hasUpper(id))
        }
    }

    @Test
    fun `source execution should dispatch a purely integer nonlinear factor`() {
        val count = factor.intVars.max() + 1
        val source = Problem(0, count, Array(count) { IntDomain(-1, 1) }, arrayOf(factor),
            openIntLo = BooleanArray(count) { true }, openIntHi = BooleanArray(count) { true })

        val result = BacktrackSolver.source(source).solve()

        assertIs<OpenTheoryResult.Sat>(result, name)
    }
}
