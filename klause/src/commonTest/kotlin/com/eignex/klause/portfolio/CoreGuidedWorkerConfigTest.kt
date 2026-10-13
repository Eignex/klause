package com.eignex.klause.portfolio

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.incumbent.CandidateVerifier
import com.eignex.klause.solver.incumbent.CompletedEngineResult
import com.eignex.klause.solver.incumbent.EngineInvocation
import com.eignex.klause.solver.incumbent.EvidenceKind
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.prepareExecutionPlan
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CoreGuidedWorkerConfigTest {
    @Test
    fun `rounded wide completion cannot certify a scalar source bound`() {
        val source = Problem(1, 0, emptyArray(), arrayOf(Clause(intArrayOf(Lit.make(0, true)))))
        val problem = source.bake()
        val objective = LinearObjective(longArrayOf(9_007_199_254_740_995L))
        val model = ModelIdentity.of(problem, objective)
        val invocation = EngineInvocation(source.prepareExecutionPlan(objective), model, objective)
        val worker = CoreGuidedWorkerConfig().materialize(
            problem, 0, 0, 3L, 1.0, objective, null, null, null, null,
        ).bindInvocation(invocation)
        val verifier = PortfolioEvidence(model, CandidateVerifier.trusting()).bindInvocation(invocation)
        val handle = requireNotNull(worker.newResumableSearch({ Double.POSITIVE_INFINITY }, Cancellation.Never))
        val result = assertIs<MinimizeResult.Optimal>(handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) {})

        val checked = verifier.proof(worker, EvidenceKind.Bound, CompletedEngineResult.Minimize(invocation, result))

        assertEquals("9007199254740995", objective.evaluateExact(result.sample).num.toString())
        assertIs<Verification.Indeterminate>(checked)
        handle.close()
        worker.close()
    }

    @Test
    fun `exact scalar completion certifies its source bound`() {
        val source = Problem(1, 0, emptyArray(), arrayOf(Clause(intArrayOf(Lit.make(0, true)))))
        val problem = source.bake()
        val objective = LinearObjective(longArrayOf(5))
        val model = ModelIdentity.of(problem, objective)
        val invocation = EngineInvocation(source.prepareExecutionPlan(objective), model, objective)
        val worker = CoreGuidedWorkerConfig().materialize(
            problem, 0, 0, 3L, 1.0, objective, null, null, null, null,
        ).bindInvocation(invocation)
        val verifier = PortfolioEvidence(model, CandidateVerifier.trusting()).bindInvocation(invocation)
        val handle = requireNotNull(worker.newResumableSearch({ Double.POSITIVE_INFINITY }, Cancellation.Never))
        val result = assertIs<MinimizeResult.Optimal>(handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) {})

        val checked = verifier.proof(worker, EvidenceKind.Bound, CompletedEngineResult.Minimize(invocation, result))

        assertIs<Verification.Accepted<*, *>>(checked)
        handle.close()
        worker.close()
    }
}
