package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.table.Table
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.baked
import com.eignex.klause.solver.result.PresolveInvocationOutcome
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class BoundedIntegerProbingTest {
    @Test
    fun `interior probes should preserve both endpoints and exclude unsupported shared values`() {
        val budget = PresolveBudget(Long.MAX_VALUE)
        val source = Problem(
            0, 2, Array(2) { IntDomain(0, 2) },
            listOf(
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0),
                Table(intArrayOf(0, 1), longArrayOf(0, 0, 0, 1, 0, 2, 1, 0, 1, 2, 2, 0, 2, 1, 2, 2)),
            ),
        ).bake(budget.orSpent(Cancellation.Never))

        val result = RootBaker.reseedBounded(source, BakeConfig())

        assertEquals(List(2) { listOf(0L, 2L) }, (0..1).map { v ->
            (0L..2L).filter { it in result.rootIntDomain(v) }
        })
    }

    @Test
    fun `unmetered preparation should leave the optional pilot unfunded`() {
        val source = Problem(
            0, 3, Array(3) { IntDomain(0, 1) },
            listOf(
                Table(intArrayOf(0, 1), longArrayOf(0, 1, 1, 0)),
                Table(intArrayOf(1, 2), longArrayOf(0, 1, 1, 0)),
                Table(intArrayOf(2, 0), longArrayOf(0, 1, 1, 0)),
            ),
        ).bake()

        val result = RootBaker.reseedBounded(source, BakeConfig())

        assertSame(source, result)
    }

    @Test
    fun `completed root refutation should remain terminal`() {
        val budget = PresolveBudget(Long.MAX_VALUE)
        val source = Problem(
            0, 3, Array(3) { IntDomain(0, 1) },
            listOf(
                Table(intArrayOf(0, 1), longArrayOf(0, 1, 1, 0)),
                Table(intArrayOf(1, 2), longArrayOf(0, 1, 1, 0)),
                Table(intArrayOf(2, 0), longArrayOf(0, 1, 1, 0)),
            ),
        ).bake(budget.orSpent(Cancellation.Never))

        val result = RootBaker.reseedBounded(source, BakeConfig())

        assertIs<PropagationResult.Unsat>(result.baked)
        assertEquals(PresolveInvocationOutcome.REFUTED, budget.invocations.single().outcome)
    }

    @Test
    fun `unproductive probing should stop without publishing hypothetical pins`() {
        val budget = PresolveBudget(Long.MAX_VALUE)
        val source = Problem(0, 100, Array(100) { IntDomain(0, 3) }, emptyList())
            .bake(budget.orSpent(Cancellation.Never))

        val result = RootBaker.reseedBounded(source, BakeConfig())

        assertEquals(source, result)
        assertEquals(128L, budget.probeCalls)
        assertEquals(PresolveInvocationOutcome.INTERRUPTED, budget.invocations.single().outcome)
    }

    @Test
    fun `a completed failed probe should retain its exclusion at the probe cap`() {
        for (cap in listOf(1, 2)) {
            val budget = PresolveBudget(Long.MAX_VALUE)
            val source = Problem(
                0, 2, Array(2) { IntDomain(0, 3) },
                listOf(
                    Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0),
                    Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 2),
                ),
            ).bake(budget.orSpent(Cancellation.Never))

            val result = RootBaker.reseedBounded(source, BakeConfig(probeTotalBudget = cap))

            assertEquals(cap, (0..1).count { result.rootIntDomain(it).min >= 1L })
            assertEquals(cap.toLong(), budget.probeCalls)
            assertEquals(PresolveInvocationOutcome.INTERRUPTED, budget.invocations.single().outcome)
        }
    }
}
