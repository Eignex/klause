package com.eignex.klause.solver.integration

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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

    @Test
    fun `a mixed search sliced one node at a time still proves its optimum`() {
        val n = 3
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = n,
            intDomains = Array(n) { IntDomain(0, 2) },
            factors = arrayOf<Factor>(
                Linear(LongArray(n) { 1L }, IntArray(n) { it }, doubleArrayOf(1.0), intArrayOf(0), LinearOp.GE, 5L),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(10.0),
        ).bake()
        val objective = LinearObjective(
            intCoefficients = longArrayOf(2L, 3L, 1L),
            realCoefficients = doubleArrayOf(1.5),
        )
        val search = BacktrackSolver(problem).resumable(objective, BacktrackParams(randomSeed = 0L))

        var terminal: MinimizeResult? = null
        while (terminal == null) {
            terminal = search.runSlice(Cancellation.Never, sliceMillis = 60_000, sliceNodes = 1L) { }
        }

        assertEquals(6.5, assertIs<MinimizeResult.Optimal>(terminal).objective)
    }

    /** A multi-row 0/1 knapsack: its LP relaxation is fractional at the root, so the LP arm branches. */
    private fun knapsack(n: Int = 20, rows: Int = 3): Problem {
        val vars = IntArray(n) { it }
        return Problem(
            numBoolVars = 0,
            numIntVars = n,
            intDomains = Array(n) { IntDomain(0, 1) },
            factors = Array<Factor>(rows) { row ->
                Linear(LongArray(n) { ((it * 7 + row * 13) % 17 + 3).toLong() }, vars, LinearOp.LE, 4L * n + row * 7)
            },
        )
    }

    private fun knapsackLp(n: Int = 20, rows: Int = 3) = BacktrackSolver(knapsack(n, rows).bake()).resumable(
        LinearObjective(intCoefficients = LongArray(n) { -((it * 11 % 19) + 5).toLong() }),
        BacktrackParams(randomSeed = 0L, lpConfig = LpConfig.AGGRESSIVE),
    )

    @Test
    fun `an LP arm's slice spends its LP work as nodes`() {
        val lp = { knapsackLp() }
        val whole = lp().also { it.runSlice(Cancellation.Never, sliceMillis = 60_000, sliceNodes = -1L) { } }
        val budget = (whole.stats.search.nodes.sum / 2).toLong()
        val search = lp()

        val terminal = search.runSlice(Cancellation.Never, sliceMillis = 60_000, sliceNodes = budget) { }

        assertTrue(
            terminal == null && search.stats.search.nodes.sum < budget,
            "slice ${search.stats.search.nodes.sum} of $budget, terminal $terminal",
        )
    }

    @Test
    fun `an LP arm repays the LP work one slice overspent with slices that explore nothing`() {
        val search = knapsackLp()

        val idle = (1..50).count {
            val before = search.stats.search.nodes.sum
            search.runSlice(Cancellation.Never, sliceMillis = 60_000, sliceNodes = 1L) { } == null &&
                search.stats.search.nodes.sum == before
        }

        assertTrue(idle > 0, "every one-node slice explored a node")
    }
}
