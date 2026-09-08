package com.eignex.klause.lp.bounding

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.OpenIntBounds
import com.eignex.klause.lp.engine.EngineConstruction
import com.eignex.klause.lp.engine.LpCertificationPolicy
import com.eignex.klause.lp.engine.LpSolveContext
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

    @Test
    fun `node construction and certification stay isolated per engine`() {
        val problem = boundedProblem()
        val objective = LinearObjective(intCoefficients = longArrayOf(1L, 1L, 1L))
        val params = LpParams(lpPlan = LpPlan(bounding = true))
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
            LpParams(lpPlan = LpPlan(bounding = true)),
            SolveStatsSink(backend = "root"),
            LpSolveContext(factory, decline),
        )

        val bound = engine.rootLpRelaxationBound(checkNotNull(engine.lpRelaxer), emptyList())

        assertTrue(bound.isNaN())
        assertEquals(1, factory.calls.count { it.kind == EngineConstruction.TABLEAU })
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
            LpParams(lpPlan = LpPlan(bounding = true)),
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
