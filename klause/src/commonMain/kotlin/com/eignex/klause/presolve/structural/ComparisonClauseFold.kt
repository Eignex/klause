package com.eignex.klause.presolve.structural

import com.eignex.klause.factor.arithmetic.ComparisonClause
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntegerConstants
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.presolve.ColumnRanges
import com.eignex.klause.presolve.PassDelta
import com.eignex.klause.presolve.SourceDelta
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationProblem
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.CheckedLongOverflowException
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.MutableIntObjectMap
import com.eignex.klause.util.mulExact
import com.eignex.klause.util.subExact

/**
 * Folds the reified encoding of an intension comparison disjunction into one [ComparisonClause]. A
 * `Clause` over indicator literals, where each indicator is the sole-use aux of a single-variable
 * reified comparison (`b ⇔ (c·x ⟨op⟩ k)`, `|c| = 1`, `b` used only by that reified factor and
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

    /** The fold over root-propagated domains, which pin more columns than the declarations do. */
    fun fold(
        problem: BakedProblem,
        objectiveBoolVars: Set<Int> = emptySet(),
        cancellation: Cancellation = Cancellation.Never,
    ): PassDelta {
        val occ = PropagationProblem(problem).boolOccurrences
        val uses = IntArray(problem.numBoolVars) { occ[it].size }
        val ranges = ColumnRanges.of(problem.rootIntDomainsInPlace)
        val folded = foldAll(problem.factors, ranges, uses, objectiveBoolVars, cancellation) ?: return PassDelta()
        return PassDelta(droppedIndices = folded.first, addedFactors = folded.second)
    }

    /**
     * The fold over the ranges the source model declares. A comparison's other terms move to the bound only
     * when the declaration fixes them; an open column is never fixed, so its term stays and the row declines
     * as a multi-variable comparison rather than being read at an invented endpoint.
     */
    fun foldSource(
        problem: Problem,
        objectiveBoolVars: Set<Int> = emptySet(),
        cancellation: Cancellation = Cancellation.Never,
    ): SourceDelta {
        val uses = IntArray(problem.numBoolVars)
        for (f in problem.factors) for (b in f.boolVars) uses[b]++
        val ranges = ColumnRanges.of(problem.intBounds)
        val folded = foldAll(problem.factors, ranges, uses, objectiveBoolVars, cancellation) ?: return SourceDelta()
        return SourceDelta(folded.first, folded.second)
    }

    private fun foldAll(
        factors: Array<Factor>,
        ranges: ColumnRanges,
        uses: IntArray,
        objectiveBoolVars: Set<Int>,
        cancellation: Cancellation,
    ): Pair<IntArray, List<Factor>>? {
        // A consumed definition must be equivalent to its single reified row.
        val defByAux = MutableIntObjectMap<Pair<Int, LinearRow>>()
        for (i in factors.indices) {
            if (factors[i] is Clause) continue
            val row = (factors[i].linearForm as? LinearForm.Conjunction)?.rows?.singleOrNull() ?: continue
            if (row.activator != LinearRow.ALWAYS) defByAux.put(row.activator, i to row)
        }
        if (defByAux.isEmpty()) return null

        val dropped = IntArrayList()
        val added = ArrayList<Factor>()
        for (i in factors.indices) {
            val f = factors[i]
            if (f !is Clause || f.literals.size < 2) continue
            cancellation.charge(FOLD_WORK_WEIGHT * (1L + f.literals.size))
            foldClause(f, defByAux, uses, objectiveBoolVars, ranges)?.let { (clause, consumed) ->
                dropped.add(i)
                for (c in consumed) dropped.add(c)
                added.add(clause)
            }
        }
        if (dropped.isEmpty()) return null
        return dropped.toIntArray() to added
    }

    /** The [ComparisonClause] equivalent of [clause] and the reified-factor indices it consumes, or
     *  `null` when any literal is not a sole-use single-variable reified comparison. */
    private fun foldClause(
        clause: Clause,
        defByAux: MutableIntObjectMap<Pair<Int, LinearRow>>,
        uses: IntArray,
        objectiveBoolVars: Set<Int>,
        ranges: ColumnRanges,
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
            if (uses[v] != 2) return null
            // An objective weight prices the indicator, which the fold would leave unconstrained.
            if (v in objectiveBoolVars) return null
            val def = defByAux[v] ?: return null
            val comp = singleVarComparison(def.second, ranges) ?: return null
            val lifted = if (Lit.isPositive(lit)) comp else negate(comp) ?: return null
            vars.add(lifted.first)
            ops.add(lifted.second)
            consts.add(lifted.third)
            consumed.add(def.first)
        }
        return ComparisonClause(vars.toIntArray(), ops.toTypedArray(), consts.toLongArray()) to consumed.toIntArray()
    }

    /**
     * A reified row body reduced to `(var, op, const)` — one free variable with unit coefficient
     * against a constant. Fixed variables (a single-value range, e.g. a FlatZinc constant lifted to a
     * `{c}` var) are substituted into the bound, so `b ⇔ (x − k ≤ 0)` with `k` fixed at 1 becomes
     * `x ≤ 1`. A `-1` coefficient on the free variable flips the operator and negates the bound. `null`
     * when more than one variable stays free, the free coefficient is not `±1`, or the bound overflows.
     */
    private fun singleVarComparison(r: LinearRow, ranges: ColumnRanges): Triple<Int, LinearOp, Long>? {
        val row = r.constants as? IntegerConstants ?: return null
        if (r.strict || !r.isIntegerOnly) return null
        var freeVar = -1
        var freeCoeff = 0L
        var bound = row.bound
        try {
            for (i in 0 until r.size) {
                val v = Term.intVar(r.ref(i))
                if (ranges.isFixed(v)) {
                    bound = subExact(bound, mulExact(row.coeff(i), ranges.min(v))) // move the fixed term to the RHS
                } else if (freeVar < 0) {
                    freeVar = v
                    freeCoeff = row.coeff(i)
                } else {
                    return null // a second free variable — not a single-variable literal
                }
            }
        } catch (_: CheckedLongOverflowException) {
            return null
        }
        // freeVar < 0: every term was fixed (a constant relation, not a comparison literal). A free
        // coefficient other than ±1 is not foldable into a bare `x op const` literal.
        return when {
            freeVar < 0 -> null
            freeCoeff == 1L -> Triple(freeVar, r.relation, bound)
            freeCoeff == -1L && bound != Long.MIN_VALUE -> Triple(freeVar, r.relation.flipSign(), -bound)
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
     *  `¬(x = c) = x ≠ c`, `¬(x ≠ c) = x = c`; `null` when the shifted bound leaves `Long`. */
    private fun negate(lit: Triple<Int, LinearOp, Long>): Triple<Int, LinearOp, Long>? {
        val (v, op, c) = lit
        return when (op) {
            LinearOp.LE -> if (c == Long.MAX_VALUE) null else Triple(v, LinearOp.GE, c + 1)
            LinearOp.GE -> if (c == Long.MIN_VALUE) null else Triple(v, LinearOp.LE, c - 1)
            LinearOp.EQ -> Triple(v, LinearOp.NE, c)
            LinearOp.NE -> Triple(v, LinearOp.EQ, c)
        }
    }

    private fun ColumnRanges.isFixed(v: Int): Boolean = hasLower(v) && hasUpper(v) && min(v) == max(v)

    private const val FOLD_WORK_WEIGHT = 10L
}
