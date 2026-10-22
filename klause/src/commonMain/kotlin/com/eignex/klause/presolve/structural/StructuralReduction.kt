package com.eignex.klause.presolve.structural

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorReduction
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PassDelta
import com.eignex.klause.presolve.SourceDelta
import com.eignex.klause.presolve.specializeLinear
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet

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
     * The source form specializes unit-fixed native integer reifications, then offers the structural
     * hook to factors whose integer columns are all closed.
     *
     * A factor reasons from the domains it is handed, and an open column has none to hand — a box with an
     * invented endpoint could let a factor conclude it is vacuous when the model is not. Over closed
     * columns the declared value set, or the declared range when no set is stated, is the column's
     * logical domain, so the hook's exactness holds as it does on the finite lane. A narrowing it returns
     * is stated as a range, which is all a source pass may carry.
     */
    @Suppress("LoopWithTooManyJumpStatements")
    fun reduceSource(problem: Problem, cancellation: Cancellation = Cancellation.Never): SourceDelta {
        val work = Work(cancellation)
        if (!work.check()) return SourceDelta()
        val units = rootUnits(problem, work) ?: return SourceDelta()
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
        for ((i, f) in problem.factors.withIndex()) {
            if (!work.visit(1L + f.intVars.size)) break
            val specialized = (f as? ReifiedLinear)?.let { specializeUnit(it, units, work) }
            if (specialized != null) {
                dropped.add(i)
                added.add(specialized)
                continue
            }
            if (work.stopped) break
            if (f.intVars.any { !closed[it] }) continue
            val reduction = f.structuralReduce(domains) as? FactorReduction.Rewrite ?: continue
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

    private fun rootUnits(problem: Problem, work: Work): IntHashSet? {
        val units = IntHashSet()
        if (problem.numBoolVars == 0) return units
        for (factor in problem.factors) {
            if (!work.visit()) return null
            if (factor !is Clause || factor.literals.size != 1) continue
            val literal = factor.literals[0]
            if (Lit.variable(literal) < problem.numBoolVars) units.add(literal)
        }
        return units
    }

    private fun specializeUnit(factor: ReifiedLinear, units: IntHashSet, work: Work): Linear? {
        if (factor.integerConstants == null || factor.vars.size > MAX_REIFIED_TERMS) return null
        val literal = Lit.make(factor.auxBoolVar, true)
        val positive = literal in units
        if (positive == (Lit.negate(literal) in units)) return null
        repeat(factor.vars.size) {
            if (!work.visit(REIFIED_CONSTRUCTION_UNITS)) return null
        }
        val row = (factor.linearForm as LinearForm.Conjunction).rows.single()
        val replacement = row.specializeLinear(positive, emptyMap()) ?: return null
        return replacement.takeIf { work.check() }
    }

    private class Work(private val cancellation: Cancellation) {
        var stopped = false
            private set

        fun check(): Boolean {
            if (!stopped && cancellation()) stopped = true
            return !stopped
        }

        fun visit(units: Long = 1L): Boolean {
            if (stopped) return false
            cancellation.charge(STRUCTURAL_WORK_WEIGHT * units)
            return check()
        }
    }

    private val OPEN_PLACEHOLDER = IntDomain(0, 0)

    // Work charged per factor offered the hook, scaled by its arity: most hooks read each column once.
    private const val STRUCTURAL_WORK_WEIGHT = 10L

    // Bound the atomic exact specialization and constructor after their term work is prepaid.
    private const val MAX_REIFIED_TERMS = 4096
    private const val REIFIED_CONSTRUCTION_UNITS = 8L
}
