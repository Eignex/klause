package com.eignex.klause.lp.bounding

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SparseLpAdmissionTest {
    @Test
    fun `estimates retain disconnected dimensions and row intersection risk`() {
        val model = LpBuilder().apply {
            repeat(5) { addVar(0L, 10L) }
            addRow(intArrayOf(0, 1), longArrayOf(1L, 1L), Relation.LE, 3L)
            addRow(intArrayOf(2, 3), longArrayOf(2L, 1L), Relation.LE, 4L)
        }.build(Sense.MINIMIZE)

        val estimate = estimateSparseLpAdmission(model)

        assertTrue(estimate.admitted)
        assertEquals(4L, estimate.metrics["Nonzeros"])
        assertEquals(3L, estimate.metrics["Components"])
        assertEquals(1L, estimate.metrics["LargestComponentRows"])
        assertEquals(2L, estimate.metrics["LargestComponentColumns"])
        assertEquals(4L, estimate.metrics["RowIntersectionRisk"])
    }

    @Test
    fun `ordinary root bounding consumes a sparse trial certificate beyond the size proxy`() {
        val problem = triangle()
        val session = PropagationSession(problem)
        val sink = SolveStatsSink("sparse-trial")

        LpEngine(problem, LinearObjective(intCoefficients = longArrayOf(1, 1, 1)),
            LpParams(lpConfig = LpConfig.parse("default,+sparse-trial")), sink).use { engine ->
            assertTrue(engine.params.lpPlan.sparseTrial)
            assertEquals(0, engine.params.lpPlan.boundMaxDepth)
            assertTrue(engine.pruneNode(session, 2.0, -1, true))
        }

        assertEquals(1L, sink.snapshot().lp.sparseTrial["Admitted"])
        assertTrue(sink.snapshot().lp.pruned.sum > 0.0)
        assertTrue(sink.snapshot().lp.workOps.sum > 0.0)
    }

    @Test
    fun `default admission preserves the size refusal`() {
        val plan = LpAutoConfig.resolve(triangle(), LpConfig.AUTO)

        assertFalse(plan.bounding)
        assertFalse(plan.sparseTrial)
    }

    @Test
    fun `cancelled sparse admission supplies no bounding route`() {
        var admission: SparseLpAdmission? = null
        sequence<Unit> {
            admission = prepareSparseLpAdmission(triangle(), null, Cancellation { true })
        }.forEach { }

        assertFalse(requireNotNull(admission).admitted)
        assertEquals(1L, requireNotNull(admission).metrics["DeclineCancelled"])
    }

    @Test
    fun `factor storage is bounded before source assembly`() {
        val row = Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 1)
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) }, List(66_000) { row },
            settings = ProblemSettings(lpCeilingTableauCells = 1L))

        val admission = LpAutoConfig.admission(problem, LpConfig.parse("default,+sparse-trial"), LpPlan())

        assertFalse(admission.plan.bounding)
        assertEquals(1L, requireNotNull(admission.sparseTrial).metrics["DeclineShape"])
    }

    private fun triangle() = Problem(0, 3, Array(3) { IntDomain(0, 1) }, listOf(
        Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
        Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 1),
        Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 1),
    ), settings = ProblemSettings(lpCeilingTableauCells = 1L))
}
