package com.eignex.klause.presolve.structural

import com.eignex.klause.factor.arithmetic.ComparisonClause
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.lp.engine.LpOverflowException
import com.eignex.klause.lp.engine.mulExact
import com.eignex.klause.lp.engine.subExact
import com.eignex.klause.presolve.PassDelta
import com.eignex.klause.propagation.PropagationProblem
import com.eignex.klause.solver.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.MutableIntObjectMap

/**
 * Folds the reified encoding of an intension comparison disjunction into one [ComparisonClause]. A
 * `Clause` over indicator literals, where each indicator is the sole-use aux of a single-variable
 * [ReifiedLinear] comparison (`b ⇔ (c·x ⟨op⟩ k)`, `|c| = 1`, `b` used only by that reified factor and
 * this clause), is exactly the disjunction of those comparisons — so it becomes one factor and the
 * indicator auxiliaries drop out. The front-ends that build such a clause directly (XCSP3 intension)
 * already emit [ComparisonClause]; this pass catches the reified form any front-end (FlatZinc, SMT-LIB)
 * produces after Tseitin lowering.
 *
 * Not solution-set preserving: the dropped indicators are left unconstrained (like affine elimination /
 * duplicate-column merging), so a complete enumerator would over-count — hence gated off under `-a` /
 * `-n N>1`. Model-variable solutions are unchanged (the comparison disjunction over the same variables).
 */
internal object ComparisonClauseFold {

    fun fold(problem: Problem): PassDelta {
        val factors = problem.factors
        // Index each indicator bool var to its (index, ReifiedLinear) definition. Aux vars are unique, so
        // a var maps to at most one reified factor.
        val defByAux = MutableIntObjectMap<Pair<Int, ReifiedLinear>>()
        for (i in factors.indices) {
            val f = factors[i]
            if (f is ReifiedLinear) defByAux.put(f.auxBoolVar, i to f)
        }
        if (defByAux.isEmpty()) return PassDelta()

        val occ = PropagationProblem(problem).boolOccurrences
        val dropped = IntArrayList()
        val added = ArrayList<Factor>()
        for (i in factors.indices) {
            val f = factors[i]
            if (f !is Clause || f.literals.size < 2) continue
            foldClause(f, defByAux, occ, problem.requireFiniteIntDomains())?.let { (clause, consumed) ->
                dropped.add(i)
                for (c in consumed) dropped.add(c)
                added.add(clause)
            }
        }
        if (dropped.isEmpty()) return PassDelta()
        return PassDelta(droppedIndices = dropped.toIntArray(), addedFactors = added)
    }

    /** The [ComparisonClause] equivalent of [clause] and the reified-factor indices it consumes, or
     *  `null` when any literal is not a sole-use single-variable reified comparison. */
    private fun foldClause(
        clause: Clause,
        defByAux: MutableIntObjectMap<Pair<Int, ReifiedLinear>>,
        occ: Array<IntArray>,
        domains: Array<IntDomain>,
    ): Pair<ComparisonClause, IntArray>? {
        val vars = IntArrayList()
        val ops = ArrayList<LinearOp>()
        val consts = ArrayList<Long>()
        val consumed = IntArrayList()
        val seen = IntHashSet()
        for (lit in clause.literals) {
            val v = Lit.variable(lit)
            // A repeated indicator in one clause would drop its reified def twice; decline conservatively.
            if (!seen.add(v)) return null
            // Sole use: referenced only by its reified definition and this clause.
            if (occ[v].size != 2) return null
            val def = defByAux[v] ?: return null
            val comp = singleVarComparison(def.second, domains) ?: return null
            val lifted = if (Lit.isPositive(lit)) comp else negate(comp)
            vars.add(lifted.first)
            ops.add(lifted.second)
            consts.add(lifted.third)
            consumed.add(def.first)
        }
        return ComparisonClause(vars.toIntArray(), ops.toTypedArray(), consts.toLongArray()) to consumed.toIntArray()
    }

    /**
     * A [ReifiedLinear] body reduced to `(var, op, const)` — one free variable with unit coefficient
     * against a constant. Fixed variables (singleton root domain, e.g. a FlatZinc constant lifted to a
     * `{c}` var) are substituted into the bound, so `b ⇔ (x − k ≤ 0)` with `k` fixed at 1 becomes
     * `x ≤ 1`. A `-1` coefficient on the free variable flips the operator and negates the bound. `null`
     * when more than one variable stays free, the free coefficient is not `±1`, or the bound overflows.
     */
    private fun singleVarComparison(r: ReifiedLinear, domains: Array<IntDomain>): Triple<Int, LinearOp, Long>? {
        val row = r.integerConstants ?: return null
        var freeVar = -1
        var freeCoeff = 0L
        var bound = row.bound
        try {
            for (i in r.vars.indices) {
                val v = r.vars[i]
                val d = domains[v]
                if (d.min == d.max) {
                    bound = subExact(bound, mulExact(row.coeff(i), d.min)) // move the fixed term to the RHS
                } else if (freeVar < 0) {
                    freeVar = v
                    freeCoeff = row.coeff(i)
                } else {
                    return null // a second free variable — not a single-variable literal
                }
            }
        } catch (_: LpOverflowException) {
            return null
        }
        // freeVar < 0: every term was fixed (a constant relation, not a comparison literal). A free
        // coefficient other than ±1 is not foldable into a bare `x op const` literal.
        return when {
            freeVar < 0 -> null
            freeCoeff == 1L -> Triple(freeVar, r.op, bound)
            freeCoeff == -1L -> Triple(freeVar, r.op.flipSign(), -bound)
            else -> null
        }
    }

    private fun LinearOp.flipSign(): LinearOp = when (this) {
        LinearOp.LE -> LinearOp.GE
        LinearOp.GE -> LinearOp.LE
        LinearOp.EQ -> LinearOp.EQ
        LinearOp.NE -> LinearOp.NE
    }

    /** The complement of a single-variable comparison: `¬(x ≤ c) = x ≥ c+1`, `¬(x ≥ c) = x ≤ c−1`,
     *  `¬(x = c) = x ≠ c`, `¬(x ≠ c) = x = c`. */
    private fun negate(lit: Triple<Int, LinearOp, Long>): Triple<Int, LinearOp, Long> {
        val (v, op, c) = lit
        return when (op) {
            LinearOp.LE -> Triple(v, LinearOp.GE, c + 1)
            LinearOp.GE -> Triple(v, LinearOp.LE, c - 1)
            LinearOp.EQ -> Triple(v, LinearOp.NE, c)
            LinearOp.NE -> Triple(v, LinearOp.EQ, c)
        }
    }
}
