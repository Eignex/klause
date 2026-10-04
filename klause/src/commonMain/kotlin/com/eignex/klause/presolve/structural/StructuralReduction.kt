package com.eignex.klause.presolve.structural

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorReduction
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PassDelta
import com.eignex.klause.presolve.SourceDelta
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntArrayList

internal object StructuralReduction {

    /**
     * Apply every factor's own `Factor.structuralReduce` under the current domains, rewriting globals
     * that their structure pins into simpler / lower-arity factors. The per-factor reductions are
     * solution-set exact (the hook's contract), so the pass preserves the solution set; the driver only
     * collects the replacements, intersects any returned bound narrowings into the domains, and rebuilds.
     */
    fun reduce(problem: BakedProblem): PassDelta {
        val dropped = IntArrayList()
        val added = ArrayList<Factor>()
        val root = problem.rootIntDomainsInPlace
        var domains: Array<IntDomain>? = null
        problem.factors.forEachIndexed { i, f ->
            when (val reduction = f.structuralReduce(root)) {
                FactorReduction.Unchanged -> {}

                is FactorReduction.Rewrite -> {
                    dropped.add(i)
                    added.addAll(reduction.replacement)
                    for ((v, range) in reduction.tightenedBounds) {
                        val d = domains ?: root.copyOf().also { domains = it }
                        d[v] = d[v].withMinAtLeast(range.first.toLong()).withMaxAtMost(range.last.toLong())
                    }
                }
            }
        }
        if (dropped.isEmpty()) return PassDelta()
        return PassDelta(dropped.toIntArray(), added, domains)
    }

    /**
     * The source form: the same hook, offered only to factors whose integer columns are all closed.
     *
     * A factor reasons from the domains it is handed, and an open column has none to hand — a box with an
     * invented endpoint could let a factor conclude it is vacuous when the model is not. Over closed
     * columns the declared value set, or the declared range when no set is stated, is the column's
     * logical domain, so the hook's exactness holds as it does on the finite lane. A narrowing it returns
     * is stated as a range, which is all a source pass may carry.
     */
    fun reduceSource(problem: Problem, cancellation: Cancellation = Cancellation.Never): SourceDelta {
        val declared = problem.declaredIntDomains
        val bounds = problem.intBounds
        val closed = BooleanArray(problem.numIntVars) { bounds.hasLower(it) && bounds.hasUpper(it) }
        // Entries for open columns are never read: only factors over closed columns reach the hook.
        val domains = Array(problem.numIntVars) { v ->
            if (!closed[v]) {
                OPEN_PLACEHOLDER
            } else {
                declared.declaredOrNull(v) ?: IntDomain(bounds.lower(v), bounds.upper(v))
            }
        }
        val dropped = IntArrayList()
        val added = ArrayList<Factor>()
        val tightening = bounds.tightening()
        problem.factors.forEachIndexed { i, f ->
            cancellation.charge(STRUCTURAL_WORK_WEIGHT * (1L + f.intVars.size))
            if (f.intVars.any { !closed[it] }) return@forEachIndexed
            val reduction = f.structuralReduce(domains) as? FactorReduction.Rewrite ?: return@forEachIndexed
            dropped.add(i)
            added.addAll(reduction.replacement)
            for ((v, range) in reduction.tightenedBounds) {
                tightening.atLeast(v, range.first.toLong())
                tightening.atMost(v, range.last.toLong())
            }
        }
        if (dropped.isEmpty()) return SourceDelta()
        return SourceDelta(dropped.toIntArray(), added, tightening.build())
    }

    private val OPEN_PLACEHOLDER = IntDomain(0, 0)

    // Work charged per factor offered the hook, scaled by its arity: most hooks read each column once.
    private const val STRUCTURAL_WORK_WEIGHT = 10L
}
