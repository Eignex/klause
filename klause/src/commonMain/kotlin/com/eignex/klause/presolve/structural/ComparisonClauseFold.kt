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
import com.eignex.klause.presolve.RebuildStep
import com.eignex.klause.presolve.SourceDelta
import com.eignex.klause.presolve.SourceRebuilds
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
 * indicator definitions drop out. Reconstruction restores each indicator from its defining comparison,
 * independently of the polarity used in the clause. Front-ends that build the disjunction directly
 * (XCSP3 intension) already emit [ComparisonClause]; this pass catches the reified form (FlatZinc, SMT-LIB)
 * produces after Tseitin lowering.
 *
 * With `keepBinary`, a clause comparing any `{0, 1}` column is left alone. Such a comparison is a Boolean
 * literal of its column: kept reified, binary-column substitution turns it into that literal and the clause
 * stays a Boolean clause, which finite search branches on and conflict analysis learns from. Folded, the
 * [ComparisonClause] reads the column value-wise and holds it, and every row it shares, in the integer
 * lane.
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
        keepBinary: Boolean = false,
    ): PassDelta {
        val occ = PropagationProblem(problem).boolOccurrences
        val uses = IntArray(problem.numBoolVars) { occ[it].size }
        val ranges = ColumnRanges.of(problem.rootIntDomainsInPlace)
        return foldAll(problem.factors, ranges, uses, objectiveBoolVars, cancellation, keepBinary)
            .asPassDelta(problem.rootIntDomainsInPlace)
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
        keepBinary: Boolean = false,
    ): SourceDelta {
        val uses = IntArray(problem.numBoolVars)
        for (f in problem.factors) for (b in f.boolVars) uses[b]++
        val ranges = ColumnRanges.of(problem.intBounds)
        return foldAll(problem.factors, ranges, uses, objectiveBoolVars, cancellation, keepBinary)
    }

    private fun foldAll(
        factors: Array<Factor>,
        ranges: ColumnRanges,
        uses: IntArray,
        objectiveBoolVars: Set<Int>,
        cancellation: Cancellation,
        keepBinary: Boolean,
    ): SourceDelta {
        // A consumed definition must be equivalent to its single reified row.
        val defByAux = MutableIntObjectMap<Pair<Int, LinearRow>>()
        for (i in factors.indices) {
            if (factors[i] is Clause) continue
            val row = (factors[i].linearForm as? LinearForm.Conjunction)?.rows?.singleOrNull() ?: continue
            if (row.activator != LinearRow.ALWAYS) defByAux.put(row.activator, i to row)
        }
        if (defByAux.isEmpty()) return SourceDelta()

        val dropped = IntArrayList()
        val added = ArrayList<Factor>()
        val rebuilds = ArrayList<RebuildStep>()
        for (i in factors.indices) {
            val f = factors[i]
            if (f !is Clause || f.literals.size < 2) continue
            cancellation.charge(FOLD_WORK_WEIGHT * (1L + f.literals.size))
            foldClause(f, defByAux, uses, objectiveBoolVars, ranges, keepBinary)?.let { (clause, consumed, steps) ->
                dropped.add(i)
                for (c in consumed) dropped.add(c)
                added.add(clause)
                rebuilds.addAll(steps)
            }
        }
        return SourceDelta(dropped.toIntArray(), added, rebuild = SourceRebuilds(rebuilds))
    }

    /** The [ComparisonClause] equivalent of [clause] and the reified-factor indices it consumes, or
     *  `null` when any literal is not a sole-use single-variable reified comparison, or compares a
     *  `{0, 1}` column under [keepBinary]. */
    private fun foldClause(
        clause: Clause,
        defByAux: MutableIntObjectMap<Pair<Int, LinearRow>>,
        uses: IntArray,
        objectiveBoolVars: Set<Int>,
        ranges: ColumnRanges,
        keepBinary: Boolean,
    ): Triple<ComparisonClause, IntArray, List<RebuildStep>>? {
        val vars = IntArrayList()
        val ops = ArrayList<LinearOp>()
        val consts = ArrayList<Long>()
        val consumed = IntArrayList()
        val rebuilds = ArrayList<RebuildStep>()
        val seen = IntHashSet()
        for (lit in clause.literals) {
            val v = Lit.variable(lit)
            // A repeated indicator in one clause would drop its reified def twice; decline conservatively.
            if (!seen.add(v)) return null
            if (uses[v] != 2) return null
            // An objective weight prices the indicator, which the fold would leave unconstrained.
            if (v in objectiveBoolVars) return null
            val def = defByAux[v] ?: return null
            val comp = singleVarComparison(def.second, ranges) ?: return null
            if (keepBinary && ranges.isBinary(comp.first)) return null
            rebuilds.add(RebuildStep.ComparisonValue(v, comp.first, comp.second, comp.third))
            val lifted = if (Lit.isPositive(lit)) comp else negate(comp) ?: return null
            vars.add(lifted.first)
            ops.add(lifted.second)
            consts.add(lifted.third)
            consumed.add(def.first)
        }
        return Triple(
            ComparisonClause(vars.toIntArray(), ops.toTypedArray(), consts.toLongArray()),
            consumed.toIntArray(),
            rebuilds,
        )
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
                    bound = subExact(bound, mulExact(row.coeff(i), ranges.min(v)))
                } else if (freeVar < 0) {
                    freeVar = v
                    freeCoeff = row.coeff(i)
                } else {
                    return null
                }
            }
        } catch (_: CheckedLongOverflowException) {
            return null
        }
        // Every term fixed (freeVar < 0) is a constant relation, not a comparison literal.
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
