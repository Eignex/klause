package com.eignex.klause.lp.cut

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntegerConstants
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.UnitConsts
import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.lp.engine.CutFamily
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.rootDomainOf
import com.eignex.klause.lp.statesBinary
import com.eignex.klause.lp.statesLowerBoundAtLeast
import com.eignex.klause.lp.statesUpperBound
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.MutableIntIntMap
import com.eignex.util.collections.MutableIntLongMap
import kotlin.math.max

// Single-node flow-cover cuts (Padberg, Van Roy and Wolsey, "Valid linear inequalities for fixed charge
// problems", Oper. Res. 1985, https://doi.org/10.1287/opre.33.4.842) over an explicit capacity row with
// variable upper bounds `yⱼ ≤ uⱼ·xⱼ`, or a 0/1 knapsack read as flows `yⱼ = wⱼ·xⱼ`. For a cover `C` with
// `λ = Σ_C capⱼ − b > 0`, `Σ_C flowⱼ + Σ_C max(0, capⱼ − λ)·(1 − xⱼ) ≤ b` holds at every feasible point.
internal class FlowCoverSeparator : CutSeparator {
    override val family: CutFamily = CutFamily.FLOW_COVER

    // For an implicit knapsack arc the flow and indicator share a column (`flowCoeff = cap = wⱼ`).
    private class Arc(val flowCol: Int, val flowCoeff: Long, val indicatorCol: Int, val cap: Long)

    override fun separate(ctx: CutContext): List<Cut> {
        val problem = ctx.problem
        val intColOf = ctx.relaxation.intColOf
        val indicator = MutableIntIntMap()
        val vubCap = MutableIntLongMap()
        for (f in problem.factors) {
            if (f !is Linear || f.op != LinearOp.LE || f.vars.size != 2) continue
            val row = f.integerConstants ?: continue
            if (row.bound != 0L) continue
            val (y, x, u) = matchVub(f.vars, row) ?: continue
            if (!problem.statesBinary(x)) continue
            // A root-box ceiling on a flow the model leaves open above bounds the search, not the model.
            if (!problem.statesUpperBound(y)) continue
            // A flow free to run negative lets untaken arcs absorb the capacity row's slack, so the cover
            // inequality would cut off a feasible point.
            if (!problem.statesLowerBoundAtLeast(y, 0L)) continue
            val cap = minOf(u, problem.rootDomainOf(y).max)
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

    // `y − u·x ≤ 0` as `(y, x, u)`.
    private fun matchVub(vars: IntArray, row: IntegerConstants): Triple<Int, Int, Long>? {
        val (c0, c1) = row.coeff(0) to row.coeff(1)
        return when {
            c0 == 1L && c1 < 0 -> Triple(vars[0], vars[1], -c1)
            c1 == 1L && c0 < 0 -> Triple(vars[1], vars[0], -c0)
            else -> null
        }
    }

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

    private fun implicitArcs(vars: IntArray, row: IntegerConstants, problem: Problem, intColOf: IntArray): List<Arc>? {
        for (i in vars.indices) {
            if (row.coeff(i) <= 0) return null
            if (!problem.statesBinary(vars[i]) || intColOf[vars[i]] < 0) return null
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
        if (lambda <= 0L) return null

        // Coalesce flow and indicator coefficients that share a column.
        val coeffByCol = MutableIntLongMap()
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
        if (activity <= rhs + VIOLATION_TOL) return null

        val cols = order.toIntArray()
        val coeffs = LongArray(cols.size) { coeffByCol.getOrDefault(cols[it], 0L) }
        return Cut(cols, coeffs, Relation.LE, rhs, global = true)
    }

    private companion object {
        const val VIOLATION_TOL = 1e-6
    }
}
