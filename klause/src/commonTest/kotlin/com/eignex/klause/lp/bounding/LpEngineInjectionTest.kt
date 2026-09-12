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
import kotlin.test.assertTrue

class LpEngineInjectionTest {

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

        val construction = factory.calls.single { it.kind == EngineConstruction.GENERAL }
        assertEquals(LpZeroObjectivePricing.LARGEST_PIVOT, construction.zeroObjectivePricing)
        assertEquals(47L, construction.tieSeed)
    }

    @Test
    fun `open bound probes use the injected general factory and policy`() {
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
        assertTrue(factory.calls.any { it.kind == EngineConstruction.GENERAL })
    }
}
