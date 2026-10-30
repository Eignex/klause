package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.portfolio.BooleanProofScheduler
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.evidence.SourceCondition
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.search.BooleanProofContext
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BooleanSatisfactionRepairTest {
    private val problem = Problem(2, 0, emptyArray(), arrayOf(
        Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
    )).bake()

    @Test
    fun `sibling queries should preserve exactly their own Boolean pins`() {
        for (native in listOf(false, true)) {
            BacktrackSolver(problem).openBooleanProof(BacktrackParams(nativeSat = native)).use { search ->
                for (value in listOf(true, false)) {
                    search.begin(Assumptions(bools = mapOf(0 to value, 1 to !value)), Long.MAX_VALUE,
                        Double.POSITIVE_INFINITY, Cancellation.Never)
                    val result = search.runSlice(Cancellation.Never, Long.MAX_VALUE, 5000L)

                    val sample = assertNotNull(result.sample)
                    assertEquals(value, sample.boolValue(0))
                    assertEquals(!value, sample.boolValue(1))
                }
            }
        }
    }

    @Test
    fun `a scoped refutation should not poison a feasible sibling`() {
        for (native in listOf(false, true)) {
            BacktrackSolver(problem).openBooleanProof(BacktrackParams(nativeSat = native)).use { search ->
                search.begin(Assumptions(bools = mapOf(0 to false, 1 to false)), Long.MAX_VALUE,
                    Double.POSITIVE_INFINITY, Cancellation.Never)
                val refuted = search.runSlice(Cancellation.Never, Long.MAX_VALUE, 5000L)
                assertNull(refuted.sample)
                assertEquals(TerminationReason.SearchExhausted, refuted.reason)

                search.begin(Assumptions(bools = mapOf(0 to true, 1 to false)), Long.MAX_VALUE,
                    Double.POSITIVE_INFINITY, Cancellation.Never)
                val sibling = search.runSlice(Cancellation.Never, Long.MAX_VALUE, 5000L)

                assertTrue(assertNotNull(sibling.sample).boolValue(0))
            }
        }
    }

    @Test
    fun `sibling queries should reuse constructor root work`() {
        BacktrackSolver(problem).openBooleanProof(BacktrackParams(nativeSat = false)).use { search ->
            val root = search.stats.search.rootPropagationWork.sum
            assertTrue(root > 0.0)
            for (value in listOf(false, true)) {
                search.begin(Assumptions(bools = mapOf(0 to value)), Long.MAX_VALUE,
                    Double.POSITIVE_INFINITY, Cancellation.Never)
                search.runSlice(Cancellation.Never, Long.MAX_VALUE, 5000L)
            }

            assertEquals(root, search.stats.search.rootPropagationWork.sum)
        }
    }

    @Test
    fun `an exhausted containing quota should not spend another decision after reseeding`() {
        val contradiction = Problem(4, 0, emptyArray(), Array(8) { mask ->
            Clause(IntArray(3) { bit -> Lit.make(bit + 1, mask and (1 shl bit) != 0) })
        }).bake()
        for (native in listOf(false, true)) {
            val budget = NodeBudget(1L)
            val params = BacktrackParams(nativeSat = native, nodeBudget = budget)
            BacktrackSolver(contradiction).openBooleanProof(params).use { search ->
                for (value in listOf(false, true)) {
                    search.begin(Assumptions(bools = mapOf(0 to value)), Long.MAX_VALUE,
                        Double.POSITIVE_INFINITY, Cancellation.Never)
                    val result = search.runSlice(Cancellation.Never, Long.MAX_VALUE, 5000L)

                    assertNull(result.sample)
                    assertEquals(TerminationReason.BudgetExhausted, result.reason)
                    assertEquals(1L, budget.spent)
                }
            }
        }
    }

    @Test
    fun `a cancelled condition should not prevent a later sibling query`() {
        for (native in listOf(false, true)) {
            BacktrackSolver(problem).openBooleanProof(BacktrackParams(nativeSat = native)).use { search ->
                search.begin(Assumptions(bools = mapOf(0 to false)), Long.MAX_VALUE,
                    Double.POSITIVE_INFINITY, Cancellation { true })
                val cancelled = search.runSlice(Cancellation.Never, Long.MAX_VALUE, 5000L)
                assertNull(cancelled.sample)
                assertEquals(TerminationReason.BudgetExhausted, cancelled.reason)

                search.begin(Assumptions(bools = mapOf(0 to true, 1 to false)), Long.MAX_VALUE,
                    Double.POSITIVE_INFINITY, Cancellation.Never)
                val sibling = search.runSlice(Cancellation.Never, Long.MAX_VALUE, 5000L)

                assertTrue(assertNotNull(sibling.sample).boolValue(0))
            }
        }
    }

    @Test
    fun `finite objective cutoffs should decline instead of being ignored`() {
        BacktrackSolver(problem).openBooleanProof(BacktrackParams()).use { search ->
            assertFailsWith<IllegalArgumentException> {
                search.begin(Assumptions.None, Long.MAX_VALUE, 0.0, Cancellation.Never)
            }
        }
    }

    @Test
    fun `direct satisfaction tasks should recover lost coverage before reporting source exhaustion`() {
        val contradiction = Problem(5, 0, emptyArray(), Array(8) { mask ->
            Clause(IntArray(3) { bit -> Lit.make(bit + 2, mask and (1 shl bit) != 0) })
        }).bake()
        val context = BooleanProofContext(SourceScope(SourceRevision("fixture", "exact-cnf")), "identity:0")
        val params = List(2) { BacktrackParams(nativeSat = false) }
        BooleanProofScheduler(contradiction, context, listOf(0, 1), params, true).use { scheduler ->
            val paused = scheduler.runSlice(0, Cancellation.Never, Long.MAX_VALUE, 1L)
            assertNull(paused.reason)
            scheduler.loseWorker(0)
            repeat(4) { scheduler.runSlice((it + 1) % 2, Cancellation.Never, Long.MAX_VALUE, 5000L) }
            val result = scheduler.run(Cancellation.Never, 0L)

            assertTrue(result is SolveResult.Unsat)
            assertEquals(4, scheduler.completed)
            assertTrue(scheduler.nativeCoverageComplete)
            assertFalse(scheduler.independentlyCheckedCoverageComplete)
        }
    }

    @Test
    fun `direct satisfaction tasks should retain caller pins and checked witnesses`() {
        val pins = mapOf(1 to false)
        val context = BooleanProofContext(SourceScope(SourceRevision("fixture", "exact-cnf"),
            SourceCondition(bools = pins)), "identity:0")
        val params = BacktrackParams(assumptions = Assumptions(bools = pins))
        BooleanProofScheduler(problem, context, listOf(0), listOf(params), true).use { scheduler ->
            val result = scheduler.run(Cancellation.Never, 10000L)

            val sample = (result as SolveResult.Sat).assignment
            assertTrue(sample.boolValue(0))
            assertFalse(sample.boolValue(1))
            assertEquals(1, scheduler.completed)
            assertFalse(scheduler.nativeCoverageComplete)
        }
    }
}
