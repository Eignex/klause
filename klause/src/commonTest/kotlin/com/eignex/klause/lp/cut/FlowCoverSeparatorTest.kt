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

/**
 * Single-node flow-cover cuts must be SOUND: every emitted inequality holds at every
 * integer-feasible point, so it only tightens the relaxation and never removes a solution. Checked by
 * brute-force enumeration of small single-node-flow problems (`Σ yⱼ ≤ b`, `yⱼ ≤ uⱼ·xⱼ`, `xⱼ ∈ {0,1}`),
 * and that a cut fires on the fractional max-flow LP point.
 */
class FlowCoverSeparatorTest {

    /** `n` flow vars `y₀..` (ints 0..n−1, domain `[0,u]`) + indicators `x₀..` (ints n..2n−1, `{0,1}`),
     *  with VUBs `yⱼ ≤ u·xⱼ` and the capacity row `Σ yⱼ ≤ b`. [openFlowAbove] marks each flow column's
     *  upper endpoint as one the finite lane invented rather than one the model states. */
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

    /** Separate flow-cover cuts at the LP point that maximizes total flow (which makes the `xⱼ` fractional). */
    private fun cutsAtMaxFlow(p: Problem, n: Int): Pair<List<Cut>, CutContext> {
        // maximize Σ yⱼ (negative coefficients) so the capacity binds and the indicators go fractional.
        val obj = LinearObjective(intCoefficients = LongArray(2 * n) { if (it < n) -1L else 0L })
        val session = PropagationSession(p)
        val relaxation = CpToLpRelaxation(p, obj).build(session)
        val primal = RevisedSimplex(relaxation.model).solve()?.primal ?: DoubleArray(relaxation.model.n)
        val ctx = CutContext(p, relaxation, primal, session)
        return FlowCoverSeparator().separate(ctx) to ctx
    }

    /** A 0/1 knapsack `Σ weights·x ≤ c` over `n` binaries — a bin-packing single-node-flow row with no
     *  explicit flow variable (flow `yⱼ = weightⱼ·xⱼ` shares the indicator column). */
    private fun knapsackProblem(weights: IntArray, c: Int): Problem {
        val n = weights.size
        val domains = Array(n) { IntDomain(0, 1) }
        val factors = arrayOf<Factor>(Linear(weights, IntArray(n) { it }, LinearOp.LE, c))
        return Problem(0, n, domains, factors)
    }

    /** Separate at the LP point that maximizes the load `Σ weights·x` (binds capacity, fractionalizes x). */
    private fun cutsAtMaxLoad(p: Problem, weights: IntArray): Pair<List<Cut>, CutContext> {
        val obj = LinearObjective(intCoefficients = LongArray(weights.size) { -weights[it].toLong() })
        val session = PropagationSession(p)
        val relaxation = CpToLpRelaxation(p, obj).build(session)
        val primal = RevisedSimplex(relaxation.model).solve()?.primal ?: DoubleArray(relaxation.model.n)
        val ctx = CutContext(p, relaxation, primal, session)
        return FlowCoverSeparator().separate(ctx) to ctx
    }

    @Test
    fun `flow-cover fires on a bin-packing knapsack with no explicit flow variable`() {
        // Σ 3·xⱼ ≤ 5 over 3 binaries: at most one item fits, so x₀+x₁ ≤ 1 — a flow-cover cut the
        // fractional max-load point (each xⱼ = 5/9) violates.
        val weights = intArrayOf(3, 3, 3)
        val (cuts, _) = cutsAtMaxLoad(knapsackProblem(weights, c = 5), weights)
        assertTrue(cuts.isNotEmpty(), "expected a flow-cover cut on the fractional knapsack LP point")
    }

    @Test
    fun `flow-cover fires on the fractional max-flow point`() {
        // 3 arcs of capacity 3 into a node of capacity 4: max flow 4 opens all three fractionally.
        val (cuts, _) = cutsAtMaxFlow(flowProblem(n = 3, u = 3, b = 4), n = 3)
        assertTrue(cuts.isNotEmpty(), "expected a flow-cover cut on the fractional max-flow LP point")
    }

    @Test
    fun `a flow variable the model leaves open above yields no cut`() {
        // Same instance, but each arc's upper endpoint is one the finite lane invented. The cover
        // inequality reads it as the arc's capacity and is published globally, so it may not be
        // derived at all — the box bounds the search, not the model.
        val (cuts, _) = cutsAtMaxFlow(flowProblem(n = 3, u = 3, b = 4, openFlowAbove = true), n = 3)
        assertTrue(cuts.isEmpty(), "an invented flow ceiling must not become a flow-cover capacity")
    }

    @Test
    fun `a flow the model lets run negative yields no cut`() {
        // y₂ ∈ [−10, 3] can absorb the capacity row's slack, so the two covered arcs may both run full
        // while `Σ y ≤ 4` still holds: at y = (3, 3, −10), x = (1, 1, 1) the cover inequality
        // `y₀ + y₁ − (1 − x₀) − (1 − x₁) ≤ 2` reads 6 > 2 and would cut off a feasible point. The
        // derivation needs `yⱼ ≥ 0`, which this model does not state.
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
        assertTrue(cuts.isEmpty(), "a flow with no stated floor of 0 must not become a flow-cover arc")
    }

}
