package com.eignex.klause.solver

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.ExecutionOption
import com.eignex.klause.solver.pipeline.ExecutionOptions
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.PipelineConfigException
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class SourceStrategyEngineTest(private val engine: FiniteEngine) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun engines(): List<FiniteEngine> = FiniteEngine.entries
    }

    @Test
    fun `explicit source engines should retain the original Boolean constraint`() {
        val source = Problem(1, 0, emptyArray(), arrayOf(Clause(intArrayOf(Lit.make(0, true)))))

        val result = BacktrackSolver.source(source).solve(engine)

        assertTrue(assertIs<OpenTheoryResult.Sat>(result).assignment.boolValue(0))
    }

    @Test
    fun `explicit source optimization should retain an open integer lower bound`() {
        val source = Problem(0, 1, arrayOf(IntDomain(-3, 3)),
            arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 42)),
            openIntLo = booleanArrayOf(true), openIntHi = booleanArrayOf(true))
        val options = ExecutionOptions.parse(listOf(
            "seed=17", "node-limit=100", "nia-candidates=32", "nia-moves=1", "max-flips=1", "alns-iterations=1",
        ))

        val execution = BacktrackSolver.source(source).optimize(
            LinearObjective(intCoefficients = longArrayOf(1)), engine, options,
        )

        val result = assertIs<QuadraticOptimizationResult.WithIncumbent>(execution.result)
        assertEquals(BigFraction.ofLong(42), result.incumbent.value.rational)
        assertEquals("42", result.incumbent.sample.exactIntValue(0).toString())
    }

    @Test
    fun `explicit source engines should reject conflicting containing node allowances`() {
        val source = Problem(1, 0, emptyArray(), emptyArray())
        val budget = NodeBudget(1)

        assertFailsWith<PipelineConfigException> {
            BacktrackSolver.source(source).solve(
                engine, ExecutionOptions(ExecutionOption.nodeLimit(2)), nodeBudget = budget,
            )
        }
        assertEquals(0L, budget.spent)
    }

    @Test
    fun `explicit source lane counts should preserve fixed engine admission`() {
        val source = Problem(1, 0, emptyArray(), arrayOf(Clause(intArrayOf(Lit.make(0, true)))))
        val strategy = BacktrackSolver.source(source)

        if (engine == FiniteEngine.FIXED) {
            assertFailsWith<PipelineConfigException> { strategy.solve(engine, cores = 2) }
        } else {
            val result = strategy.solve(engine, cores = 2)
            assertTrue(assertIs<OpenTheoryResult.Sat>(result).assignment.boolValue(0))
        }
    }
}
