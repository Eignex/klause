package com.eignex.klause.portfolio

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.InstructionSlicedSearch
import com.eignex.klause.solver.ReseedGuard
import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.evidence.quadraticEngineInvocation
import com.eignex.klause.solver.incumbent.CompletedEnginePublication
import com.eignex.klause.solver.incumbent.EngineInvocation
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.prepareExecutionPlan
import com.eignex.klause.solver.pipeline.preparePolynomialExecutionPlan
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PortfolioWorkerTest {
    @Test
    fun `objective coordinate search should preserve instruction slices and preparation continuation`() {
        val point = MinimizeResult.Optimal(Sample(booleanArrayOf(), longArrayOf(3)), 3.0)
        val tracking = TrackingResumableSearch(point)
        val allowances = mutableListOf<Long>()
        val child = object : ResumableSearch by tracking, InstructionSlicedSearch, ReseedGuard {
            override val canReseed = false
            override val canContinuePreparation = true
            override fun runInstructionSlice(
                global: Cancellation,
                sliceMillis: Long,
                sliceInstructions: Long,
                onIncumbent: (MinimizeResult.WithSample) -> Unit,
            ): MinimizeResult {
                allowances += sliceInstructions
                onIncumbent(point)
                return point
            }
        }
        val worker = PortfolioWorker.ofMinimize("private", 0, true, resumable = { child }) { _, _, _, _ ->
            sequenceOf(point)
        }.objectiveCoordinateProposals(ModelIdentity.of(Any())) {
            MinimizeResult.BestFound(it, 6.0, TerminationReason.SearchExhausted)
        }
        val handle = assertNotNull(worker.newResumableSearch { 0.0 })
        val points = mutableListOf<MinimizeResult.WithSample>()

        val terminal = assertIs<InstructionSlicedSearch>(handle).runInstructionSlice(
            Cancellation.Never, 1L, 17L, points::add,
        )

        assertEquals(listOf(17L), allowances)
        assertEquals(listOf(6.0), points.map { it.objective })
        assertIs<MinimizeResult.BestFound>(terminal)
        val guard = assertIs<ReseedGuard>(handle)
        assertFalse(guard.canReseed)
        assertTrue(guard.canContinuePreparation)
        handle.close()
        assertEquals(1, tracking.closes)
    }

    @Test
    fun `objective coordinate proposals should not promote private terminal proofs`() {
        val sample = Sample(booleanArrayOf(), longArrayOf(3))
        val results = listOf(
            MinimizeResult.Optimal(sample, 3.0),
            MinimizeResult.Unbounded(sample, 3.0, emptyList()),
            MinimizeResult.Infeasible(),
        )
        for (result in results) {
            val child = TrackingResumableSearch(result, result as? MinimizeResult.WithSample)
            val native = PortfolioWorker.ofMinimize("private", 0, resumable = { child }) { _, _, _, _ ->
                sequenceOf(result)
            }
            val worker = native.objectiveCoordinateProposals(ModelIdentity.of(Any())) {
                MinimizeResult.BestFound(it, 6.0, TerminationReason.SearchExhausted)
            }
            val points = mutableListOf<MinimizeResult.WithSample>()

            val terminal = assertNotNull(worker.newResumableSearch { 0.0 }).use {
                it.runSlice(Cancellation.Never, 1L, points::add)
            }

            if (result.assignment == null) {
                assertIs<MinimizeResult.Unknown>(terminal)
                assertTrue(points.isEmpty())
            } else {
                assertEquals(6.0, assertIs<MinimizeResult.BestFound>(terminal).objective)
                assertEquals(listOf(6.0), points.map { it.objective })
            }
            assertFalse(worker.permitsSourceProofs)
            assertEquals(1, child.closes)
        }
    }

    @Test
    fun `rejected objective coordinate proposals should not stream incumbents`() {
        val point = MinimizeResult.Optimal(Sample(booleanArrayOf(), longArrayOf(0)), 0.0)
        val child = TrackingResumableSearch(point, point)
        val worker = PortfolioWorker.ofMinimize("private", 0, resumable = { child }) { _, _, _, _ ->
            sequenceOf(point)
        }.objectiveCoordinateProposals(ModelIdentity.of(Any())) { null }
        val points = mutableListOf<MinimizeResult.WithSample>()

        val terminal = assertNotNull(worker.newResumableSearch { 0.0 }).use {
            it.runSlice(Cancellation.Never, 1L, 1L, points::add)
        }

        assertIs<MinimizeResult.Unknown>(terminal)
        assertTrue(points.isEmpty())
        assertEquals(1, child.closes)
    }

    @Test
    fun `sharing should keep the containing invocation separate from a stronger worker`() {
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
    fun `a worker should not replace its containing sharing invocation`() {
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
    fun `a bound worker should not replace its model or objective identity`() {
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
    fun `a foreign source binding should leave the worker unbound`() {
        val source = Problem(0, 0, emptyArray(), emptyArray())
        val model = ModelIdentity.of(source)
        val worker = PortfolioWorker.ofSolve("fixture", 0) { _, _ -> SolveResult.Unsat() }.bindEvidence(model)
        val foreign = Problem(0, 0, emptyArray(), emptyArray())
        val invocation = EngineInvocation(foreign.prepareExecutionPlan(), ModelIdentity.of(foreign))

        assertFailsWith<IllegalArgumentException> { worker.bindSource(SourceWorkerBinding(invocation)) }

        assertNull(worker.sourceBinding)
    }

    @Test
    fun `repeated source binding should retain witness only authority`() {
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
    fun `sharing should reject another prepared invocation without equating conditions`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 5)), emptyArray())
        val model = ModelIdentity.of(source)
        val producer = EngineInvocation(source.prepareExecutionPlan(), model)
        val foreign = EngineInvocation(source.prepareExecutionPlan(), model)

        assertFailsWith<IllegalArgumentException> { SourceWorkerBinding(producer, sharingInvocation = foreign) }
    }

    @Test
    fun `source binding should reject a changed objective identity`() {
        val source = Problem(0, 0, emptyArray(), emptyArray())
        val invocation = EngineInvocation(source.prepareExecutionPlan(), ModelIdentity.of(source))

        assertFailsWith<IllegalArgumentException> {
            SourceWorkerBinding(ModelIdentity.of(source, Any()), invocation)
        }
    }

    @Test
    fun `low level workers should retain unbound caller trusted execution`() {
        val source = Problem(0, 0, emptyArray(), emptyArray())
        val worker = PortfolioWorker.of("fixture", 0, BacktrackSolver(source.bake()).session(), BacktrackParams())

        val result = Portfolio.thompson(listOf(worker)).use { it.solve() }

        assertTrue(result is SolveResult.Sat)
        assertNull(worker.sourceBinding)
    }

    @Test
    fun `quadratic binding should reject a foreign source behind matching producer labels`() {
        val source = Problem(0, 0, emptyArray(), emptyArray())
        val foreign = Problem(0, 0, emptyArray(), emptyArray())
        val objective = QuadraticObjectiveSpec(emptyList())
        val plan = source.preparePolynomialExecutionPlan().withObjective(objective)
        val producer = requireNotNull(
            foreign.preparePolynomialExecutionPlan().quadraticEngineInvocation(
                objective,
                ObjectiveSense.MINIMIZE,
                Assumptions.None,
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            SourceWorkerBinding(
                ModelIdentity.of(foreign, objective),
                preparedPlan = plan,
                quadraticInvocation = producer,
            )
        }
    }

    @Test
    fun `quadratic binding should reject a changed source objective direction`() {
        val source = Problem(0, 0, emptyArray(), emptyArray())
        val objective = QuadraticObjectiveSpec(emptyList())
        val plan = source.preparePolynomialExecutionPlan().withObjective(objective)
        val producer = requireNotNull(
            plan.quadraticEngineInvocation(
                objective,
                ObjectiveSense.MAXIMIZE,
                Assumptions.None,
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            SourceWorkerBinding(
                ModelIdentity.of(source, objective),
                preparedPlan = plan,
                quadraticInvocation = producer,
            )
        }
    }
}
