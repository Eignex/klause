package com.eignex.klause.lp.cut

import com.eignex.klause.factor.arithmetic.IntegerConstants
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.lp.Relation
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.UnitConsts
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.MutableIntIntMap
import com.eignex.klause.util.MutableIntLongMap
import kotlin.math.max

/**
 * Single-node flow-cover cuts (Padberg–Van Roy–Wolsey). Two structural forms are read directly from the
 * integer model, both reducing to the same cover separation over LP columns:
 *
 *  - **Explicit flow**: a capacity row `Σⱼ yⱼ ≤ b` whose flow variables each carry a variable-upper-bound
 *    `yⱼ ≤ uⱼ·xⱼ` (a 2-term [Linear] `yⱼ − uⱼ·xⱼ ≤ 0`, `xⱼ ∈ {0,1}`).
 *  - **Implicit / bin-packing**: a 0/1 knapsack `Σⱼ wⱼ·xⱼ ≤ C` (`xⱼ ∈ {0,1}`, `wⱼ > 0`) viewed as a
 *    single-node flow with `yⱼ = wⱼ·xⱼ` — the flow and its indicator share the column, capacity `wⱼ`.
 *    This exposes the flow-cover family to bin-packing / network-flow models, which carry the
 *    structure as plain weighted knapsacks rather than explicit flow variables.
 *
 * For a cover `C` whose capacity exceeds the demand by `λ = Σ_C capⱼ − b > 0`, every feasible point
 * satisfies `Σ_C flowⱼ + Σ_C max(0, capⱼ − λ)·(1 − xⱼ) ≤ b`. The cover is chosen greedily from the arcs
 * the LP opens most (largest `xⱼ*`), and the inequality is emitted only when the LP point violates it.
 * Sound: it holds at every integer-feasible point (brute-validated for both forms), so it only tightens
 * the relaxation. Cuts from globally valid rows are [Cut.global].
 */
internal class FlowCoverSeparator : CutSeparator {

    /** One flow arc over LP columns: the flow contributes `flowCoeff · primal(flowCol)` (so the cut term
     *  is `flowCoeff·flowCol`), gated by the `{0,1}` indicator column, with effective capacity [cap]. For
     *  an implicit knapsack arc the flow and indicator are the same column (`flowCoeff = cap = wⱼ`). */
    private class Arc(val flowCol: Int, val flowCoeff: Long, val indicatorCol: Int, val cap: Long)

    override fun separate(ctx: CutContext): List<Cut> {
        val problem = ctx.problem
        val intColOf = ctx.relaxation.intColOf
        // Explicit VUB per flow variable: yⱼ ≤ uⱼ·xⱼ ⇔ a 2-term Linear `yⱼ − uⱼ·xⱼ ≤ 0`, xⱼ ∈ {0,1}.
        val indicator = MutableIntIntMap()
        val vubCap = MutableIntLongMap()
        for (f in problem.factors) {
            if (f !is Linear || f.op != LinearOp.LE || f.vars.size != 2) continue
            val row = f.integerConstants ?: continue
            if (row.bound != 0L) continue
            val (y, x, u) = matchVub(f.vars, row) ?: continue
            if (problem.requireFiniteIntDomains()[x].min != 0L ||
                problem.requireFiniteIntDomains()[x].max != 1L
            ) {
                continue // xⱼ ∈ {0,1}
            }
            val cap = minOf(u, problem.requireFiniteIntDomains()[y].max) // effective flow when xⱼ = 1
            if (cap <= 0L) continue
            indicator.put(y, x)
            vubCap.put(y, cap)
        }

        val cuts = ArrayList<Cut>()
        for (f in problem.factors) {
            if (f !is Linear || f.op != LinearOp.LE || f.vars.size < 2) continue
            val row = f.integerConstants ?: continue
            val arcs = explicitArcs(f.vars, row, indicator, vubCap, intColOf)
                ?: implicitArcs(f.vars, row, problem, intColOf)
                ?: continue
            separateCover(ctx, arcs, row.bound)?.let { cuts.add(it) }
        }
        return cuts
    }

    /** Match `a₀·v₀ + a₁·v₁ ≤ 0` to `y − u·x ≤ 0`: the `+1` term is the flow `y`, the negative term the
     *  indicator `x` with capacity `u = −coeff`. Returns `(y, x, u)` or null when it is not that shape. */
    private fun matchVub(vars: IntArray, row: IntegerConstants): Triple<Int, Int, Long>? {
        val (c0, c1) = row.coeff(0) to row.coeff(1)
        return when {
            c0 == 1L && c1 < 0 -> Triple(vars[0], vars[1], -c1)
            c1 == 1L && c0 < 0 -> Triple(vars[1], vars[0], -c0)
            else -> null
        }
    }

    /** Arcs for an explicit capacity row `Σ yⱼ ≤ b` (unit coefficients) whose every flow variable carries
     *  a registered VUB, or null when the row is not that shape. */
    private fun explicitArcs(
        vars: IntArray,
        row: IntegerConstants,
        indicator: MutableIntIntMap,
        vubCap: MutableIntLongMap,
        intColOf: IntArray,
    ): List<Arc>? {
        if (row.coefficients !is UnitConsts || vars.any { !indicator.containsKey(it) }) return null
        return vars.map { y ->
            val x = indicator.getOrDefault(y, -1)
            if (intColOf[y] < 0 || intColOf[x] < 0) return null
            Arc(flowCol = intColOf[y], flowCoeff = 1L, indicatorCol = intColOf[x], cap = vubCap.getOrDefault(y, 0L))
        }
    }

    /** Arcs for an implicit bin-packing knapsack `Σ wⱼ·xⱼ ≤ C` (`xⱼ ∈ {0,1}`, `wⱼ > 0`), each term a flow
     *  `yⱼ = wⱼ·xⱼ` over the indicator's own column, or null when the row is not that shape. */
    private fun implicitArcs(vars: IntArray, row: IntegerConstants, problem: Problem, intColOf: IntArray): List<Arc>? {
        for (i in vars.indices) {
            if (row.coeff(i) <= 0) return null
            val d = problem.requireFiniteIntDomains()[vars[i]]
            if (d.min != 0L || d.max != 1L || intColOf[vars[i]] < 0) return null
        }
        return vars.indices.map { i ->
            val col = intColOf[vars[i]]
            val w = row.coeff(i)
            Arc(flowCol = col, flowCoeff = w, indicatorCol = col, cap = w)
        }
    }

    private fun separateCover(ctx: CutContext, arcs: List<Arc>, b: Long): Cut? {
        if (b < 0L) return null
        // Greedy cover: open the arcs the LP favours (largest indicator value) until capacity exceeds b.
        val byOpen = arcs.sortedByDescending { ctx.primalOf(it.indicatorCol) }
        val cover = ArrayList<Arc>()
        var capSum = 0L
        for (arc in byOpen) {
            cover.add(arc)
            capSum += arc.cap
            if (capSum > b) break
        }
        val lambda = capSum - b
        if (lambda <= 0L) return null // not a cover

        // Σ_C flowⱼ + Σ_C mⱼ·(1 − xⱼ) ≤ b, mⱼ = max(0, capⱼ − λ). Rewrite over columns, coalescing the
        // flow and indicator coefficients that share a column (the implicit knapsack arc).
        val coeffByCol = MutableIntLongMap()
        // Columns in first-seen order, so the emitted cut keeps the cover's arc order.
        val order = IntArrayList()
        var rhs = b
        for (arc in cover) {
            if (!coeffByCol.containsKey(arc.flowCol)) order.add(arc.flowCol)
            coeffByCol.put(arc.flowCol, coeffByCol.getOrDefault(arc.flowCol, 0L) + arc.flowCoeff)
            val m = max(0L, arc.cap - lambda)
            if (m != 0L) {
                if (!coeffByCol.containsKey(arc.indicatorCol)) order.add(arc.indicatorCol)
                coeffByCol.put(arc.indicatorCol, coeffByCol.getOrDefault(arc.indicatorCol, 0L) - m)
                rhs -= m
            }
        }
        var activity = 0.0
        order.forEach { col -> activity += coeffByCol.getOrDefault(col, 0L) * ctx.primalOf(col) }
        if (activity <= rhs + VIOLATION_TOL) return null // not violated

        val cols = order.toIntArray()
        val coeffs = LongArray(cols.size) { coeffByCol.getOrDefault(cols[it], 0L) }
        // Globally valid iff the source rows are global (every detected row is a declared constraint).
        return Cut(cols, coeffs, Relation.LE, rhs, global = true)
    }

    private companion object {
        /** LP activity must exceed the cut rhs by at least this for the cut to be worth emitting. */
        const val VIOLATION_TOL = 1e-6
    }
}
