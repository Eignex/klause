package com.eignex.klause.backtrack

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

class ResumableMinimizeNodeSliceTest {

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
    fun `node budgeted slice should stop on its budget`() {
        val full = fullSearchNodes()
        assertTrue(full > 12.0, "full=$full")

        val budgeted = nodesAfterOneSlice((full / 4).toLong())

        assertTrue(budgeted < full, "$budgeted of $full")
    }

    @Test
    fun `node budgeted slice should be reproducible`() {
        val budget = (fullSearchNodes() / 4).toLong()

        val first = nodesAfterOneSlice(budget)
        val second = nodesAfterOneSlice(budget)

        assertEquals(first, second)
    }

    @Test
    fun `successive node budgeted slices should resume instead of restart`() {
        val budget = (fullSearchNodes() / 4).toLong()
        val search = handle()

        search.runSlice(Cancellation.Never, sliceMillis = 60_000, sliceNodes = budget) { }
        val afterFirst = search.stats.search.nodes.sum
        search.runSlice(Cancellation.Never, sliceMillis = 60_000, sliceNodes = budget) { }
        val afterSecond = search.stats.search.nodes.sum

        assertTrue(afterSecond > afterFirst)
    }

    @Test
    fun `mixed search sliced one node at a time should prove its optimum`() {
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
        var slices = 0
        while (terminal == null && slices < 1000) {
            terminal = search.runSlice(Cancellation.Never, sliceMillis = 60_000, sliceNodes = 1L) { }
            slices++
        }
        search.close()

        assertTrue(slices > 1)
        assertEquals(6.5, assertIs<MinimizeResult.Optimal>(terminal).objective)
    }

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
    fun `LP arm slice should spend its LP work as nodes`() {
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
    fun `LP arm should repay overspent LP work with idle slices`() {
        val search = knapsackLp()

        val idle = (1..50).count {
            val before = search.stats.search.nodes.sum
            search.runSlice(Cancellation.Never, sliceMillis = 60_000, sliceNodes = 1L) { } == null &&
                search.stats.search.nodes.sum == before
        }

        assertTrue(idle > 0)
    }

    @Test
    fun `single node slices should preserve the optimum and node count`() {
        val whole = handle()
        val expected = whole.runSlice(Cancellation.Never, sliceMillis = 60_000, sliceNodes = -1L) { }
        val search = handle()
        var terminal: MinimizeResult? = null
        var slices = 0

        while (terminal == null && slices++ < 1_000) {
            terminal = search.runSlice(Cancellation.Never, sliceMillis = 60_000, sliceNodes = 1L) { }
        }

        assertEquals(
            assertIs<MinimizeResult.Optimal>(expected).objective,
            assertIs<MinimizeResult.Optimal>(terminal).objective,
        )
        assertEquals(whole.stats.search.nodes, search.stats.search.nodes)
        assertEquals(whole.stats.search.propagationWork, search.stats.search.propagationWork)
        assertTrue(search.stats.search.propagationWork.sum > 0.0)
    }

    @Test
    fun `leaf LP completions should charge work without a node LP arm`() {
        val reals = 20
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 1)),
            numRealVars = reals,
            realLower = DoubleArray(reals),
            realUpper = DoubleArray(reals) { 2.0 },
            factors = Array<Factor>(reals) { v ->
                Linear(longArrayOf(1L), intArrayOf(0), doubleArrayOf(1.0), intArrayOf(v), LinearOp.GE, 1L)
            },
        ).bake()
        val search = BacktrackSolver(problem).resumable(
            LinearObjective(realCoefficients = DoubleArray(reals) { 1.0 }),
            BacktrackParams(randomSeed = 0L),
        )

        search.runSlice(Cancellation.Never, sliceMillis = 60_000, sliceNodes = -1L) { }
        val leafWork = search.stats.lp.standaloneWorkOps.sum.toLong() / LP_WORK_PER_NODE

        assertTrue(leafWork > 0L)
        assertTrue(search.work >= search.stats.search.nodes.sum.toLong() + leafWork)
    }

    @Test
    fun `root refutation should return infeasible within the first slice`() {
        val variables = 5_000
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = variables,
            intDomains = Array(variables) { IntDomain(0, 1) },
            factors = arrayOf<Factor>(
                Linear(LongArray(variables) { 1L }, IntArray(variables) { it }, LinearOp.GE, variables + 1L),
            ),
        ).bake()
        val search = BacktrackSolver(problem).resumable(LinearObjective(), BacktrackParams(randomSeed = 0L))

        val verdict = search.runSlice(Cancellation.Never, sliceMillis = 60_000, sliceNodes = 1L) { }

        assertIs<MinimizeResult.Infeasible>(verdict)
    }
}
