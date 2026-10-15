package com.eignex.klause.solver

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ResumableSearchTest {

    private fun pigeonhole(pigeons: Int, holes: Int): Problem {
        fun v(p: Int, h: Int) = p * holes + h
        val factors = ArrayList<Factor>()
        for (p in 0 until pigeons) {
            factors.add(Cardinality.exactlyOne(IntArray(holes) { h -> Lit.make(v(p, h), true) }))
        }
        for (h in 0 until holes) {
            factors.add(Cardinality.atMostOne(IntArray(pigeons) { p -> Lit.make(v(p, h), true) }))
        }
        return Problem(
            numBoolVars = pigeons * holes,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = factors.toTypedArray(),
        )
    }

    private fun driveInSlices(problem: Problem, objective: LinearObjective): Pair<MinimizeResult, Int> {
        val handle = BacktrackSolver(problem.bake()).resumable(objective, BacktrackParams(randomSeed = 0L))
        var slices = 0
        var terminal: MinimizeResult? = null
        while (terminal == null && slices < 1_000_000) {
            var checks = 0
            val pauseAfterOnePoll = Cancellation { checks++ > 0 }
            terminal = if (handle.preparationPending) {
                handle.runSlice(Cancellation.Never, sliceMillis = 60_000, sliceNodes = 1L) { }
            } else {
                handle.runSlice(pauseAfterOnePoll, sliceMillis = 60_000) { }
            }
            slices++
        }
        assertNotNull(terminal)
        assertTrue(handle.isDone)
        return terminal to slices
    }

    @Test
    fun `sliced resumable search should prove infeasibility across many slices`() {
        val problem = pigeonhole(pigeons = 7, holes = 6)
        val obj = LinearObjective(boolWeights = LongArray(problem.numBoolVars) { if (it == 0) 1L else 0L })

        val (terminal, slices) = driveInSlices(problem, obj)

        assertIs<MinimizeResult.Infeasible>(terminal)
        assertTrue(slices > 1)
    }

    @Test
    fun `sliced resumable branch and bound should prove the optimum`() {
        val weights = intArrayOf(3, 4, 5, 2, 6, 1, 4)
        val values = longArrayOf(5, 6, 8, 3, 9, 2, 5)
        val n = weights.size
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = n,
            intDomains = Array(n) { IntDomain(0, 1) },
            factors = arrayOf<Factor>(
                Linear(coeffs = weights, vars = IntArray(n) { it }, op = LinearOp.LE, bound = 12),
            ),
        )
        val obj = LinearObjective(intCoefficients = LongArray(n) { -values[it] })

        val (terminal, _) = driveInSlices(problem, obj)

        val optimal = assertIs<MinimizeResult.Optimal>(terminal)
        assertEquals(-19.0, optimal.objective)
        assertTrue(weights.indices.sumOf { weights[it] * optimal.sample.ints[it] } <= 12L)
    }

    @Test
    fun `finished handle should return the same terminal result`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5)),
            factors = arrayOf<Factor>(
                Linear(coeffs = intArrayOf(1, 1), vars = intArrayOf(0, 1), op = LinearOp.GE, bound = 3),
            ),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1L, 2L))
        val handle = BacktrackSolver(problem.bake()).resumable(obj, BacktrackParams(randomSeed = 0L))
        val first = handle.runSlice(Cancellation.Never, sliceMillis = 60_000) {}
        assertIs<MinimizeResult.Optimal>(first)
        assertEquals(3.0, first.objectiveValue)
        val again = handle.runSlice(Cancellation.Never, sliceMillis = 60_000) {}
        assertEquals(first, again)
    }
}
