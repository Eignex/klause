package com.eignex.klause.portfolio

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.evidence.quadraticEngineInvocation
import com.eignex.klause.solver.incumbent.CompletedEnginePublication
import com.eignex.klause.solver.incumbent.EngineInvocation
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.prepareExecutionPlan
import com.eignex.klause.solver.pipeline.preparePolynomialExecutionPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

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
        worker.sharedPools = SharedPools(null, null, bounds = bounds, identity = model)

        worker.bindSource(SourceWorkerBinding(stronger, sharingInvocation = containing))
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
        worker.bindSource(SourceWorkerBinding(containing, sharingInvocation = containing))

        assertFailsWith<IllegalArgumentException> {
            worker.bindSource(SourceWorkerBinding(containing, sharingInvocation = stronger))
        }
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

    @Test
    fun `a foreign source binding leaves the worker unbound`() {
        val source = Problem(0, 0, emptyArray(), emptyArray())
        val model = ModelIdentity.of(source)
        val worker = PortfolioWorker.ofSolve("fixture", 0) { _, _ -> SolveResult.Unsat() }.bindEvidence(model)
        val foreign = Problem(0, 0, emptyArray(), emptyArray())
        val invocation = EngineInvocation(foreign.prepareExecutionPlan(), ModelIdentity.of(foreign))

        assertFailsWith<IllegalArgumentException> { worker.bindSource(SourceWorkerBinding(invocation)) }

        assertNull(worker.sourceBinding)
    }

    @Test
    fun `repeated source binding retains witness only authority`() {
        val source = Problem(0, 0, emptyArray(), emptyArray())
        val invocation = EngineInvocation(source.prepareExecutionPlan(), ModelIdentity.of(source))
        val worker = PortfolioWorker.ofSolve("fixture", 0) { _, _ -> SolveResult.Unsat() }
        val binding = SourceWorkerBinding(invocation, provesResults = false)
        worker.bindSource(binding)

        worker.bindSource(SourceWorkerBinding(invocation, provesResults = false)).bindEvidence(invocation.model)

        assertSame(binding, worker.sourceBinding)
        assertFalse(worker.permitsSourceProofs)
        assertFailsWith<IllegalArgumentException> { worker.bindSource(SourceWorkerBinding(invocation)) }
    }

    @Test
    fun `sharing rejects another prepared invocation without equating conditions`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 5)), emptyArray())
        val model = ModelIdentity.of(source)
        val producer = EngineInvocation(source.prepareExecutionPlan(), model)
        val foreign = EngineInvocation(source.prepareExecutionPlan(), model)

        assertFailsWith<IllegalArgumentException> { SourceWorkerBinding(producer, sharingInvocation = foreign) }
    }

    @Test
    fun `source binding rejects a changed objective identity`() {
        val source = Problem(0, 0, emptyArray(), emptyArray())
        val invocation = EngineInvocation(source.prepareExecutionPlan(), ModelIdentity.of(source))

        assertFailsWith<IllegalArgumentException> {
            SourceWorkerBinding(ModelIdentity.of(source, Any()), invocation)
        }
    }

    @Test
    fun `low level workers retain unbound caller trusted execution`() {
        val source = Problem(0, 0, emptyArray(), emptyArray())
        val worker = PortfolioWorker.of("fixture", 0, BacktrackSolver(source.bake()).session(), BacktrackParams())

        val result = Portfolio.thompson(listOf(worker)).use { it.solve() }

        assertTrue(result is SolveResult.Sat)
        assertNull(worker.sourceBinding)
    }

    @Test
    fun `quadratic binding rejects a foreign source behind matching producer labels`() {
        val source = Problem(0, 0, emptyArray(), emptyArray())
        val foreign = Problem(0, 0, emptyArray(), emptyArray())
        val objective = QuadraticObjectiveSpec(emptyList())
        val plan = source.preparePolynomialExecutionPlan().withObjective(objective)
        val producer = requireNotNull(foreign.preparePolynomialExecutionPlan().quadraticEngineInvocation(
            objective, ObjectiveSense.MINIMIZE, Assumptions.None))

        assertFailsWith<IllegalArgumentException> {
            SourceWorkerBinding(ModelIdentity.of(foreign, objective), preparedPlan = plan, quadraticInvocation = producer)
        }
    }

    @Test
    fun `quadratic binding rejects a changed source objective direction`() {
        val source = Problem(0, 0, emptyArray(), emptyArray())
        val objective = QuadraticObjectiveSpec(emptyList())
        val plan = source.preparePolynomialExecutionPlan().withObjective(objective)
        val producer = requireNotNull(plan.quadraticEngineInvocation(
            objective, ObjectiveSense.MAXIMIZE, Assumptions.None))

        assertFailsWith<IllegalArgumentException> {
            SourceWorkerBinding(ModelIdentity.of(source, objective), preparedPlan = plan, quadraticInvocation = producer)
        }
    }

}
