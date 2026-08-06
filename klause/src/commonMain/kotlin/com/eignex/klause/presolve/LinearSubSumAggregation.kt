package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Problem
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.MutableIntLongMap
import com.eignex.klause.util.MutableIntObjectMap

/**
 * Common linear sub-sum extraction (the contract-direction counterpart of [AffineSingletons], which
 * *expands* a variable into its definition). When an equality defines a variable as a linear sub-sum
 * `y = B + Σ_j A_j·x_j` (unit pivot on `y`, at least two partner terms), any other [Linear] row that
 * contains that whole sub-sum with a common integer multiplier `k` — every `x_j` at coefficient `k·A_j`
 * — has `k·Σ_j A_j·x_j` rewritten to the single term `k·y` (the bound absorbing `k·B`). The partner
 * terms collapse into one variable, shrinking the row's arity and tightening the LP relaxation's density.
 *
 * Solution-set exact and needs no reconstruction: the defining equality is retained, so `Σ_j A_j·x_j =
 * y − B` holds in every solution and the rewritten row is equivalent term-for-term. No variable is
 * eliminated — `y` already exists — so unlike affine elimination this is solution-set *preserving*.
 */
internal object LinearSubSumAggregation {

    /** Above this factor count the candidate scan is skipped — the reduction is a density optimisation,
     *  not a correctness need, so forgoing it on giant models keeps presolve bounded (sound). */
    private const val MAX_FACTORS = 200_000

    /** A sub-sum definition `y = B + Σ A_j·x_j` read off a unit-pivot equality: the pivot [y], the
     *  constant [b] (`B`), and the partner form `x_j ↦ A_j` in [form]. [defIndex] is the equality's own
     *  factor index, so it is never rewritten against itself. */
    private class Definition(val defIndex: Int, val y: Int, val b: Long, val form: Map<Int, Long>)

    fun aggregateSubSums(problem: Problem): PassDelta {
        val factors = problem.factors
        if (factors.size > MAX_FACTORS) return PassDelta()

        val definitions = collectDefinitions(factors)
        if (definitions.isEmpty()) return PassDelta()

        // Index Linear rows by variable, so a definition is matched only against rows that mention its
        // rarest partner rather than the whole model.
        val rowsByVar = MutableIntObjectMap<IntArrayList>()
        for (i in factors.indices) {
            val f = factors[i]
            if (f is Linear && f.isIntegerCore) for (v in f.vars) rowsByVar.getOrPut(v) { IntArrayList() }.add(i)
        }

        val dropped = IntArrayList()
        val added = ArrayList<Factor>()
        val rewritten = HashSet<Int>() // one substitution per row per pass; the round engine re-runs
        for (def in definitions) {
            val anchor = rarestPartner(def.form, rowsByVar) ?: continue
            for (r in 0 until anchor.size) {
                val i = anchor[r]
                if (i == def.defIndex || i in rewritten) continue
                val row = factors[i] as Linear
                val k = matchMultiplier(row, def) ?: continue
                if (overflowsBoundShift(k, def.b, row.bound)) continue
                added.add(rewrite(row, def, k))
                dropped.add(i)
                rewritten.add(i)
            }
        }
        if (dropped.isEmpty()) return PassDelta()
        return PassDelta(droppedIndices = dropped.toIntArray(), addedFactors = added)
    }

    /** Every unit-pivot sub-sum definition among the equality [Linear]s: an equality `Σ c_j·v_j = b` with
     *  a variable `v_p` of coefficient `±1` and at least two other terms yields `v_p = c_p·b + Σ_{j≠p}
     *  (−c_p·c_j)·v_j`. The lowest-id unit-coefficient variable is the pivot (deterministic). */
    private fun collectDefinitions(factors: Array<Factor>): List<Definition> {
        val out = ArrayList<Definition>()
        for (i in factors.indices) {
            val f = factors[i]
            if (f !is Linear || !f.isIntegerCore || f.op != LinearOp.EQ || f.vars.size < 3) continue
            val p = unitPivotIndex(f) ?: continue
            val sign = f.coeff(p) // ±1
            val form = HashMap<Int, Long>(f.vars.size)
            // A zero-coefficient term is vacuous (coalescing keeps it, but it names no real partner), so it
            // is not part of the sub-sum — dropping it also keeps [matchMultiplier]'s `c / A_j` well-defined.
            for (j in f.vars.indices) if (j != p && f.coeff(j) != 0L) form[f.vars[j]] = -sign * f.coeff(j)
            if (form.size < 2) continue // fewer than two real partners is no sub-sum to aggregate
            out.add(Definition(i, f.vars[p], sign * f.bound, form))
        }
        return out
    }

    /** Index of the lowest-id variable with a `±1` coefficient, or `null` if none — the deterministic
     *  pivot whose isolation keeps every folded coefficient integral. */
    private fun unitPivotIndex(f: Linear): Int? {
        var best = -1
        for (j in f.vars.indices) {
            if (f.coeff(j) != 1L && f.coeff(j) != -1L) continue
            if (best < 0 || f.vars[j] < f.vars[best]) best = j
        }
        return if (best < 0) null else best
    }

    /** The partner variable of [form] contained in the fewest rows — the cheapest anchor to scan. */
    private fun rarestPartner(form: Map<Int, Long>, rowsByVar: MutableIntObjectMap<IntArrayList>): IntArrayList? {
        var best: IntArrayList? = null
        for (v in form.keys) {
            val rows = rowsByVar[v] ?: return null // a partner in no row: no match possible
            if (best == null || rows.size < best.size) best = rows
        }
        return best
    }

    /** The common integer multiplier `k` with which [row] contains [def]'s whole partner form — every
     *  partner `x_j` present at coefficient `k·A_j`, one nonzero `k` for all — or `null` if the form is
     *  absent, partial, or unevenly scaled (so no exact sub-sum to fold). */
    private fun matchMultiplier(row: Linear, def: Definition): Long? {
        val coeffByVar = MutableIntLongMap(row.vars.size)
        for (j in row.vars.indices) coeffByVar.put(row.vars[j], row.coeff(j))
        var k = 0L
        for ((x, a) in def.form) {
            if (!coeffByVar.containsKey(x)) return null // partner missing → not a full sub-sum
            val c = coeffByVar.getOrDefault(x, 0L)
            if (a == 0L) return null // a zero form coefficient is no term (collectDefinitions drops these)
            if (c % a != 0L) return null
            val ratio = c / a
            if (ratio == 0L) return null
            if (k == 0L) {
                k = ratio
            } else if (k != ratio) {
                return null
            }
        }
        return if (k == 0L) null else k
    }

    /** Magnitude past which the `k·B` bound shift (or its addition to the row bound) is treated as
     *  overflow-risky and the fold declined — real coefficients sit far below this. */
    private const val OVERFLOW_GUARD = 1_000_000_000_000_000L

    private fun overflowsBoundShift(k: Long, b: Long, bound: Long): Boolean {
        if (b != 0L && (abs(k) > OVERFLOW_GUARD / abs(b))) return true
        val shift = k * b
        return abs(shift) > OVERFLOW_GUARD || abs(bound) > OVERFLOW_GUARD
    }

    private fun abs(x: Long): Long = if (x < 0L) -x else x

    /** [row] with `k·Σ A_j·x_j` replaced by the single term `k·y`: drop every partner term, add `k` to
     *  `y`'s coefficient, and shift the bound by `k·B` (moving `k·(y − B)` to the left leaves the same
     *  relation). Coalescing in [Linear]'s constructor folds `k·y` into any existing `y` term. */
    private fun rewrite(row: Linear, def: Definition, k: Long): Linear {
        val vars = IntArrayList(row.vars.size)
        val coeffs = ArrayList<Long>(row.vars.size)
        for (j in row.vars.indices) {
            if (def.form.containsKey(row.vars[j])) continue // absorbed into k·y
            vars.add(row.vars[j])
            coeffs.add(row.coeff(j))
        }
        vars.add(def.y)
        coeffs.add(k)
        return Linear(coeffs.toLongArray(), vars.toIntArray(), row.op, row.bound + k * def.b)
    }
}
