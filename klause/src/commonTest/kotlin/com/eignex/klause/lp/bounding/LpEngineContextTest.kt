package com.eignex.klause.lp.bounding

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.OpenIntBounds
import com.eignex.klause.lp.engine.Basis
import com.eignex.klause.lp.engine.EngineConstruction
import com.eignex.klause.lp.engine.LpCertificationPolicy
import com.eignex.klause.lp.engine.LpFloatTermination
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.LpSolveMetrics
import com.eignex.klause.lp.engine.LpSolver
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.engine.LpZeroObjectivePricing
import com.eignex.klause.lp.engine.RecordingLpEngineFactory
import com.eignex.klause.lp.tightenOpenIntBounds
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.util.ExecutionControl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LpEngineContextTest {
    private val decline = LpCertificationPolicy { _, _ -> false }

    private fun metricSolver(
        workOps: Long = 0L,
        termination: LpFloatTermination? = null,
        pivots: Int = 0,
    ): LpSolver = object : LpSolver {
        override val infeasibleRay: DoubleArray? = null
        override val lastWorkOps: Long = workOps
        override val lastTermination = termination
        override val lastPivots = pivots
        override fun solve(warm: Basis?) = null
        override fun solvePrimal(warm: Basis?) = null
    }

    private fun budgetedEngine(
        sink: SolveStatsSink,
        plan: LpPlan = LpPlan(bounding = true),
        policy: LpRootQueryPolicy = LpRootQueryPolicy.SHARED_WORK,
        budgetMillis: Long = 4L,
    ): LpEngine {
        val problem = boundedLpEngineProblem()
        return LpEngine(
            problem,
            LinearObjective(intCoefficients = LongArray(problem.numIntVars)),
            LpParams(lpPlan = plan, solveBudgetMillis = budgetMillis),
            sink,
            rootQueryPolicy = policy,
        )
    }

    @Test
    fun `root work spending the LP allowance should be reported before node work`() {
        val sink = SolveStatsSink(backend = "root-allowance")

        budgetedEngine(sink).use { it.observeRootSolve(metricSolver(), LpSolveMetrics(workOps = 20_000L)) }

        assertTrue(sink.lp.snapshot().workAllowanceSpent)
    }

    @Test
    fun `node solves should be capped at the remaining LP allowance`() {
        val engine = budgetedEngine(SolveStatsSink(backend = "node-cap"))

        val budget = engine.use {
            it.observeRootSolve(metricSolver(), LpSolveMetrics(workOps = 5_000L))
            it.nodeWorkBudget()
        }

        assertEquals(15_000L, budget)
    }

    @Test
    fun `root primal queries should use the smaller remaining root and LP allowance`() {
        for (
        (rootLimit, spent, expected) in listOf(
            Triple(12_000L, 5_000L, 7_000L),
            Triple(50_000L, 5_000L, 15_000L),
            Triple(0L, 5_000L, 15_000L),
            Triple(12_000L, 20_000L, 1L),
        )
        ) {
            val plan = LpPlan(bounding = true, rootMaxWork = rootLimit)
            budgetedEngine(SolveStatsSink(backend = "primal-root-cap"), plan).use { engine ->
                engine.observeRootSolve(metricSolver(), LpSolveMetrics(workOps = spent))

                assertEquals(expected, engine.rootPrimalWorkBudget())
            }
        }
    }

    @Test
    fun `private root probes should charge assembly and solves once to the containing engine`() {
        val sink = SolveStatsSink(backend = "private-root")
        budgetedEngine(sink, LpPlan(bounding = true, rootMaxWork = 12_000L)).use { parent ->
            parent.forObjective(LinearObjective(), ExecutionControl.Never, rootProbe = true).use { child ->
                child.noteNodeOverhead(3_000L)
                child.observeRootSolve(metricSolver(), LpSolveMetrics(workOps = 2_000L))

                assertEquals(5_000L, parent.totalSolveWork())
                assertEquals(5_000L, child.totalSolveWork())
                assertEquals(7_000L, child.nodeQueryWorkBudget())
                assertEquals(2_000.0, sink.snapshot().lp.rootWorkOps.sum)
                assertEquals(3_000.0, sink.snapshot().lp.overheadOps.sum)
            }
        }
    }

    @Test
    fun `standalone completion should reduce remaining root work without duplicate accounting`() {
        budgetedEngine(
            SolveStatsSink(backend = "completion-root"),
            LpPlan(bounding = true, rootMaxWork = 12_000L),
        ).use { engine ->
            engine.observeRootSolve(metricSolver(), LpSolveMetrics(workOps = 5_000L))

            assertEquals(4_000L, engine.rootPrimalWorkBudget(additionalWork = 3_000L))
            assertEquals(5_000L, engine.totalSolveWork())
            assertFalse(engine.workSpentExceeds(12_000L, additionalWork = 6_999L))
            assertTrue(engine.workSpentExceeds(12_000L, additionalWork = 7_000L))
            assertFalse(engine.lpWorkAllowanceSpent(additionalWork = 14_999L))
            assertTrue(engine.lpWorkAllowanceSpent(additionalWork = 15_000L))
        }
    }

    @Test
    fun `root primal work should be independent of the adaptive node allowance`() {
        val problem = boundedLpEngineProblem()
        val plan = LpPlan(bounding = true, rootMaxWork = 50_000_000L)
        val engine = LpEngine(
            problem,
            LinearObjective(),
            LpParams(lpPlan = plan),
            SolveStatsSink(backend = "primal-root-allowance"),
        )

        engine.use {
            assertEquals(50_000_000L, it.rootPrimalWorkBudget())
            assertTrue(it.nodeWorkBudget() < it.rootPrimalWorkBudget())
        }
    }

    @Test
    fun `node queries should use the smaller containing and offered native allowances`() {
        val problem = boundedLpEngineProblem()
        var allowance: Long? = null
        val engine = LpEngine(
            problem,
            LinearObjective(intCoefficients = LongArray(problem.numIntVars)),
            LpParams(lpPlan = LpPlan(bounding = true), solveBudgetMillis = 4L),
            SolveStatsSink(backend = "slice-cap"),
            nodeWorkAllowance = { allowance },
        )

        engine.use {
            it.observeRootSolve(metricSolver(), LpSolveMetrics(workOps = 5_000L))
            for ((offered, expected) in listOf(null to null, 30_000L to 15_000L, 500L to 500L, 0L to 1L)) {
                allowance = offered
                assertEquals(15_000L, it.nodeWorkBudget())
                assertEquals(expected, it.nodeQueryWorkBudget())
            }
        }
    }

    @Test
    fun `optional node queries should receive the offered cap after retained construction`() {
        val problem = boundedLpEngineProblem()
        val factory = RecordingLpEngineFactory()
        val engine = LpEngine(
            problem,
            LinearObjective(intCoefficients = longArrayOf(1L, 1L, 1L)),
            LpParams(lpPlan = LpPlan(bounding = true)),
            SolveStatsSink(backend = "native-slice-cap"),
            LpSolveContext(factory, decline),
            nodeWorkAllowance = { 500L },
        )

        val pruned = engine.use { it.pruneNode(PropagationSession(problem), 1.5, -1, true) }

        assertFalse(pruned)
        assertTrue(factory.calls.single { it.kind == EngineConstruction.PERSISTENT }.workLimit > 500L)
        assertEquals(500L, assertNotNull(factory.floatAllowances.single()).work)
    }

    @Test
    fun `separate preparation should preserve the adaptive float query cap`() {
        val problem = boundedLpEngineProblem()
        for ((components, reuse) in listOf(false to false, true to false, false to true)) {
            val factory = RecordingLpEngineFactory()
            LpEngine(
                problem,
                LinearObjective(intCoefficients = longArrayOf(1L, 1L, 1L)),
                LpParams(lpPlan = LpPlan(bounding = true, boundMaxDepth = 0, componentOwnership = components,
                    componentReuse = reuse),
                    solveBudgetMillis = 30_000L),
                SolveStatsSink(backend = "separate-preparation-cap"),
                LpSolveContext(factory, decline),
                rootQueryPolicy = if (components) LpRootQueryPolicy.SHARED_WORK else
                    LpRootQueryPolicy.SEPARATE_PREPARATION,
            ).use { engine ->
                val queryCap = engine.nodeWorkBudget()

                engine.pruneNode(PropagationSession(problem), Double.POSITIVE_INFINITY, -1, true)

                assertTrue(factory.calls.single { it.kind == EngineConstruction.PERSISTENT }.workLimit > queryCap)
                assertEquals(queryCap, assertNotNull(factory.floatAllowances.single()).work)
            }
        }
    }

    @Test
    fun `component preparation without a timed allowance uses the remaining root work grant`() {
        val problem = boundedLpEngineProblem()
        var offered: Long? = null
        LpEngine(
            problem,
            LinearObjective(),
            LpParams(lpPlan = LpPlan(bounding = true, componentOwnership = true, rootMaxWork = 80_000L)),
            SolveStatsSink(backend = "component-preparation-root-grant"),
            nodeWorkAllowance = { offered },
        ).use { engine ->
            engine.observeRootSolve(metricSolver(), LpSolveMetrics(workOps = 5_000L))

            assertEquals(75_000L, engine.nodePreparationWorkBudget())
            offered = 500L
            assertEquals(500L, engine.nodePreparationWorkBudget())
            offered = null
            engine.observeRootSolve(metricSolver(), LpSolveMetrics(workOps = 75_000L))
            assertEquals(1L, engine.nodePreparationWorkBudget())
        }
    }

    @Test
    fun `preparation should respect the remaining grant under its slice policy`() {
        val problem = boundedLpEngineProblem()
        for ((components, reuse) in listOf(false to false, true to false, false to true)) {
            val plan = LpPlan(bounding = true, boundMaxDepth = 0, componentOwnership = components,
                componentReuse = reuse)
            var offered: Long? = null
            LpEngine(
                problem,
                LinearObjective(),
                LpParams(lpPlan = plan, solveBudgetMillis = 4L),
                SolveStatsSink(backend = "preparation-remaining"),
                nodeWorkAllowance = { offered },
                rootQueryPolicy = if (components) LpRootQueryPolicy.SHARED_WORK else
                    LpRootQueryPolicy.SEPARATE_PREPARATION,
            ).use { engine ->
                engine.observeRootSolve(metricSolver(), LpSolveMetrics(workOps = 5_000L))

                for ((grant, expected) in listOf(null to 15_000L, 30_000L to 15_000L, 500L to 500L, 0L to 1L)) {
                    offered = grant
                    assertEquals(if (reuse) 15_000L else expected, engine.nodePreparationWorkBudget())
                }
            }
        }
    }

    @Test
    fun `adaptive node queries should grow only a pivoting work stop without a point`() {
        for (policy in LpRootQueryPolicy.entries) {
            for (termination in LpFloatTermination.entries + listOf(null)) {
                for (pivots in listOf(0, 1)) {
                    budgetedEngine(
                        SolveStatsSink(backend = "interrupted-root"),
                        LpPlan(bounding = true, boundMaxDepth = 0),
                        policy,
                        budgetMillis = 30_000L,
                    ).use { engine ->
                        val before = engine.nodeWorkBudget()

                        engine.observeNodeWorkStop(metricSolver(termination = termination, pivots = pivots))

                        val grows = (policy == LpRootQueryPolicy.SHARED_WORK ||
                            policy == LpRootQueryPolicy.INTERRUPTED_WORK) &&
                            termination == LpFloatTermination.WORK && pivots > 0
                        assertEquals(if (grows) before * 2 else before, engine.nodeWorkBudget(),
                            "$policy $termination pivots=$pivots")
                    }
                }
            }
        }
    }

    @Test
    fun `measured root query bounds should honor certificate acceptance`() {
        for (accept in listOf(true, false)) {
            val problem = boundedLpEngineProblem()
            val factory = RecordingLpEngineFactory()
            LpEngine(
                problem,
                LinearObjective(intCoefficients = longArrayOf(1L, 1L, 1L)),
                LpParams(lpPlan = LpPlan(bounding = true, boundMaxDepth = 0), solveBudgetMillis = 30_000L),
                SolveStatsSink(backend = "measured-root-bound"),
                if (accept) LpSolveContext(factory) else LpSolveContext(factory, decline),
                rootQueryPolicy = LpRootQueryPolicy.INTERRUPTED_WORK,
            ).use { engine ->
                engine.pruneNode(PropagationSession(problem), Double.POSITIVE_INFINITY, -1, true)

                assertEquals(if (accept) 2.0 else null, engine.measuredRootLowerBound)
            }
        }
    }

    @Test
    fun `sliced LP queries should cap numerical work independently of component preparation`() {
        for ((reuse, cutoff) in listOf(false to Double.POSITIVE_INFINITY, false to 100.0,
            true to Double.POSITIVE_INFINITY, true to 100.0)) {
            val problem = boundedLpEngineProblem()
            val factory = RecordingLpEngineFactory()
            val params = BacktrackParams(
                lpPlan = LpPlan(bounding = true, rootCutHarvest = false, componentReuse = reuse),
                objectiveBoundSupplier = { cutoff },
            )
            val solver = BacktrackSolver(problem.bake(), LpSolveContext(factory, decline))

            solver.resumable(LinearObjective(intCoefficients = longArrayOf(1L, 1L, 1L)), params).use { search ->
                while (factory.floatAllowances.isEmpty() && !search.isDone) {
                    search.runSlice(ExecutionControl.Never, 1_000L, 64L) {}
                }
            }

            assertTrue(factory.floatAllowances.isNotEmpty())
            assertEquals(reuse || cutoff.isFinite(), factory.floatAllowances.first() != null)
        }
    }

    @Test
    fun `node construction and certification should stay isolated per engine`() {
        val problem = boundedLpEngineProblem()
        val objective = LinearObjective(intCoefficients = longArrayOf(1L, 1L, 1L))
        val params = LpParams(
            lpPlan = LpPlan(bounding = true),
            randomSeed = 31L,
        )
        val rejectingFactory = RecordingLpEngineFactory()
        val rejecting = LpEngine(
            problem,
            objective,
            params,
            SolveStatsSink(backend = "rejecting"),
            LpSolveContext(rejectingFactory, decline),
        )
        val production = LpEngine(
            problem,
            objective,
            params,
            SolveStatsSink(backend = "production"),
        )

        val rejected = rejecting.pruneNode(PropagationSession(problem), 1.5, -1, true)
        val accepted = production.pruneNode(PropagationSession(problem), 1.5, -1, true)

        assertFalse(rejected)
        assertTrue(accepted)
        assertEquals(1, rejectingFactory.calls.count { it.kind == EngineConstruction.PERSISTENT })
        val construction = rejectingFactory.calls.single { it.kind == EngineConstruction.PERSISTENT }
        assertEquals(LpZeroObjectivePricing.MIN_BOUND_SUPPORT, construction.zeroObjectivePricing)
        assertEquals(31L, construction.tieSeed)
    }

    @Test
    fun `root bound should use the injected tableau factory and policy`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(5, 9)),
            factors = emptyArray(),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(1L))
        val factory = RecordingLpEngineFactory()
        val engine = LpEngine(
            problem,
            objective,
            LpParams(
                lpPlan = LpPlan(bounding = true),
                randomSeed = 37L,
                zeroObjectivePricing = LpZeroObjectivePricing.LARGEST_PIVOT,
            ),
            SolveStatsSink(backend = "root"),
            LpSolveContext(factory, decline),
            nodeWorkAllowance = { 1L },
        )

        val bound = engine.rootLpRelaxationBound(checkNotNull(engine.lpRelaxer), emptyList())

        assertTrue(bound.isNaN())
        assertEquals(1, factory.calls.count { it.kind == EngineConstruction.TABLEAU })
        val construction = factory.calls.single { it.kind == EngineConstruction.TABLEAU }
        assertEquals(LpZeroObjectivePricing.LARGEST_PIVOT, construction.zeroObjectivePricing)
        assertEquals(37L, construction.tieSeed)
        assertTrue(construction.workLimit > 1L)
    }

    @Test
    fun `root infeasibility should not bypass the injected policy`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 1)),
            factors = arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 2)),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(0L))
        val factory = RecordingLpEngineFactory()
        val rejecting = LpEngine(
            problem,
            objective,
            LpParams(
                lpPlan = LpPlan(bounding = true),
                randomSeed = 41L,
                zeroObjectivePricing = LpZeroObjectivePricing.LARGEST_PIVOT,
            ),
            SolveStatsSink(backend = "rejecting"),
            LpSolveContext(factory, decline),
        )
        val production = LpEngine(
            problem,
            objective,
            LpParams(lpPlan = LpPlan(bounding = true)),
            SolveStatsSink(backend = "production"),
        )

        assertFalse(rejecting.rootLpInfeasibleNoBake(ExecutionControl.Never))
        assertTrue(production.rootLpInfeasibleNoBake(ExecutionControl.Never))
        assertEquals(1, factory.calls.count { it.kind == EngineConstruction.TABLEAU })
        val construction = factory.calls.single { it.kind == EngineConstruction.TABLEAU }
        assertEquals(LpZeroObjectivePricing.LARGEST_PIVOT, construction.zeroObjectivePricing)
        assertEquals(41L, construction.tieSeed)
    }

    @Test
    fun `leaf certification should report unbounded only with a ray the factors keep`() {
        // r >= 0 with no row above it: minimizing -r descends without limit, minimizing nothing does not.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Linear(longArrayOf(), intArrayOf(), doubleArrayOf(1.0), intArrayOf(0), LinearOp.GE, 1L),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
        )
        val cases = listOf(doubleArrayOf(-1.0) to LpVerdict.UNBOUNDED, doubleArrayOf(0.0) to LpVerdict.ATTAINED_OPTIMUM)
        for ((costs, verdict) in cases) {
            val engine = LpEngine(
                problem,
                LinearObjective(realCoefficients = costs),
                LpParams(lpPlan = LpPlan(bounding = true, realResidual = true, componentSplit = false)),
                SolveStatsSink(backend = "leaf-unbounded"),
            )

            val leaf = engine.use { it.leafCertify(PropagationSession(problem)) }

            assertEquals(verdict, leaf.verdict)
            assertEquals(verdict == LpVerdict.UNBOUNDED, leaf.direction != null)
        }
    }

    @Test
    fun `leaf certification should accept numerical refutations only for tolerance requests`() {
        val problem = Problem(
            0, 0, emptyArray(),
            arrayOf<Factor>(
                Linear(longArrayOf(), intArrayOf(), doubleArrayOf(3.0), intArrayOf(0), LinearOp.GE, 4L),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(1.0),
        )
        for (numerical in listOf(false, true)) {
            val context = LpSolveContext(certificationPolicy = decline, numericalDeductions = numerical)
            LpEngine(problem, LinearObjective(realCoefficients = doubleArrayOf(0.0)),
                LpParams(lpPlan = LpPlan(bounding = true, realResidual = true, componentSplit = false)),
                SolveStatsSink("numerical-leaf-refutation"), context).use { engine ->
                val result = engine.leafCertify(PropagationSession(problem), toleranceCheck = { true })

                assertEquals(if (numerical) LpVerdict.INFEASIBLE else LpVerdict.INDETERMINATE, result.verdict)
            }
        }
    }

    @Test
    fun `leaf certification should report an accepted float optimum as a tolerance optimum`() {
        // 3x = 1 with x in [0, 2], minimizing x; every exact certifier is vetoed, so only float evidence can decide.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Linear(longArrayOf(), intArrayOf(), doubleArrayOf(3.0), intArrayOf(0), LinearOp.EQ, 1L),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(2.0),
        )
        val engine = LpEngine(
            problem,
            LinearObjective(realCoefficients = doubleArrayOf(1.0)),
            LpParams(lpPlan = LpPlan(bounding = true, realResidual = true, componentSplit = false)),
            SolveStatsSink(backend = "leaf-tolerance"),
            LpSolveContext(certificationPolicy = LpCertificationPolicy { _, _ -> false }),
        )

        val leaf = engine.use { it.leafCertify(PropagationSession(problem), toleranceCheck = { true }) }

        assertEquals(LpVerdict.TOLERANCE_OPTIMUM, leaf.verdict)
        assertEquals(1.0 / 3.0, leaf.reals.single(), 1e-12)
    }

    @Test
    fun `leaf certification should use the caller cancellation without an internal work ceiling`() {
        val problem = boundedLpEngineProblem()
        val factory = RecordingLpEngineFactory()
        val engine = LpEngine(
            problem,
            LinearObjective(intCoefficients = LongArray(problem.numIntVars)),
            LpParams(lpPlan = LpPlan(bounding = true, realResidual = true, componentSplit = false)),
            SolveStatsSink(backend = "leaf-work"),
            LpSolveContext(factory, decline),
            nodeWorkAllowance = { 1L },
        )

        engine.use { it.leafCertify(PropagationSession(problem)) }

        assertEquals(0L, factory.calls.single { it.kind == EngineConstruction.PERSISTENT }.workLimit)
    }

    @Test
    fun `open bound probes should use one injected retained owner and preserve proof policy`() {
        val rows = listOf(
            Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0),
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 10),
        )
        val bounds = arrayOf(OpenIntBounds(null, null), OpenIntBounds(null, null))
        val factory = RecordingLpEngineFactory()

        val rejected = tightenOpenIntBounds(
            bounds,
            rows,
            context = LpSolveContext(factory, decline),
        )
        val accepted = tightenOpenIntBounds(bounds, rows)

        assertEquals(null, rejected.bounds[0].hi)
        assertEquals(5L, accepted.bounds[0].hi)
        assertEquals(1, factory.calls.count { it.kind == EngineConstruction.PERSISTENT })
        assertEquals(0, factory.calls.count { it.kind == EngineConstruction.GENERAL })
    }
}
