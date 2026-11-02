package com.eignex.klause.backtrack.lp

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpEngine
import com.eignex.klause.lp.bounding.LpParams
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.lp.bounding.SPARSE_LP_MAX_PREPARATION_WORK
import com.eignex.klause.lp.bounding.rootLpRelaxationBound
import com.eignex.klause.lp.engine.LpProjectionMeter
import com.eignex.klause.lp.engine.LpProjectionStop
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpRootComponentsTest {
    @Test
    fun `paid lp work withholds a fresh component preparation grant`() {
        val problem = Problem(0, 6, Array(6) { IntDomain(0, 1) }, listOf(
            Linear(intArrayOf(1, 1, 1), intArrayOf(0, 2, 4), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1, 1), intArrayOf(1, 3, 5), LinearOp.GE, 1),
        )).bake()
        val objective = LinearObjective(intCoefficients = LongArray(6) { 1L })
        LpEngine(problem, objective,
            LpParams(lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true,
                rootCutHarvest = false)), SolveStatsSink("charged-component-admission")).use { engine ->
            assertTrue(engine.prepareRootLayout(Cancellation.Never, Cancellation.Never))
            engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList())
            assertNotNull(engine.lpRoundingProbe(objective, Cancellation.Never))
            val targets = assertNotNull(engine.rootPrimalTargets)
            engine.noteNodeOverhead(SPARSE_LP_MAX_PREPARATION_WORK)
            assertTrue(engine.retainedRootRemainingWork() > 0L)

            val chosen = engine.pickRootComponent(PropagationSession(problem), Cancellation.Never)

            assertNull(chosen)
            assertNull(targets.components)
        }
    }

    @Test
    fun `declared integer components stay contiguous in source coordinates`() {
        val problem = Problem(0, 6, Array(6) { IntDomain(0, 1) }, listOf(
            Linear(intArrayOf(1, 1, 1), intArrayOf(0, 2, 4), LinearOp.LE, 3),
            Linear(intArrayOf(1, 1, 1), intArrayOf(1, 3, 5), LinearOp.LE, 3),
        ))

        val order = assertNotNull(rootComponentOrder(problem, LpProjectionMeter()))

        assertEquals(listOf(0, 2, 4, 1, 3, 5), order.members.toList())
        assertEquals(listOf(0, 3, 6), order.offsets.take(order.groups + 1))
        assertTrue(order.retainedBytes <= rootComponentPreparationBytes(6))
    }

    @Test
    fun `a factor joining components preserves the connected fallback`() {
        val problem = Problem(0, 6, Array(6) { IntDomain(0, 1) }, listOf(
            Linear(intArrayOf(1, 1, 1), intArrayOf(0, 2, 4), LinearOp.LE, 3),
            Linear(intArrayOf(1, 1, 1), intArrayOf(1, 3, 5), LinearOp.LE, 3),
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 2),
        ))

        val order = rootComponentOrder(problem, LpProjectionMeter())

        assertNull(order)
    }

    @Test
    fun `preparation refuses work and storage before allocating component arrays`() {
        val problem = Problem(0, 6, Array(6) { IntDomain(0, 1) }, emptyList())
        for ((work, bytes) in listOf(0L to Long.MAX_VALUE, Long.MAX_VALUE to 0L)) {
            val meter = LpProjectionMeter(work, bytes)

            assertFailsWith<LpProjectionStop> { rootComponentOrder(problem, meter) }

            assertEquals(0L, meter.work)
            assertEquals(0L, meter.allocation)
        }
    }

    @Test
    fun `interrupted component preparation preserves its completed charge`() {
        val problem = Problem(0, 6, Array(6) { IntDomain(0, 1) }, listOf(
            Linear(intArrayOf(1, 1, 1), intArrayOf(0, 2, 4), LinearOp.LE, 3),
            Linear(intArrayOf(1, 1, 1), intArrayOf(1, 3, 5), LinearOp.LE, 3),
        ))
        var polls = 0
        val meter = LpProjectionMeter(1_000L, rootComponentPreparationBytes(6), Cancellation { ++polls >= 2 })

        assertFailsWith<LpProjectionStop> { rootComponentOrder(problem, meter) }

        assertTrue(meter.work > 0L && meter.work <= 1_000L)
        assertTrue(meter.allocation <= rootComponentPreparationBytes(6))
    }

    @Test
    fun `component choice revisits retracted variables and charges membership visits`() {
        val problem = Problem(0, 6, Array(6) { IntDomain(0, 1) }, listOf(
            Linear(intArrayOf(1, 1, 1), intArrayOf(0, 2, 4), LinearOp.LE, 3),
            Linear(intArrayOf(1, 1, 1), intArrayOf(1, 3, 5), LinearOp.LE, 3),
        ))
        val order = assertNotNull(rootComponentOrder(problem, LpProjectionMeter()))
        val session = PropagationSession(problem)
        assertEquals(0, firstRootComponentVariable(order, session, Cancellation.Never))
        session.pinInt(0, 0)
        assertEquals(2, firstRootComponentVariable(order, session, Cancellation.Never))
        session.pinInt(2, 0)
        assertEquals(4, firstRootComponentVariable(order, session, Cancellation.Never))
        session.popToLevel(0)
        val before = session.work

        val variable = firstRootComponentVariable(order, session, Cancellation.Never)

        assertEquals(0, variable)
        assertEquals(before + 1L, session.work)
    }
}
