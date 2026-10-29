package com.eignex.klause.portfolio

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.evidence.SourceCondition
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BooleanProofSchedulerTest {
    private val context = BooleanProofContext(SourceScope(SourceRevision("fixture", "exact-cnf")), "identity:0")
    private val contradiction = Problem(4, 0, emptyArray(), arrayOf(
        Clause(intArrayOf(Lit.make(2, true), Lit.make(3, true))), Clause(intArrayOf(Lit.make(2, true), Lit.make(3, false))),
        Clause(intArrayOf(Lit.make(2, false), Lit.make(3, true))), Clause(intArrayOf(Lit.make(2, false), Lit.make(3, false))),
    )).bake()

    @Test
    fun `retained workers should refute every disjoint task without independent proof authority`() {
        BooleanProofScheduler(contradiction, context, listOf(0, 1), List(2) { BacktrackParams() }).use { scheduler ->
            repeat(4) { task ->
                val step = scheduler.runSlice(task % 2, Cancellation.Never, Long.MAX_VALUE, 5000L)
                assertEquals(task, step.task)
            }

            assertEquals(4, scheduler.completed)
            assertTrue(scheduler.nativeCoverageComplete)
            assertFalse(scheduler.independentlyCheckedCoverageComplete)
        }
    }

    @Test
    fun `a checked witness should preserve caller pins and leave proof coverage open`() {
        val problem = Problem(2, 0, emptyArray(), arrayOf(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))))).bake()
        val assumptions = Assumptions(bools = mapOf(1 to false))
        val scoped = context.copy(scope = context.scope.copy(condition = SourceCondition(bools = assumptions.bools)))
        BooleanProofScheduler(problem, scoped, listOf(0), listOf(BacktrackParams(assumptions = assumptions)))
            .use { scheduler ->
                scheduler.runSlice(0, Cancellation.Never, Long.MAX_VALUE, 5000L)
                val step = scheduler.runSlice(0, Cancellation.Never, Long.MAX_VALUE, 5000L)

                val sample = assertNotNull(step.sample)
                assertTrue(sample.boolValue(0))
                assertFalse(sample.boolValue(1))
                assertEquals(1, scheduler.completed)
                assertFalse(scheduler.nativeCoverageComplete)
                val charged = scheduler.work
                val repeated = scheduler.runSlice(0, Cancellation.Never, Long.MAX_VALUE, 5000L)
                assertEquals(sample, repeated.sample)
                assertEquals(charged, scheduler.work)
            }
    }

    @Test
    fun `worker loss should return the whole paused task without renewing the node allowance`() {
        val budget = NodeBudget(0L)
        BooleanProofScheduler(contradiction, context, listOf(0), listOf(BacktrackParams(nodeBudget = budget)))
            .use { scheduler ->
                val first = scheduler.runSlice(0, Cancellation.Never, Long.MAX_VALUE, 5000L)
                val work = scheduler.work
                scheduler.loseWorker(0)
                val second = scheduler.runSlice(0, Cancellation.Never, Long.MAX_VALUE, 5000L)

                assertEquals(first.task, second.task)
                assertNull(second.sample)
                assertFalse(scheduler.nativeCoverageComplete)
                assertTrue(scheduler.work >= work)
                assertTrue(budget.exhausted())
            }
    }

    @Test
    fun `changed assumptions should revoke accepted coverage and retire private state`() {
        BooleanProofScheduler(contradiction, context, listOf(0), listOf(BacktrackParams())).use { scheduler ->
            scheduler.runSlice(0, Cancellation.Never, Long.MAX_VALUE, 5000L)
            assertEquals(1, scheduler.completed)
            val work = scheduler.work

            scheduler.replaceContext(context.copy(scope = context.scope.copy(
                condition = SourceCondition(bools = mapOf(1 to true)),
            )))

            assertEquals(0, scheduler.completed)
            assertEquals(2, scheduler.pending)
            assertTrue(scheduler.work >= work)
        }
    }

    @Test
    fun `idle workers should leave the allowance for an unfinished sibling`() {
        val factors = arrayOf(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, false))),
        ) + Array(8) { mask ->
            Clause(IntArray(3) { bit -> Lit.make(bit + 2, mask and (1 shl bit) != 0) })
        }
        val problem = Problem(5, 0, emptyArray(), factors).bake()
        BooleanProofScheduler(problem, context, listOf(0), List(2) { BacktrackParams() }).use { scheduler ->
            val result = scheduler.run(Cancellation.Never, 100L, 1L, 1L)

            assertTrue(result is com.eignex.klause.solver.SolveResult.Unsat)
            assertTrue(scheduler.nativeCoverageComplete)
        }
    }

    @Test
    fun `objective contexts should decline before constructing private search`() {
        val optimizing = context.copy(scope = context.scope.copy(
            objective = SourceObjective("cost", ObjectiveSense.MINIMIZE),
        ))

        assertFailsWith<IllegalArgumentException> {
            BooleanProofScheduler(contradiction, optimizing, listOf(0), listOf(BacktrackParams()))
        }
    }

    @Test
    fun `repeated runs should retain the containing charged work cap`() {
        val budget = NodeBudget(0L)
        BooleanProofScheduler(contradiction, context, listOf(0), listOf(BacktrackParams(nodeBudget = budget)))
            .use { scheduler ->
                scheduler.run(Cancellation.Never, 5000L)
                val charged = scheduler.chargedWork

                scheduler.run(Cancellation.Never, 5000L)

                assertEquals(charged, scheduler.chargedWork)
                assertFalse(scheduler.nativeCoverageComplete)
            }
    }

    @Test
    fun `cancelled entry should leave every task pending`() {
        BooleanProofScheduler(contradiction, context, listOf(0), listOf(BacktrackParams())).use { scheduler ->
            val step = scheduler.runSlice(0, Cancellation { true }, Long.MAX_VALUE, 5000L)

            assertNull(step.task)
            assertEquals(2, scheduler.pending)
            assertEquals(0L, scheduler.work)
        }
    }
}
