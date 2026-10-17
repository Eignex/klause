package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.bounding.LpEmphasis
import com.eignex.klause.lp.bounding.LpTechnique
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

@RunWith(Parameterized::class)
class SourceExecutionPolicyEngineTest(private val engine: FiniteEngine) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun engines(): List<FiniteEngine> = FiniteEngine.entries
    }

    @Test
    fun `finite policy defaults should follow the selected engine`() {
        val shape = FiniteSolveShape(Problem(0, 0, emptyArray(), emptyArray()), false, false, null, null, null, null)

        val request = SourceExecutionPolicy().finiteRequest(shape, engine)

        assertEquals(if (engine == FiniteEngine.FIXED) 1 else autoArms(1), request.defaultArms)
        assertEquals(if (engine == FiniteEngine.FIXED) LpEmphasis.OFF else LpEmphasis.AGGRESSIVE,
            request.lpConfig.emphasis)
        assertEquals(0L, request.randomSeed)
    }

    @Test
    fun `explicit arm settings should take precedence over application defaults`() {
        val shape = FiniteSolveShape(Problem(0, 0, emptyArray(), emptyArray()), false, false, null, null, null, null)
        val policy = SourceExecutionPolicy(portfolioArms = 7)
        val options = ExecutionOptions(ExecutionOption.arms(2))

        val request = policy.finiteRequest(shape, engine, executionOptions = options)

        assertEquals(2, request.executionOptions.reader()[ExecutionOption.arms])
        assertEquals(if (engine == FiniteEngine.FIXED) 1 else 7, request.defaultArms)
    }

    @Test
    fun `fixed policy should reject an enabled LP ceiling`() {
        val shape = FiniteSolveShape(Problem(0, 0, emptyArray(), emptyArray()), false, false, null, null, null, null)
        val policy = SourceExecutionPolicy(lpCeiling = LpConfig.AUTO)

        if (engine == FiniteEngine.FIXED) {
            assertFailsWith<PipelineConfigException> { policy.finiteRequest(shape, engine) }
        } else {
            assertEquals(LpEmphasis.DEFAULT, policy.finiteRequest(shape, engine).lpConfig.emphasis)
        }
    }

    @Test
    fun `application LP defaults should leave fixed execution without relaxation`() {
        val shape = FiniteSolveShape(Problem(0, 0, emptyArray(), emptyArray()), false, false, null, null, null, null)

        val request = SourceExecutionPolicy(portfolioLpCeiling = LpConfig.OFF).finiteRequest(shape, engine)

        assertEquals(LpEmphasis.OFF, request.lpConfig.emphasis)
        assertFalse(request.lpConfig.resolved(LpTechnique.CUTS))
    }
}
