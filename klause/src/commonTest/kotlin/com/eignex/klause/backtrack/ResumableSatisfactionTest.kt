package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.ResumableSolve
import com.eignex.klause.solver.SearchInitializationCancelled
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResumableSatisfactionTest {

    /** Pigeons into holes, clause-encoded so propagation alone cannot settle it and the search must branch. */
    private fun pigeonhole(pigeons: Int, holes: Int): Problem {
        fun lit(p: Int, h: Int) = Lit.make(p * holes + h, true)
        val factors = ArrayList<Factor>()
        for (p in 0 until pigeons) factors += Clause(IntArray(holes) { h -> lit(p, h) })
        for (h in 0 until holes) {
            for (a in 0 until pigeons) {
                for (b in a + 1 until pigeons) {
                    factors += Clause(intArrayOf(Lit.negate(lit(a, h)), Lit.negate(lit(b, h))))
                }
            }
        }
        return Problem(pigeons * holes, 0, emptyArray(), factors.toTypedArray())
    }

    private fun handle(problem: Problem): ResumableSolve =
        BacktrackSolver(problem.bake()).resumableSolve(BacktrackParams(randomSeed = 0L))

    private fun runToVerdict(search: ResumableSolve, sliceWork: Long): SolveResult {
        while (true) search.runSlice(Cancellation.Never, Long.MAX_VALUE, sliceWork)?.let { return it }
    }

    @Test
    fun `a work-budgeted slice pauses before the search finishes`() {
        val search = handle(pigeonhole(6, 5))

        assertNull(search.runSlice(Cancellation.Never, Long.MAX_VALUE, sliceNodes = 1L))
        assertFalse(search.isDone)
    }

    @Test
    fun `slices resumed to a verdict prove unsat like one uninterrupted solve`() {
        val problem = pigeonhole(6, 5)
        val whole = BacktrackSolver(problem.bake()).solve(BacktrackParams(randomSeed = 0L))

        val sliced = runToVerdict(handle(problem), sliceWork = 1L)

        assertIs<SolveResult.Unsat>(whole)
        assertIs<SolveResult.Unsat>(sliced)
        assertEquals(whole.stats.search.nodes, sliced.stats.search.nodes, "a resumed search must not restart")
    }

    @Test
    fun `slices resumed to a verdict find a model`() {
        val sat = assertIs<SolveResult.Sat>(runToVerdict(handle(pigeonhole(5, 5)), sliceWork = 1L))

        for (p in 0 until 5) assertTrue((0 until 5).any { h -> sat.assignment.bools[p * 5 + h] }, "pigeon $p unplaced")
    }

    @Test
    fun `the same work budget pauses at the same node every time`() {
        fun nodesAfterOneSlice(): Double {
            val search = handle(pigeonhole(6, 5))
            search.runSlice(Cancellation.Never, Long.MAX_VALUE, sliceNodes = 20L)
            return search.stats.search.nodes.sum
        }

        assertEquals(nodesAfterOneSlice(), nodesAfterOneSlice())
    }

    @Test
    fun `a fired run token ends the search instead of pausing it`() {
        val search = handle(pigeonhole(6, 5))

        val verdict = search.runSlice(Cancellation { true }, Long.MAX_VALUE, sliceNodes = 1_000_000L)

        assertIs<SolveResult.Unknown>(verdict)
        assertTrue(search.isDone)
    }

    @Test
    fun `a constructor refutation returns even with no slice work allowance`() {
        val search = handle(pigeonhole(2, 1))

        val verdict = search.runSlice(Cancellation.Never, Long.MAX_VALUE, sliceNodes = 0L)

        assertIs<SolveResult.Unsat>(verdict)
    }
    @Test
    fun `cancelled construction cannot report a partial root verdict`() {
        val solver = BacktrackSolver(pigeonhole(3, 2).bake())

        assertFailsWith<SearchInitializationCancelled> {
            solver.resumableSolve(BacktrackParams(cancellation = Cancellation { true }))
        }

        assertIs<SolveResult.Unsat>(runToVerdict(solver.resumableSolve(BacktrackParams()), 1L))
    }

    @Test
    fun `resuming satisfaction does not renew the solve node allowance`() {
        val budget = NodeBudget(3L)
        val search = BacktrackSolver(pigeonhole(6, 5).bake()).resumableSolve(BacktrackParams(nodeBudget = budget))

        val result = search.use { runToVerdict(it, 1L) }

        assertIs<SolveResult.Unknown>(result)
        assertEquals(3L, budget.spent)
        assertEquals(3.0, result.stats.search.nodes.sum)
    }

}
