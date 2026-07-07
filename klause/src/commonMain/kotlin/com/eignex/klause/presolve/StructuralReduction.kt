package com.eignex.klause.presolve

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.FactorReduction
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.util.IntArrayList

internal object StructuralReduction {

    /**
     * Apply every factor's own [Factor.structuralReduce] under the current domains, rewriting globals
     * that their structure pins into simpler / lower-arity factors. The per-factor reductions are
     * solution-set exact (the hook's contract), so the pass preserves the solution set; the driver only
     * collects the replacements, intersects any returned bound narrowings into the domains, and rebuilds.
     */
    fun reduce(problem: Problem): PassDelta {
        val dropped = IntArrayList()
        val added = ArrayList<Factor>()
        var domains: Array<IntDomain>? = null
        problem.factors.forEachIndexed { i, f ->
            when (val reduction = f.structuralReduce(problem.intDomains)) {
                FactorReduction.Unchanged -> {}

                is FactorReduction.Rewrite -> {
                    dropped.add(i)
                    added.addAll(reduction.replacement)
                    for ((v, range) in reduction.tightenedBounds) {
                        val d = domains ?: problem.intDomains.copyOf().also { domains = it }
                        d[v] = d[v].withMinAtLeast(range.first.toLong()).withMaxAtMost(range.last.toLong())
                    }
                }
            }
        }
        if (dropped.isEmpty()) return PassDelta()
        return PassDelta(dropped.toIntArray(), added, domains)
    }
}
