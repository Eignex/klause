package com.eignex.klause.backtrack

import com.eignex.klause.backtrack.selector.IndomainMin
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.ExecutionControl
import org.junit.BeforeClass
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class ResumableSatisfactionRealLeafTest(private val tolerant: Boolean) {
    @Test
    fun `real leaf completion should preserve its assignment and work across slices`() {
        val n = 12
        val problem = Problem(0, 1, arrayOf(IntDomain(0L, 1L)),
            Array<Factor>(n) { row ->
                Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(row, (row + 1) % n),
                    doubleArrayOf(1.0, 1.0), LinearOp.GE, 1.0)
            }, numRealVars = n, realLower = DoubleArray(n), realUpper = DoubleArray(n) { 1.0 }).bake()
        val check: ((Sample) -> Boolean)? = if (tolerant) {
            { sample ->
                (0 until n).all { row ->
                    sample.ints.single() + sample.reals[row] + sample.reals[(row + 1) % n] >= 1.0 - 1e-7
                }
            }
        } else null
        val params = BacktrackParams(randomSeed = 0L, valueSelector = IndomainMin, toleranceCheck = check,
            lpPlan = LpPlan(bounding = false, probe = false, rootCutHarvest = false, componentSplit = false))
        val baseline = assertIs<SolveResult.Sat>(BacktrackSolver(problem).solve(params))
        var queries = 0
        val context = LpSolveContext(onQuery = { query ->
            queries++
            assertEquals(0L, query.workLimit)
        })
        var result: SolveResult? = null
        var pausedNodes: Double? = null

        BacktrackSolver(problem, context).resumableSolve(params).use { search ->
            repeat(256) {
                if (result == null) {
                    result = search.runSlice(ExecutionControl.Never, 60_000L, 1L)
                    if (result == null && search.stats.lp.standaloneWorkOps.sum > 0.0) {
                        val nodes = search.stats.search.nodes.sum
                        pausedNodes?.let { assertEquals(it, nodes) } ?: run { pausedNodes = nodes }
                        assertFalse(search.isDone)
                    }
                }
            }

            val sample = assertIs<SolveResult.Sat>(result).assignment
            assertNotNull(pausedNodes)
            assertEquals(baseline.assignment.ints.toList(), sample.ints.toList())
            assertEquals(baseline.assignment.reals.toList(), sample.reals.toList())
            assertEquals(baseline.stats.lp.standaloneWorkOps.sum, search.stats.lp.standaloneWorkOps.sum)
            assertEquals(1, queries)
        }
    }

    @Test
    fun `cancellation should terminate a retained satisfaction leaf without a witness`() {
        val fixture = UnresolvedRealLeafFixture(false)
        var cancelled = false
        var queries = 0
        val token = ExecutionControl { cancelled }
        val params = fixture.params.copy(cancellation = token, toleranceCheck = if (tolerant) ({ true }) else null)
        val context = fixture.context.copy(onQuery = { queries++ })
        val sink = SolveStatsSink("cancelled-leaf")

        CpSatisfactionTraversal(fixture.problem, params, sink, context, slice = TraversalSlice(
            pauses = { !cancelled }, beforeBranch = { false }, remainingLpWork = { 1L },
        )).use { traversal ->
            assertNull(traversal.next())
            assertEquals(1, queries)
            cancelled = true

            val outcome = assertNotNull(traversal.next())
            val result = fixture.solver.verdictOf(outcome, Assumptions.None, sink)
            assertEquals(TerminationReason.Cancelled, assertIs<SolveResult.Unknown>(result).reason)
        }
        assertEquals(fixture.persistentOpened, fixture.persistentClosed)
    }

    @Test
    fun `closing a paused satisfaction leaf should retire its numerical owners`() {
        val fixture = UnresolvedRealLeafFixture(false)
        val params = fixture.params.copy(toleranceCheck = if (tolerant) ({ true }) else null)

        CpSatisfactionTraversal(fixture.problem, params, null, fixture.context, slice = TraversalSlice(
            pauses = { true }, beforeBranch = { false }, remainingLpWork = { 1L },
        )).use { traversal ->
            assertNull(traversal.next())
            assertTrue(fixture.persistentOpened > 0)
            assertEquals(0, fixture.persistentClosed)
        }

        assertEquals(fixture.persistentOpened, fixture.persistentClosed)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "tolerant={0}")
        fun parameters(): List<Array<Any>> = listOf(arrayOf(false), arrayOf(true))

        @JvmStatic
        @BeforeClass
        fun warmRuntime() {
            val fixture = UnresolvedRealLeafFixture(false)
            BacktrackSolver(fixture.problem).solve(fixture.params)
        }
    }
}
