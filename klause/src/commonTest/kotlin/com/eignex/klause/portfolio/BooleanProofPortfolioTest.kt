package com.eignex.klause.portfolio

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.backtrack.selector.IndomainMin
import com.eignex.klause.backtrack.selector.InputOrder
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.cut.CutExchange
import com.eignex.klause.lp.cut.CutSharing
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.ProblemProfile
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.evidence.SourceCondition
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.incumbent.EngineInvocation
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.pipeline.prepareExecutionPlan
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.search.BooleanProofContext
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BooleanProofPortfolioTest {
    private val context = BooleanProofContext(SourceScope(SourceRevision("fixture", "exact-cnf")), "identity:0")
    private val problem = Problem(5, 0, emptyArray(), Array(8) { mask ->
        Clause(IntArray(3) { bit -> Lit.make(bit + 2, mask and (1 shl bit) != 0) })
    }).bake()

    @Test
    fun `a separate baked generation should decline despite identical clause objects`() {
        val foreign = Problem(5, 0, emptyArray(), problem.factors).bake()
        val invocation = EngineInvocation(foreign.prepareExecutionPlan(), ModelIdentity.of(problem))
        val scenario = PortfolioScenario(1, 2, Kind.CSP, EngineMix.BACKTRACK,
            btPool = listOf { BacktrackCatalog.byLabel("first-fail") })
        val factories = PortfolioBuilder.factories(problem, scenario, null, null, null, null,
            ProblemProfile.of(problem, false))
        val workers = List(2) { slot ->
            factories[slot % factories.size].materialize(slot, 1L, null)
                .bindSource(SourceWorkerBinding(invocation))
        }
        try {
            var decline: String? = null
            assertNull(BooleanProofPortfolio.forWorkers(workers, probeFirst = true) { decline = it })

            assertEquals("source-plan-or-objective-ineligible", decline)
        } finally {
            workers.forEach { it.close() }
        }
    }

    @Test
    fun `native first fail workers should preserve full source task coverage`() {
        val scenario = PortfolioScenario(1, 2, Kind.CSP, EngineMix.BACKTRACK,
            btPool = listOf { BacktrackCatalog.byLabel("first-fail") })
        val workers = PortfolioBuilder.build(problem, scenario)
        try {
            var identities = 0
            val admitted = BooleanProofPortfolio.forWorkers(workers, probeFirst = true, nativeOnly = true,
                newIdentity = { "task-authority-${++identities}" })
            assertNotNull(admitted).use { tasks ->
                assertEquals(0, identities)
                val first = tasks.open(0, Cancellation.Never)
                assertNull(first.runSlice(Cancellation.Never, Long.MAX_VALUE, 1L))
                assertEquals(2, tasks.size)
                assertEquals(1, identities)
                assertNull(first.runSlice(Cancellation.Never, Long.MAX_VALUE, 5000L))
                val second = tasks.open(1, Cancellation.Never)
                val result = second.runSlice(Cancellation.Never, Long.MAX_VALUE, 5000L)

                assertTrue(result is SolveResult.Unsat)
                assertEquals(2, tasks.completed)
                assertEquals(first.work + second.work, tasks.work)
                assertEquals(1, identities)
            }
        } finally {
            workers.forEach { it.close() }
        }
    }

    @Test
    fun `automatic task admission should decline nonnative ordered workers`() {
        val params = BacktrackParams(variableSelector = InputOrder, valueSelector = IndomainMin, nativeSat = false)
        val scenario = PortfolioScenario(1, 2, Kind.CSP, EngineMix.BACKTRACK,
            btPool = listOf { BacktrackWorkerConfig.ofParams("fixed", params) })
        val workers = PortfolioBuilder.build(problem, scenario)
        try {
            assertNull(BooleanProofPortfolio.forWorkers(workers, probeFirst = true, nativeOnly = true))
        } finally {
            workers.forEach { it.close() }
        }
    }

    @Test
    fun `a completed source probe should preserve caller pins without allocating task authority`() {
        val pins = Assumptions(bools = mapOf(0 to true))
        val params = BacktrackCatalog.byLabel("first-fail").build(0L, null).copy(assumptions = pins)
        val scenario = PortfolioScenario(1, 2, Kind.CSP, EngineMix.BACKTRACK,
            btPool = listOf { BacktrackWorkerConfig.ofParams("pinned", params) })
        val invocation = EngineInvocation(problem.prepareExecutionPlan(), ModelIdentity.of(problem),
            sourceAssumptions = pins)
        val factories = PortfolioBuilder.factories(problem, scenario, null, null, null, null,
            ProblemProfile.of(problem, false))
        val workers = List(2) { slot ->
            factories[slot % factories.size].materialize(slot, 1L, null)
                .bindSource(SourceWorkerBinding(invocation))
        }
        try {
            val admitted = BooleanProofPortfolio.forWorkers(workers, probeFirst = true,
                newIdentity = { error("completed source probe requested task authority") })
            assertNotNull(admitted).use { tasks ->
                val result = tasks.open(0, Cancellation.Never).runSlice(Cancellation.Never, Long.MAX_VALUE, 5000L)

                assertTrue(result is SolveResult.Unsat)
                assertEquals(pins.bools, assertNotNull(result.assumptionCore).bools)
                assertEquals(0, tasks.size)
            }
        } finally {
            workers.forEach { it.close() }
        }
    }

    @Test
    fun `a probe first portfolio should reject objective context before opening a probe`() {
        val objective = SourceObjective("sum-bools", ObjectiveSense.MINIMIZE)
        val scoped = context.copy(scope = context.scope.copy(objective = objective))

        assertFailsWith<IllegalArgumentException> {
            BooleanProofPortfolio(problem, scoped, listOf(0), List(2) { BacktrackParams(nativeSat = false) },
                probeFirst = true)
        }
    }

    @Test
    fun `mixed supported recipes should decline a shared task board`() {
        val ordered = BacktrackParams(variableSelector = InputOrder, valueSelector = IndomainMin, nativeSat = false)
        val scenario = PortfolioScenario(1, 2, Kind.CSP, EngineMix.BACKTRACK, btPool = listOf(
            { BacktrackWorkerConfig.ofParams("ordered", ordered) },
            { BacktrackCatalog.byLabel("first-fail") },
        ))
        val workers = PortfolioBuilder.build(problem, scenario)
        try {
            assertNull(BooleanProofPortfolio.forWorkers(workers, probeFirst = true))
        } finally {
            workers.forEach { it.close() }
        }
    }

    @Test
    fun `unsupported first fail settings should decline task sharing`() {
        val firstFail = BacktrackCatalog.byLabel("first-fail").build(0L, null)
        for (params in listOf(firstFail.copy(nativeSat = false), firstFail.copy(lubyRestartBase = 128L),
            firstFail.copy(phaseSaving = true))) {
            val scenario = PortfolioScenario(1, 2, Kind.CSP, EngineMix.BACKTRACK,
                btPool = listOf { BacktrackWorkerConfig.ofParams("changed", params) })
            val workers = PortfolioBuilder.build(problem, scenario)
            try {
                assertNull(BooleanProofPortfolio.forWorkers(workers, probeFirst = true))
            } finally {
                workers.forEach { it.close() }
            }
        }
    }

    @Test
    fun `a source probe should split a live decision beyond caller and root pins`() {
        val pinned = Problem(5, 0, emptyArray(), problem.factors + Clause(intArrayOf(Lit.make(1, true)))).bake()
        val pins = mapOf(0 to true)
        val scoped = context.copy(scope = context.scope.copy(condition = SourceCondition(bools = pins)))
        val params = List(2) { BacktrackParams(nativeSat = false, variableSelector = InputOrder,
            valueSelector = IndomainMin, assumptions = Assumptions(bools = pins)) }
        BooleanProofPortfolio(pinned, scoped, listOf(1), params, probeFirst = true).use { tasks ->
            val first = tasks.open(0, Cancellation.Never)
            assertNull(first.runSlice(Cancellation.Never, Long.MAX_VALUE, 1L))
            val root = first.stats.search.rootPropagationWork.sum
            val probe = tasks.probeWork
            assertEquals(2, Lit.variable(assertNotNull(tasks.partitionLiteral)))
            assertEquals(2, tasks.size)
            assertEquals(1, tasks.probeSegments)
            assertTrue(probe > 0L)
            assertEquals(probe, tasks.work)
            assertNull(first.runSlice(Cancellation.Never, Long.MAX_VALUE, 5000L))
            val second = tasks.open(1, Cancellation.Never)
            val result = second.runSlice(Cancellation.Never, Long.MAX_VALUE, 5000L)

            assertTrue(result is SolveResult.Unsat)
            assertEquals(pins, assertNotNull(result.assumptionCore).bools)
            assertEquals(root, first.stats.search.rootPropagationWork.sum)
            assertEquals(2, tasks.completed)
            assertEquals(first.work + second.work, tasks.work)
            assertTrue(tasks.work >= probe)
        }
    }

    @Test
    fun `a completed normal source probe should avoid a task board and peer root`() {
        val params = List(2) { BacktrackParams(nativeSat = false) }
        BooleanProofPortfolio(problem, context, listOf(0), params, probeFirst = true).use { tasks ->
            val first = tasks.open(0, Cancellation.Never)
            val result = first.runSlice(Cancellation.Never, Long.MAX_VALUE, 5000L)

            assertTrue(result is SolveResult.Unsat)
            assertEquals(0, tasks.size)
            assertNull(tasks.partitionLiteral)
            assertEquals(first.work, tasks.probeWork)
            assertEquals(first.work, tasks.work)
            assertFalse(tasks.available(1))
        }
    }

    @Test
    fun `losing an adopted probe should return its cube and retain its charges`() {
        val params = List(2) { BacktrackParams(nativeSat = false) }
        BooleanProofPortfolio(problem, context, listOf(0), params, probeFirst = true).use { tasks ->
            val first = tasks.open(0, Cancellation.Never)
            assertNull(first.runSlice(Cancellation.Never, Long.MAX_VALUE, 1L))
            assertEquals(2, tasks.size)
            first.close()
            val lostWork = first.work
            val second = tasks.open(1, Cancellation.Never)
            assertNull(second.runSlice(Cancellation.Never, Long.MAX_VALUE, 5000L))
            val result = second.runSlice(Cancellation.Never, Long.MAX_VALUE, 5000L)

            assertTrue(result is SolveResult.Unsat)
            assertEquals(2, tasks.completed)
            assertTrue(lostWork > 0L)
            assertEquals(lostWork + second.work, tasks.work)
        }
    }

    @Test
    fun `adopting a probe should preserve its exhausted containing quota`() {
        val quotaProblem = Problem(6, 0, emptyArray(), Array(16) { mask ->
            Clause(IntArray(4) { bit -> Lit.make(bit + 2, mask and (1 shl bit) != 0) })
        }).bake()
        val budget = NodeBudget(100L)
        val params = List(2) { BacktrackParams(nativeSat = false, nodeBudget = budget) }
        BooleanProofPortfolio(quotaProblem, context, listOf(0), params, probeFirst = true).use { tasks ->
            val first = tasks.open(0, Cancellation.Never)
            assertNull(first.runSlice(Cancellation.Never, Long.MAX_VALUE, 1L))
            assertEquals(2, tasks.size)
            val nodes = first.stats.search.nodes.sum
            while (!budget.exhausted()) budget.spend()
            val spent = budget.spent
            val result = first.runSlice(Cancellation.Never, Long.MAX_VALUE, 5000L)

            assertTrue(result is SolveResult.Unknown, "expected Unknown, got " + result?.let { it::class.simpleName })
            assertEquals(TerminationReason.BudgetExhausted, result.reason)
            assertEquals(spent, budget.spent)
            assertEquals(nodes, first.stats.search.nodes.sum)
            assertEquals(0, tasks.completed)
        }
    }

    @Test
    fun `a source probe should retain its original session owner through adoption`() {
        val params = BacktrackParams(variableSelector = InputOrder, valueSelector = IndomainMin, nativeSat = false)
        val scenario = PortfolioScenario(1, 2, Kind.CSP, EngineMix.BACKTRACK,
            btPool = listOf { BacktrackWorkerConfig.ofParams("fixed", params) })
        val workers = PortfolioBuilder.build(problem, scenario)
        try {
            assertNotNull(BooleanProofPortfolio.forWorkers(workers, probeFirst = true)).use { tasks ->
                val first = tasks.open(0, Cancellation.Never)
                assertNull(first.runSlice(Cancellation.Never, Long.MAX_VALUE, 1L))
                assertEquals(2, tasks.size)

                assertFailsWith<IllegalStateException> { workers[0].newResumableSolve() }
                first.close()
                assertNotNull(workers[0].newResumableSolve()).close()
            }
        } finally {
            workers.forEach { it.close() }
        }
    }

    @Test
    fun `a refuted adopted probe should preserve a feasible opposite guard`() {
        val guarded = Problem(5, 0, emptyArray(), Array(8) { mask ->
            Clause(intArrayOf(Lit.make(0, true)) +
                IntArray(3) { bit -> Lit.make(bit + 2, mask and (1 shl bit) != 0) })
        }).bake()
        for (native in listOf(false, true)) {
            val pool = SharedClausePool()
            val params = List(2) { worker -> BacktrackParams(nativeSat = native,
                variableSelector = InputOrder, valueSelector = IndomainMin,
                clauseExchange = PoolClauseExchange(pool, origin = worker).conditioned()) }
            BooleanProofPortfolio(guarded, context, listOf(0), params, probeFirst = true).use { tasks ->
                val first = tasks.open(0, Cancellation.Never)
                assertNull(first.runSlice(Cancellation.Never, Long.MAX_VALUE, 1L))
                assertEquals(Lit.make(0, false), tasks.partitionLiteral)
                assertNull(first.runSlice(Cancellation.Never, Long.MAX_VALUE, 5000L))
                val second = tasks.open(1, Cancellation.Never)
                val result = second.runSlice(Cancellation.Never, Long.MAX_VALUE, 5000L)

                assertTrue(result is SolveResult.Sat)
                assertTrue(result.assignment.boolValue(0))
                assertEquals(1, tasks.completed)
            }
        }
    }

    @Test
    fun `branched conditional refutations should preserve feasible sibling queries`() {
        val guarded = Problem(5, 0, emptyArray(), Array(8) { mask ->
            Clause(intArrayOf(Lit.make(0, true)) +
                IntArray(3) { bit -> Lit.make(bit + 2, mask and (1 shl bit) != 0) })
        }).bake()
        for (native in listOf(false, true)) {
            val pool = SharedClausePool()
            val params = List(2) { worker -> BacktrackParams(randomSeed = worker.toLong(), nativeSat = native,
                variableSelector = InputOrder, valueSelector = IndomainMin,
                clauseExchange = PoolClauseExchange(pool, origin = worker).conditioned()) }
            BooleanProofPortfolio(guarded, context, listOf(1, 0), params).use { tasks ->
                val first = tasks.open(0, Cancellation.Never)
                val second = tasks.open(1, Cancellation.Never)
                assertNull(first.runSlice(Cancellation.Never, Long.MAX_VALUE, 1L))
                assertEquals(0, tasks.completed)
                assertNull(first.runSlice(Cancellation.Never, Long.MAX_VALUE, 5000L))
                assertNull(second.runSlice(Cancellation.Never, Long.MAX_VALUE, 5000L))
                val result = first.runSlice(Cancellation.Never, Long.MAX_VALUE, 5000L)

                assertTrue(result is SolveResult.Sat)
                assertTrue(result.assignment.boolValue(0))
            }
        }
    }

    @Test
    fun `repeated source invocations should preserve feasible models after scoped learning`() {
        val guarded = Problem(5, 0, emptyArray(), Array(8) { mask ->
            Clause(intArrayOf(Lit.make(0, true)) +
                IntArray(3) { bit -> Lit.make(bit + 2, mask and (1 shl bit) != 0) })
        }).bake()
        val params = BacktrackParams(variableSelector = InputOrder, valueSelector = IndomainMin, nativeSat = false)
        val scenario = PortfolioScenario(1, 2, Kind.CSP, EngineMix.BACKTRACK,
            btPool = listOf { BacktrackWorkerConfig.ofParams("fixed", params) })
        val workers = PortfolioBuilder.build(guarded, scenario)
        Portfolio.thompson(workers, diagnostics = PortfolioDiagnostics(100000L, 1L)).use { portfolio ->
            portfolio.disjointBooleanProof = true
            repeat(2) {
                val result = portfolio.solve(Cancellation.Never)

                assertTrue(result is SolveResult.Sat)
                assertTrue(result.assignment.boolValue(0))
                assertEquals(4, portfolio.lastBooleanProofTasks, portfolio.lastBooleanProofDecline)
            }
        }
    }

    @Test
    fun `bound deterministic workers should preserve ordinary portfolio proof admission`() {
        val params = BacktrackParams(variableSelector = InputOrder, valueSelector = IndomainMin, nativeSat = false)
        val scenario = PortfolioScenario(1, 2, Kind.CSP, EngineMix.BACKTRACK,
            btPool = listOf { BacktrackWorkerConfig.ofParams("fixed", params) })
        val workers = PortfolioBuilder.build(problem, scenario)
        Portfolio.thompson(workers, diagnostics = PortfolioDiagnostics(100000L, 1L)).use { portfolio ->
            portfolio.disjointBooleanProof = true
            val result = portfolio.solve(Cancellation.Never)

            assertTrue(result is SolveResult.Unsat)
            assertEquals(4, portfolio.lastBooleanProofTasks, portfolio.lastBooleanProofDecline)
            assertEquals(4, portfolio.lastBooleanProofCompleted)
        }
    }

    @Test
    fun `a task lane should retain the source session exclusive owner until retirement`() {
        val params = BacktrackParams(variableSelector = InputOrder, valueSelector = IndomainMin, nativeSat = false)
        val scenario = PortfolioScenario(1, 2, Kind.CSP, EngineMix.BACKTRACK,
            btPool = listOf { BacktrackWorkerConfig.ofParams("fixed", params) })
        val workers = PortfolioBuilder.build(problem, scenario)
        try {
            val admitted = BooleanProofPortfolio.forWorkers(workers) { error("unexpected decline: " + it) }
            assertNotNull(admitted).use { tasks ->
                val first = tasks.open(0, Cancellation.Never)
                assertNull(first.runSlice(Cancellation.Never, Long.MAX_VALUE, 1L))

                assertFailsWith<IllegalStateException> { workers[0].newResumableSolve() }
                first.close()
                assertNotNull(workers[0].newResumableSolve()).close()
            }
        } finally {
            workers.forEach { it.close() }
        }
    }

    @Test
    fun `opaque cut hooks should decline instead of being ignored`() {
        val cut = object : CutExchange {
            override fun exchange(sharing: CutSharing) = Unit
        }
        val params = BacktrackParams(variableSelector = InputOrder, valueSelector = IndomainMin,
            nativeSat = false, cutExchange = cut)
        val scenario = PortfolioScenario(1, 2, Kind.CSP, EngineMix.BACKTRACK, shareCuts = false,
            btPool = listOf { BacktrackWorkerConfig.ofParams("fixed", params) })
        val workers = PortfolioBuilder.build(problem, scenario)
        try {
            assertNull(BooleanProofPortfolio.forWorkers(workers))
        } finally {
            workers.forEach { it.close() }
        }
    }

    @Test
    fun `separately bound invocations should decline a shared task board`() {
        val params = BacktrackParams(variableSelector = InputOrder, valueSelector = IndomainMin, nativeSat = false)
        val scenario = PortfolioScenario(1, 2, Kind.CSP, EngineMix.BACKTRACK,
            btPool = listOf { BacktrackWorkerConfig.ofParams("fixed", params) })
        val first = PortfolioBuilder.build(problem, scenario)
        val second = PortfolioBuilder.build(problem, scenario)
        try {
            assertNull(BooleanProofPortfolio.forWorkers(listOf(first[0], second[1])))
        } finally {
            (first + second).forEach { it.close() }
        }
    }

    @Test
    fun `a lane should publish source exhaustion only after every disjoint cube closes`() {
        val pool = SharedClausePool()
        val params = List(2) { worker -> BacktrackParams(nativeSat = false,
            clauseExchange = PoolClauseExchange(pool, origin = worker).conditioned()) }
        BooleanProofPortfolio(problem, context, listOf(0, 1), params).use { tasks ->
            val first = tasks.open(0, Cancellation.Never)
            val second = tasks.open(1, Cancellation.Never)
            assertNull(first.runSlice(Cancellation.Never, Long.MAX_VALUE, 5000L))
            assertNull(second.runSlice(Cancellation.Never, Long.MAX_VALUE, 5000L))
            assertNull(first.runSlice(Cancellation.Never, Long.MAX_VALUE, 5000L))
            val result = second.runSlice(Cancellation.Never, Long.MAX_VALUE, 5000L)
            val work = second.work

            assertTrue(result is SolveResult.Unsat)
            assertTrue(second.isDone)
            assertEquals(result, second.runSlice(Cancellation.Never, Long.MAX_VALUE, 5000L))
            assertEquals(work, second.work)
        }
    }

    @Test
    fun `closing a paused lane should return its unresolved coverage to its peer`() {
        val params = List(2) { BacktrackParams(nativeSat = false) }
        BooleanProofPortfolio(problem, context, listOf(0, 1), params).use { tasks ->
            val first = tasks.open(0, Cancellation.Never)
            val second = tasks.open(1, Cancellation.Never)
            assertNull(first.runSlice(Cancellation.Never, Long.MAX_VALUE, 1L))
            first.close()
            val spent = first.work
            repeat(3) { assertNull(second.runSlice(Cancellation.Never, Long.MAX_VALUE, 5000L)) }
            val result = second.runSlice(Cancellation.Never, Long.MAX_VALUE, 5000L)

            assertTrue(result is SolveResult.Unsat)
            assertEquals(spent, first.work)
            assertFalse(tasks.available(0))
        }
    }

    @Test
    fun `a lane with no available task should leave the schedule to its pending peer`() {
        val params = List(2) { BacktrackParams(nativeSat = false) }
        BooleanProofPortfolio(problem, context, listOf(0), params).use { tasks ->
            val first = tasks.open(0, Cancellation.Never)
            val second = tasks.open(1, Cancellation.Never)
            assertNull(first.runSlice(Cancellation.Never, Long.MAX_VALUE, 1L))
            assertNull(second.runSlice(Cancellation.Never, Long.MAX_VALUE, 5000L))

            assertTrue(tasks.available(0))
            assertFalse(tasks.available(1))
            assertTrue(first.runSlice(Cancellation.Never, Long.MAX_VALUE, 5000L) is SolveResult.Unsat)
        }
    }
}
