package com.eignex.klause.solver.presolve

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.arithmetic.Linear
import com.eignex.klause.solver.factor.arithmetic.LinearOp
import com.eignex.klause.solver.factor.table.Element

internal object ElementReduction {

    /**
     * Structural reduction of [Element] `result = arr(idx − indexOffset)` constraints — rewriting the
     * global into a cheap equality when the structure fixes the selection, which plain domain
     * propagation cannot do (it only filters the domains, never removes the constraint). Two cases,
     * both feasible-set-preserving:
     *
     *  1. **Fixed index** — `idx` is pinned to a single in-range position `p`. The element is then a
     *     plain equality: `result = arr[p]` (a constant when the array is constant, or `result = v`
     *     when `arr[p]` is the variable `v`). A self-reference `result = result` drops outright.
     *  2. **Constant array of one value** — every entry of a constant array equals `c`, so `result = c`
     *     regardless of the index. Dropping the element would also drop its implicit `idx ∈ valid
     *     range` constraint, so `idx`'s domain is tightened to that range to compensate.
     *
     * An out-of-range fixed index, or a constant-one-value array whose index range is empty, is left
     * untouched — that is an infeasibility the [Element] propagator already reports.
     */
    fun reduceElement(problem: Problem): Problem {
        var changed = false
        var domains: Array<IntDomain>? = null
        val out = ArrayList<Factor>(problem.factors.size)
        for (f in problem.factors) {
            if (f !is Element) {
                out.add(f)
                continue
            }
            val idxDom = problem.intDomains[f.idx]
            val replacement: Reduction? = when {
                idxDom.min == idxDom.max -> fixedIndex(f, idxDom.min)
                !f.arrIsVars && f.arr.all { it == f.arr[0] } -> constantArray(f, problem.intDomains)
                else -> null
            }
            if (replacement == null) {
                out.add(f)
                continue
            }
            changed = true
            replacement.factor?.let { out.add(it) }
            replacement.tightenedIdx?.let {
                val d = domains ?: problem.intDomains.copyOf().also { copy -> domains = copy }
                d[f.idx] = it
            }
        }
        if (!changed) return problem
        return PresolveShared.rebuildProblem(problem, out, domains ?: problem.intDomains.copyOf())
    }

    /** A reduced element: the equality [factor] that replaces it (`null` to drop), plus an optional
     *  tightened domain for the index variable. */
    private class Reduction(val factor: Factor?, val tightenedIdx: IntDomain? = null)

    private fun fixedIndex(e: Element, fixed: Int): Reduction? {
        val pos = fixed - e.indexOffset
        if (pos !in e.arr.indices) return null // out-of-range index — leave it to propagation
        return if (!e.arrIsVars) {
            Reduction(Linear(intArrayOf(1), intArrayOf(e.result), LinearOp.EQ, e.arr[pos]))
        } else {
            val v = e.arr[pos]
            if (v == e.result) {
                Reduction(null) // result = result, vacuous
            } else {
                Reduction(Linear(intArrayOf(1, -1), intArrayOf(e.result, v), LinearOp.EQ, 0))
            }
        }
    }

    private fun constantArray(e: Element, domains: Array<IntDomain>): Reduction? {
        val lo = e.indexOffset
        val hi = e.indexOffset + e.arr.size - 1
        val idxDom = domains[e.idx]
        val newMin = maxOf(idxDom.min, lo)
        val newMax = minOf(idxDom.max, hi)
        if (newMin > newMax) return null // index range and domain are disjoint — leave it to propagation
        // newMin/newMax lie within the current domain, so neither narrowing can empty it.
        val tightened = idxDom.withMinAtLeast(newMin).withMaxAtMost(newMax)
        return Reduction(Linear(intArrayOf(1), intArrayOf(e.result), LinearOp.EQ, e.arr[0]), tightened)
    }
}
