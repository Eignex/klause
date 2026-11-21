package com.eignex.klause.lp.bounding

import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.table.Element
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.ir.Term
import com.eignex.klause.lp.engine.FloatLpResult
import com.eignex.klause.lp.engine.LpCertificationPolicy
import com.eignex.klause.lp.engine.LpCertifier
import com.eignex.klause.lp.engine.LpEngineFactory
import com.eignex.klause.lp.engine.LpFloatAllowance
import com.eignex.klause.lp.engine.LpFloatTermination
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpPricingOptions
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.engine.PersistentLpSolver
import com.eignex.klause.lp.engine.ProductionLpEngineFactory
import com.eignex.klause.lp.engine.RecordingLpEngineFactory
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.relaxation.LpAssemblyCancelled
import com.eignex.klause.lp.relaxation.LpSourcePreparation
import com.eignex.klause.lp.relaxation.RootDomains
import com.eignex.klause.propagation.CpSearchComponent
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.WorkMeter
import com.eignex.util.numbers.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpEngineTest {
    @Test
    fun `automatic root points survive a slice cancelled during bound checking`() {
        val problem = boundedLpEngineProblem().withSettings(
            ProblemSettings(lpMaxTableauCells = 1L, lpCeilingTableauCells = 1L),
        ).bake()
        val objective = LinearObjective(intCoefficients = LongArray(3) { 2L })
        var checkingStarted = false
        val query = ExecutionControl { checkingStarted }
        val context = LpSolveContext(certificationPolicy = LpCertificationPolicy { _, _ ->
            checkingStarted = true
            false
        })
        LpEngine(problem, objective, LpParams(lpConfig = LpConfig.AUTO),
            SolveStatsSink("root-point-before-bound-check"), context,
            nodeWorkAllowance = { 1_000_000L }).use { engine ->
            assertTrue(engine.prepareRootLayout(ExecutionControl.Never, ExecutionControl.Never))
            assertTrue(engine.automaticSparseTrial)

            val bound = engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList(), query)
            val retained = engine.retainRootPrimalTargets(ExecutionControl.Never)

            assertTrue(checkingStarted)
            assertTrue(bound.isNaN())
            assertTrue(retained)
        }
    }

    @Test
    fun `integer box bounds compare exact fractional cutoffs`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(3, 5)), emptyArray())
        val session = PropagationSession(problem)
        LpEngine(problem, LinearObjective(intCoefficients = longArrayOf(1)),
            LpParams(), SolveStatsSink("exact-box-cutoff")).use { engine ->
            val half = BigFraction.ofLong(2).reciprocal()
            for ((cutoff, expected) in listOf(BigFraction.ofLong(5) * half to true,
                BigFraction.ofLong(3) to true, BigFraction.ofLong(7) * half to false)) {
                assertEquals(expected, engine.linearBoundDominates(session, cutoff))
            }
        }
    }

    @Test
    fun `the smallest integer box bound retains exact dominance`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(1, 1)), emptyArray())
        val session = PropagationSession(problem)
        LpEngine(problem, LinearObjective(intCoefficients = longArrayOf(Long.MIN_VALUE)),
            LpParams(), SolveStatsSink("smallest-exact-box-cutoff")).use { engine ->

            val dominated = engine.linearBoundDominates(session, BigFraction.ofLong(Long.MIN_VALUE))

            assertTrue(dominated)
        }
    }

    @Test
    fun `overflowing integer box bounds retain exact dominance`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(1, 1) }, emptyArray())
        val session = PropagationSession(problem)
        val lower = BigFraction.ofLong(Long.MAX_VALUE) * BigFraction.ofLong(2)
        LpEngine(problem, LinearObjective(intCoefficients = LongArray(2) { Long.MAX_VALUE }),
            LpParams(), SolveStatsSink("overflowing-exact-box-cutoff")).use { engine ->
            for ((cutoff, expected) in listOf(lower to true, lower + BigFraction.ONE to false)) {
                assertEquals(expected, engine.linearBoundDominates(session, cutoff))
            }
        }
    }

    @Test
    fun `exact boolean box dominance survives cached contribution overflow`() {
        val problem = Problem(2, 0, emptyArray(), emptyArray())
        val session = PropagationSession(problem)
        LpEngine(problem, LinearObjective(boolWeights = LongArray(2) { Long.MAX_VALUE }),
            LpParams(), SolveStatsSink("overflowing-boolean-box-cutoff")).use { engine ->
            engine.linearLowerBound(LinearObjective(boolWeights = LongArray(2) { Long.MAX_VALUE }), session)
            session.pinBool(0, true)
            session.pinBool(1, true)

            val dominated = engine.linearBoundDominates(session, BigFraction.ONE)

            assertTrue(dominated)
        }
    }

    @Test
    fun `exact boolean box dominance follows a backtrack`() {
        val problem = Problem(2, 0, emptyArray(), emptyArray())
        val session = PropagationSession(problem)
        LpEngine(problem, LinearObjective(boolWeights = longArrayOf(5, -2)),
            LpParams(), SolveStatsSink("trailed-exact-box-cutoff")).use { engine ->
            assertFalse(engine.linearBoundDominates(session, BigFraction.ZERO))
            session.pinBool(0, true)
            assertTrue(engine.linearBoundDominates(session, BigFraction.ZERO))

            session.popToLevel(0)

            assertFalse(engine.linearBoundDominates(session, BigFraction.ZERO))
        }
    }

    @Test
    fun `linear bound should release branch costs when first read after a backtrack`() {
        val problem = Problem(2, 1, arrayOf(IntDomain(0, 1)), emptyArray())
        val session = PropagationSession(problem)
        session.pinBool(0, true)
        session.pinBool(1, true)
        session.popToLevel(1)

        val rootPruned = LpEngine(problem,
            LinearObjective(boolWeights = longArrayOf(5, 2), intCoefficients = longArrayOf(1)),
            LpParams(), SolveStatsSink("late-linear-bound")).use { engine ->
            assertTrue(engine.pruneNode(session, 4.0, -1, true))
            session.popToLevel(0)
            engine.pruneNode(session, 4.0, -1, true)
        }

        assertFalse(rootPruned)
    }

    @Test
    fun `a restored root refreshes expired preferences without another lp query`() {
        val problem = Problem(0, 6, Array(6) { IntDomain(0, 5) }, listOf(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 2),
            Linear(intArrayOf(1, 1), intArrayOf(2, 3), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(4, 5), LinearOp.GE, 1),
        )).bake()
        val objective = LinearObjective(intCoefficients = LongArray(6) { 2L })
        val factory = RecordingLpEngineFactory()
        LpEngine(problem, objective,
            LpParams(lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true,
                rootCutHarvest = false)), SolveStatsSink("refreshed-root-preferences"),
            LpSolveContext(factory)).use { engine ->
            val cp = CpSearchComponent(PropagationSession(problem))
            engine.cpAdapter.attach(cp.session, feasibility = false)
            val shared = SearchSession(listOf(cp, engine.propagator))
            shared.initialize()
            engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList())
            assertTrue(engine.retainRootPrimalTargets(ExecutionControl.Never))
            engine.rootRoundingWarmStart = null
            val expired = assertNotNull(engine.rootPrimalTargets)
            shared.push(SearchDecision.IntAtLeast(0, 1L))
            shared.restart()
            engine.clearRootPrimalTargets()
            val queries = factory.floatAllowances.size
            val before = engine.totalSolveWork()

            val refreshed = engine.refreshRootPrimalTargets(cp.session, ExecutionControl.Never)

            assertTrue(refreshed)
            assertFalse(expired === engine.rootPrimalTargets)
            assertNull(engine.rootRoundingWarmStart)
            assertEquals(queries, factory.floatAllowances.size)
            assertTrue(engine.totalSolveWork() > before)
        }
    }

    @Test
    fun `root preference refresh declines live search restrictions`() {
        val problem = boundedLpEngineProblem().bake()
        val factory = RecordingLpEngineFactory()
        LpEngine(problem, LinearObjective(intCoefficients = LongArray(3) { 2L }),
            LpParams(lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true,
                rootCutHarvest = false)), SolveStatsSink("nested-root-preference-refresh"),
            LpSolveContext(factory)).use { engine ->
            val cp = CpSearchComponent(PropagationSession(problem))
            engine.cpAdapter.attach(cp.session, feasibility = false)
            val shared = SearchSession(listOf(cp, engine.propagator))
            shared.initialize()
            engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList())
            engine.rootRoundingWarmStart = null
            shared.push(SearchDecision.IntAtLeast(0, 1L))
            val before = engine.totalSolveWork()
            val queries = factory.floatAllowances.size

            val refreshed = engine.refreshRootPrimalTargets(cp.session, ExecutionControl.Never)

            assertFalse(refreshed)
            assertNull(engine.rootPrimalTargets)
            assertEquals(before, engine.totalSolveWork())
            assertEquals(queries, factory.floatAllowances.size)
        }
    }

    @Test
    fun `cancelled root preference refresh withholds a new guide`() {
        val problem = boundedLpEngineProblem().bake()
        LpEngine(problem, LinearObjective(intCoefficients = LongArray(3) { 2L }),
            LpParams(lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true,
                rootCutHarvest = false)), SolveStatsSink("cancelled-root-preference-refresh")).use { engine ->
            val session = PropagationSession(problem)
            val before = engine.totalSolveWork()

            val refreshed = engine.refreshRootPrimalTargets(session, ExecutionControl { true })

            assertFalse(refreshed)
            assertNull(engine.rootPrimalTargets)
            assertEquals(before, engine.totalSolveWork())
        }
    }

    @Test
    fun `deferring node simplex preserves combinatorial objective pruning`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(1, 2)), emptyList())
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val session = PropagationSession(problem)
        LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)),
            SolveStatsSink("deferred-simplex-objective-bound")).use { engine ->

            val pruned = engine.pruneNode(session, 1.0, -1, true, deferSimplex = true)

            assertTrue(pruned)
        }
    }

    @Test
    fun `numerical root preparation finishes before the bounded root query`() {
        val sink = SolveStatsSink("paid-numerical-root-preparation")
        LpEngine(boundedLpEngineProblem().bake(), LinearObjective(intCoefficients = LongArray(3) { 2L }),
            LpParams(lpPlan = LpPlan(bounding = true, componentOwnership = true,
                probe = false, rootCutHarvest = false)), sink).use { engine ->
            assertTrue(engine.prepareRootLayout(ExecutionControl.Never, ExecutionControl.Never))
            val prepared = assertNotNull(engine.propagator.metrics)
            val paid = prepared.preparationWork
            val query = ExecutionControl { engine.propagator.metrics?.preparationSuccesses == 0L }

            val bound = engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList(), query)

            assertTrue(paid > 0L)
            assertEquals(3.0, bound)
            assertEquals(paid, assertNotNull(engine.propagator.metrics).preparationWork)
            assertEquals(1L, sink.snapshot().lp.phases["ROOT_NUMERICAL_PREPARATION_ROOT"]?.outcomes?.get("READY"))
        }
    }

    @Test
    fun `paused root layout preparation retains charged construction`() {
        val problem = boundedLpEngineProblem().bake()
        val sink = SolveStatsSink("paused-root-layout")
        LpEngine(problem, LinearObjective(intCoefficients = LongArray(3) { 2L }),
            LpParams(lpPlan = LpPlan(bounding = true, componentOwnership = true,
                probe = false, rootCutHarvest = false)), sink,
            projections = FactorProjectionProviders.Default.prepare(problem)).use { engine ->
            val before = engine.totalSolveWork()

            val readyInFirstSlice = engine.prepareRootLayout(ExecutionControl.Never, ExecutionControl {
                engine.totalSolveWork() > before
            })
            val paid = engine.totalSolveWork()
            val prepared = sink.snapshot().lp.phases["TEMPLATE_PREPARATION_NODE"]?.calls
            val ready = engine.prepareRootLayout(ExecutionControl.Never, ExecutionControl.Never)
            val imported = sink.snapshot().lp.phases["TEMPLATE_ASSEMBLY_NODE"]?.outcomes?.get("EXACT_BOUND_OBJECTS")
            val bound = engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList())

            assertFalse(readyInFirstSlice)
            assertTrue(paid > before)
            assertTrue(ready)
            assertNotNull(prepared)
            assertEquals(prepared, sink.snapshot().lp.phases["TEMPLATE_PREPARATION_NODE"]?.calls)
            assertNotNull(imported)
            assertEquals(imported,
                sink.snapshot().lp.phases["TEMPLATE_ASSEMBLY_NODE"]?.outcomes?.get("EXACT_BOUND_OBJECTS"))
            assertEquals(3.0, bound)
        }
    }

    @Test
    fun `invocation boundaries pause root layout without cancelling its lifetime`() {
        val problem = boundedLpEngineProblem().bake()
        val sink = SolveStatsSink("root-layout-invocation-boundary")
        var current: LpEngine? = null
        var sliceStart = Long.MAX_VALUE
        val invocation = ExecutionControl { (current?.totalSolveWork() ?: 0L) > sliceStart }
        LpEngine(problem, LinearObjective(intCoefficients = LongArray(3) { 2L }),
            LpParams(lpPlan = LpPlan(bounding = true, componentOwnership = true,
                probe = false, rootCutHarvest = false), cancellation = invocation), sink,
            projections = FactorProjectionProviders.Default.prepare(problem)).use { engine ->
            current = engine
            val before = engine.totalSolveWork()
            sliceStart = before

            val readyInFirstSlice = engine.prepareRootLayout(ExecutionControl.Never, invocation)
            val paid = engine.totalSolveWork()
            sliceStart = Long.MAX_VALUE
            val ready = engine.prepareRootLayout(ExecutionControl.Never, invocation)
            val bound = engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList())

            assertFalse(readyInFirstSlice)
            assertTrue(paid > before)
            assertTrue(ready)
            assertEquals(3.0, bound)
            assertNull(sink.snapshot().lp.phases["ROOT_LAYOUT_PREPARATION_ROOT"]?.outcomes?.get("CANCELLED"))
        }
    }

    @Test
    fun `containing cancellation retires a paused root layout`() {
        var cancelled = false
        val lifetime = ExecutionControl { cancelled }
        val sink = SolveStatsSink("cancelled-root-layout")
        LpEngine(boundedLpEngineProblem(), LinearObjective(intCoefficients = LongArray(3) { 2L }),
            LpParams(lpPlan = LpPlan(bounding = true, componentOwnership = true,
                probe = false, rootCutHarvest = false)), sink).use { engine ->
            val before = engine.totalSolveWork()
            assertFalse(engine.prepareRootLayout(lifetime, ExecutionControl { engine.totalSolveWork() > before }))
            val paid = engine.totalSolveWork()
            cancelled = true

            engine.prepareRootLayout(lifetime, ExecutionControl.Never)
            val bound = engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList(), lifetime)

            assertTrue(bound.isNaN())
            assertTrue(engine.totalSolveWork() >= paid)
            assertEquals(1L, sink.snapshot().lp.phases["ROOT_LAYOUT_PREPARATION_ROOT"]?.outcomes?.get("CANCELLED"))
        }
    }

    @Test
    fun `root layout resumption does not renew spent construction work`() {
        LpEngine(boundedLpEngineProblem(), LinearObjective(intCoefficients = LongArray(3) { 2L }),
            LpParams(lpPlan = LpPlan(bounding = true, componentOwnership = true, rootMaxWork = 1L,
                probe = false, rootCutHarvest = false)), SolveStatsSink("spent-root-layout")).use { engine ->
            engine.prepareRootLayout(ExecutionControl.Never, ExecutionControl.Never)
            val spent = engine.totalSolveWork()

            engine.prepareRootLayout(ExecutionControl.Never, ExecutionControl.Never)
            val bound = engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList())

            assertTrue(spent > 0L)
            assertEquals(spent, engine.totalSolveWork())
            assertTrue(bound.isNaN())
        }
    }

    @Test
    fun `root query cancellation preserves completed preparation for node queries`() {
        val problem = boundedLpEngineProblem().bake()
        val sink = SolveStatsSink("retained-root-preparation")
        var rootPolls = 0
        val rootToken = ExecutionControl { rootPolls++ > 0 }
        LpEngine(problem, LinearObjective(intCoefficients = LongArray(3) { 2L }),
            LpParams(lpPlan = LpPlan(bounding = true, componentOwnership = true,
                probe = false, rootCutHarvest = false)), sink,
            projections = FactorProjectionProviders.Default.prepare(problem)).use { engine ->
            val cp = CpSearchComponent(PropagationSession(problem))
            engine.cpAdapter.attach(cp.session, feasibility = false)
            SearchSession(listOf(cp, engine.propagator)).initialize()
            val relaxer = assertNotNull(engine.lpRelaxer)

            val root = engine.rootLpRelaxationBound(relaxer, emptyList(), rootToken,
                preparationCancellation = ExecutionControl.Never)
            val prepared = sink.snapshot().lp.phases["TEMPLATE_PREPARATION_NODE"]?.calls ?: 0L
            val node = assertNotNull(engine.nodeRelaxation(relaxer, cp.session))
            val bound = assertNotNull(engine.solveNode(node.model, null, ExecutionControl.Never)?.second).objective

            assertTrue(root.isNaN())
            assertTrue(prepared > 0L)
            assertEquals(prepared, sink.snapshot().lp.phases["TEMPLATE_PREPARATION_NODE"]?.calls)
            assertEquals(3.0, bound)
        }
    }

    @Test
    fun `source root bounds exclude live search restrictions`() {
        val problem = boundedLpEngineProblem()
        LpEngine(problem, LinearObjective(intCoefficients = LongArray(3) { 2L }),
            LpParams(lpPlan = LpPlan(bounding = true, componentOwnership = true,
                probe = false, rootCutHarvest = false)),
            SolveStatsSink("unconditional-source-root")).use { engine ->
            val cp = CpSearchComponent(PropagationSession(problem))
            engine.cpAdapter.attach(cp.session, feasibility = false)
            val shared = SearchSession(listOf(cp, engine.propagator))
            shared.initialize()
            assertTrue(engine.prepareRootLayout(ExecutionControl.Never, ExecutionControl.Never))
            shared.push(SearchDecision.IntAtLeast(0, 1L))

            val root = engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList())
            val child = assertNotNull(engine.nodeRelaxation(assertNotNull(engine.lpRelaxer), cp.session))
            val childBound = assertNotNull(engine.solveNode(child.model, null,
                ExecutionControl.Never)?.second).objective

            assertEquals(3.0, root)
            assertEquals(4.0, childBound)
        }
    }

    @Test
    fun `source root bounds accept noncompact exact rows`() {
        val coefficient = BigFraction.ofLong(Long.MAX_VALUE) + BigFraction.ONE
        val problem = Problem(0, 4, Array(4) { IntDomain(0, 1) }, (0 until 2).map { block ->
            ExactLinear.of(intArrayOf(Term.ofIntVar(2 * block), Term.ofIntVar(2 * block + 1)),
                listOf(coefficient, coefficient + BigFraction.ONE), LinearOp.GE, coefficient)
        })
        LpEngine(problem, LinearObjective(intCoefficients = LongArray(4) { 1L }),
            LpParams(lpPlan = LpPlan(bounding = true, componentOwnership = true,
                probe = false, rootCutHarvest = false)),
            SolveStatsSink("noncompact-source-root")).use { engine ->

            val bound = engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList())

            assertTrue(bound in 1.0..2.0)
        }
    }

    @Test
    fun `retained layouts accept noncompact exact source rows`() {
        val coefficient = BigFraction.ofLong(Long.MAX_VALUE) + BigFraction.ONE
        val problem = Problem(0, 4, Array(4) { IntDomain(0, 1) }, (0 until 2).map { block ->
            ExactLinear.of(intArrayOf(Term.ofIntVar(2 * block), Term.ofIntVar(2 * block + 1)),
                listOf(coefficient, coefficient + BigFraction.ONE), LinearOp.GE, coefficient)
        })
        val objective = LinearObjective(intCoefficients = LongArray(4) { 1L })
        assertNotNull(CpToLpRelaxation(problem, objective).build(RootDomains(problem)).model.doubleView)
        LpEngine(problem, objective,
            LpParams(lpPlan = LpPlan(bounding = true, componentOwnership = true,
                probe = false, rootCutHarvest = false)),
            SolveStatsSink("exact-retained-layout")).use { engine ->

            val relaxation = engine.nodeRelaxation(assertNotNull(engine.lpRelaxer), PropagationSession(problem))

            assertNotNull(assertNotNull(relaxation).model.exactState)
        }
    }

    @Test
    fun `default consumers should earn selective preparation from completed LP builds`() {
        val problem = boundedLpEngineProblem().bake()
        val objective = LinearObjective(intCoefficients = LongArray(3) { 2L })
        val projections = FactorProjectionProviders.Default.prepare(problem)
        val sink = SolveStatsSink("default-earned-preparation")

        repeat(32) {
            LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)), sink,
                projections = projections).use { engine ->
                assertEquals(3.0, engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList()))
            }
        }

        val phases = sink.snapshot().lp.phases
        assertTrue(phases.filterKeys { it.startsWith("TEMPLATE_FUNDING_") }.values.sumOf { it.calls } > 0L)
        assertTrue(phases.filterKeys { it.startsWith("TEMPLATE_PREPARATION_") }.values.sumOf {
            it.outcomes["FACTORS_REUSED"] ?: 0L
        } > 0L)
    }

    @Test
    fun `cancelled template preparation charges the containing LP allowance`() {
        val problem = boundedLpEngineProblem().bake()
        var work = 0L
        val token = object : ExecutionControl() {
            private val meter = WorkMeter { work += it }
            override fun isCancelled(): Boolean = work >= 3L
            override val accounting: WorkMeter get() = meter
        }
        LpEngine(problem, LinearObjective(intCoefficients = LongArray(3) { 2L }),
            LpParams(lpPlan = LpPlan(bounding = true), cancellation = token),
            SolveStatsSink("cancelled-template-charge"),
            projections = FactorProjectionProviders.Default.prepare(problem),
            eagerSourcePreparation = true).use { engine ->
            val before = engine.totalSolveWork()
            work = 0L

            assertFailsWith<LpAssemblyCancelled> {
                assertNotNull(engine.lpRelaxer).build(RootDomains(problem), cancellation = token)
            }

            assertTrue(work >= 3L)
            assertEquals(work, engine.totalSolveWork() - before)
        }
    }

    @Test
    fun `cancelled admission lookup charges the containing LP allowance`() {
        val problem = boundedLpEngineProblem().bake()
        var work = 0L
        val token = object : ExecutionControl() {
            private val meter = WorkMeter { work += it }
            override fun isCancelled(): Boolean = work >= 1L
            override val accounting: WorkMeter get() = meter
        }
        LpEngine(problem, LinearObjective(intCoefficients = LongArray(3) { 2L }),
            LpParams(lpPlan = LpPlan(bounding = true, sourcePreparation = LpSourcePreparation.ADMITTED),
                cancellation = token),
            SolveStatsSink("cancelled-admission-charge"),
            projections = FactorProjectionProviders.Default.prepare(problem)).use { engine ->
            val before = engine.totalSolveWork()
            work = 0L

            assertFailsWith<LpAssemblyCancelled> {
                assertNotNull(engine.lpRelaxer).build(RootDomains(problem), cancellation = token)
            }

            assertTrue(work >= 1L)
            assertEquals(work, engine.totalSolveWork() - before)
        }
    }

    @Test
    fun `retained root layouts adopt propagated source bounds`() {
        val source = boundedLpEngineProblem()
        val problem = source.withFactors(source.factors +
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1))
        LpEngine(problem, LinearObjective(intCoefficients = LongArray(3) { 2L }),
            LpParams(lpPlan = LpPlan(bounding = true, componentOwnership = true,
                probe = false, rootCutHarvest = false)),
            SolveStatsSink("root-layout-propagation")).use { engine ->

            val bound = engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList())

            assertEquals(4.0, bound)
        }
    }

    @Test
    fun `root query observations distinguish work cancellation and checked bounds`() {
        val cases = listOf(
            Triple(0L, false, "NO_INITIAL_WORK"),
            Triple(100_000L, true, "CANCELLED_INITIAL"),
            Triple(100_000L, false, "BOUND"),
        )
        for ((grant, cancelled, outcome) in cases) {
            val sink = SolveStatsSink("root-query-admission")
            LpEngine(boundedLpEngineProblem(), LinearObjective(intCoefficients = LongArray(3) { 2L }),
                LpParams(lpPlan = LpPlan(bounding = true, componentOwnership = true,
                    probe = false, rootCutHarvest = false)), sink,
                nodeWorkAllowance = { grant }).use { engine ->

                val bound = engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList(),
                    ExecutionControl { cancelled })

                if (outcome == "BOUND") assertEquals(3.0, bound) else assertTrue(bound.isNaN())
                assertEquals(1L, sink.snapshot().lp.phases["ROOT_QUERY_ROOT"]?.outcomes?.get("COMPONENT_$outcome"))
            }
        }
    }

    @Test
    fun `complete component node deductions honor the injected proof policy`() {
        for (accepted in listOf(false, true)) {
            val problem = Problem(0, 4, Array(4) { IntDomain(0, 5) }, listOf(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
                Linear(intArrayOf(1, 1), intArrayOf(2, 3), LinearOp.GE, 1),
            ))
            val sink = SolveStatsSink("complete-component-policy")
            val context = LpSolveContext(certificationPolicy = LpCertificationPolicy { _, success ->
                accepted && success
            })

            val pruned = LpEngine(problem,
                LinearObjective(intCoefficients = LongArray(4) { 1L }, constant = 7L),
                LpParams(lpPlan = LpPlan(bounding = true, componentOwnership = true,
                    probe = false, rootCutHarvest = false)), sink, context).use { engine ->
                engine.pruneNode(PropagationSession(problem), 8.0, -1, true)
            }

            assertEquals(accepted, pruned)
            assertEquals(1L, sink.snapshot().lp.phases["NODE_DEDUCTION_NODE"]?.outcomes?.get("COMPONENT_COMPLETE"))
        }
    }

    @Test
    fun `ordinary component roots yield checked partial bounds after their adaptive query`() {
        val problem = Problem(0, 120, Array(120) { IntDomain(0, 1) }, (0 until 120).map { variable ->
            val next = (variable / 60) * 60 + (variable + 1) % 60
            Linear(intArrayOf(1, 1), intArrayOf(variable, next), LinearOp.GE, 1)
        })
        LpEngine(problem, LinearObjective(intCoefficients = LongArray(120) { 1L }),
            LpParams(lpPlan = LpPlan(bounding = true, componentOwnership = true,
                probe = false, rootCutHarvest = false), solveBudgetMillis = 30_000L),
            SolveStatsSink("ordinary-root-query-quantum")).use { engine ->
            engine.observeNodeWork(reachedOptimum = true, degenerateColumns = 0, columns = 1, rows = 1)

            val bound = engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList())

            assertTrue(bound > 0.0 && bound <= 60.0)
            assertEquals(LpFloatTermination.WORK, engine.propagator.floatTermination)
            assertFalse(engine.propagator.floatPaused)
        }
    }

    @Test
    fun `fixed root installation completes under its preparation token after slice expiry`() {
        val problem = boundedLpEngineProblem().bake()
        val factory = RecordingLpEngineFactory()
        var sliceStopped = false
        LpEngine(problem, LinearObjective(intCoefficients = LongArray(3) { 2L }),
            LpParams(lpPlan = LpPlan(bounding = true, componentOwnership = true,
                probe = false, rootCutHarvest = false), cancellation = ExecutionControl { sliceStopped },
                solveBudgetMillis = 30_000L),
            SolveStatsSink("root-installation-token"), LpSolveContext(factory)).use { engine ->
            val base = assertNotNull(engine.lpRelaxer).build(RootDomains(problem))
            sliceStopped = true
            val installed = assertNotNull(engine.cpAdapter.sourceRootRelaxation(base, ExecutionControl.Never))
            assertTrue(engine.propagator.prepare(ExecutionControl.Never, 100_000L))
            val prepared = factory.calls.size
            assertTrue(prepared > 0)
            sliceStopped = false

            val result = assertNotNull(engine.solveNode(installed.model, null, ExecutionControl.Never)?.second)

            assertEquals(3.0, result.objective)
            assertEquals(prepared, factory.calls.size)
        }
    }

    @Test
    fun `ordinary component roots honor an empty cut selection from a nonempty pool`() {
        val problem = boundedLpEngineProblem()
        LpEngine(problem, LinearObjective(intCoefficients = LongArray(3) { 2L }),
            LpParams(lpPlan = LpPlan(bounding = true, componentOwnership = true,
                probe = false, rootCutHarvest = false), solveBudgetMillis = 30_000L),
            SolveStatsSink("ordinary-root-cut-selection")).use { engine ->
            val relaxer = assertNotNull(engine.lpRelaxer)
            val base = relaxer.build(PropagationSession(problem))
            val cut = engine.dualSimplex(base.model, ExecutionControl.Never).use { simplex ->
                assertNotNull(simplex.solve())
                simplex.gomoryCuts(8).first()
            }
            assertTrue(engine.cutPool.add(cut, base))
            assertEquals(1, engine.lpGlobalCuts.size)
            assertEquals(4.0, engine.rootLpRelaxationBound(relaxer, engine.lpGlobalCuts))

            val bound = engine.rootLpRelaxationBound(relaxer, emptyList())

            assertEquals(3.0, bound)
        }
    }

    @Test
    fun `ordinary component roots retain global cuts for later node queries`() {
        val problem = Problem(0, 4, Array(4) { IntDomain(0, 5) }, listOf(
            Linear(intArrayOf(2, 2), intArrayOf(0, 1), LinearOp.LE, 3),
            Linear(intArrayOf(2, 2), intArrayOf(2, 3), LinearOp.LE, 3),
        ))
        val factory = RecordingLpEngineFactory()
        val sink = SolveStatsSink("ordinary-root-cuts")
        LpEngine(problem, LinearObjective(intCoefficients = LongArray(4) { -1L }),
            LpParams(lpPlan = LpPlan(bounding = true, componentOwnership = true,
                probe = false, rootCutHarvest = false), solveBudgetMillis = 30_000L),
            sink, LpSolveContext(factory)).use { engine ->
            val cp = CpSearchComponent(PropagationSession(problem))
            engine.cpAdapter.attach(cp.session, feasibility = false)
            SearchSession(listOf(cp, engine.propagator)).initialize()
            val relaxer = assertNotNull(engine.lpRelaxer)
            val base = relaxer.build(cp.session)
            val cut = engine.dualSimplex(base.model, ExecutionControl.Never).use { simplex ->
                assertNotNull(simplex.solve())
                simplex.gomoryCuts(8).first()
            }
            assertTrue(engine.cutPool.add(cut, base))
            assertEquals(1, engine.lpGlobalCuts.size)

            val bound = engine.rootLpRelaxationBound(relaxer, engine.lpGlobalCuts)
            val constructed = factory.calls.size
            val pruned = engine.pruneNode(cp.session, -1.0, -1, true)

            assertEquals(-2.0, bound)
            assertFalse(pruned)
            assertEquals(constructed, factory.calls.size)
            assertEquals(2L, sink.snapshot().lp.phases["COMPONENT_LIFECYCLE_NODE"]?.outcomes?.get("REUSED"))
        }
    }

    @Test
    fun `an ordinary component root solves only the child narrowed by a later decision`() {
        val problem = Problem(0, 4, Array(4) { IntDomain(0, 5) }, listOf(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(2, 3), LinearOp.GE, 1),
        ))
        val factory = RecordingLpEngineFactory()
        val sink = SolveStatsSink("ordinary-root-child")
        LpEngine(problem, LinearObjective(intCoefficients = LongArray(4) { 1L }),
            LpParams(lpPlan = LpPlan(bounding = true, componentOwnership = true,
                probe = false, rootCutHarvest = false), solveBudgetMillis = 30_000L),
            sink, LpSolveContext(factory)).use { engine ->
            val cp = CpSearchComponent(PropagationSession(problem))
            engine.cpAdapter.attach(cp.session, feasibility = false)
            val shared = SearchSession(listOf(cp, engine.propagator))
            shared.initialize()
            assertEquals(2.0, engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList()))
            val constructed = factory.calls.size
            shared.push(SearchDecision.IntAtLeast(0, 1L))

            val pruned = engine.pruneNode(cp.session, 4.0, -1, true)

            assertFalse(pruned)
            assertEquals(constructed, factory.calls.size)
            val outcomes = sink.snapshot().lp.phases["COMPONENT_LIFECYCLE_NODE"]?.outcomes
            assertEquals(1L, outcomes?.get("SOLVED"))
            assertEquals(1L, outcomes?.get("REUSED"))
        }
    }

    @Test
    fun `ordinary component roots retain their owners for later node queries`() {
        val problem = Problem(0, 4, Array(4) { IntDomain(0, 5) }, listOf(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(2, 3), LinearOp.GE, 1),
        ))
        val factory = RecordingLpEngineFactory()
        val sink = SolveStatsSink("ordinary-root-owner")
        LpEngine(problem, LinearObjective(intCoefficients = LongArray(4) { 1L }),
            LpParams(lpPlan = LpPlan(bounding = true, componentOwnership = true,
                probe = false, rootCutHarvest = false), solveBudgetMillis = 30_000L),
            sink, LpSolveContext(factory)).use { engine ->
            val cp = CpSearchComponent(PropagationSession(problem))
            engine.cpAdapter.attach(cp.session, feasibility = false)
            SearchSession(listOf(cp, engine.propagator)).initialize()
            val bound = engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList())
            val constructed = factory.calls.size

            val pruned = engine.pruneNode(cp.session, 3.0, -1, true)

            assertEquals(2.0, bound)
            assertFalse(pruned)
            assertEquals(constructed, factory.calls.size)
            assertEquals(2L, sink.snapshot().lp.phases["COMPONENT_LIFECYCLE_NODE"]?.outcomes?.get("REUSED"))
            assertEquals(Int.MAX_VALUE, engine.params.lpPlan.boundMaxDepth)
            assertFalse(engine.sparseTrialActive)
        }
    }

    @Test
    fun `partial component root bounds honor the injected proof policy`() {
        val problem = Problem(0, 6, Array(6) { IntDomain(0, 5) }, listOf(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 2),
            Linear(intArrayOf(1, 1), intArrayOf(2, 3), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(4, 5), LinearOp.GE, 1),
        ))
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel, cancellation: ExecutionControl, refactorUpdateLimit: Int,
                iterationLimit: Int, workLimit: Long, trackDegeneracy: Boolean, pricing: LpPricingOptions,
            ): PersistentLpSolver {
                val delegate = ProductionLpEngineFactory.newPersistentSolver(
                    model, cancellation, refactorUpdateLimit, iterationLimit, workLimit, trackDegeneracy, pricing,
                )
                return object : PersistentLpSolver by delegate {
                    override fun resolveBounds(allowance: LpFloatAllowance?): FloatLpResult? =
                        delegate.resolveBounds(if (model.m == 2) LpFloatAllowance(1L, 0) else allowance)
                }
            }
        }
        for (allowed in listOf(emptySet(), setOf(LpCertifier.INTEGER), setOf(LpCertifier.RATIONAL))) {
            val sink = SolveStatsSink("partial-component-root-policy")
            val context = LpSolveContext(factory, LpCertificationPolicy { certifier, success ->
                success && certifier in allowed
            })
            LpEngine(problem, LinearObjective(intCoefficients = LongArray(6) { 1L }, constant = 7L),
                LpParams(lpPlan = LpPlan(bounding = true, componentOwnership = true,
                    probe = false, rootCutHarvest = false)), sink, context).use { engine ->

                val bound = engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList())

                if (allowed.isNotEmpty()) assertEquals(9.0, bound) else assertTrue(bound.isNaN())
                assertEquals(LpFloatTermination.WORK, engine.propagator.floatTermination)
                assertFalse(engine.propagator.floatPaused)
                if (allowed.isNotEmpty()) {
                    assertEquals(1L,
                        sink.snapshot().lp.phases["ROOT_QUERY_ROOT"]?.outcomes?.get("COMPONENT_PARTIAL_BOUND"))
                }
            }
        }
    }

    @Test
    fun `ordinary node pruning consumes a checked partial component bound`() {
        val problem = Problem(0, 6, Array(6) { IntDomain(0, 5) }, listOf(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 2),
            Linear(intArrayOf(1, 1), intArrayOf(2, 3), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(4, 5), LinearOp.GE, 1),
        ))
        val sink = SolveStatsSink("partial-component-prune")
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel, cancellation: ExecutionControl, refactorUpdateLimit: Int,
                iterationLimit: Int, workLimit: Long, trackDegeneracy: Boolean, pricing: LpPricingOptions,
            ): PersistentLpSolver {
                val delegate = ProductionLpEngineFactory.newPersistentSolver(
                    model, cancellation, refactorUpdateLimit, iterationLimit, workLimit, trackDegeneracy, pricing,
                )
                return object : PersistentLpSolver by delegate {
                    override fun resolveBounds(allowance: LpFloatAllowance?): FloatLpResult? =
                        delegate.resolveBounds(if (model.m == 2) LpFloatAllowance(1L, 0) else allowance)
                }
            }
        }

        val pruned = LpEngine(problem, LinearObjective(intCoefficients = LongArray(6) { 1L }, constant = 7L),
            LpParams(lpPlan = LpPlan(bounding = true, componentOwnership = true,
                probe = false, rootCutHarvest = false)), sink, LpSolveContext(factory)).use { engine ->
            engine.pruneNode(PropagationSession(problem), 8.0, -1, true).also {
                assertEquals(LpFloatTermination.WORK, engine.propagator.floatTermination)
            }
        }

        assertTrue(pruned)
        assertEquals(1.0, sink.snapshot().lp.pruned.sum)
        assertEquals(3L, sink.snapshot().lp.phases["COMPONENT_LIFECYCLE_NODE"]?.outcomes?.get("SOLVED"))
    }

    @Test
    fun `root pruning retains complete useful element property groups`() {
        val problem = Problem(
            0, 4, arrayOf(IntDomain(0, 2), IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 9)),
            arrayOf(
                Element(0, 1, longArrayOf(0, 1, 0), false, 0),
                Element(0, 2, longArrayOf(1, 0, 1), false, 0),
                Element(0, 3, longArrayOf(8, 9, 8), false, 0),
            ),
        )
        LpEngine(
            problem, LinearObjective(intCoefficients = longArrayOf(0, 1, 1, 0)),
            LpParams(lpConfig = LpConfig.AUTO), SolveStatsSink("groups"),
        ).use { engine ->
            engine.pruneIneffectiveHulls(ExecutionControl.Never)

            val relaxation = assertNotNull(engine.lpRelaxer).build(PropagationSession(problem))
            assertEquals(setOf(0, 1, 2), relaxation.hullFactorIds.toSet())
        }
    }

    @Test
    fun `fixed fractional real leaf should retain its exact contradiction`() {
        val problem = Problem(
            0,
            0,
            emptyArray(),
            arrayOf(
                Linear(
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0),
                    doubleArrayOf(0.1),
                    LinearOp.EQ,
                    0.010000000000000002,
                ),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.1),
            realUpper = doubleArrayOf(0.1),
        )
        val sink = SolveStatsSink(backend = "fractional-leaf")
        val session = PropagationSession(problem)

        LpEngine(
            problem,
            LinearObjective(),
            LpParams(lpPlan = LpPlan(bounding = true, realResidual = true)),
            sink,
        ).use { engine ->
            val relaxation = assertNotNull(engine.nodeRelaxation(assertNotNull(engine.lpRelaxer), session))
            assertNotNull(relaxation.model.exactState)
            val result = engine.leafCertify(session)

            assertEquals(LpVerdict.INFEASIBLE, result.verdict, "LP evidence: ${sink.snapshot().lp}")
        }
    }

    @Test
    fun `a leaf whose relaxation assembly is cancelled should be indeterminate`() {
        val problem = Problem(0, 0, emptyArray(), arrayOf<Factor>(
            Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0, 1), doubleArrayOf(1.0, -1.0), LinearOp.LE, 0.0),
        ), numRealVars = 2, realLower = DoubleArray(2), realUpper = DoubleArray(2) { 1.0 })
        val params = LpParams(lpPlan = LpPlan(bounding = true, realResidual = true),
            cancellation = ExecutionControl { true })

        val leaf = LpEngine(problem, LinearObjective(), params, SolveStatsSink(backend = "cancelled-leaf")).use {
            it.leafCertify(PropagationSession(problem))
        }

        assertEquals(LpVerdict.INDETERMINATE, leaf.verdict)
    }
}

internal fun boundedLpEngineProblem(): Problem = Problem(
    numBoolVars = 0,
    numIntVars = 3,
    intDomains = Array(3) { IntDomain(0, 1) },
    factors = arrayOf<Factor>(
        Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
        Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 1),
        Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 1),
    ),
)
