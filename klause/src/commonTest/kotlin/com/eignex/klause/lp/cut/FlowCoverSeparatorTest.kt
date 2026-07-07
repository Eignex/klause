package com.eignex.klause.lp.cut

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.lp.RevisedSimplex
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.random.Random
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
     *  with VUBs `yⱼ ≤ u·xⱼ` and the capacity row `Σ yⱼ ≤ b`. */
    private fun flowProblem(n: Int, u: Int, b: Int): Problem {
        val domains = Array(2 * n) { if (it < n) IntDomain(0, u.toLong()) else IntDomain(0, 1) }
        val factors = ArrayList<Factor>()
        for (j in 0 until n) factors.add(Linear(intArrayOf(1, -u), intArrayOf(j, n + j), LinearOp.LE, 0))
        factors.add(Linear(IntArray(n) { 1 }, IntArray(n) { it }, LinearOp.LE, b))
        return Problem(0, 2 * n, domains, factors.toTypedArray())
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
    fun `randomized bin-packing flow-cover cuts never exclude a feasible integer point`() {
        val rng = Random(20260625)
        var fired = 0
        repeat(400) { _ ->
            val n = rng.nextInt(2, 5)
            val weights = IntArray(n) { rng.nextInt(1, 5) }
            val sum = weights.sum()
            val maxW = weights.max()
            if (sum <= maxW) return@repeat
            val c = rng.nextInt(maxW, sum) // binds (≥ a single item, < the total) so x can go fractional
            val p = knapsackProblem(weights, c)
            val (cuts, ctx) = cutsAtMaxLoad(p, weights)
            if (cuts.isEmpty()) return@repeat
            fired++
            val point = IntArray(n)
            fun rec(idx: Int) {
                if (idx == n) {
                    var load = 0L
                    for (i in 0 until n) load += weights[i].toLong() * point[i]
                    if (load > c) return // infeasible — not constrained by the cut
                    for (cut in cuts) {
                        var lhs = 0L
                        for (t in cut.cols.indices) lhs += cut.coeffs[t] * point[ctx.relaxation.colVarId[cut.cols[t]]]
                        assertTrue(
                            lhs <= cut.rhs,
                            "flow-cover cut excludes feasible point ${point.toList()} (weights ${weights.toList()}, " +
                                "c=$c): $lhs > ${cut.rhs}",
                        )
                    }
                    return
                }
                for (v in 0..1) {
                    point[idx] = v
                    rec(idx + 1)
                }
            }
            rec(0)
        }
        assertTrue(fired > 20, "bin-packing flow-cover fired on only $fired instances")
    }

    @Test
    fun `flow-cover fires on the fractional max-flow point`() {
        // 3 arcs of capacity 3 into a node of capacity 4: max flow 4 opens all three fractionally.
        val (cuts, _) = cutsAtMaxFlow(flowProblem(n = 3, u = 3, b = 4), n = 3)
        assertTrue(cuts.isNotEmpty(), "expected a flow-cover cut on the fractional max-flow LP point")
    }

    @Test
    fun `randomized flow-cover cuts never exclude a feasible integer point`() {
        val rng = Random(20260624)
        var fired = 0
        repeat(400) { _ ->
            val n = rng.nextInt(2, 4)
            val u = rng.nextInt(1, 4)
            val b = rng.nextInt(1, u * n) // 1 .. u*n-1, so the capacity genuinely binds
            val p = flowProblem(n, u, b)
            val (cuts, ctx) = cutsAtMaxFlow(p, n)
            if (cuts.isEmpty()) return@repeat
            fired++
            val point = IntArray(2 * n)
            fun feasible(): Boolean {
                for (f in p.factors.filterIsInstance<Linear>()) {
                    var s = 0L
                    for (i in f.vars.indices) s += f.coeffs[i].toLong() * point[f.vars[i]]
                    if (s > f.bound) return false // every factor is LE here
                }
                return true
            }
            fun rec(idx: Int) {
                if (idx == 2 * n) {
                    if (!feasible()) return
                    for (cut in cuts) {
                        var lhs = 0L
                        for (t in cut.cols.indices) lhs += cut.coeffs[t] * point[ctx.relaxation.colVarId[cut.cols[t]]]
                        assertTrue(
                            lhs <= cut.rhs,
                            "flow-cover cut excludes feasible point ${point.toList()}: $lhs > ${cut.rhs}",
                        )
                    }
                    return
                }
                val hi = if (idx < n) u else 1
                for (v in 0..hi) {
                    point[idx] = v
                    rec(idx + 1)
                }
            }
            rec(0)
        }
        assertTrue(fired > 50, "flow-cover fired on only $fired instances")
    }
}
