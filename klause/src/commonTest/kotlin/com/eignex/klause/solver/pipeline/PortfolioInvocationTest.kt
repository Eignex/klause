package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.Problem
import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Session
import com.eignex.klause.portfolio.ArmFamily
import com.eignex.klause.portfolio.PortfolioEvidence
import com.eignex.klause.portfolio.PortfolioWorker
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.incumbent.CandidateVerifier
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

class PortfolioInvocationTest {
    @Test
    fun `parallel construction creates seeded private slots and closes them once`() {
        val source = Problem(0, 0, emptyArray(), emptyArray())
        val plan = source.prepareExecutionPlan()
        val seeds = mutableListOf<Long>()
        val closed = mutableListOf<Int>()
        val factory = PortfolioArmFactory(plan, 0, ArmFamily.Backtrack) { slot ->
            seeds += slot.seed
            slot.own(AutoCloseable { closed += slot.index })
            PortfolioWorker.ofSolve("source", 0) { _, _ -> SolveResult.Unknown(TerminationReason.BudgetExhausted) }
        }
        val invocation = PortfolioInvocation(plan, listOf(factory),
            PortfolioInvocationControls(parallelism = 4, seed = 17),
            PortfolioEvidence(ModelIdentity.of(source), CandidateVerifier.trusting()))

        invocation.close()
        invocation.close()

        assertEquals(listOf(17L, 18L, 19L, 20L), seeds)
        assertEquals(listOf(3, 2, 1, 0), closed)
        assertEquals(4, invocation.resources.lanes)
    }

    @Test
    fun `construction failure closes private source state allocated by earlier slots`() {
        val source = Problem(0, 0, emptyArray(), emptyArray())
        val plan = source.prepareExecutionPlan()
        val foreign = Problem(0, 0, emptyArray(), emptyArray()).prepareExecutionPlan()
        var closed = 0
        val first = PortfolioArmFactory(plan, 0, ArmFamily.Backtrack) { slot ->
            slot.own(AutoCloseable { closed++ })
            PortfolioWorker.ofSolve("source", 0) { _, _ -> SolveResult.Unknown(TerminationReason.BudgetExhausted) }
        }
        val second = PortfolioArmFactory(foreign, 1, ArmFamily.LocalSearch) {
            error("foreign plan cannot allocate private state")
        }

        assertFailsWith<IllegalArgumentException> {
            PortfolioInvocation(plan, listOf(first, second), PortfolioInvocationControls(),
                PortfolioEvidence(ModelIdentity.of(source), CandidateVerifier.trusting()))
        }

        assertEquals(1, closed)
    }

    @Test
    fun `containing cancellation prevents entry into an admitted worker`() {
        val source = Problem(0, 0, emptyArray(), emptyArray())
        val plan = source.prepareExecutionPlan()
        var entered = false
        val factory = PortfolioArmFactory(plan, 0, ArmFamily.Backtrack) {
            PortfolioWorker.ofSolve("source", 0) { _, _ ->
                entered = true
                SolveResult.Unknown(TerminationReason.BudgetExhausted)
            }
        }
        val invocation = PortfolioInvocation(plan, listOf(factory),
            PortfolioInvocationControls(cancellation = Cancellation { true }),
            PortfolioEvidence(ModelIdentity.of(source), CandidateVerifier.trusting()))

        val result = invocation.use { it.solve(Cancellation.Never) }

        assertIs<SolveResult.Unknown>(result)
        assertFalse(entered)
    }

    @Test
    fun `runtime binding failure closes the materialized worker before unwinding source state`() {
        val source = Problem(0, 0, emptyArray(), emptyArray())
        val plan = source.prepareExecutionPlan()
        val delegate = BacktrackSolver(source.bake()).session()
        val closed = mutableListOf<String>()
        val session = object : Session<BacktrackParams> by delegate {
            override fun close() {
                closed += "worker"
                delegate.close()
            }
        }
        val factory = PortfolioArmFactory(plan, 0, ArmFamily.Backtrack,
            bind = { error("invalid runtime binding") }) { slot ->
            slot.own(AutoCloseable { closed += "source" })
            PortfolioWorker.of("source", 0, session, BacktrackParams())
        }

        val failure = assertFailsWith<IllegalStateException> {
            PortfolioInvocation(plan, listOf(factory), PortfolioInvocationControls(),
                PortfolioEvidence(ModelIdentity.of(source), CandidateVerifier.trusting()))
        }

        assertEquals("invalid runtime binding", failure.message)
        assertEquals(listOf("worker", "source"), closed)
    }
}
