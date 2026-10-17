package com.eignex.klause.lp.bounding

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.OpenIntBounds
import com.eignex.klause.lp.cut.AggregationMirSeparator
import com.eignex.klause.lp.cut.CutContext
import com.eignex.klause.lp.cut.CutSeparator
import com.eignex.klause.lp.engine.Basis
import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.lp.engine.EngineConstruction
import com.eignex.klause.lp.engine.LpCertificationPolicy
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
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LpEngineTest {
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

    private val decline = LpCertificationPolicy { _, _ -> false }

    private fun boundedProblem(): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 3,
        intDomains = Array(3) { IntDomain(0, 1) },
        factors = arrayOf<Factor>(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 1),
        ),
    )

    private fun metricSolver(workOps: Long = 0L): LpSolver = object : LpSolver {
        override val infeasibleRay: DoubleArray? = null
        override val lastWorkOps: Long = workOps
        override fun solve(warm: Basis?) = null
        override fun solvePrimal(warm: Basis?) = null
    }

    private fun budgetedEngine(sink: SolveStatsSink, plan: LpPlan = LpPlan(bounding = true)): LpEngine {
        val problem = boundedProblem()
        return LpEngine(
            problem,
            LinearObjective(intCoefficients = LongArray(problem.numIntVars)),
            LpParams(lpPlan = plan, solveBudgetMillis = 4L),
            sink,
        )
    }

    @Test
    fun `root and node separation should charge their containing token and LP owner once`() {
        for (root in listOf(true, false)) {
            val problem = boundedProblem()
            val sink = SolveStatsSink(backend = "separation-work")
            var charged = 0L
            val token = object : Cancellation {
                override fun isCancelled(): Boolean = false
                override fun workMeter(): WorkMeter = WorkMeter { charged += it }
            }
            val session = PropagationSession(problem)
            if (!root) session.pinInt(0, 1L)
            var calls = 0
            LpEngine(
                problem,
                LinearObjective(intCoefficients = longArrayOf(1L, 1L, 1L)),
                LpParams(lpPlan = LpPlan(bounding = true, cuts = true), solveBudgetMillis = 1_000L),
                sink,
            ).use { engine ->
                val separator = object : CutSeparator {
                    override fun separate(ctx: CutContext): List<Cut> {
                        calls++
                        val callerBefore = charged
                        val totalBefore = engine.totalSolveWork()
                        val nodeBefore = engine.pendingNodeSolveWork()
                        val overheadBefore = sink.snapshot().lp.overheadOps.sum

                        assertTrue(ctx.spendSeparationWork(7L))

                        assertEquals(7L, charged - callerBefore)
                        assertEquals(7L, engine.totalSolveWork() - totalBefore)
                        assertEquals(if (root) 0L else 7L, engine.pendingNodeSolveWork() - nodeBefore)
                        assertEquals(7.0, sink.snapshot().lp.overheadOps.sum - overheadBefore)
                        return emptyList()
                    }
                }
                val relaxer = assertNotNull(engine.lpRelaxer)
                if (root) {
                    engine.harvestRootCuts(relaxer, session, listOf(separator), false, false, token)
                } else {
                    val relaxation = relaxer.build(session)
                    separator.separate(CutContext(
                        problem, relaxation, doubleArrayOf(), session, engine.separationWork(token, root = false),
                    ))
                }
            }
            assertEquals(1, calls)
        }
    }

    @Test
    fun `root separation should stop later families on containing cancellation`() {
        val problem = boundedProblem()
        val sink = SolveStatsSink(backend = "root-separation-stop")
        var stopped = false
        val token = Cancellation { stopped }
        var calls = 0
        LpEngine(
            problem,
            LinearObjective(intCoefficients = longArrayOf(1L, 1L, 1L)),
            LpParams(lpPlan = LpPlan(bounding = true, cuts = true), solveBudgetMillis = 1_000L),
            sink,
        ).use { engine ->
            val first = object : CutSeparator {
                override fun separate(ctx: CutContext): List<Cut> {
                    calls++
                    assertTrue(ctx.spendSeparationWork(7L))
                    stopped = true
                    assertFalse(ctx.spendSeparationWork())
                    return emptyList()
                }
            }
            val second = object : CutSeparator {
                override fun separate(ctx: CutContext): List<Cut> = error("separation continued after stop")
            }

            val cuts = engine.harvestRootCuts(
                assertNotNull(engine.lpRelaxer),
                PropagationSession(problem),
                listOf(first, second),
                false,
                false,
                token,
            )

            assertTrue(cuts.isEmpty())
        }
        assertEquals(1, calls)
    }

    @Test
    fun `interrupted node MIR should retain its gate without pruning or continuing families`() {
        val problem = Problem(0, 3, Array(3) { IntDomain(0, 1) }, arrayOf<Factor>(
            Linear(intArrayOf(2, 2), intArrayOf(0, 1), LinearOp.LE, 3),
        ))
        val sink = SolveStatsSink(backend = "node-separation-stop")
        val session = PropagationSession(problem)
        session.pinInt(2, 0L)
        LpEngine(
            problem,
            LinearObjective(intCoefficients = longArrayOf(-1L, -1L, 0L)),
            LpParams(lpPlan = LpPlan(bounding = true, cuts = true), solveBudgetMillis = 1_000L),
            sink,
        ).use { engine ->
            val mir = engine.lpSeparators.indexOfFirst { it is AggregationMirSeparator }
            assertTrue(mir >= 0)
            for (family in engine.lpSeparators.indices) {
                repeat(LpSeparatorGate.DEFAULT_WARMUP - 1) {
                    assertTrue(engine.lpSeparatorGate.shouldRun(family))
                    engine.lpSeparatorGate.record(family, false)
                }
            }
            var stopped = false
            var totalBefore = 0L
            var nodeBefore = 0L
            var overheadBefore = 0.0
            // MIR reserves 49 row slots and 16 cut slots before scanning factors.
            val reservation = 49L + 16L
            val token = object : Cancellation {
                override fun isCancelled(): Boolean = stopped
                override fun workMeter(): WorkMeter = WorkMeter { units ->
                    if (units == reservation) {
                        totalBefore = engine.totalSolveWork()
                        nodeBefore = engine.pendingNodeSolveWork()
                        overheadBefore = sink.snapshot().lp.overheadOps.sum
                        stopped = true
                    }
                }
            }

            val outcome = engine.lpBoundAndFix(
                assertNotNull(engine.lpRelaxer), session, 10.0, sink, -1, true, token, cutsAllowed = true,
            )

            assertTrue(stopped)
            assertFalse(outcome.prune)
            assertEquals(null, outcome.explanation)
            assertEquals(reservation, engine.totalSolveWork() - totalBefore)
            assertEquals(reservation, engine.pendingNodeSolveWork() - nodeBefore)
            assertEquals(reservation.toDouble(), sink.snapshot().lp.overheadOps.sum - overheadBefore)
            for (family in 0 until mir) assertFalse(engine.lpSeparatorGate.isEnabled(family))
            for (family in mir until engine.lpSeparators.size) assertTrue(engine.lpSeparatorGate.isEnabled(family))
        }
    }

    @Test
    fun `separation should not renew an exhausted LP allowance`() {
        for (root in listOf(true, false)) {
            var charged = 0L
            val token = object : Cancellation {
                override fun isCancelled(): Boolean = false
                override fun workMeter(): WorkMeter = WorkMeter { charged += it }
            }
            budgetedEngine(SolveStatsSink(backend = "separation-exhausted")).use { engine ->
                engine.observeRootSolve(metricSolver(), LpSolveMetrics(workOps = 20_000L))

                val allowed = engine.separationWork(token, root)(1L)

                assertFalse(allowed)
                assertEquals(0L, charged)
                assertEquals(20_000L, engine.totalSolveWork())
            }
        }
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
            parent.forObjective(LinearObjective(), Cancellation.Never, rootProbe = true).use { child ->
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
        val problem = boundedProblem()
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
        val problem = boundedProblem()
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
        val problem = boundedProblem()
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
    fun `sliced LP queries should receive a native slice cap only behind a finite external cutoff`() {
        for (cutoff in listOf(Double.POSITIVE_INFINITY, 100.0)) {
            val problem = boundedProblem()
            val factory = RecordingLpEngineFactory()
            val params = BacktrackParams(
                lpPlan = LpPlan(bounding = true, rootCutHarvest = false),
                objectiveBoundSupplier = { cutoff },
            )
            val solver = BacktrackSolver(problem.bake(), LpSolveContext(factory, decline))

            solver.resumable(LinearObjective(intCoefficients = longArrayOf(1L, 1L, 1L)), params).use { search ->
                while (factory.floatAllowances.isEmpty() && !search.isDone) {
                    search.runSlice(Cancellation.Never, 1_000L, 64L) {}
                }
            }

            assertTrue(factory.floatAllowances.isNotEmpty())
            assertEquals(cutoff.isFinite(), factory.floatAllowances.first() != null)
        }
    }

    @Test
    fun `node construction and certification should stay isolated per engine`() {
        val problem = boundedProblem()
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

        assertFalse(rejecting.rootLpInfeasibleNoBake(Cancellation.Never))
        assertTrue(production.rootLpInfeasibleNoBake(Cancellation.Never))
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
    fun `leaf certification should cap its solve by work`() {
        val problem = boundedProblem()
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

        assertTrue(factory.calls.single { it.kind == EngineConstruction.PERSISTENT }.workLimit > 1L)
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
