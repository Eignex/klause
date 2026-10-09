package com.eignex.klause.lp.bounding

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.OpenIntBounds
import com.eignex.klause.lp.engine.Basis
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
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LpEngineInjectionTest {
    @Test
    fun `a fixed fractional real leaf retains its exact contradiction`() {
        val problem = Problem(
            0, 0, emptyArray(),
            arrayOf(Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(0.1),
                LinearOp.EQ, 0.010000000000000002)),
            numRealVars = 1, realLower = doubleArrayOf(0.1), realUpper = doubleArrayOf(0.1),
        )
        val sink = SolveStatsSink(backend = "fractional-leaf")
        val session = PropagationSession(problem)

        LpEngine(problem, LinearObjective(),
            LpParams(lpPlan = LpPlan(bounding = true, realResidual = true)), sink).use { engine ->
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
        // 4 ms at a quarter share is a 20k-operation allowance.
        return LpEngine(
            problem,
            LinearObjective(intCoefficients = LongArray(problem.numIntVars)),
            LpParams(lpPlan = plan, solveBudgetMillis = 4L),
            sink,
        )
    }

    @Test
    fun `root work spending the LP allowance is reported before node work`() {
        val sink = SolveStatsSink(backend = "root-allowance")

        budgetedEngine(sink).use { it.observeRootSolve(metricSolver(), LpSolveMetrics(workOps = 20_000L)) }

        assertTrue(sink.lp.snapshot().workAllowanceSpent)
    }

    @Test
    fun `root work short of the LP allowance leaves it unspent`() {
        val sink = SolveStatsSink(backend = "root-allowance-left")

        budgetedEngine(sink).use { it.observeRootSolve(metricSolver(), LpSolveMetrics(workOps = 19_999L)) }

        assertFalse(sink.lp.snapshot().workAllowanceSpent)
    }

    @Test
    fun `node solves are capped at the remaining LP allowance`() {
        val engine = budgetedEngine(SolveStatsSink(backend = "node-cap"))

        val budget = engine.use {
            it.observeRootSolve(metricSolver(), LpSolveMetrics(workOps = 5_000L))
            it.nodeWorkBudget()
        }

        assertEquals(15_000L, budget)
    }

    @Test
    fun `the LP allowance caps node solves without an adaptive budget`() {
        val engine = budgetedEngine(
            SolveStatsSink(backend = "node-cap-fixed"),
            LpPlan(bounding = true, boundAdaptiveWork = false),
        )

        assertEquals(20_000L, engine.use { it.nodeWorkBudget() })
    }

    @Test
    fun `a spent LP allowance still bounds node solves`() {
        val engine = budgetedEngine(SolveStatsSink(backend = "node-cap-spent"))

        val budget = engine.use {
            it.observeRootSolve(metricSolver(), LpSolveMetrics(workOps = 50_000L))
            it.nodeWorkBudget()
        }

        assertEquals(1L, budget, "a solve limit of 0 would mean unbounded")
    }

    @Test
    fun `node overhead spends the LP allowance`() {
        val sink = SolveStatsSink(backend = "overhead-allowance")

        budgetedEngine(sink).use { it.noteNodeOverhead(20_000L) }

        assertTrue(sink.lp.snapshot().workAllowanceSpent)
    }

    private fun accountingEngine(backend: String): LpEngine = LpEngine(
        Problem(numBoolVars = 0, numIntVars = 0, intDomains = emptyArray(), factors = emptyArray()),
        LinearObjective(),
        LpParams(),
        SolveStatsSink(backend = backend),
    )

    @Test
    fun `node construction and certification stay isolated per engine`() {
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
    fun `root bound uses the injected tableau factory and policy`() {
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
        )

        val bound = engine.rootLpRelaxationBound(checkNotNull(engine.lpRelaxer), emptyList())

        assertTrue(bound.isNaN())
        assertEquals(1, factory.calls.count { it.kind == EngineConstruction.TABLEAU })
        val construction = factory.calls.single { it.kind == EngineConstruction.TABLEAU }
        assertEquals(LpZeroObjectivePricing.LARGEST_PIVOT, construction.zeroObjectivePricing)
        assertEquals(37L, construction.tieSeed)
    }

    @Test
    fun `root work is cumulative without entering the node charge`() {
        val engine = accountingEngine("root-work")

        engine.observeRootSolve(metricSolver(13L))

        assertEquals(13L, engine.totalSolveWork())
        assertEquals(0L, engine.pendingNodeSolveWork())
        assertTrue(engine.workSpentExceeds(13L))
    }

    @Test
    fun `node overhead counts as node work and is reported apart from the simplex`() {
        val sink = SolveStatsSink(backend = "node-overhead")
        val engine = LpEngine(Problem(0, 0, emptyArray(), emptyArray()), LinearObjective(), LpParams(), sink)

        engine.use { it.noteNodeOverhead(11L) }

        assertEquals(
            listOf(11L, 11L, 11.0, 0.0),
            listOf(
                engine.pendingNodeSolveWork(),
                engine.totalSolveWork(),
                sink.snapshot().lp.overheadOps.sum,
                sink.snapshot().lp.workOps.sum,
            ),
        )
    }

    @Test
    fun `root work uses supplied metrics and saturates`() {
        val engine = accountingEngine("root-work-override")
        val solver = metricSolver(1L)

        engine.observeRootSolve(solver, LpSolveMetrics(workOps = Long.MAX_VALUE - 3L))
        engine.observeRootSolve(solver, LpSolveMetrics(workOps = 7L))

        assertEquals(Long.MAX_VALUE, engine.totalSolveWork())
        assertEquals(0L, engine.pendingNodeSolveWork())
    }

    @Test
    fun `root infeasibility cannot bypass the injected policy`() {
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
    fun `gated residual uses the injected pricing policy`() {
        val problem = Problem(
            numBoolVars = 1,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                ReifiedRealLinear(
                    aux = 0,
                    vars = IntArray(0),
                    intCoeffs = DoubleArray(0),
                    realVars = intArrayOf(0),
                    realCoeffs = doubleArrayOf(1.0),
                    op = LinearOp.GE,
                    bound = 2.0,
                ),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(1.0),
        )
        val factory = RecordingLpEngineFactory()
        val engine = LpEngine(
            problem,
            LinearObjective(intCoefficients = LongArray(0)),
            LpParams(
                lpPlan = LpPlan(bounding = true, realResidual = true),
                randomSeed = 43L,
                zeroObjectivePricing = LpZeroObjectivePricing.LARGEST_PIVOT,
            ),
            SolveStatsSink(backend = "gated"),
            LpSolveContext(factory, decline),
        )

        engine.use {
            assertTrue(it.gatedResidual(PropagationSession(problem)) != null)
        }

        val construction = factory.calls.single { it.kind == EngineConstruction.PERSISTENT }
        assertEquals(LpZeroObjectivePricing.LARGEST_PIVOT, construction.zeroObjectivePricing)
        assertEquals(43L, construction.tieSeed)
    }

    @Test
    fun `leaf certification uses the injected pricing policy`() {
        val problem = boundedProblem()
        val factory = RecordingLpEngineFactory()
        val engine = LpEngine(
            problem,
            LinearObjective(intCoefficients = LongArray(problem.numIntVars)),
            LpParams(
                lpPlan = LpPlan(bounding = true, realResidual = true),
                randomSeed = 47L,
                zeroObjectivePricing = LpZeroObjectivePricing.LARGEST_PIVOT,
            ),
            SolveStatsSink(backend = "leaf"),
            LpSolveContext(factory, decline),
        )

        engine.use {
            it.leafCertify(PropagationSession(problem))
        }

        val construction = factory.calls.single { it.kind == EngineConstruction.PERSISTENT }
        assertEquals(LpZeroObjectivePricing.LARGEST_PIVOT, construction.zeroObjectivePricing)
        assertEquals(47L, construction.tieSeed)
    }

    @Test
    fun `leaf certification reports unbounded only with a ray the factors keep`() {
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
    fun `leaf certification reports a float optimum its tolerance check accepts as a tolerance optimum`() {
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
    fun `leaf certification caps its solve by work`() {
        val problem = boundedProblem()
        val factory = RecordingLpEngineFactory()
        val engine = LpEngine(
            problem,
            LinearObjective(intCoefficients = LongArray(problem.numIntVars)),
            LpParams(lpPlan = LpPlan(bounding = true, realResidual = true, componentSplit = false)),
            SolveStatsSink(backend = "leaf-work"),
            LpSolveContext(factory, decline),
        )

        engine.use { it.leafCertify(PropagationSession(problem)) }

        assertTrue(factory.calls.single { it.kind == EngineConstruction.PERSISTENT }.workLimit > 0L)
    }

    @Test
    fun `open bound probes use one injected retained owner and preserve proof policy`() {
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
