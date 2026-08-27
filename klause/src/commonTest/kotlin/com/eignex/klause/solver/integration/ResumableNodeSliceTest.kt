package com.eignex.klause.solver.integration

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Slicing a resumable search by nodes rather than by the clock.
 *
 * A slice measured in milliseconds pauses somewhere different on every run, and every counter
 * downstream of the search inherits that — which is why two identical invocations of the same model
 * report different `nodes` and `lpSolves`. A slice measured in nodes pauses at the same point in the
 * same tree every time, which is what makes a run's counters comparable at all.
 */
class ResumableNodeSliceTest {

    /** A minimisation wide enough to branch for a while rather than being refuted by propagation. */
    private fun problem(): Problem {
        val n = 6
        val vars = IntArray(n) { it }
        return Problem(
            numBoolVars = 0,
            numIntVars = n,
            intDomains = Array(n) { IntDomain(0, 3) },
            factors = arrayOf<Factor>(
                Linear(LongArray(n) { 1L }, vars, LinearOp.GE, 11L),
                Linear(LongArray(n) { if (it % 2 == 0) 2L else 1L }, vars, LinearOp.LE, 19L),
                Linear(LongArray(n) { if (it % 3 == 0) 3L else 1L }, vars, LinearOp.LE, 20L),
            ),
        )
    }

    private fun objective() = LinearObjective(intCoefficients = LongArray(6) { (it % 4 + 1).toLong() })

    private fun handle() = BacktrackSolver(problem().bake()).resumable(objective(), BacktrackParams(randomSeed = 0L))

    /** Nodes the whole search takes, so a slice budget can be set as a fraction of a real number. */
    private fun fullSearchNodes(): Double {
        val search = handle()
        search.runSlice(Cancellation.Never, sliceMillis = 60_000, sliceNodes = -1L) { }
        return search.stats.search.nodes.sum
    }

    private fun nodesAfterOneSlice(budget: Long): Double {
        val search = handle()
        search.runSlice(Cancellation.Never, sliceMillis = 60_000, sliceNodes = budget) { }
        return search.stats.search.nodes.sum
    }

    @Test
    fun `a node-budgeted slice stops on its budget rather than running the search out`() {
        val full = fullSearchNodes()
        assertTrue(full > 12.0, "fixture must take enough nodes to slice, saw $full")

        val budgeted = nodesAfterOneSlice((full / 4).toLong())

        assertTrue(budgeted < full, "the budget must stop the slice short, saw $budgeted of $full")
    }

    @Test
    fun `the same node budget stops at the same place every time`() {
        val budget = (fullSearchNodes() / 4).toLong()

        val first = nodesAfterOneSlice(budget)
        val second = nodesAfterOneSlice(budget)

        assertEquals(first, second, "a counted slice is reproducible; a timed one is not")
    }

    @Test
    fun `successive node-budgeted slices resume rather than restart`() {
        val budget = (fullSearchNodes() / 4).toLong()
        val search = handle()

        search.runSlice(Cancellation.Never, sliceMillis = 60_000, sliceNodes = budget) { }
        val afterFirst = search.stats.search.nodes.sum
        search.runSlice(Cancellation.Never, sliceMillis = 60_000, sliceNodes = budget) { }
        val afterSecond = search.stats.search.nodes.sum

        assertTrue(afterSecond > afterFirst, "the second slice must add nodes, not replay the first")
    }
}
