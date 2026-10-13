package com.eignex.klause.portfolio

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.incumbent.CompletedEnginePublication
import com.eignex.klause.solver.incumbent.EngineInvocation
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.prepareExecutionPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class PortfolioWorkerTest {
    @Test
    fun `sharing keeps the containing invocation separate from a stronger worker`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 5)), emptyArray())
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val model = ModelIdentity.of(source, objective)
        val plan = source.prepareExecutionPlan(objective)
        val containing = EngineInvocation(plan, model, objective)
        val stronger = EngineInvocation(plan, model, objective, sourceAssumptions = Assumptions(ints = mapOf(0 to 3L)))
        val bounds = SharedObjectiveBound(identity = model)
        val worker = PortfolioWorker.ofSolve("fixture", 0) { _, _ -> SolveResult.Unsat() }
            .bindEvidence(model).bindInvocation(stronger)
        worker.sharedPools = SharedPools(null, null, bounds = bounds, identity = model)

        worker.bindSharingInvocation(containing)
        bounds.publish(CompletedEnginePublication.ObjectiveBound(stronger, 3.0))

        assertSame(stronger, worker.evidenceInvocation)
        assertEquals(Double.NEGATIVE_INFINITY, bounds.current())
    }

    @Test
    fun `a worker cannot replace its containing sharing invocation`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 5)), emptyArray())
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val model = ModelIdentity.of(source, objective)
        val plan = source.prepareExecutionPlan(objective)
        val containing = EngineInvocation(plan, model, objective)
        val stronger = EngineInvocation(plan, model, objective, sourceAssumptions = Assumptions(ints = mapOf(0 to 3L)))
        val worker = PortfolioWorker.ofSolve("fixture", 0) { _, _ -> SolveResult.Unsat() }.bindEvidence(model)
        worker.sharedPools = SharedPools(null, null, bounds = SharedObjectiveBound(identity = model), identity = model)
        worker.bindSharingInvocation(containing)

        assertFailsWith<IllegalArgumentException> { worker.bindSharingInvocation(stronger) }
    }

    @Test
    fun `a bound worker cannot replace its model or objective identity`() {
        val source = Any()
        val objective = Any()
        val identity = ModelIdentity.of(source, objective)
        for (foreign in listOf(ModelIdentity.of(Any(), objective), ModelIdentity.of(source, Any()))) {
            val worker = PortfolioWorker.ofSolve("fixture", 0) { _, _ -> SolveResult.Unsat() }
                .bindEvidence(identity)

            assertFailsWith<IllegalArgumentException> { worker.bindEvidence(foreign) }
        }
    }
}
