package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.portfolio.PortfolioWorker
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.evidence.QuadraticEngineDerivation
import com.eignex.klause.solver.evidence.quadraticEngineInvocation
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.QuadraticCertificate
import com.eignex.klause.solver.result.QuadraticClaim
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class QuadraticPortfolioInvocationTest {
    @Test
    fun `conditional source refutation cannot finish a weaker quadratic invocation`() {
        val source = Problem(1, 0, emptyArray(), arrayOf(Clause(intArrayOf(Lit.make(0, true)))))
        val objective = QuadraticObjectiveSpec(emptyList())
        val plan = source.preparePolynomialExecutionPlan().withObjective(objective)
        val identity = ModelIdentity.of(source, objective)
        val conditional = requireNotNull(plan.quadraticEngineInvocation(objective, ObjectiveSense.MINIMIZE,
            Assumptions(bools = mapOf(0 to false))))
        val certificate = QuadraticCertificate.engine(identity, conditional, QuadraticClaim.Infeasible,
            QuadraticEngineDerivation.Branches(emptyList()))
        val terminal = QuadraticOptimizationResult.Infeasible(certificate, ObjectiveSense.MINIMIZE, SolveStats.EMPTY)
        val params = PolynomialTheoryParams()
        val native = QuadraticNativeScope(params)
        var entered = 0

        val result = QuadraticPortfolioInvocation(plan, objective, ObjectiveSense.MINIMIZE, params, native,
            FiniteEngine.BACKTRACK, 1, Assumptions.None).execute { arm, _, _, _ ->
            PortfolioWorker.ofMinimize("conditional", arm, resumable = {
                object : QuadraticPortfolioSearch {
                    private var done = false
                    override val completion: QuadraticOptimizationResult? get() = if (done) terminal else null
                    override val isDone: Boolean get() = done
                    override val stats: SolveStats get() = SolveStats.EMPTY
                    override fun runSlice(global: Cancellation, sliceMillis: Long, sliceNodes: Long,
                        onIncumbent: (MinimizeResult.WithSample) -> Unit): MinimizeResult {
                        entered++
                        done = true
                        return MinimizeResult.Unknown(TerminationReason.BudgetExhausted)
                    }
                }
            }) { _, _, _, _ -> error("fixture runs by slice") }.bindEvidence(identity)
        }

        assertIs<QuadraticOptimizationResult.Unknown>(result)
        assertEquals(1, entered)
        assertEquals(1L, result.stats.portfolio.arms.single().faults)
    }
}
