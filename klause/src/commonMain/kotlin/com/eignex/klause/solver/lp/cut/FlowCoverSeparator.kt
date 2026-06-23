package com.eignex.klause.solver.lp.cut

import com.eignex.klause.solver.factor.arithmetic.Linear
import com.eignex.klause.solver.factor.arithmetic.LinearOp
import com.eignex.klause.solver.lp.Relation
import kotlin.math.max

/**
 * Single-node flow-cover cuts. Detects single-node flow structure
 * directly from the integer model — a **capacity row** `Σⱼ yⱼ ≤ b` whose flow variables each carry a
 * **variable-upper-bound** (VUB) `yⱼ ≤ uⱼ·xⱼ` with `xⱼ ∈ {0,1}` (every constraint a plain [Linear], no
 * new columns) — and separates the Padberg–Van Roy–Wolsey flow-cover inequality.
 *
 * For a cover `C` whose capacity exceeds the demand by `λ = Σ_{C} uⱼ − b > 0`, every feasible
 * `(x, y)` satisfies `Σ_{j∈C} yⱼ + Σ_{j∈C} max(0, uⱼ − λ)·(1 − xⱼ) ≤ b`. The cover is chosen greedily
 * from the arcs the LP opens most (largest `xⱼ*`), and the inequality is emitted only when the LP point
 * violates it. Sound: the inequality holds at every integer-feasible point (brute-validated), so it only
 * tightens the relaxation — it never removes a feasible solution. Cuts from globally valid rows are
 * [Cut.global].
 */
internal class FlowCoverSeparator : CutSeparator {

    /** One flow arc: the integer flow variable, its `{0,1}` indicator, and the effective capacity `uⱼ`. */
    private class Arc(val flowVar: Int, val indicatorVar: Int, val cap: Long)

    override fun separate(ctx: CutContext): List<Cut> {
        val problem = ctx.problem
        // VUB per flow variable: yⱼ ≤ uⱼ·xⱼ ⇔ a 2-term Linear `yⱼ − uⱼ·xⱼ ≤ 0`, xⱼ ∈ {0,1}.
        val indicator = HashMap<Int, Int>()
        val vubCap = HashMap<Int, Long>()
        for (f in problem.factors) {
            if (f !is Linear || f.op != LinearOp.LE || f.vars.size != 2 || f.bound != 0) continue
            val (y, x, u) = matchVub(f) ?: continue
            if (problem.intDomains[x].min != 0 || problem.intDomains[x].max != 1) continue // xⱼ ∈ {0,1}
            val cap = minOf(u, problem.intDomains[y].max.toLong()) // effective flow when xⱼ = 1
            if (cap <= 0L) continue
            indicator[y] = x
            vubCap[y] = cap
        }
        if (indicator.isEmpty()) return emptyList()

        val intColOf = ctx.relaxation.intColOf
        val cuts = ArrayList<Cut>()
        for (f in problem.factors) {
            if (f !is Linear || f.op != LinearOp.LE) continue
            if (f.vars.size < 2 || f.coeffs.any { it != 1 }) continue // unit-coefficient capacity row
            if (f.vars.any { it !in indicator }) continue // every flow var must carry a VUB
            val cut = separateRow(ctx, f, indicator, vubCap, intColOf) ?: continue
            cuts.add(cut)
        }
        return cuts
    }

    /** Match `a₀·v₀ + a₁·v₁ ≤ 0` to `y − u·x ≤ 0`: the `+1` term is the flow `y`, the negative term the
     *  indicator `x` with capacity `u = −coeff`. Returns `(y, x, u)` or null when it is not that shape. */
    private fun matchVub(f: Linear): Triple<Int, Int, Long>? {
        val (c0, c1) = f.coeffs[0] to f.coeffs[1]
        return when {
            c0 == 1 && c1 < 0 -> Triple(f.vars[0], f.vars[1], -c1.toLong())
            c1 == 1 && c0 < 0 -> Triple(f.vars[1], f.vars[0], -c0.toLong())
            else -> null
        }
    }

    private fun separateRow(
        ctx: CutContext,
        capacityRow: Linear,
        indicator: Map<Int, Int>,
        vubCap: Map<Int, Long>,
        intColOf: IntArray,
    ): Cut? {
        val b = capacityRow.bound.toLong()
        if (b < 0L) return null
        val arcs = capacityRow.vars.map { y -> Arc(y, indicator.getValue(y), vubCap.getValue(y)) }

        // Greedy cover: open the arcs the LP favours (largest indicator value) until capacity exceeds b.
        val byOpen = arcs.sortedByDescending { ctx.primalOf(intColOf[it.indicatorVar]) }
        val cover = ArrayList<Arc>()
        var capSum = 0L
        for (arc in byOpen) {
            cover.add(arc)
            capSum += arc.cap
            if (capSum > b) break
        }
        val lambda = capSum - b
        if (lambda <= 0L) return null // not a cover

        // Σ_C yⱼ + Σ_C mⱼ·(1 − xⱼ) ≤ b, mⱼ = max(0, uⱼ − λ). Rewrite over columns:
        // Σ_C yⱼ − Σ_C mⱼ·xⱼ ≤ b − Σ_C mⱼ. Emit only if the LP point violates it.
        val cols = ArrayList<Int>()
        val coeffs = ArrayList<Long>()
        var rhs = b
        var activity = 0.0
        for (arc in cover) {
            val m = max(0L, arc.cap - lambda)
            val yCol = intColOf[arc.flowVar]
            cols.add(yCol)
            coeffs.add(1L)
            activity += ctx.primalOf(yCol)
            if (m != 0L) {
                val xCol = intColOf[arc.indicatorVar]
                cols.add(xCol)
                coeffs.add(-m)
                activity -= m * ctx.primalOf(xCol)
                rhs -= m
            }
        }
        if (activity <= rhs + VIOLATION_TOL) return null // not violated
        // Globally valid iff the source rows are global (every detected row is a declared constraint).
        return Cut(cols.toIntArray(), coeffs.toLongArray(), Relation.LE, rhs, global = true)
    }

    private companion object {
        /** LP activity must exceed the cut rhs by at least this for the cut to be worth emitting. */
        const val VIOLATION_TOL = 1e-6
    }
}
