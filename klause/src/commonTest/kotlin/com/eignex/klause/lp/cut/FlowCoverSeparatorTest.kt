package com.eignex.klause.lp.cut

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.lp.engine.RevisedSimplex
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.test.Test
import kotlin.test.assertTrue

class FlowCoverSeparatorTest {

    private fun flowProblem(n: Int, u: Int, b: Int, openFlowAbove: Boolean = false): Problem {
        val domains = Array(2 * n) { if (it < n) IntDomain(0, u.toLong()) else IntDomain(0, 1) }
        val factors = ArrayList<Factor>()
        for (j in 0 until n) factors.add(Linear(intArrayOf(1, -u), intArrayOf(j, n + j), LinearOp.LE, 0))
        factors.add(Linear(IntArray(n) { 1 }, IntArray(n) { it }, LinearOp.LE, b))
        return Problem(
            numBoolVars = 0,
            numIntVars = 2 * n,
            intDomains = domains,
            factors = factors.toTypedArray(),
            openIntHi = BooleanArray(2 * n) { openFlowAbove && it < n },
        )
    }

    private fun cutsAtMaxFlow(p: Problem, n: Int): Pair<List<Cut>, CutContext> {
        val obj = LinearObjective(intCoefficients = LongArray(2 * n) { if (it < n) -1L else 0L })
        val session = PropagationSession(p)
        val relaxation = CpToLpRelaxation(p, obj).build(session)
        val primal = RevisedSimplex(relaxation.model).solve()?.primal ?: DoubleArray(relaxation.model.n)
        val ctx = CutContext(p, relaxation, primal, session)
        return FlowCoverSeparator().separate(ctx) to ctx
    }

    private fun knapsackProblem(weights: IntArray, c: Int): Problem {
        val n = weights.size
        val domains = Array(n) { IntDomain(0, 1) }
        val factors = arrayOf<Factor>(Linear(weights, IntArray(n) { it }, LinearOp.LE, c))
        return Problem(0, n, domains, factors)
    }

    private fun cutsAtMaxLoad(p: Problem, weights: IntArray): Pair<List<Cut>, CutContext> {
        val obj = LinearObjective(intCoefficients = LongArray(weights.size) { -weights[it].toLong() })
        val session = PropagationSession(p)
        val relaxation = CpToLpRelaxation(p, obj).build(session)
        val primal = RevisedSimplex(relaxation.model).solve()?.primal ?: DoubleArray(relaxation.model.n)
        val ctx = CutContext(p, relaxation, primal, session)
        return FlowCoverSeparator().separate(ctx) to ctx
    }

    @Test
    fun `flow cover should fire on a knapsack with no explicit flow variable`() {
        val weights = intArrayOf(3, 3, 3)
        val (cuts, _) = cutsAtMaxLoad(knapsackProblem(weights, c = 5), weights)
        assertTrue(cuts.isNotEmpty())
    }

    @Test
    fun `flow cover should fire on the fractional max-flow point`() {
        val (cuts, _) = cutsAtMaxFlow(flowProblem(n = 3, u = 3, b = 4), n = 3)
        assertTrue(cuts.isNotEmpty())
    }

    @Test
    fun `flow cover should not be derived over a flow open above`() {
        val (cuts, _) = cutsAtMaxFlow(flowProblem(n = 3, u = 3, b = 4, openFlowAbove = true), n = 3)
        assertTrue(cuts.isEmpty())
    }

    @Test
    fun `flow cover should not be derived over a flow that may run negative`() {
        // At y = (3, 3, -10), x = (1, 1, 1) the cover inequality would cut off a feasible point.
        val domains = Array(6) {
            if (it == 2) {
                IntDomain(-10, 3)
            } else if (it < 3) {
                IntDomain(0, 3)
            } else {
                IntDomain(0, 1)
            }
        }
        val factors = ArrayList<Factor>()
        for (j in 0 until 3) factors.add(Linear(intArrayOf(1, -3), intArrayOf(j, 3 + j), LinearOp.LE, 0))
        factors.add(Linear(IntArray(3) { 1 }, IntArray(3) { it }, LinearOp.LE, 4))
        val (cuts, _) = cutsAtMaxFlow(Problem(0, 6, domains, factors.toTypedArray()), n = 3)
        assertTrue(cuts.isEmpty())
    }
}
