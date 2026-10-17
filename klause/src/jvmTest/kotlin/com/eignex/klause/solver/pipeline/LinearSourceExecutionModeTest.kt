package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.ir.LinearSourceObjective
import com.eignex.klause.ir.LinearSourceProblem
import com.eignex.klause.ir.LinearSourceTerm
import com.eignex.klause.ir.LinearSourceVariable
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.engine.LpZeroObjectivePricing
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.util.Cancellation
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@RunWith(Parameterized::class)
class LinearSourceExecutionModeTest(private val engine: FiniteEngine) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun engines(): List<FiniteEngine> = listOf(FiniteEngine.LOCAL_SEARCH, FiniteEngine.ALNS)
    }

    @Test
    fun `a numerical real source should retain heuristic execution with fixed discrete coordinates`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("x", false, SemanticRational.ONE, SemanticRational.ONE)),
            emptyList(), LinearSourceObjective(listOf(LinearSourceTerm(0, SemanticRational.ONE))),
        )
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            SourceAttachment(source, ProblemSettings()), ExecutionOperation.OPTIMIZE, engine,
        ))
        val budget = NodeBudget(32)

        val result = SourceSetup.execute(SourceExecutionRequest(invocation, finiteRequest = { shape ->
            FiniteSolveRequest(
                shape, engine, PresolveConfig.NONE, false, false, Cancellation.after(50.milliseconds),
                presolveBudget = null, cores = 1, executionOptions = ExecutionOptions(),
                randomSeed = 17, defaultArms = 1, lpConfig = LpConfig.AUTO,
                zeroObjectivePricing = LpZeroObjectivePricing.MIN_BOUND_SUPPORT, nodeBudget = budget,
                solveBudgetMillis = null, allSolutions = false, solutionCap = null,
                deadlineExceeded = { false }, onEvent = null, onPortfolioEvent = null,
            )
        }))

        val finite = assertIs<SourceExecutionResult.Finite>(result)
        val outcome = assertIs<FiniteSolveOutcome.Completed>(finite.result.outcome)
        val arms = outcome.stats.portfolio.arms
        val prefix = if (engine == FiniteEngine.ALNS) "alns/" else "ls/"
        assertTrue(arms.isNotEmpty())
        assertTrue(arms.any { it.segments > 0L })
        assertTrue(arms.all { it.label.startsWith(prefix) && !it.label.contains("exact-neighborhood") })
    }
}
