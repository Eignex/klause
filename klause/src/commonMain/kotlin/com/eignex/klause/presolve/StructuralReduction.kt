package com.eignex.klause.presolve

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.FactorReduction
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem

internal object StructuralReduction {

    /**
     * Apply every factor's own [Factor.structuralReduce] under the current domains, rewriting globals
     * that their structure pins into simpler / lower-arity factors. The per-factor reductions are
     * solution-set exact (the hook's contract), so the pass preserves the solution set; the driver only
     * collects the replacements, intersects any returned bound narrowings into the domains, and rebuilds.
     */
    fun reduce(problem: Problem): Problem {
        var changed = false
        var domains: Array<IntDomain>? = null
        val out = ArrayList<Factor>(problem.factors.size)
        for (f in problem.factors) {
            when (val reduction = f.structuralReduce(problem.intDomains)) {
                FactorReduction.Unchanged -> out.add(f)

                is FactorReduction.Rewrite -> {
                    changed = true
                    out.addAll(reduction.replacement)
                    for ((v, range) in reduction.tightenedBounds) {
                        val d = domains ?: problem.intDomains.copyOf().also { domains = it }
                        d[v] = d[v].withMinAtLeast(range.first).withMaxAtMost(range.last)
                    }
                }
            }
        }
        if (!changed) return problem
        return PresolveShared.rebuildProblem(problem, out, domains ?: problem.intDomains.copyOf())
    }
}
