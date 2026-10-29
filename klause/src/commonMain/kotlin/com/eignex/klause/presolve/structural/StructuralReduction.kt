package com.eignex.klause.presolve.structural

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorReduction
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PassDelta
import com.eignex.klause.presolve.SourceDelta
import com.eignex.klause.presolve.retainsStructure
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
    fun reduce(
        problem: BakedProblem,
        cancellation: Cancellation = Cancellation.Never,
        preserveStructure: Boolean = false,
    ): PassDelta {
        if (cancellation()) return PassDelta()
        val dropped = IntArrayList()
        val added = ArrayList<Factor>()
        val root = problem.rootIntDomainsInPlace
        var domains: Array<IntDomain>? = null
        for ((i, f) in problem.factors.withIndex()) {
            if (!visit(cancellation)) break
            // Clause is final and inherits the unchanged hook.
            if (f is Clause || (preserveStructure && f.retainsStructure(problem, cancellation))) continue
            for (v in f.intVars) if (!visit(cancellation)) {
                return PassDelta(dropped.toIntArray(), added, domains)
            }
            for (v in f.boolVars) if (!visit(cancellation)) {
                return PassDelta(dropped.toIntArray(), added, domains)
            }
            when (val reduction = f.structuralReduce(root)) {
                FactorReduction.Unchanged -> {}

                is FactorReduction.Rewrite -> {
                    if (!completeRewrite(reduction, cancellation)) break
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
    fun reduceSource(
        problem: Problem,
        cancellation: Cancellation = Cancellation.Never,
        preserveStructure: Boolean = false,
    ): SourceDelta {
        if (cancellation()) return SourceDelta()
        val declared = problem.declaredIntDomains
        val bounds = problem.intBounds
        val closed = BooleanArray(problem.numIntVars)
        // Entries for open columns are never read: only factors over closed columns reach the hook.
        val domains = Array(problem.numIntVars) { v ->
            if (!visit(cancellation)) return SourceDelta()
            closed[v] = bounds.hasLower(v) && bounds.hasUpper(v)
            if (!closed[v]) {
                OPEN_PLACEHOLDER
            } else {
                declared.declaredOrNull(v) ?: IntDomain(bounds.lower(v), bounds.upper(v))
            }
        }
        val dropped = IntArrayList()
        val added = ArrayList<Factor>()
        val tightening = bounds.tightening()
        for ((i, f) in problem.factors.withIndex()) {
            if (!visit(cancellation)) break
            if (f is Clause || (preserveStructure && f.retainsStructure(problem, cancellation))) continue
            var allClosed = true
            for (v in f.intVars) {
                if (!visit(cancellation)) return SourceDelta(dropped.toIntArray(), added, tightening.build())
                if (!closed[v]) allClosed = false
            }
            for (v in f.boolVars) if (!visit(cancellation)) {
                return SourceDelta(dropped.toIntArray(), added, tightening.build())
            }
            if (!allClosed) continue
            val reduction = f.structuralReduce(domains) as? FactorReduction.Rewrite ?: continue
            if (!completeRewrite(reduction, cancellation)) break
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

    private fun completeRewrite(reduction: FactorReduction.Rewrite, cancellation: Cancellation): Boolean {
        for (factor in reduction.replacement) {
            if (!visit(cancellation)) return false
            for (v in factor.intVars) if (!visit(cancellation)) return false
            for (v in factor.boolVars) if (!visit(cancellation)) return false
        }
        for (bound in reduction.tightenedBounds) if (!visit(cancellation)) return false
        return !cancellation()
    }

    private fun visit(cancellation: Cancellation): Boolean {
        if (cancellation()) return false
        cancellation.charge(STRUCTURAL_WORK_WEIGHT)
        return !cancellation()
    }

    // Every factor is charged; hook candidates also pay for their columns.
    private const val STRUCTURAL_WORK_WEIGHT = 10L
}
